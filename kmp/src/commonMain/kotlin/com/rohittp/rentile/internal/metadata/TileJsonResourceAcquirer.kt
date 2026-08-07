package com.rohittp.rentile.internal.metadata

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
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import com.rohittp.rentile.internal.style.TileScheme
import com.rohittp.rentile.internal.style.SourceBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull

internal data class ResolvedTileJson(
    val tileTemplates: List<String>,
    val scheme: TileScheme,
    val minZoom: Int,
    val maxZoom: Int,
    val tileSize: Int?,
    val bounds: SourceBounds?,
    val contentDigest: String,
    val identityDigest: String,
)

internal class TileJsonResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val json = Json { isLenient = false }
    private val singleFlight = SingleFlight<RawResourceKey, ResolvedTileJson>(scope)

    suspend fun acquire(url: String): ResolvedTileJson {
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        val key = RawResourceKey(sanitizedId, ResourceClass.TILE_JSON)
        val cached = readStore(key)
        if (cached != null) {
            val bytes = cached.bytes
            if (bytes.sha256Hex() == cached.contentDigest) {
                parseOrNull(bytes, url, sanitizedId)?.let { parsed ->
                    configuration.metricsSink.recordSafely(
                        RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = ResourceClass.TILE_JSON),
                    )
                    return parsed.copy(contentDigest = cached.contentDigest)
                }
            }
            removeStore(key)
        }
        configuration.metricsSink.recordSafely(
            RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = ResourceClass.TILE_JSON),
        )
        return singleFlight.run(
            key = key,
            onJoin = {
                configuration.metricsSink.recordSafely(
                    RentileMetric(MetricName.SINGLE_FLIGHT_JOIN, resourceClass = ResourceClass.TILE_JSON),
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
    ): ResolvedTileJson {
        val response = workCoordinator.exchange(url) {
            configuration.metricsSink.recordSafely(
                RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = ResourceClass.TILE_JSON),
            )
            try {
                configuration.transport.execute(
                    TransportRequest(
                        url = url,
                        resourceClass = ResourceClass.TILE_JSON,
                        maxResponseBytes = configuration.resourceLimits.maxMetadataBytes,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ResourceAcquisitionException(
                    message = "TileJSON transport failed",
                    resourceClass = ResourceClass.TILE_JSON,
                    sanitizedResourceId = sanitizedId,
                )
            }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "TileJSON transport returned a non-success status",
                resourceClass = ResourceClass.TILE_JSON,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
            )
        }
        val bytes = response.body
        val limit = configuration.resourceLimits.maxMetadataBytes
        if (bytes.size.toLong() > limit) {
            throw SafetyLimitException(
                message = "TileJSON exceeds the configured metadata byte limit",
                limitName = "maxMetadataBytes",
                limit = limit,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
            )
        }
        val parsed = parseOrThrow(bytes, url, sanitizedId)
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
                resourceClass = ResourceClass.TILE_JSON,
            ),
        )
        return parsed.copy(contentDigest = digest)
    }

    private fun parseOrNull(bytes: ByteArray, baseUrl: String, sanitizedId: String): ResolvedTileJson? = try {
        parseOrThrow(bytes, baseUrl, sanitizedId)
    } catch (_: ResourceDecodeException) {
        null
    }

    private fun parseOrThrow(bytes: ByteArray, baseUrl: String, sanitizedId: String): ResolvedTileJson {
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: failDecode(sanitizedId, "TileJSON root must be an object")
        } catch (error: ResourceDecodeException) {
            throw error
        } catch (_: SerializationException) {
            failDecode(sanitizedId, "TileJSON is malformed")
        } catch (_: IllegalArgumentException) {
            failDecode(sanitizedId, "TileJSON is not valid UTF-8 JSON")
        }
        val tileJsonVersion = root["tilejson"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: failDecode(sanitizedId, "TileJSON version must be a string")
        }
        if (tileJsonVersion != null && !tileJsonVersion.startsWith("2.") && !tileJsonVersion.startsWith("3.")) {
            failDecode(sanitizedId, "TileJSON version is unsupported")
        }
        val templates = (root["tiles"] as? JsonArray)?.map { element ->
            val reference = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: failDecode(sanitizedId, "TileJSON tile templates must be strings")
            resolveHttpReference(baseUrl, reference)
                ?: failDecode(sanitizedId, "TileJSON contains an unsupported tile-template URL")
        }.orEmpty()
        if (templates.isEmpty()) failDecode(sanitizedId, "TileJSON must declare at least one tile template")
        val minZoom = root["minzoom"]?.let { value ->
            (value as? JsonPrimitive)?.intOrNull ?: failDecode(sanitizedId, "TileJSON minzoom must be an integer")
        } ?: 0
        val maxZoom = root["maxzoom"]?.let { value ->
            (value as? JsonPrimitive)?.intOrNull ?: failDecode(sanitizedId, "TileJSON maxzoom must be an integer")
        } ?: 22
        if (minZoom !in 0..30 || maxZoom !in minZoom..30) {
            failDecode(sanitizedId, "TileJSON zoom range is invalid")
        }
        val schemeValue = root["scheme"]?.let { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: failDecode(sanitizedId, "TileJSON scheme must be a string")
        } ?: "xyz"
        val scheme = when (schemeValue) {
            "xyz" -> TileScheme.XYZ
            "tms" -> TileScheme.TMS
            else -> failDecode(sanitizedId, "TileJSON scheme is unsupported")
        }
        val declaredTileSize = root["tileSize"] ?: root["tile-size"]
        val tileSize = declaredTileSize?.let { value ->
            (value as? JsonPrimitive)?.intOrNull ?: failDecode(sanitizedId, "TileJSON tile size must be an integer")
        }
        if (tileSize != null && tileSize !in setOf(64, 256, 512)) {
            failDecode(sanitizedId, "TileJSON tile size is outside the compatibility profile")
        }
        val bounds = root["bounds"]?.let { value -> parseBounds(value, sanitizedId) }
        val identityDigest = buildString {
            append(scheme.name)
            append('\n')
            append(minZoom)
            append('\n')
            append(maxZoom)
            append('\n')
            append(tileSize ?: "")
            append('\n')
            append(bounds ?: "")
            templates.forEach { template ->
                append('\n')
                append(template.withRedactedAuthenticationQuery())
            }
        }.sha256Hex()
        return ResolvedTileJson(
            tileTemplates = templates,
            scheme = scheme,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileSize = tileSize,
            bounds = bounds,
            contentDigest = "",
            identityDigest = identityDigest,
        )
    }

    private fun parseBounds(value: kotlinx.serialization.json.JsonElement, sanitizedId: String): SourceBounds {
        val array = value as? JsonArray ?: failDecode(sanitizedId, "TileJSON bounds must be an array")
        if (array.size != 4) failDecode(sanitizedId, "TileJSON bounds must contain four numbers")
        val numbers = array.map { element ->
            (element as? JsonPrimitive)?.doubleOrNull
                ?.takeIf(Double::isFinite)
                ?: failDecode(sanitizedId, "TileJSON bounds must contain finite numbers")
        }
        val bounds = SourceBounds(numbers[0], numbers[1], numbers[2], numbers[3])
        if (bounds.west !in -180.0..180.0 || bounds.east !in -180.0..180.0 ||
            bounds.south !in -90.0..90.0 || bounds.north !in -90.0..90.0 || bounds.south > bounds.north
        ) {
            failDecode(sanitizedId, "TileJSON bounds are outside the geographic range")
        }
        return bounds
    }

    private fun failDecode(sanitizedId: String, message: String): Nothing = throw ResourceDecodeException(
        message = message,
        resourceClass = ResourceClass.TILE_JSON,
        sanitizedResourceId = sanitizedId,
    )

    private suspend fun readStore(key: RawResourceKey): StoredRawResource? = try {
        configuration.rawResourceStore.read(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException("Raw TileJSON cache read failed")
    }

    private suspend fun writeStore(key: RawResourceKey, resource: StoredRawResource) {
        try {
            configuration.rawResourceStore.write(key, resource)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Raw TileJSON cache write failed")
        }
    }

    private suspend fun removeStore(key: RawResourceKey) {
        try {
            configuration.rawResourceStore.remove(key)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Corrupt TileJSON cache removal failed")
        }
    }
}

