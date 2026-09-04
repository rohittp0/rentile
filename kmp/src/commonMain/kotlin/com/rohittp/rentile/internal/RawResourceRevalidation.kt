package com.rohittp.rentile.internal

import com.rohittp.rentile.MetricName
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceMetadata
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StoredRawResource
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.TransportRequestMetadata
import kotlinx.coroutines.CancellationException

internal const val NOT_MODIFIED_STATUS: Int = 304

/**
 * Acquires one style-closure resource through the raw store, revalidating rather than refetching.
 *
 * The style, its sprite JSON, its sprite image and every source's TileJSON are the documents a
 * process start must have before it can plan a single tile, and they are the ones a warm start
 * should not be paying for again. Written once for all four because they had drifted into three
 * different policies: the style went straight to the transport past the store entirely, while the
 * sprite and TileJSON entries, once written, were reused forever with nothing ever asking the
 * origin whether they were still current.
 *
 * The policy is ADR 0007's: a fresh entry is used directly, a stale entry with validators is
 * revalidated conditionally and its bytes reused on `304`, and a stale entry without validators is
 * refetched. Failed revalidation raises a typed error and never silently substitutes stale content.
 *
 * Two deliberate refinements of it:
 *
 * - [alwaysRevalidate] skips the freshness shortcut, and the style passes it. The style is the root
 *   of the closure: serving one from a `max-age` without asking the origin would pin an entire
 *   cached resource tree to a document nobody re-confirmed, and a style switch is a user action
 *   that must not wait out an expiry. Everything below it may take the shortcut.
 * - **Reusing stored bytes rewrites the entry.** A consumer's raw cache is trimmed by file age, so
 *   an entry that is read on every start and never written becomes the oldest file in the cache and
 *   is evicted first — the exact entries this exists to keep are the ones that would go. The
 *   rewrite also carries whatever validators and freshness the `304` returned.
 *
 * An entry that could never be reused — no `ETag`, no `Last-Modified`, no expiry — is not stored at
 * all. Storing it would buy nothing and cost a payload read plus a SHA-256 on every later start.
 */
internal suspend fun RentileConfiguration.acquireRevalidatedRawResource(
    workCoordinator: ResourceWorkCoordinator,
    key: RawResourceKey,
    url: String,
    sanitizedId: String,
    maxBytes: Long,
    transportLabel: String,
    cacheLabel: String,
    accept: String? = null,
    limitName: String? = null,
    alwaysRevalidate: Boolean = false,
    isStoredEntryUsable: (ByteArray) -> Boolean = { true },
): ByteArray {
    val resourceClass = key.resourceClass
    val cached = readReusableEntry(key, maxBytes, cacheLabel, isStoredEntryUsable)
    if (cached != null && !alwaysRevalidate && isFresh(cached.metadata)) {
        recordStoredResourceReuse(resourceClass)
        rewriteStoredEntry(key, cached, cached.metadata, cacheLabel)
        return cached.bytes
    }
    val response = workCoordinator.exchange(url) {
        metricsSink.recordSafely(RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass))
        try {
            transport.execute(
                TransportRequest(
                    url = url,
                    resourceClass = resourceClass,
                    maxResponseBytes = maxBytes,
                    metadata = TransportRequestMetadata(
                        ifNoneMatch = cached?.metadata?.etag,
                        ifModifiedSince = cached?.metadata?.lastModified,
                        accept = accept,
                    ),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceAcquisitionException(
                message = "$transportLabel transport failed",
                resourceClass = resourceClass,
                sanitizedResourceId = sanitizedId,
            )
        }
    }
    if (response.statusCode == NOT_MODIFIED_STATUS && cached != null) {
        recordStoredResourceReuse(resourceClass)
        rewriteStoredEntry(key, cached, response.metadata.mergedOnto(cached.metadata, clock.nowEpochMillis()), cacheLabel)
        return cached.bytes
    }
    if (response.statusCode !in 200..299) {
        throw ResourceAcquisitionException(
            message = "$transportLabel transport returned a non-success status",
            resourceClass = resourceClass,
            sanitizedResourceId = sanitizedId,
            statusCode = response.statusCode,
            retryAfterMillis = response.metadata.retryAfterMillis,
        )
    }
    // Copied out once: TransportResponse.body hands back a fresh array on every read.
    val bytes = response.body
    if (limitName != null && bytes.size.toLong() > maxBytes) {
        throw SafetyLimitException(
            message = "$transportLabel resource exceeds its configured byte limit",
            limitName = limitName,
            limit = maxBytes,
            observed = bytes.size.toLong(),
            stage = PipelineStage.RESOURCE_ACQUISITION,
        )
    }
    metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = resourceClass))
    metricsSink.recordSafely(
        RentileMetric(
            name = MetricName.RESOURCE_WIRE_BYTES,
            value = response.metadata.wireByteCount ?: bytes.size.toLong(),
            resourceClass = resourceClass,
        ),
    )
    val metadata = RawResourceMetadata(
        contentType = response.metadata.contentType,
        etag = response.metadata.etag,
        lastModified = response.metadata.lastModified,
        freshUntilEpochMillis = response.metadata.expiresAtEpochMillis,
        storedAtEpochMillis = clock.nowEpochMillis(),
    )
    if (bytes.size.toLong() <= maxBytes && metadata.canBeReused()) {
        rawResourceStore.writeStore(
            key,
            StoredRawResource(bytes = bytes, contentDigest = bytes.sha256Hex(), metadata = metadata),
            "Raw $cacheLabel cache write failed",
        )
    }
    return bytes
}

