package com.rohittp.rentile.internal.sprite

import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RawResourceKey
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.ResourceAccessMode
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.SpriteAtlas
import com.rohittp.rentile.SpriteContentBox
import com.rohittp.rentile.SpriteImageEntry
import com.rohittp.rentile.SpriteStretchRange
import com.rohittp.rentile.internal.SingleFlight
import com.rohittp.rentile.internal.RevalidatingResourceAcquirer
import com.rohittp.rentile.internal.hasPngSignature
import com.rohittp.rentile.internal.isJsonObjectDocument
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
    /**
     * `icon-text-fit` metadata, carried for the public atlas only (ADR 0036). The Output Tile
     * path never reads these: it draws every icon and pattern at the image's own aspect, where the
     * style specification gives stretch zones and a content box no effect.
     */
    val stretchX: List<SpriteStretchRange>? = null,
    val stretchY: List<SpriteStretchRange>? = null,
    val content: SpriteContentBox? = null,
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
    private val resourceAcquirer: RevalidatingResourceAcquirer,
) {
    private val json = Json { isLenient = false }
    private val singleFlight = SingleFlight<String, CompiledSpriteAtlas>(scope)

    /** The 1x sheet, as preparation acquires it: no access mode, stored-then-revalidated. */
    suspend fun acquire(baseUrl: String): CompiledSpriteAtlas =
        acquire(baseUrl, pixelRatio = 1, accessMode = ResourceAccessMode.NORMAL)

    /**
     * The sheet at [pixelRatio] - `1` for `<base>.json`/`<base>.png`, `2` for the `@2x` pair -
     * under [accessMode].
     *
     * The flight key carries the ratio and the access mode as well as the base, because each
     * changes the answer: a ratio-2 caller joining a ratio-1 flight would get the wrong sheet, and
     * a cache-only caller joining a normal flight would be handed bytes its own mode forbade
     * fetching. A ratio-1 normal flight keeps the bare base as its key, so preparation and a host's
     * ratio-1 request still share one.
     */
    suspend fun acquire(baseUrl: String, pixelRatio: Int, accessMode: ResourceAccessMode): CompiledSpriteAtlas {
        require(pixelRatio == 1 || pixelRatio == 2) { "Sprite pixel ratio must be 1 or 2" }
        val mode = if (accessMode == ResourceAccessMode.CACHE_SUBSTITUTE_THEN_NETWORK) {
            ResourceAccessMode.NORMAL
        } else {
            accessMode
        }
        val suffix = if (pixelRatio == 1) "" else "@${pixelRatio}x"
        val stableBase = baseUrl.withRedactedAuthenticationQuery().sha256Hex()
        val flightKey = if (pixelRatio == 1 && mode == ResourceAccessMode.NORMAL) {
            stableBase
        } else {
            "$stableBase|$suffix|${mode.name}"
        }
        return singleFlight.run(flightKey) {
            coroutineScope {
                val metadata = async {
                    acquireRaw(
                        url = appendSpriteExtension(baseUrl, "$suffix.json"),
                        resourceClass = ResourceClass.SPRITE_JSON,
                        limit = configuration.resourceLimits.maxMetadataBytes,
                        accept = "application/json",
                        accessMode = mode,
                    )
                }
                val image = async {
                    acquireRaw(
                        url = appendSpriteExtension(baseUrl, "$suffix.png"),
                        resourceClass = ResourceClass.SPRITE_IMAGE,
                        limit = configuration.resourceLimits.maxSpriteImageBytes,
                        accept = "image/png",
                        accessMode = mode,
                    )
                }
                compile(metadata.await(), image.await(), stableBase)
            }
        }
    }

    /**
     * Acquires one sprite document: from the store when it is there, from the origin when it is not.
     *
     * A cached sprite was once reused unconditionally and for good, so a corrected icon sheet never
     * reached a consumer that had already fetched the old one; then it was revalidated in front of
     * the caller, which cost two round trips on the preparation path for documents that answer 304
     * every time. A stored sprite is now returned immediately and refreshed behind the caller, and
     * a corrected sheet arrives at the next preparation.
     *
     * The shape checks -- a JSON object for the metadata, a PNG signature for the image -- keep a
     * captive portal's HTML 200 from being stored as either.
     */
    private suspend fun acquireRaw(
        url: String,
        resourceClass: ResourceClass,
        limit: Long,
        accept: String,
        accessMode: ResourceAccessMode,
    ): ByteArray {
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        return resourceAcquirer.acquire(
            key = RawResourceKey(sanitizedId, resourceClass),
            url = url,
            sanitizedId = sanitizedId,
            maxBytes = limit,
            transportLabel = "Sprite",
            cacheLabel = "sprite",
            accept = accept,
            limitName = if (resourceClass == ResourceClass.SPRITE_JSON) "maxMetadataBytes" else "maxSpriteImageBytes",
            isStoredEntryUsable = if (resourceClass == ResourceClass.SPRITE_JSON) {
                ByteArray::isJsonObjectDocument
            } else {
                ByteArray::hasPngSignature
            },
            accessMode = accessMode,
        )
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
            SpriteAtlasEntry(
                x = x,
                y = y,
                width = width,
                height = height,
                pixelRatio = ratio,
                sdf = sdf,
                // A JSON null is an absent value, as MapLibre reads it.
                stretchX = entry.declared("stretchX")?.let { stretchRanges(it, width, sanitizedId) },
                stretchY = entry.declared("stretchY")?.let { stretchRanges(it, height, sanitizedId) },
                content = entry.declared("content")?.let { contentBox(it, width, height, sanitizedId) },
            )
        }
        return CompiledSpriteAtlas(
            entries = entries,
            pngBytes = pngBytes.copyOf(),
            width = imageWidth,
            height = imageHeight,
            contentDigest = (jsonBytes.sha256Hex() + "\n" + pngBytes.sha256Hex()).sha256Hex(),
        )
    }

    /**
     * `stretchX` or `stretchY`: `[from, to]` pairs inside the image's own [extent], each starting
     * no earlier than the previous one ended - MapLibre's `_validateStretch`, applied as strictly
     * as every other entry field here, so a bad value fails the sheet rather than reaching a host.
     *
     * These used to fail the sheet merely by being present (ADR 0036). Accepting them changes no
     * Output Tile pixel: that path draws every icon and pattern at its own aspect, where the
     * style specification gives stretch zones no effect, and nothing on it reads them.
     */
    private fun stretchRanges(value: JsonElement, extent: Int, sanitizedId: String): List<SpriteStretchRange> {
        val pairs = value as? JsonArray ?: failDecode(sanitizedId, MALFORMED_STRETCH)
        var last = 0.0
        return pairs.map { pair ->
            val bounds = (pair as? JsonArray)?.takeIf { it.size == 2 } ?: failDecode(sanitizedId, MALFORMED_STRETCH)
            val from = bounds[0].finiteNumber() ?: failDecode(sanitizedId, MALFORMED_STRETCH)
            val to = bounds[1].finiteNumber() ?: failDecode(sanitizedId, MALFORMED_STRETCH)
            if (from < last || to < from || to > extent) failDecode(sanitizedId, MALFORMED_STRETCH)
            last = to
            SpriteStretchRange(from, to)
        }
    }

    /** `content`: left, top, right, bottom inside the image - MapLibre's `_validateContent`. */
    private fun contentBox(value: JsonElement, width: Int, height: Int, sanitizedId: String): SpriteContentBox {
        val edges = (value as? JsonArray)?.takeIf { it.size == 4 } ?: failDecode(sanitizedId, MALFORMED_CONTENT)
        val (left, top, right, bottom) = edges.map { it.finiteNumber() ?: failDecode(sanitizedId, MALFORMED_CONTENT) }
        if (left < 0.0 || top < 0.0 || right > width || bottom > height || right < left || bottom < top) {
            failDecode(sanitizedId, MALFORMED_CONTENT)
        }
        return SpriteContentBox(left, top, right, bottom)
    }

    private fun JsonObject.declared(name: String): JsonElement? = this[name]?.takeUnless { it is JsonNull }

    private fun JsonElement.finiteNumber(): Double? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

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

    private companion object {
        const val MAX_SPRITE_ENTRIES = 100_000
        const val MALFORMED_STRETCH = "Sprite stretch metadata is malformed"
        const val MALFORMED_CONTENT = "Sprite content metadata is malformed"
    }
}

