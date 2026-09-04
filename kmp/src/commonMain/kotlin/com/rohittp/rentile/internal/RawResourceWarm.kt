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
 * load-bearing.** It means a flight another coroutine can see is always one that already holds its
 * permits, so an acquisition that joins a prefetch waits for one in-flight exchange -- the bound
 * [PriorityGate] already documents -- and never for a WARM queue position behind every queued
 * acquisition. It is also why the prefetch does not join a flight it did not create: it would be
 * waiting, permit in hand, for work that needs a permit.
 *
 * The price of that ordering is that [fetchRawResourceForWarm]'s retry now runs under the permit,
 * so a throttled prefetch holds its slot across the `Retry-After` wait as well as both attempts --
 * bounded by [MAX_TILE_RETRY_DELAY_MILLIS]. Releasing the permit between attempts would put a
 * flight that joiners are already attached to back in the WARM queue, which is the wait this
 * ordering exists to prevent.
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
): Boolean = workCoordinator.exchange(url, ResourcePriority.WARM) {
    val fetched = singleFlight.tryRun(key) {
        val response = fetchRawResourceForWarm(
            url = url,
            sanitizedId = sanitizedId,
            resourceClass = resourceClass,
            outputTile = outputTile,
        )
        val bytes = response.body
        val contentDigest = bytes.sha256Hex()
        storeWarmedRawResource(key, bytes, contentDigest, response, resourceClass)
        flightValue(bytes, contentDigest)
    }
    fetched != null
}

/**
 * Performs the warm exchange itself. The caller holds the exchange permit; see [warmRawResource].
 *
 * Fetch-and-store without decode, written once rather than per acquirer because the raster and
 * vector paths differ only in resource class: everything that matters here -- the retry, the byte
 * ceiling, the digest, the metadata -- must behave identically to the decoding path, and two copies
 * would drift.
 *
 * WARM: this only ever runs on a connection slot no acquisition wanted, so a prefetch can cover a
 * whole session without taking a slot from the work it is meant to help.
 */
internal suspend fun RentileConfiguration.fetchRawResourceForWarm(
    url: String,
    sanitizedId: String,
    resourceClass: ResourceClass,
    outputTile: TileId,
): TransportResponse {
    val response = executeTileRequestWithRetry {
        metricsSink.recordSafely(RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass))
        try {
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
    if (response.body.size.toLong() > resourceLimits.maxTileBytes) {
        throw SafetyLimitException(
            message = "Raw resource exceeds the configured encoded byte limit",
            limitName = "maxTileBytes",
            limit = resourceLimits.maxTileBytes,
            observed = response.body.size.toLong(),
            stage = PipelineStage.RESOURCE_ACQUISITION,
            affectedTiles = listOf(outputTile),
        )
    }
    return response
}

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