/** A stored entry that passed integrity, its byte ceiling and the caller's usability check. */
private class ReusableRawResource(
    val bytes: ByteArray,
    val contentDigest: String,
    val metadata: RawResourceMetadata,
)

/**
 * The stored entry when it is reusable, evicting it when it is not.
 *
 * A torn or unusable entry is removed here rather than carried, because its validators would
 * otherwise make the next start ask the origin to confirm bytes this process could not read.
 */
private suspend fun RentileConfiguration.readReusableEntry(
    key: RawResourceKey,
    maxBytes: Long,
    cacheLabel: String,
    isStoredEntryUsable: (ByteArray) -> Boolean,
): ReusableRawResource? {
    val stored = rawResourceStore.readStore(key, "Raw $cacheLabel cache read failed") ?: return null
    val bytes = stored.bytes
    if (bytes.size.toLong() <= maxBytes &&
        bytes.sha256Hex() == stored.contentDigest &&
        isStoredEntryUsable(bytes)
    ) {
        return ReusableRawResource(bytes, stored.contentDigest, stored.metadata)
    }
    rawResourceStore.removeStore(key, "Corrupt $cacheLabel cache removal failed")
    return null
}

private suspend fun RentileConfiguration.rewriteStoredEntry(
    key: RawResourceKey,
    entry: ReusableRawResource,
    metadata: RawResourceMetadata,
    cacheLabel: String,
) {
    rawResourceStore.writeStore(
        key,
        StoredRawResource(bytes = entry.bytes, contentDigest = entry.contentDigest, metadata = metadata),
        "Raw $cacheLabel cache write failed",
    )
}

private fun RentileConfiguration.recordStoredResourceReuse(resourceClass: ResourceClass) {
    metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = resourceClass))
}

private fun RentileConfiguration.isFresh(metadata: RawResourceMetadata): Boolean {
    val freshUntil = metadata.freshUntilEpochMillis ?: return false
    return clock.nowEpochMillis() < freshUntil
}

/** Whether a later start could do anything with this entry other than throw it away. */
private fun RawResourceMetadata.canBeReused(): Boolean =
    etag != null || lastModified != null || freshUntilEpochMillis != null

/** A `304` may restate validators or freshness; anything it omits stays as it was stored. */
private fun com.rohittp.rentile.TransportResponseMetadata.mergedOnto(
    stored: RawResourceMetadata,
    nowEpochMillis: Long,
): RawResourceMetadata = RawResourceMetadata(
    contentType = contentType ?: stored.contentType,
    etag = etag ?: stored.etag,
    lastModified = lastModified ?: stored.lastModified,
    freshUntilEpochMillis = expiresAtEpochMillis ?: stored.freshUntilEpochMillis,
    storedAtEpochMillis = nowEpochMillis,
)
