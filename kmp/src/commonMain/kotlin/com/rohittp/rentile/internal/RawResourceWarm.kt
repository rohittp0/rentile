package com.rohittp.rentile.internal

import com.rohittp.rentile.MetricName
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceMetadata
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StoredRawResource
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.TransportResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Whether the store already holds [key], answered from its header alone.
 *
 * Deliberately neither hashed nor decoded. The probe used to read the whole payload back and
 * re-hash it, which on a disk-backed store meant warming an already-cached session read and hashed
 * that session -- precisely the work the prefetch existed to have already done. Integrity is the
 * read path's job either way: it re-hashes what it hands out and evicts what does not match, so a
 * torn entry is repaired on first use rather than at warm time. The only thing given up is
 * repairing it slightly earlier.
 */
internal suspend fun RentileConfiguration.isRawResourceStored(key: RawResourceKey): Boolean {
    val metadata = try {
        rawResourceStore.metadata(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException("Raw resource cache read failed")
    }
    return metadata != null
}

/**
 * Warms one raw resource on the single flight the on-demand acquisition of the same URL uses.
 *
 * One flight for both is the point. A cursor-ordered prefetch running alongside playback reaches,
 * by design, the tiles the renderer is about to ask for, so the two collide constantly; separate
 * flights meant both requests went out and the duplicate was charged to the very connection budget
 * the prefetch exists to spend well.
 *
 * **The exchange permit is taken before the flight rather than inside it, and that ordering is
 * load-bearing** (ADR 0031). It means a flight another coroutine can see is always one that already
 * holds its permit, so an acquisition that joins a prefetch waits for one exchange that is already
 * on the wire and never for a WARM queue position behind every queued acquisition. It is also why
 * the prefetch does not join a flight it did not create: it would be waiting, permit in hand, for
 * work that needs a permit.
 *
 * **The retry is therefore outside the permit, not inside the flight.** One attempt is one permit
 * and one exchange; a retryable failure leaves the gate, waits, and comes back for a fresh permit
 * and a fresh flight. Holding the permit across `Retry-After` would let a warm burst park every
 * permit in the gate asleep for up to [MAX_TILE_RETRY_DELAY_MILLIS] while acquisitions queued
 * behind them. The cost is that an acquisition which joined the first attempt receives its failure
 * rather than waiting for the prefetch's second try; for the substitutable classes prefetching
 * covers, those statuses are substitution-eligible, and the caller still owns recovery.
 *
 * Returns true when this call fetched, false when there was nothing for it to do because the
 * resource was already cached or already being acquired by someone else.
 */
internal suspend fun <V : Any> RentileConfiguration.warmRawResource(
    workCoordinator: ResourceWorkCoordinator,
    singleFlight: SingleFlight<RawResourceKey, V>,
    key: RawResourceKey,
    url: String,
    sanitizedId: String,
    resourceClass: ResourceClass,
    outputTile: TileId,
    flightValue: (bytes: ByteArray, contentDigest: String) -> V,
): Boolean {
    var retryAvailable = true
    while (true) {
        try {
            return workCoordinator.exchange(url, ResourcePriority.WARM) {
                // Accepted slack: cancelling this prefetch returns the permit here, while a joiner
                // that is still attached keeps the flight alive. The exchange it is waiting for is
                // already on the wire, so nothing new is started -- the gate is momentarily one
                // request over its count rather than one request short of its work.
                val fetched = singleFlight.tryRun(key) {
                    val warmed = fetchRawResourceForWarm(
                        url = url,
                        sanitizedId = sanitizedId,
                        resourceClass = resourceClass,
                        outputTile = outputTile,
                    )
                    val contentDigest = warmed.bytes.sha256Hex()
                    storeWarmedRawResource(key, warmed.bytes, contentDigest, warmed.response, resourceClass)
                    flightValue(warmed.bytes, contentDigest)
                }
                if (fetched == null) {
                    metricsSink.recordSafely(
                        RentileMetric(MetricName.WARM_ALREADY_IN_FLIGHT, resourceClass = resourceClass),
                    )
                }
                fetched != null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResourceAcquisitionException) {
            if (!retryAvailable || !error.isTransientWarmFailure()) throw error
            retryAvailable = false
            // The permit was returned on the way out of `exchange`, so this wait costs the gate
            // nothing and an acquisition can take the slot while it runs.
            val retryDelayMillis = error.retryAfterMillis?.coerceIn(0L, MAX_TILE_RETRY_DELAY_MILLIS) ?: 0L
            if (retryDelayMillis > 0L) delay(retryDelayMillis)
        }
    }
}

/** The statuses [executeTileRequestWithRetry] retries, asked of a failure instead of a response. */
private fun ResourceAcquisitionException.isTransientWarmFailure(): Boolean {
    // No status at all means the transport itself failed, which the decoding path also retries once.
    val status = statusCode ?: return true
    return status == 408 || status == 429 || status in 500..599
}

/**
 * Performs one warm exchange. The caller holds the exchange permit and owns the retry; see
 * [warmRawResource].
 *
 * Fetch-and-store without decode, written once rather than per acquirer because the raster and
 * vector paths differ only in resource class: everything that matters here -- the byte ceiling, the
 * digest, the metadata -- must behave identically to the decoding path, and two copies would drift.
 *
 * WARM: this only ever runs on a connection slot no acquisition wanted, so a prefetch can cover a
 * whole session without taking a slot from the work it is meant to help.
 */
internal suspend fun RentileConfiguration.fetchRawResourceForWarm(
    url: String,
    sanitizedId: String,
    resourceClass: ResourceClass,
    outputTile: TileId,
): WarmedRawResource {
    metricsSink.recordSafely(RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass))
    val response = try {
        transport.execute(
            TransportRequest(
                url = url,
                resourceClass = resourceClass,
                maxResponseBytes = resourceLimits.maxTileBytes,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceAcquisitionException(
            message = "Raw resource transport failed while warming",
            resourceClass = resourceClass,
            sanitizedResourceId = sanitizedId,
            affectedTiles = listOf(outputTile),
        )
    }
    if (response.statusCode !in 200..299) {
        throw ResourceAcquisitionException(
            message = "Raw resource transport returned a non-success status while warming",
            resourceClass = resourceClass,
            sanitizedResourceId = sanitizedId,
            statusCode = response.statusCode,
            retryAfterMillis = response.metadata.retryAfterMillis,
            affectedTiles = listOf(outputTile),
        )
    }
    // Copied out once: TransportResponse.body hands back a fresh array on every read.
    val bytes = response.body
    if (bytes.size.toLong() > resourceLimits.maxTileBytes) {
        throw SafetyLimitException(
            message = "Raw resource exceeds the configured encoded byte limit",
            limitName = "maxTileBytes",
            limit = resourceLimits.maxTileBytes,
            observed = bytes.size.toLong(),
            stage = PipelineStage.RESOURCE_ACQUISITION,
            affectedTiles = listOf(outputTile),
        )
    }
    return WarmedRawResource(bytes, response)
}

/** One warmed resource's bytes, copied out of [TransportResponse.body] exactly once. */
internal class WarmedRawResource(val bytes: ByteArray, val response: TransportResponse)

internal suspend fun RentileConfiguration.storeWarmedRawResource(
    key: RawResourceKey,
    bytes: ByteArray,
    contentDigest: String,
    response: TransportResponse,
    resourceClass: ResourceClass,
) {
    try {
        rawResourceStore.write(
            key,
            StoredRawResource(
                bytes = bytes,
                contentDigest = contentDigest,
                metadata = RawResourceMetadata(
                    contentType = response.metadata.contentType,
                    etag = response.metadata.etag,
                    lastModified = response.metadata.lastModified,
                    freshUntilEpochMillis = response.metadata.expiresAtEpochMillis,
                    storedAtEpochMillis = clock.nowEpochMillis(),
                ),
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException("Raw resource cache write failed")
    }
    metricsSink.recordSafely(
        RentileMetric(
            name = MetricName.RESOURCE_WIRE_BYTES,
            value = response.metadata.wireByteCount ?: bytes.size.toLong(),
            resourceClass = resourceClass,
        ),
    )
}
