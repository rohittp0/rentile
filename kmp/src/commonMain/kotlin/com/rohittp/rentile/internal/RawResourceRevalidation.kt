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
import com.rohittp.rentile.TransportResponse
import com.rohittp.rentile.TransportResponseMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val NOT_MODIFIED_STATUS: Int = 304

/**
 * Acquires the documents a style names — the style, its sprite JSON, its sprite image and every
 * source's TileJSON — through the raw store, serving what is stored and refreshing it afterwards.
 *
 * These four are what a process start must have before it can plan a single tile, and they were the
 * ones a warm start kept paying for. Written once for all four because they had drifted into three
 * different policies: the style went straight to the transport past the store entirely, while a
 * stored sprite or TileJSON was reused forever with nothing ever asking the origin about it.
 *
 * **A stored entry is returned immediately, whatever its freshness, and revalidated afterwards.**
 * Conditional revalidation in front of the caller was correct and too slow: production sends no
 * `Cache-Control` on any of these documents, so every preparation made about five conditional
 * requests that all answered `304` before anything was usable — one extra round trip on the path a
 * style switch is supposed to be instant on, and exposure to the multi-second tail one slow request
 * adds. Serving stale and refreshing behind the caller keeps the bytes correct on the *next*
 * preparation instead of paying for correctness on this one.
 *
 * The rules that follow from that:
 *
 * - A **fresh** entry (an explicit `max-age` or `Expires` that has not passed) needs no refresh at
 *   all, exactly as ADR 0007 says. Serving it rewrites the entry, because a consumer's raw cache is
 *   trimmed by file age and an entry read on every start and never written becomes its oldest file.
 * - A **stale** entry is served and one background revalidation is scheduled: conditional when the
 *   entry has validators, unconditional when it has none. `304` rewrites the entry, `200` replaces
 *   it, and any failure keeps what is stored. **A background refresh never fails a preparation**;
 *   the only trace of one going wrong is `BACKGROUND_REVALIDATION_FAILED`.
 * - The **style is included**, and this is the change with a visible consequence: a changed upstream
 *   style is picked up at the next preparation rather than this one. The consumer memoises its
 *   compiled style per process anyway, so a style can already only change between preparations; what
 *   this gives up is one preparation's worth of latency, and what it buys is a style switch that
 *   does not wait for the origin.
 * - **No stored entry** is the only case that reaches the transport in front of the caller, and it
 *   keeps the typed failure it always had.
 */
