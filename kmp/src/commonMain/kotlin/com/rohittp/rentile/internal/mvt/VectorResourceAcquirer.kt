package com.rohittp.rentile.internal.mvt

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
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StoredRawResource
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.internal.ResourceWorkCoordinator
import com.rohittp.rentile.internal.SingleFlight
import com.rohittp.rentile.internal.executeTileRequestWithRetry
import com.rohittp.rentile.internal.recordSafely
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class VectorResource(
    val sample: VectorTileSample,
    val tile: DecodedVectorTile,
    val contentDigest: String,
    val diagnostics: List<RenderDiagnostic>,
    val encodedBytes: ByteArray? = null,
    val exactSample: VectorTileSample = sample,
    val substitution: ResourceSubstitution? = null,
)

internal class VectorResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val decoder = MvtDecoder(configuration.resourceLimits)
    private val singleFlight = SingleFlight<RawResourceKey, VectorResource>(scope)

    suspend fun acquire(sample: VectorTileSample, accessMode: ResourceAccessMode): VectorResource {
        sample.source.geoJson?.let { geoJson ->
            return VectorResource(
                sample = sample,
                tile = projectGeoJson(sample, geoJson),
                contentDigest = geoJson.contentDigest,
                diagnostics = emptyList(),
            )
        }
        val url = sample.tileUrl()
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        val key = RawResourceKey(sanitizedId, ResourceClass.VECTOR_TILE)

        if (accessMode != ResourceAccessMode.RELOAD) {
            val cached = readStore(key)
            if (cached != null) {
                val bytes = cached.bytes
                val actualDigest = bytes.sha256Hex()
                val decoded = if (actualDigest == cached.contentDigest) decodeOrNull(bytes, sanitizedId, sample) else null
                if (decoded != null) {
                    val diagnostic = cacheDiagnostic(DiagnosticCode.RESOURCE_CACHE_HIT, sanitizedId, sample)
                    configuration.metricsSink.recordSafely(
                        RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = ResourceClass.VECTOR_TILE),
                    )
                    configuration.diagnosticSink.recordSafely(diagnostic)
                    return VectorResource(
                        sample = sample,
                        tile = decoded,
                        contentDigest = actualDigest,
                        diagnostics = listOf(diagnostic),
                        encodedBytes = bytes,
                    )
                }
                removeStore(key)
            }
            configuration.metricsSink.recordSafely(
                RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = ResourceClass.VECTOR_TILE),
            )
            if (accessMode == ResourceAccessMode.CACHE_ONLY) {
                throw ResourceAcquisitionException(
                    message = "Vector resource is unavailable in cache-only mode",
                    resourceClass = ResourceClass.VECTOR_TILE,
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
                    RentileMetric(MetricName.SINGLE_FLIGHT_JOIN, resourceClass = ResourceClass.VECTOR_TILE),
                )
            },
        ) {
            fetchDecodeAndStore(sample, url, sanitizedId, key)
        }
        return shared.copy(sample = sample, diagnostics = listOf(miss))
    }

    private suspend fun fetchDecodeAndStore(
        sample: VectorTileSample,
        url: String,
        sanitizedId: String,
        key: RawResourceKey,
    ): VectorResource {
        val response = executeTileRequestWithRetry {
            workCoordinator.exchange(url) {
            configuration.metricsSink.recordSafely(
                RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = ResourceClass.VECTOR_TILE),
            )
            try {
                configuration.transport.execute(
                    TransportRequest(
                        url = url,
                        resourceClass = ResourceClass.VECTOR_TILE,
                        maxResponseBytes = configuration.resourceLimits.maxTileBytes,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ResourceAcquisitionException(
                    message = "Vector tile transport failed",
                    resourceClass = ResourceClass.VECTOR_TILE,
                    sanitizedResourceId = sanitizedId,
                    affectedTiles = listOf(sample.outputTile),
                )
            }
            }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "Vector tile transport returned a non-success status",
                resourceClass = ResourceClass.VECTOR_TILE,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
                affectedTiles = listOf(sample.outputTile),
            )
        }
        val bytes = response.body
        val byteLimit = configuration.resourceLimits.maxTileBytes
        if (bytes.size.toLong() > byteLimit) {
            throw SafetyLimitException(
                message = "Vector tile exceeds the configured encoded byte limit",
                limitName = "maxTileBytes",
                limit = byteLimit,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
                affectedTiles = listOf(sample.outputTile),
            )
        }
        val decoded = decodeOrThrow(bytes, sanitizedId, sample)
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
                name = MetricName.RESOURCE_WIRE_BYTES,
                value = response.metadata.wireByteCount ?: bytes.size.toLong(),
                resourceClass = ResourceClass.VECTOR_TILE,
            ),
        )
        return VectorResource(
            sample = sample,
            tile = decoded,
            contentDigest = digest,
            diagnostics = emptyList(),
            encodedBytes = bytes,
        )
    }

    private suspend fun decodeOrNull(
        bytes: ByteArray,
        sanitizedId: String,
        sample: VectorTileSample,
    ): DecodedVectorTile? = try {
        decodeOrThrow(bytes, sanitizedId, sample)
    } catch (_: ResourceDecodeException) {
        null
    } catch (_: SafetyLimitException) {
        null
    }

    private suspend fun decodeOrThrow(
        bytes: ByteArray,
        sanitizedId: String,
        sample: VectorTileSample,
    ): DecodedVectorTile = workCoordinator.decode {
        try {
            decoder.decode(bytes)
        } catch (error: MvtDecodingException) {
            val limitName = error.limitName
            if (limitName != null) {
                throw SafetyLimitException(
                    message = "Vector tile exceeds a configured decode limit",
                    limitName = limitName,
                    limit = error.limit ?: 0L,
                    observed = error.observed ?: 0L,
                    stage = PipelineStage.RESOURCE_DECODING,
                    affectedTiles = listOf(sample.outputTile),
                )
            }
            throw ResourceDecodeException(
                message = "Vector tile cannot be decoded",
                resourceClass = ResourceClass.VECTOR_TILE,
                sanitizedResourceId = sanitizedId,
                affectedTiles = listOf(sample.outputTile),
                cause = error,
            )
        }
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

    private fun cacheDiagnostic(
        code: DiagnosticCode,
        sanitizedId: String,
        sample: VectorTileSample,
    ): RenderDiagnostic = RenderDiagnostic(
        code = code,
        severity = DiagnosticSeverity.INFO,
        stage = PipelineStage.RESOURCE_ACQUISITION,
        message = if (code == DiagnosticCode.RESOURCE_CACHE_HIT) "Raw vector cache hit" else "Raw vector cache miss",
        details = mapOf("resourceId" to sanitizedId),
        affectedTiles = listOf(sample.outputTile),
    )

    private fun projectGeoJson(
        sample: VectorTileSample,
        data: com.rohittp.rentile.internal.style.CompiledGeoJsonData,
    ): DecodedVectorTile {
        val dimension = 1L shl sample.outputTile.z
        val tileX = sample.sourceX.toDouble()
        val tileY = sample.sourceY.toDouble()
        val features = data.features.mapNotNull { feature ->
            val clippedLines = feature.lines.flatMap { line ->
                line.zipWithNext().flatMap { (start, end) ->
                    val startWorld = start.toWorldCoordinate()
                    var endWorldX = end.toWorldCoordinate().first
                    if (abs(end.longitude - start.longitude) < 359.999) {
                        if (endWorldX - startWorld.first > 0.5) endWorldX -= 1.0
                        if (startWorld.first - endWorldX > 0.5) endWorldX += 1.0
                    }
                    val endWorld = endWorldX to end.toWorldCoordinate().second
                    listOf(-1.0, 0.0, 1.0).mapNotNull { wrap ->
                        clipSegment(
                            x0 = (startWorld.first + wrap) * dimension - tileX,
                            y0 = startWorld.second * dimension - tileY,
                            x1 = (endWorld.first + wrap) * dimension - tileX,
                            y1 = endWorld.second * dimension - tileY,
                            minimum = -GEO_JSON_TILE_HALO,
                            maximum = 1.0 + GEO_JSON_TILE_HALO,
                        )?.map { point ->
                            VectorCoordinate(
                                x = (point.first * GEO_JSON_EXTENT).roundToInt(),
                                y = (point.second * GEO_JSON_EXTENT).roundToInt(),
                            )
                        }?.takeIf { points -> points[0] != points[1] }
                    }
                }
            }
            if (clippedLines.isEmpty()) return@mapNotNull null
            DecodedVectorFeature(
                id = null,
                geometryType = com.rohittp.rentile.internal.style.FeatureGeometryType.LINE_STRING,
                properties = feature.properties,
                geometry = DecodedVectorGeometry.Lines(clippedLines),
            )
        }
        return DecodedVectorTile(
            layers = listOf(
                DecodedVectorLayer(
                    name = com.rohittp.rentile.internal.style.GEO_JSON_SOURCE_LAYER,
                    extent = GEO_JSON_EXTENT,
                    features = features,
                ),
            ),
        )
    }

    private fun com.rohittp.rentile.internal.style.GeoJsonPosition.toWorldCoordinate(): Pair<Double, Double> {
        val clampedLatitude = latitude.coerceIn(-MERCATOR_LATITUDE_LIMIT, MERCATOR_LATITUDE_LIMIT)
        val latitudeRadians = clampedLatitude * PI / 180.0
        val worldX = (longitude + 180.0) / 360.0
        val worldY = 0.5 - ln((1.0 + sin(latitudeRadians)) / (1.0 - sin(latitudeRadians))) / (4.0 * PI)
        return worldX to worldY
    }

    private fun clipSegment(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        minimum: Double,
        maximum: Double,
    ): List<Pair<Double, Double>>? {
        val dx = x1 - x0
        val dy = y1 - y0
        var start = 0.0
        var end = 1.0

        fun clip(p: Double, q: Double): Boolean {
            if (p == 0.0) return q >= 0.0
            val ratio = q / p
            if (p < 0.0) {
                if (ratio > end) return false
                if (ratio > start) start = ratio
            } else {
                if (ratio < start) return false
                if (ratio < end) end = ratio
            }
            return true
        }

        if (!clip(-dx, x0 - minimum) ||
            !clip(dx, maximum - x0) ||
            !clip(-dy, y0 - minimum) ||
            !clip(dy, maximum - y0)
        ) return null
        return listOf(
            (x0 + start * dx) to (y0 + start * dy),
            (x0 + end * dx) to (y0 + end * dy),
        )
    }

    private companion object {
        const val GEO_JSON_EXTENT = 4096
        const val GEO_JSON_TILE_HALO = 0.125
        const val MERCATOR_LATITUDE_LIMIT = 85.0511287798066
    }
}
