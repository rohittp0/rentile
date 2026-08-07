package com.rohittp.rentile.internal.geojson

import com.rohittp.rentile.MetricName
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RawResourceMetadata
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.RentileMetric
import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.ResourceStoreException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.StoredRawResource
import com.rohittp.rentile.TransportRequest
import com.rohittp.rentile.internal.ResourceWorkCoordinator
import com.rohittp.rentile.internal.SingleFlight
import com.rohittp.rentile.internal.recordSafely
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.style.CompiledGeoJsonData
import com.rohittp.rentile.internal.style.CompiledGeoJsonLineFeature
import com.rohittp.rentile.internal.style.GeoJsonPosition
import com.rohittp.rentile.internal.style.StyleValue
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

internal class GeoJsonResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val json = Json { isLenient = false }
    private val singleFlight = SingleFlight<RawResourceKey, CompiledGeoJsonData>(scope)

    suspend fun acquire(url: String): CompiledGeoJsonData {
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        val key = RawResourceKey(sanitizedId, ResourceClass.GEO_JSON)
        val cached = readStore(key)
        if (cached != null) {
            val bytes = cached.bytes
            if (bytes.sha256Hex() == cached.contentDigest) {
                parseOrNull(bytes, sanitizedId)?.let { parsed ->
                    configuration.metricsSink.recordSafely(
                        RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = ResourceClass.GEO_JSON),
                    )
                    return parsed.copy(contentDigest = cached.contentDigest)
                }
            }
            removeStore(key)
        }
        configuration.metricsSink.recordSafely(
            RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = ResourceClass.GEO_JSON),
        )
        return singleFlight.run(
            key = key,
            onJoin = {
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.SINGLE_FLIGHT_JOIN, resourceClass = ResourceClass.GEO_JSON),
                )
            },
        ) {
            fetchParseAndStore(url, sanitizedId, key)
        }
    }

    private suspend fun fetchParseAndStore(
        url: String,
        sanitizedId: String,
        key: RawResourceKey,
    ): CompiledGeoJsonData {
        val response = workCoordinator.exchange(url) {
            configuration.metricsSink.recordSafely(
                RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = ResourceClass.GEO_JSON),
            )
            try {
                configuration.transport.execute(
                    TransportRequest(
                        url = url,
                        resourceClass = ResourceClass.GEO_JSON,
                        maxResponseBytes = configuration.resourceLimits.maxGeoJsonBytes,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ResourceAcquisitionException(
                    message = "GeoJSON transport failed",
                    resourceClass = ResourceClass.GEO_JSON,
                    sanitizedResourceId = sanitizedId,
                )
            }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "GeoJSON transport returned a non-success status",
                resourceClass = ResourceClass.GEO_JSON,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
            )
        }
        val bytes = response.body
        val limit = configuration.resourceLimits.maxGeoJsonBytes
        if (bytes.size.toLong() > limit) {
            throw SafetyLimitException(
                message = "GeoJSON exceeds the configured byte limit",
                limitName = "maxGeoJsonBytes",
                limit = limit,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
            )
        }
        val parsed = workCoordinator.decode { parseOrThrow(bytes, sanitizedId) }
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
                resourceClass = ResourceClass.GEO_JSON,
            ),
        )
        return parsed.copy(contentDigest = digest)
    }

    private fun parseOrNull(bytes: ByteArray, sanitizedId: String): CompiledGeoJsonData? = try {
        parseOrThrow(bytes, sanitizedId)
    } catch (_: ResourceDecodeException) {
        null
    } catch (_: SafetyLimitException) {
        null
    }

    private fun parseOrThrow(bytes: ByteArray, sanitizedId: String): CompiledGeoJsonData {
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: failDecode(sanitizedId, "GeoJSON root must be an object")
        } catch (error: ResourceDecodeException) {
            throw error
        } catch (_: SerializationException) {
            failDecode(sanitizedId, "GeoJSON is malformed")
        } catch (_: IllegalArgumentException) {
            failDecode(sanitizedId, "GeoJSON is not valid UTF-8 JSON")
        }
        if (root.string("type", sanitizedId) != "FeatureCollection") {
            failDecode(sanitizedId, "GeoJSON root must be a FeatureCollection")
        }
        val featureElements = root["features"] as? JsonArray
            ?: failDecode(sanitizedId, "GeoJSON FeatureCollection must contain a feature array")
        val featureLimit = configuration.resourceLimits.maxMvtFeatures
        if (featureElements.size > featureLimit) {
            throw SafetyLimitException(
                message = "GeoJSON feature count exceeds the configured limit",
                limitName = "maxMvtFeatures",
                limit = featureLimit.toLong(),
                observed = featureElements.size.toLong(),
                stage = PipelineStage.RESOURCE_DECODING,
            )
        }
        var coordinateCount = 0L
        val features = featureElements.map { element ->
            val feature = element as? JsonObject ?: failDecode(sanitizedId, "GeoJSON feature must be an object")
            if (feature.string("type", sanitizedId) != "Feature") {
                failDecode(sanitizedId, "GeoJSON feature type must be Feature")
            }
            val geometry = feature["geometry"] as? JsonObject
                ?: failDecode(sanitizedId, "GeoJSON line feature must contain geometry")
            if (geometry.string("type", sanitizedId) != "LineString") {
                failDecode(sanitizedId, "Only LineString GeoJSON geometry is supported by this profile")
            }
            val positions = geometry["coordinates"] as? JsonArray
                ?: failDecode(sanitizedId, "GeoJSON LineString coordinates must be an array")
            if (positions.size < 2) failDecode(sanitizedId, "GeoJSON LineString requires at least two positions")
            coordinateCount += positions.size
            if (coordinateCount > configuration.resourceLimits.maxMvtCoordinates) {
                throw SafetyLimitException(
                    message = "GeoJSON coordinate count exceeds the configured limit",
                    limitName = "maxMvtCoordinates",
                    limit = configuration.resourceLimits.maxMvtCoordinates.toLong(),
                    observed = coordinateCount,
                    stage = PipelineStage.RESOURCE_DECODING,
                )
            }
            val line = positions.map { positionElement ->
                val position = positionElement as? JsonArray
                    ?: failDecode(sanitizedId, "GeoJSON position must be an array")
                if (position.size != 2) failDecode(sanitizedId, "GeoJSON positions must contain longitude and latitude")
                val longitude = (position[0] as? JsonPrimitive)?.doubleOrNull
                    ?: failDecode(sanitizedId, "GeoJSON longitude must be numeric")
                val latitude = (position[1] as? JsonPrimitive)?.doubleOrNull
                    ?: failDecode(sanitizedId, "GeoJSON latitude must be numeric")
                if (!longitude.isFinite() || longitude !in -180.0..180.0 ||
                    !latitude.isFinite() || latitude !in -90.0..90.0
                ) {
                    failDecode(sanitizedId, "GeoJSON position is outside longitude/latitude bounds")
                }
                GeoJsonPosition(longitude, latitude)
            }
            val properties = (feature["properties"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                value.toStyleValue(sanitizedId)
            }
            CompiledGeoJsonLineFeature(properties = properties, lines = listOf(line))
        }
        return CompiledGeoJsonData(contentDigest = bytes.sha256Hex(), features = features)
    }

    private fun JsonObject.string(name: String, sanitizedId: String): String =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: failDecode(sanitizedId, "GeoJSON $name must be a string")

    private fun JsonElement.toStyleValue(sanitizedId: String): StyleValue = when (this) {
        JsonNull -> StyleValue.Null
        is JsonPrimitive -> when {
            isString -> StyleValue.StringValue(content)
            booleanOrNull != null -> StyleValue.BooleanValue(booleanOrNull!!)
            doubleOrNull != null && doubleOrNull!!.isFinite() -> StyleValue.NumberValue(doubleOrNull!!)
            else -> failDecode(sanitizedId, "GeoJSON property must be a finite scalar value")
        }
        else -> failDecode(sanitizedId, "GeoJSON array and object properties are outside this profile")
    }

    private fun failDecode(sanitizedId: String, message: String): Nothing = throw ResourceDecodeException(
        message = message,
        resourceClass = ResourceClass.GEO_JSON,
        sanitizedResourceId = sanitizedId,
    )

    private suspend fun readStore(key: RawResourceKey): StoredRawResource? = try {
        configuration.rawResourceStore.read(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException("Raw GeoJSON cache read failed")
    }

    private suspend fun writeStore(key: RawResourceKey, resource: StoredRawResource) {
        try {
            configuration.rawResourceStore.write(key, resource)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Raw GeoJSON cache write failed")
        }
    }

    private suspend fun removeStore(key: RawResourceKey) {
        try {
            configuration.rawResourceStore.remove(key)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Corrupt raw GeoJSON cache removal failed")
        }
    }
}