internal fun resolveHttpReference(baseUrl: String, reference: String): String? {
    if (reference.startsWith("https://") || reference.startsWith("http://")) return reference
    val schemeEnd = baseUrl.indexOf("://")
    if (schemeEnd <= 0) return null
    val originStart = schemeEnd + 3
    val pathStart = baseUrl.indexOf('/', originStart).let { if (it < 0) baseUrl.length else it }
    val origin = baseUrl.substring(0, pathStart).substringBefore('?')
    val basePath = baseUrl.substring(pathStart).substringBefore('?').substringBefore('#')
    val combined = when {
        reference.startsWith("//") -> baseUrl.substring(0, schemeEnd + 1) + reference
        reference.startsWith('/') -> origin + reference
        else -> origin + basePath.substringBeforeLast('/', missingDelimiterValue = "") + "/" + reference
    }
    val absoluteSchemeEnd = combined.indexOf("://")
    val absolutePathStart = combined.indexOf('/', absoluteSchemeEnd + 3).let { if (it < 0) combined.length else it }
    val absoluteOrigin = combined.substring(0, absolutePathStart)
    val pathAndQuery = combined.substring(absolutePathStart)
    val path = pathAndQuery.substringBefore('?').substringBefore('#')
    val suffix = pathAndQuery.removePrefix(path)
    val normalized = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
            else -> normalized += segment
        }
    }
    return absoluteOrigin + "/" + normalized.joinToString("/") + suffix
}
