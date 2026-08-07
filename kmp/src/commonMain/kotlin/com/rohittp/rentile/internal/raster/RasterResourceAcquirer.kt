package com.rohittp.rentile.internal.raster

import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.MetricName
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceMetadata
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
import com.rohittp.rentile.ResourceAccessMode
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StoredRawResource
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.internal.recordSafely
import com.rohittp.rentile.internal.ResourceWorkCoordinator
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.SingleFlight
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.Image

internal class RasterResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val singleFlight = SingleFlight<RawResourceKey, RasterResource>(scope)

    suspend fun acquire(sample: RasterSample, accessMode: ResourceAccessMode): RasterResource {
        val url = sample.tileUrl()
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        val key = RawResourceKey(sanitizedId, sample.source.resourceClass)

        if (accessMode != ResourceAccessMode.RELOAD) {
            val cached = readStore(key)
            if (cached != null) {
                val bytes = cached.bytes
                val actualDigest = bytes.sha256Hex()
                val dimensions = if (actualDigest == cached.contentDigest) {
                    validateRaster(bytes, sanitizedId, sample)
                } else {
                    null
                }
                if (dimensions != null) {
                    val diagnostic = cacheDiagnostic(DiagnosticCode.RESOURCE_CACHE_HIT, sanitizedId, sample)
                    configuration.metricsSink.recordSafely(
                        RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = sample.source.resourceClass),
                    )
                    configuration.diagnosticSink.recordSafely(diagnostic)
                    return RasterResource(
                        sample,
                        bytes,
                        actualDigest,
                        dimensions.width,
                        dimensions.height,
                        listOf(diagnostic),
                    )
                }
                removeStore(key)
            }
            configuration.metricsSink.recordSafely(
                RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = sample.source.resourceClass),
            )
            if (accessMode == ResourceAccessMode.CACHE_ONLY) {
                throw ResourceAcquisitionException(
                    message = "Raster resource is unavailable in cache-only mode",
                    resourceClass = sample.source.resourceClass,
                    sanitizedResourceId = sanitizedId,
                    affectedTiles = listOf(sample.outputTile),
                )
            }
        }

        val miss = cacheDiagnostic(DiagnosticCode.RESOURCE_CACHE_MISS, sanitizedId, sample)
        configuration.diagnosticSink.recordSafely(miss)
        val shared = singleFlight.run(
            key = key,
            onJoin = {
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.SINGLE_FLIGHT_JOIN, resourceClass = sample.source.resourceClass),
                )
            },
        ) {
            fetchValidateAndStore(sample, url, sanitizedId, key)
        }
        return shared.copy(sample = sample, diagnostics = listOf(miss))
    }

    private suspend fun fetchValidateAndStore(
        sample: RasterSample,
        url: String,
        sanitizedId: String,
        key: RawResourceKey,
    ): RasterResource {
        val response = workCoordinator.exchange(url) {
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = sample.source.resourceClass),
                )
                try {
                    configuration.transport.execute(
                        TransportRequest(
                            url = url,
                            resourceClass = sample.source.resourceClass,
                            maxResponseBytes = configuration.resourceLimits.maxTileBytes,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    throw ResourceAcquisitionException(
                        message = "Raster tile transport failed",
                        resourceClass = sample.source.resourceClass,
                        sanitizedResourceId = sanitizedId,
                        affectedTiles = listOf(sample.outputTile),
                    )
                }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "Raster tile transport returned a non-success status",
                resourceClass = sample.source.resourceClass,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
                affectedTiles = listOf(sample.outputTile),
            )
        }
        val bytes = response.body
        if (bytes.size.toLong() > configuration.resourceLimits.maxTileBytes) {
            throw SafetyLimitException(
                message = "Raster tile exceeds the configured encoded byte limit",
                limitName = "maxTileBytes",
                limit = configuration.resourceLimits.maxTileBytes,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
                affectedTiles = listOf(sample.outputTile),
            )
        }
        val dimensions = validateRasterOrThrow(bytes, sanitizedId, sample)
        val digest = bytes.sha256Hex()
        writeStore(
            key,
            StoredRawResource(
                bytes = bytes,
                contentDigest = digest,
                metadata = RawResourceMetadata(
                    contentType = response.metadata.contentType,
                    etag = response.metadata.etag,
                    lastModified = response.metadata.lastModified,
                    freshUntilEpochMillis = response.metadata.expiresAtEpochMillis,
                    storedAtEpochMillis = configuration.clock.nowEpochMillis(),
                ),
            ),
        )
        configuration.metricsSink.recordSafely(
            RentileMetric(
                MetricName.RESOURCE_WIRE_BYTES,
                value = response.metadata.wireByteCount ?: bytes.size.toLong(),
                resourceClass = sample.source.resourceClass,
            ),
        )
        return RasterResource(sample, bytes, digest, dimensions.width, dimensions.height, emptyList())
    }

    private suspend fun readStore(key: RawResourceKey): StoredRawResource? = try {
        configuration.rawResourceStore.read(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException("Raw resource cache read failed")
    }

    private suspend fun writeStore(key: RawResourceKey, resource: StoredRawResource) {
        try {
            configuration.rawResourceStore.write(key, resource)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Raw resource cache write failed")
        }
    }

    private suspend fun removeStore(key: RawResourceKey) {
        try {
            configuration.rawResourceStore.remove(key)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Corrupt raw resource cache removal failed")
        }
    }

    private suspend fun validateRaster(
        bytes: ByteArray,
        sanitizedId: String,
        sample: RasterSample,
    ): RasterDimensions? = try {
        validateRasterOrThrow(bytes, sanitizedId, sample)
    } catch (_: ResourceDecodeException) {
        null
    } catch (_: SafetyLimitException) {
        null
    }

    private suspend fun validateRasterOrThrow(
        bytes: ByteArray,
        sanitizedId: String,
        sample: RasterSample,
    ): RasterDimensions = workCoordinator.decode {
        val image = try {
            Image.makeFromEncoded(bytes)
        } catch (error: Throwable) {
            throw ResourceDecodeException(
                message = "Raster tile cannot be decoded",
                resourceClass = sample.source.resourceClass,
                sanitizedResourceId = sanitizedId,
                affectedTiles = listOf(sample.outputTile),
                cause = error,
            )
        }
        try {
            val maxDimension = configuration.resourceLimits.maxRasterDimensionPx
            val observedDimension = maxOf(image.width, image.height)
            if (image.width <= 0 || image.height <= 0 || observedDimension > maxDimension) {
                throw SafetyLimitException(
                    message = "Raster dimensions exceed the configured limit",
                    limitName = "maxRasterDimensionPx",
                    limit = maxDimension.toLong(),
                    observed = observedDimension.toLong(),
                    stage = PipelineStage.RESOURCE_DECODING,
                    affectedTiles = listOf(sample.outputTile),
                )
            }
            val decodedBytes = image.width.toLong() * image.height.toLong() * 4L
            val decodedLimit = minOf(
                configuration.resourceLimits.maxDecodedRasterBytes,
                configuration.executionPolicy.maxResidentDecodedBytes,
            )
            if (decodedBytes > decodedLimit) {
                throw SafetyLimitException(
                    message = "Decoded raster exceeds the configured memory limit",
                    limitName = "maxResidentDecodedBytes",
                    limit = decodedLimit,
                    observed = decodedBytes,
                    stage = PipelineStage.RESOURCE_DECODING,
                    affectedTiles = listOf(sample.outputTile),
                )
            }
            configuration.metricsSink.recordSafely(
                RentileMetric(
                    MetricName.RESOURCE_DECODED_BYTES,
                    value = decodedBytes,
                    resourceClass = sample.source.resourceClass,
                ),
            )
            return@decode RasterDimensions(image.width, image.height)
        } finally {
            image.close()
        }
    }

    private data class RasterDimensions(val width: Int, val height: Int)

    private fun cacheDiagnostic(code: DiagnosticCode, sanitizedId: String, sample: RasterSample): RenderDiagnostic =
        RenderDiagnostic(
            code = code,
            severity = DiagnosticSeverity.INFO,
            stage = PipelineStage.RESOURCE_ACQUISITION,
            message = if (code == DiagnosticCode.RESOURCE_CACHE_HIT) "Raw raster cache hit" else "Raw raster cache miss",
            details = mapOf("resourceId" to sanitizedId),
            affectedTiles = listOf(sample.outputTile),
        )
}