/**
 * Bump when the parsing behind [SpriteAtlas.entries] changes for identical sheet bytes, so a host
 * holding an uploaded sheet under the old key re-reads its entries.
 */
private const val SPRITE_ATLAS_SEMANTICS_VERSION = "rentile-sprite-atlas-1"

/**
 * The public view of a compiled sheet. [pixelRatio] is the sheet that was asked for; the key
 * deliberately omits it, because identical bytes are one texture whichever request returned them.
 */
internal fun CompiledSpriteAtlas.toPublicSpriteAtlas(pixelRatio: Int): SpriteAtlas = SpriteAtlas(
    // Copied: the compiled atlas is shared by every batch of its style and every joiner of its
    // flight, and SpriteAtlas hashes the array's contents.
    pngBytes = pngBytes.copyOf(),
    width = width,
    height = height,
    pixelRatio = pixelRatio,
    contentKey = "$SPRITE_ATLAS_SEMANTICS_VERSION\n$contentDigest".sha256Hex(),
    entries = entries.mapValues { (name, entry) ->
        SpriteImageEntry(
            name = name,
            x = entry.x,
            y = entry.y,
            width = entry.width,
            height = entry.height,
            pixelRatio = entry.pixelRatio,
            sdf = entry.sdf,
            stretchX = entry.stretchX,
            stretchY = entry.stretchY,
            content = entry.content,
        )
    },
)

internal fun appendSpriteExtension(baseUrl: String, extension: String): String {
    val fragmentIndex = baseUrl.indexOf('#').let { if (it < 0) baseUrl.length else it }
    val withoutFragment = baseUrl.substring(0, fragmentIndex)
    val fragment = baseUrl.substring(fragmentIndex)
    val queryIndex = withoutFragment.indexOf('?').let { if (it < 0) withoutFragment.length else it }
    val path = withoutFragment.substring(0, queryIndex)
    val query = withoutFragment.substring(queryIndex)
    return path + extension + query + fragment
}