internal class RevalidatingResourceAcquirer(
    private val configuration: RentileConfiguration,
    private val scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val revalidatedMutex = Mutex()
    private val revalidated = mutableSetOf<RawResourceKey>()

    suspend fun acquire(
        key: RawResourceKey,
        url: String,
        sanitizedId: String,
        maxBytes: Long,
        transportLabel: String,
        cacheLabel: String,
        accept: String? = null,
        limitName: String? = null,
        isStoredEntryUsable: (ByteArray) -> Boolean = { true },
    ): ByteArray {
        val request = ResourceRequest(
            key = key,
            url = url,
            sanitizedId = sanitizedId,
            maxBytes = maxBytes,
            transportLabel = transportLabel,
            cacheLabel = cacheLabel,
            accept = accept,
            limitName = limitName,
            isStoredEntryUsable = isStoredEntryUsable,
        )
        val cached = readReusableEntry(request)
        if (cached != null) {
            configuration.metricsSink.recordSafely(
                RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = key.resourceClass),
            )
            if (isFresh(cached.metadata)) {
                rewriteStoredEntry(request, cached, cached.metadata)
            } else {
                scheduleRevalidation(request, cached)
            }
            return cached.bytes
        }
        return fetchAndStore(request)
    }

    /**
     * Starts at most one background revalidation per key per process.
     *
     * The memo is what bounds the work: a key revalidated once this run is not revalidated again,
     * which also makes a second, concurrent read of the same stale key a no-op — the claim and the
     * launch happen under one lock, so two readers cannot both start one. A separate in-flight
     * single flight would have nothing left to deduplicate.
     *
     * It runs in the rasterizer's scope rather than the caller's, because the caller has already
     * been served and is free to finish, and it must not outlive the rasterizer: closing cancels it
     * along with everything else.
     */
    private suspend fun scheduleRevalidation(request: ResourceRequest, cached: ReusableRawResource) {
        val claimed = revalidatedMutex.withLock { revalidated.add(request.key) }
        if (!claimed) return
        scope.launch { revalidate(request, cached) }
    }

    private suspend fun revalidate(request: ResourceRequest, cached: ReusableRawResource) {
        val resourceClass = request.key.resourceClass
        record(MetricName.BACKGROUND_REVALIDATION_STARTED, resourceClass)
        val response = try {
            // WARM: a refresh for the *next* preparation must never take a connection slot from the
            // tiles this one is drawing (ADR 0031).
            workCoordinator.exchange(request.url, ResourcePriority.WARM) {
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass),
                )
                configuration.transport.execute(request.conditionalRequest(cached.metadata))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            record(MetricName.BACKGROUND_REVALIDATION_FAILED, resourceClass)
            return
        }
        try {
            when {
                response.statusCode == NOT_MODIFIED_STATUS -> {
                    rewriteStoredEntry(
                        request,
                        cached,
                        response.metadata.mergedOnto(cached.metadata, configuration.clock.nowEpochMillis()),
                    )
                    record(MetricName.BACKGROUND_REVALIDATION_NOT_MODIFIED, resourceClass)
                }
                response.statusCode in 200..299 -> {
                    if (storeIfUsable(request, response)) {
                        record(MetricName.BACKGROUND_REVALIDATION_REPLACED, resourceClass)
                    } else {
                        record(MetricName.BACKGROUND_REVALIDATION_FAILED, resourceClass)
                    }
                }
                else -> record(MetricName.BACKGROUND_REVALIDATION_FAILED, resourceClass)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A store that cannot be written leaves the caller with the bytes it already has.
            record(MetricName.BACKGROUND_REVALIDATION_FAILED, resourceClass)
        }
    }

    /** Replaces the stored entry, unless the new bytes are unusable or over the ceiling. */
    private suspend fun storeIfUsable(request: ResourceRequest, response: TransportResponse): Boolean {
        val bytes = response.body
        if (bytes.size.toLong() > request.maxBytes || !request.isStoredEntryUsable(bytes)) return false
        configuration.rawResourceStore.writeStore(
            request.key,
            StoredRawResource(
                bytes = bytes,
                contentDigest = bytes.sha256Hex(),
                metadata = response.metadata.stored(configuration.clock.nowEpochMillis()),
            ),
            "Raw ${request.cacheLabel} cache write failed",
        )
        return true
    }

    /** The only path that reaches the transport in front of a caller: nothing is stored yet. */
    private suspend fun fetchAndStore(request: ResourceRequest): ByteArray {
        val resourceClass = request.key.resourceClass
        val response = workCoordinator.exchange(request.url) {
            configuration.metricsSink.recordSafely(
                RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass),
            )
            try {
                configuration.transport.execute(request.conditionalRequest(validators = null))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ResourceAcquisitionException(
                    message = "${request.transportLabel} transport failed",
                    resourceClass = resourceClass,
                    sanitizedResourceId = request.sanitizedId,
                )
            }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "${request.transportLabel} transport returned a non-success status",
                resourceClass = resourceClass,
                sanitizedResourceId = request.sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
            )
        }
        // Copied out once: TransportResponse.body hands back a fresh array on every read.
        val bytes = response.body
        if (request.limitName != null && bytes.size.toLong() > request.maxBytes) {
            throw SafetyLimitException(
                message = "${request.transportLabel} resource exceeds its configured byte limit",
                limitName = request.limitName,
                limit = request.maxBytes,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
            )
        }
        configuration.metricsSink.recordSafely(
            RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = resourceClass),
        )
        configuration.metricsSink.recordSafely(
            RentileMetric(
                name = MetricName.RESOURCE_WIRE_BYTES,
                value = response.metadata.wireByteCount ?: bytes.size.toLong(),
                resourceClass = resourceClass,
            ),
        )
        // Stored whatever validators came with it. Under stale-while-revalidate an entry with none
        // is still worth keeping: it is served immediately and refreshed unconditionally behind the
        // next caller, which is what production's header-less documents need.
        if (bytes.size.toLong() <= request.maxBytes) {
            configuration.rawResourceStore.writeStore(
                request.key,
                StoredRawResource(
                    bytes = bytes,
                    contentDigest = bytes.sha256Hex(),
                    metadata = response.metadata.stored(configuration.clock.nowEpochMillis()),
                ),
                "Raw ${request.cacheLabel} cache write failed",
            )
        }
        return bytes
    }

    /**
     * The stored entry when it is reusable, evicting it when it is not.
     *
     * A torn or unusable entry is removed here rather than carried, because its validators would
     * otherwise make the next start ask the origin to confirm bytes this process could not read.
     */
    private suspend fun readReusableEntry(request: ResourceRequest): ReusableRawResource? {
        val stored = configuration.rawResourceStore
            .readStore(request.key, "Raw ${request.cacheLabel} cache read failed") ?: return null
        val bytes = stored.bytes
        if (bytes.size.toLong() <= request.maxBytes &&
            bytes.sha256Hex() == stored.contentDigest &&
            request.isStoredEntryUsable(bytes)
        ) {
            return ReusableRawResource(bytes, stored.contentDigest, stored.metadata)
        }
        configuration.rawResourceStore.removeStore(request.key, "Corrupt ${request.cacheLabel} cache removal failed")
        return null
    }

    private suspend fun rewriteStoredEntry(
        request: ResourceRequest,
        entry: ReusableRawResource,
        metadata: RawResourceMetadata,
    ) {
        configuration.rawResourceStore.writeStore(
            request.key,
            StoredRawResource(bytes = entry.bytes, contentDigest = entry.contentDigest, metadata = metadata),
            "Raw ${request.cacheLabel} cache write failed",
        )
    }

    private fun record(name: MetricName, resourceClass: ResourceClass) {
        configuration.metricsSink.recordSafely(RentileMetric(name, resourceClass = resourceClass))
    }

    private fun isFresh(metadata: RawResourceMetadata): Boolean {
        val freshUntil = metadata.freshUntilEpochMillis ?: return false
        return configuration.clock.nowEpochMillis() < freshUntil
    }
}

