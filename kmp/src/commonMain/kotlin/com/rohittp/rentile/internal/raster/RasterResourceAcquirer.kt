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
import com.rohittp.rentile.internal.isRawResourceStoredIntact
import com.rohittp.rentile.internal.fetchRawResourceForWarm
import com.rohittp.rentile.internal.storeWarmedRawResource
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.SingleFlight
import com.rohittp.rentile.internal.executeTileRequestWithRetry
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal class RasterResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val singleFlight = SingleFlight<RasterFlightKey, RasterResource>(scope)

    /**
     * Acquires one raster resource, optionally keeping the pixels its validation decode produces.
     *
     * [retainPixels] is off for every ordinary raster path, so nothing but a caller that asked
     * holds a decoded copy. It is on for terrain acquisition, whose whole purpose is to hand those
     * pixels to a consumer that has no decoder for the container the provider chose.
     */
    suspend fun acquire(
        sample: RasterSample,
        accessMode: ResourceAccessMode,
        retainPixels: Boolean = false,
    ): RasterResource {
        val url = sample.tileUrl()
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        val key = RawResourceKey(sanitizedId, sample.source.resourceClass)

        if (accessMode != ResourceAccessMode.RELOAD) {
            val cached = readStore(key)
            if (cached != null) {
                val bytes = cached.bytes
                val actualDigest = bytes.sha256Hex()
                val decoded = if (actualDigest == cached.contentDigest) {
                    validateRaster(bytes, sanitizedId, sample, retainPixels)
                } else {
                    null
                }
                if (decoded != null) {
                    val diagnostic = cacheDiagnostic(DiagnosticCode.RESOURCE_CACHE_HIT, sanitizedId, sample)
                    configuration.metricsSink.recordSafely(
                        RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = sample.source.resourceClass),
                    )
                    configuration.diagnosticSink.recordSafely(diagnostic)
                    return RasterResource(
                        sample,
                        bytes,
                        actualDigest,
                        decoded.width,
                        decoded.height,
                        listOf(diagnostic),
                        rgba = decoded.rgba,
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
            key = RasterFlightKey(key, retainPixels),
            onJoin = {
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.SINGLE_FLIGHT_JOIN, resourceClass = sample.source.resourceClass),
                )
            },
        ) {
            fetchValidateAndStore(sample, url, sanitizedId, key, retainPixels)
        }
        return shared.copy(sample = sample, diagnostics = listOf(miss))
    }

    /**
     * Fetches [sample]'s bytes into the raw cache without decoding them.
     *
     * The point of not decoding is CPU, not tidiness. A prefetch exists to fill idle network time
     * while rasterization runs; decoding as it went would take the cores the rasterizer needs, which
     * is how an earlier whole-set read-ahead built on `prepareBatch` turned into a 5-7x regression.
     *
     * Skipping the validation decode means bytes reach the cache unvalidated, which is safe because
     * every cache read already re-validates and evicts what it cannot decode. An undecodable entry
     * therefore costs one wasted store and heals on first read.
     *
     * Returns true when a fetch happened, false when the entry was already cached. Per-resource
     * failures are the caller's to swallow: a prefetch must never fail the work it is trying to
     * help, and the on-demand path will surface anything genuinely wrong.
     */
    suspend fun warm(sample: RasterSample, accessMode: ResourceAccessMode): Boolean {
        val url = sample.tileUrl()
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        val key = RawResourceKey(sanitizedId, sample.source.resourceClass)
        if (accessMode != ResourceAccessMode.RELOAD &&
            configuration.isRawResourceStoredIntact(key)
        ) {
            return false
        }
        val response = configuration.fetchRawResourceForWarm(
            workCoordinator = workCoordinator,
            url = url,
            sanitizedId = sanitizedId,
            resourceClass = sample.source.resourceClass,
            outputTile = sample.outputTile,
        )
        configuration.storeWarmedRawResource(key, response, sample.source.resourceClass)
        return true
    }

    private suspend fun fetchValidateAndStore(
        sample: RasterSample,
        url: String,
        sanitizedId: String,
        key: RawResourceKey,
        retainPixels: Boolean,
    ): RasterResource {
        val response = executeTileRequestWithRetry {
            workCoordinator.exchange(url) {
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
        val decoded = validateRasterOrThrow(bytes, sanitizedId, sample, retainPixels)
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
        return RasterResource(
            sample,
            bytes,
            digest,
            decoded.width,
            decoded.height,
            emptyList(),
            rgba = decoded.rgba,
        )
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
        retainPixels: Boolean,
    ): DecodedRaster? = try {
        validateRasterOrThrow(bytes, sanitizedId, sample, retainPixels)
    } catch (_: ResourceDecodeException) {
        null
    } catch (_: SafetyLimitException) {
        null
    }

    private suspend fun validateRasterOrThrow(
        bytes: ByteArray,
        sanitizedId: String,
        sample: RasterSample,
        retainPixels: Boolean,
    ): DecodedRaster = workCoordinator.decode {
        // Two decoders, one decode either way.
        //
        // Image.makeFromEncoded reports PREMUL: it associates colour with alpha while decoding, so
        // a red of 200 behind an alpha of 128 is stored as 100 and comes back out as 199. In a
        // picture that is invisible. In a DEM those channels are an elevation, so the same
        // rounding silently moves the ground. Codec reports the file's own unassociated alpha, and
        // performs no colour conversion when the destination names no colour space, so the
        // retaining path uses it and never builds an Image at all. Every other raster keeps the
        // Image path exactly as it was.
        if (retainPixels) {
            decodeUnassociated(bytes, sanitizedId, sample)
        } else {
            decodeForValidationOnly(bytes, sanitizedId, sample)
        }
    }

    private fun decodeForValidationOnly(
        bytes: ByteArray,
        sanitizedId: String,
        sample: RasterSample,
    ): DecodedRaster {
        val image = try {
            Image.makeFromEncoded(bytes)
        } catch (error: Throwable) {
            throw undecodableTile(sanitizedId, sample, error)
        }
        try {
            enforceRasterLimits(image.width, image.height, sample)
            return DecodedRaster(image.width, image.height, null)
        } finally {
            image.close()
        }
    }

    /**
     * Decodes to canonical RGBA8 - unpremultiplied, top-down, tightly packed at `width * 4` bytes
     * per row - and keeps the pixels.
     *
     * Every part of that is stated rather than inherited. `allocN32Pixels(width, height, false)`,
     * the idiom used where pixels only have to look right, is premultiplied, and N32's channel
     * order is whichever the platform prefers rather than a fixed one; either would make one DEM
     * decode to different elevations on different targets. The destination carries no colour
     * space, so the codec applies no colour transform even when the image declares a profile.
     *
     * Dimensions are checked against the configured limits before anything is allocated, because
     * the header is what an attacker controls.
     */
    private fun decodeUnassociated(
        bytes: ByteArray,
        sanitizedId: String,
        sample: RasterSample,
    ): DecodedRaster {
        val data = Data.makeFromBytes(bytes)
        try {
            val codec = try {
                Codec.makeFromData(data)
            } catch (error: Throwable) {
                throw undecodableTile(sanitizedId, sample, error)
            }
            try {
                val width = codec.imageInfo.width
                val height = codec.imageInfo.height
                enforceRasterLimits(width, height, sample)
                val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
                val rowBytes = width * 4
                val bitmap = Bitmap()
                try {
                    val rgba = try {
                        if (bitmap.allocPixels(info, rowBytes)) {
                            codec.readPixels(bitmap)
                            bitmap.readPixels(info, rowBytes)
                        } else {
                            null
                        }
                    } catch (error: Throwable) {
                        throw unreadableTilePixels(sanitizedId, sample, error)
                    }
                    return DecodedRaster(width, height, rgba ?: throw unreadableTilePixels(sanitizedId, sample, null))
                } finally {
                    bitmap.close()
                }
            } finally {
                codec.close()
            }
        } finally {
            data.close()
        }
    }

    private fun enforceRasterLimits(width: Int, height: Int, sample: RasterSample) {
        val maxDimension = configuration.resourceLimits.maxRasterDimensionPx
        val observedDimension = maxOf(width, height)
        if (width <= 0 || height <= 0 || observedDimension > maxDimension) {
            throw SafetyLimitException(
                message = "Raster dimensions exceed the configured limit",
                limitName = "maxRasterDimensionPx",
                limit = maxDimension.toLong(),
                observed = observedDimension.toLong(),
                stage = PipelineStage.RESOURCE_DECODING,
                affectedTiles = listOf(sample.outputTile),
            )
        }
        val decodedBytes = width.toLong() * height.toLong() * 4L
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
    }

    private fun undecodableTile(sanitizedId: String, sample: RasterSample, cause: Throwable?) =
        ResourceDecodeException(
            message = "Raster tile cannot be decoded",
            resourceClass = sample.source.resourceClass,
            sanitizedResourceId = sanitizedId,
            affectedTiles = listOf(sample.outputTile),
            cause = cause,
        )

    private fun unreadableTilePixels(sanitizedId: String, sample: RasterSample, cause: Throwable?) =
        ResourceDecodeException(
            message = "Raster tile pixels cannot be read",
            resourceClass = sample.source.resourceClass,
            sanitizedResourceId = sanitizedId,
            affectedTiles = listOf(sample.outputTile),
            cause = cause,
        )

    private data class DecodedRaster(val width: Int, val height: Int, val rgba: ByteArray?)

    /**
     * In-process dedupe identity, which includes retention because a flight has one result shared
     * by every joiner. A caller that needs pixels cannot be served by a flight created by one that
     * did not, and the alternative - retaining on every DEM fetch regardless of who asked - would
     * make each hillshade preparation decode and discard a megabyte per source tile.
     */
    private data class RasterFlightKey(val resource: RawResourceKey, val retainPixels: Boolean)

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
