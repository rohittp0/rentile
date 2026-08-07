package com.rohittp.rentile.internal.sprite

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
import com.rohittp.rentile.TransportRequestMetadata
import com.rohittp.rentile.internal.ResourceWorkCoordinator
import com.rohittp.rentile.internal.SingleFlight
import com.rohittp.rentile.internal.recordSafely
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal data class SpriteAtlasEntry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixelRatio: Double,
    val sdf: Boolean,
)

internal data class CompiledSpriteAtlas(
    val entries: Map<String, SpriteAtlasEntry>,
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val contentDigest: String,
)

internal class SpriteResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val json = Json { isLenient = false }
    private val singleFlight = SingleFlight<String, CompiledSpriteAtlas>(scope)

    suspend fun acquire(baseUrl: String): CompiledSpriteAtlas {
        val stableBase = baseUrl.withRedactedAuthenticationQuery().sha256Hex()
        return singleFlight.run(stableBase) {
            coroutineScope {
                val metadata = async {
                    acquireRaw(
                        url = appendSpriteExtension(baseUrl, ".json"),
                        resourceClass = ResourceClass.SPRITE_JSON,
                        limit = configuration.resourceLimits.maxMetadataBytes,
                        accept = "application/json",
                    )
                }
                val image = async {
                    acquireRaw(
                        url = appendSpriteExtension(baseUrl, ".png"),
                        resourceClass = ResourceClass.SPRITE_IMAGE,
                        limit = configuration.resourceLimits.maxSpriteImageBytes,
                        accept = "image/png",
                    )
                }
                compile(metadata.await(), image.await(), stableBase)
            }
        }
    }

    private suspend fun acquireRaw(
        url: String,
        resourceClass: ResourceClass,
        limit: Long,
        accept: String,
    ): ByteArray {
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        val key = RawResourceKey(sanitizedId, resourceClass)
        val cached = readStore(key)
        if (cached != null) {
            if (cached.bytes.size.toLong() <= limit && cached.bytes.sha256Hex() == cached.contentDigest) {
                configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = resourceClass))
                return cached.bytes
            }
            removeStore(key)
        }
        configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = resourceClass))
        val response = workCoordinator.exchange(url) {
            configuration.metricsSink.recordSafely(RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass))
            try {
                configuration.transport.execute(
                    TransportRequest(
                        url = url,
                        resourceClass = resourceClass,
                        maxResponseBytes = limit,
                        metadata = TransportRequestMetadata(accept = accept),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ResourceAcquisitionException(
                    message = "Sprite transport failed",
                    resourceClass = resourceClass,
                    sanitizedResourceId = sanitizedId,
                )
            }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "Sprite transport returned a non-success status",
                resourceClass = resourceClass,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
            )
        }
        val bytes = response.body
        if (bytes.size.toLong() > limit) {
            throw SafetyLimitException(
                message = "Sprite resource exceeds its configured byte limit",
                limitName = if (resourceClass == ResourceClass.SPRITE_JSON) "maxMetadataBytes" else "maxSpriteImageBytes",
                limit = limit,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
            )
        }
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
                resourceClass = resourceClass,
            ),
        )
        return bytes
    }

    private fun compile(jsonBytes: ByteArray, pngBytes: ByteArray, sanitizedId: String): CompiledSpriteAtlas {
        val root = try {
            json.parseToJsonElement(jsonBytes.decodeToString()) as? JsonObject
                ?: failDecode(sanitizedId, "Sprite metadata root must be an object")
        } catch (error: ResourceDecodeException) {
            throw error
        } catch (_: SerializationException) {
            failDecode(sanitizedId, "Sprite metadata is malformed")
        } catch (_: IllegalArgumentException) {
            failDecode(sanitizedId, "Sprite metadata is not valid UTF-8 JSON")
        }
        if (root.size > MAX_SPRITE_ENTRIES) {
            throw SafetyLimitException(
                message = "Sprite atlas exceeds its entry-count limit",
                limitName = "maxSpriteEntries",
                limit = MAX_SPRITE_ENTRIES.toLong(),
                observed = root.size.toLong(),
                stage = PipelineStage.RESOURCE_DECODING,
            )
        }
        val imageWidth = pngDimension(pngBytes, 16, sanitizedId)
        val imageHeight = pngDimension(pngBytes, 20, sanitizedId)
        if (
            imageWidth !in 1..configuration.resourceLimits.maxRasterDimensionPx ||
            imageHeight !in 1..configuration.resourceLimits.maxRasterDimensionPx
        ) {
            failDecode(sanitizedId, "Sprite image dimensions are outside configured limits")
        }
        val entries = root.mapValues { (_, value) ->
            val entry = value as? JsonObject ?: failDecode(sanitizedId, "Sprite entry must be an object")
            if (entry.keys.any { it in UNSUPPORTED_ENTRY_FIELDS }) {
                failDecode(sanitizedId, "Sprite stretch/content metadata is unsupported")
            }
            val x = entry.requiredInt("x", sanitizedId)
            val y = entry.requiredInt("y", sanitizedId)
            val width = entry.requiredInt("width", sanitizedId)
            val height = entry.requiredInt("height", sanitizedId)
            val ratio = (entry["pixelRatio"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
            val sdf = (entry["sdf"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > imageWidth || y + height > imageHeight) {
                failDecode(sanitizedId, "Sprite entry lies outside the atlas image")
            }
            if (!ratio.isFinite() || ratio <= 0.0) failDecode(sanitizedId, "Sprite pixelRatio must be positive")
            SpriteAtlasEntry(x, y, width, height, ratio, sdf)
        }
        return CompiledSpriteAtlas(
            entries = entries,
            pngBytes = pngBytes.copyOf(),
            width = imageWidth,
            height = imageHeight,
            contentDigest = (jsonBytes.sha256Hex() + "\n" + pngBytes.sha256Hex()).sha256Hex(),
        )
    }

    private fun JsonObject.requiredInt(name: String, sanitizedId: String): Int =
        (this[name] as? JsonPrimitive)?.intOrNull ?: failDecode(sanitizedId, "Sprite entry $name must be an integer")

    private fun pngDimension(bytes: ByteArray, offset: Int, sanitizedId: String): Int {
        if (
            bytes.size < 24 ||
            bytes[0] != 0x89.toByte() ||
            bytes[1] != 0x50.toByte() ||
            bytes[2] != 0x4e.toByte() ||
            bytes[3] != 0x47.toByte()
        ) {
            failDecode(sanitizedId, "Sprite image must be PNG")
        }
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun failDecode(sanitizedId: String, message: String): Nothing = throw ResourceDecodeException(
        message = message,
        resourceClass = ResourceClass.SPRITE_JSON,
        sanitizedResourceId = sanitizedId,
    )

    private suspend fun readStore(key: RawResourceKey): StoredRawResource? = try {
        configuration.rawResourceStore.read(key)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        throw ResourceStoreException("Raw sprite cache read failed")
    }

    private suspend fun writeStore(key: RawResourceKey, resource: StoredRawResource) {
        try {
            configuration.rawResourceStore.write(key, resource)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Raw sprite cache write failed")
        }
    }

    private suspend fun removeStore(key: RawResourceKey) {
        try {
            configuration.rawResourceStore.remove(key)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ResourceStoreException("Corrupt sprite cache removal failed")
        }
    }

    private companion object {
        const val MAX_SPRITE_ENTRIES = 100_000
        val UNSUPPORTED_ENTRY_FIELDS = setOf("stretchX", "stretchY", "content")
    }
}

internal fun appendSpriteExtension(baseUrl: String, extension: String): String {
    val fragmentIndex = baseUrl.indexOf('#').let { if (it < 0) baseUrl.length else it }
    val withoutFragment = baseUrl.substring(0, fragmentIndex)
    val fragment = baseUrl.substring(fragmentIndex)
    val queryIndex = withoutFragment.indexOf('?').let { if (it < 0) withoutFragment.length else it }
    val path = withoutFragment.substring(0, queryIndex)
    val query = withoutFragment.substring(queryIndex)
    return path + extension + query + fragment
}