/** Everything one closure-document acquisition needs, so the background half can outlive its call. */
private class ResourceRequest(
    val key: RawResourceKey,
    val url: String,
    val sanitizedId: String,
    val maxBytes: Long,
    val transportLabel: String,
    val cacheLabel: String,
    val accept: String?,
    val limitName: String?,
    val isStoredEntryUsable: (ByteArray) -> Boolean,
) {
    fun conditionalRequest(validators: RawResourceMetadata?): TransportRequest = TransportRequest(
        url = url,
        resourceClass = key.resourceClass,
        maxResponseBytes = maxBytes,
        metadata = TransportRequestMetadata(
            ifNoneMatch = validators?.etag,
            ifModifiedSince = validators?.lastModified,
            accept = accept,
        ),
    )
}

/** A stored entry that passed integrity, its byte ceiling and the caller's usability check. */
private class ReusableRawResource(
    val bytes: ByteArray,
    val contentDigest: String,
    val metadata: RawResourceMetadata,
)

private fun TransportResponseMetadata.stored(nowEpochMillis: Long): RawResourceMetadata = RawResourceMetadata(
    contentType = contentType,
    etag = etag,
    lastModified = lastModified,
    freshUntilEpochMillis = expiresAtEpochMillis,
    storedAtEpochMillis = nowEpochMillis,
)

/** A `304` may restate validators or freshness; anything it omits stays as it was stored. */
private fun TransportResponseMetadata.mergedOnto(
    stored: RawResourceMetadata,
    nowEpochMillis: Long,
): RawResourceMetadata = RawResourceMetadata(
    contentType = contentType ?: stored.contentType,
    etag = etag ?: stored.etag,
    lastModified = lastModified ?: stored.lastModified,
    freshUntilEpochMillis = expiresAtEpochMillis ?: stored.freshUntilEpochMillis,
    storedAtEpochMillis = nowEpochMillis,
)
