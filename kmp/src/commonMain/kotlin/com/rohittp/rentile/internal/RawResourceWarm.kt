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
 * Fetch-and-store without decode, shared by the raster and vector acquirers.
 *
 * Written once rather than per acquirer because the two differ only in resource class: everything
 * that matters here -- the retry, the origin permit, the byte ceiling, the digest, the metadata --
 * must behave identically to the decoding path, and two copies would drift.
 */
internal suspend fun RentileConfiguration.isRawResourceStoredIntact(key: RawResourceKey): Boolean {
    val stored = try {
        rawResourceStore.read(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException("Raw resource cache read failed")
    } ?: return false
    // Digest-checked but not decoded. A torn or truncated entry is worth refetching now; an entry
    // that is intact but undecodable is left for the read path, which evicts it on first use.
    return stored.bytes.sha256Hex() == stored.contentDigest
}

internal suspend fun RentileConfiguration.fetchRawResourceForWarm(
    workCoordinator: ResourceWorkCoordinator,
    url: String,
    sanitizedId: String,
    resourceClass: ResourceClass,
    outputTile: TileId,
): TransportResponse {
    val response = executeTileRequestWithRetry {
        // WARM: this only ever runs on a connection slot no acquisition wanted, so a prefetch can
        // cover a whole session without taking a slot from the work it is meant to help.
        workCoordinator.exchange(url, ResourcePriority.WARM) {
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
    response: TransportResponse,
    resourceClass: ResourceClass,
) {
    val bytes = response.body
    try {
        rawResourceStore.write(
            key,
            StoredRawResource(
                bytes = bytes,
                contentDigest = bytes.sha256Hex(),
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
