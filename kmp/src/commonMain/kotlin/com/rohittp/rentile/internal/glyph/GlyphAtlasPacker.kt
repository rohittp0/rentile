package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.LabelGlyphEntry
import com.rohittp.rentile.internal.sha256Hex
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** A packed glyph atlas: the entries a label layout consults plus the texture bytes to upload. */
internal data class PackedGlyphAtlas(
    val entries: List<LabelGlyphEntry>,
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val contentKey: String,
    val indexOf: Map<Pair<String, Int>, Int>,
)

/**
 * Packs decoded signed-distance-field glyph ranges into a single atlas texture.
 *
 * Packing order is canonicalized to `(fontStackDigest, codepoint)` so the same glyph set always
 * produces the same layout regardless of the order ranges arrived in - otherwise [PackedGlyphAtlas.contentKey]
 * would change between runs and a consumer would needlessly re-upload an unchanged texture.
 */
internal object GlyphAtlasPacker {
    private const val MAX_WIDTH = 1024

    /**
     * A total-order string over a glyph's own content: its metrics and its decoded bitmap
     * digest. Used only to break ties deterministically when two ranges disagree about the
     * same (fontStackDigest, codepoint) - the tiebreak must depend on glyph content, never on
     * which range happened to be iterated first.
     */
    private fun DecodedGlyph.contentSignature(): String =
        "$width:$height:$left:$top:$advance:${bitmap.sha256Hex()}"

    fun pack(ranges: List<AcquiredGlyphRange>): PackedGlyphAtlas {
        // Flatten to (fontStackDigest, glyph), then order canonically so layout is
        // independent of arrival order. Whitespace glyphs carry no bitmap and are dropped.
        //
        // Sort BEFORE dedup, with a content-signature tiebreak. If two ranges disagree about
        // one (fontStackDigest, codepoint) - different metrics or bitmap bytes - distinctBy
        // keeps whichever sorts first, which is now a function of glyph content, not of which
        // range happened to arrive first. Deduping before sorting would let arrival order pick
        // the survivor, defeating determinism whenever two ranges collide on the same glyph.
        val drawable = ranges
            .flatMap { range -> range.glyphs.map { range.fontStack.sha256Hex() to it } }
            .filter { (_, glyph) -> glyph.bitmap.isNotEmpty() }
            .sortedWith(compareBy({ it.first }, { it.second.codepoint }, { it.second.contentSignature() }))
            .distinctBy { (digest, glyph) -> digest to glyph.codepoint }

        // Shelf-pack left to right, wrapping at MAX_WIDTH. Each cell is the buffered
        // bitmap extent, so the consumer samples the same padding the provider encoded.
        val entries = ArrayList<LabelGlyphEntry>(drawable.size)
        val index = HashMap<Pair<String, Int>, Int>(drawable.size)
        var penX = 0
        var penY = 0
        var shelfHeight = 0
        var atlasWidth = 0
        for ((digest, glyph) in drawable) {
            val cellWidth = glyph.width + GlyphRangeDecoder.BUFFER_PX * 2
            val cellHeight = glyph.height + GlyphRangeDecoder.BUFFER_PX * 2
            if (penX + cellWidth > MAX_WIDTH && penX > 0) {
                penY += shelfHeight
                penX = 0
                shelfHeight = 0
            }
            index[digest to glyph.codepoint] = entries.size
            entries += LabelGlyphEntry(
                fontStackDigest = digest,
                codepoint = glyph.codepoint,
                x = penX, y = penY,
                width = cellWidth, height = cellHeight,
                left = glyph.left, top = glyph.top,
                advance = glyph.advance,
            )
            penX += cellWidth
            atlasWidth = maxOf(atlasWidth, penX)
            shelfHeight = maxOf(shelfHeight, cellHeight)
        }
        val atlasHeight = penY + shelfHeight

        // contentKey covers the glyph set, its metrics, AND its decoded bitmap bytes - never
        // the encoded PNG. ADR 0010 forbids keying on encoded PNG bytes because encoders are
        // not byte-identical across platforms; it does not forbid keying on the decoded SDF
        // bitmap, which arrives verbatim from the provider and is identical everywhere. Without
        // the bitmap in the key, an upstream font revision that changes distance values without
        // changing box metrics or advance would silently keep the old contentKey, and a
        // consumer would never re-upload the changed texture.
        val contentKey = entries.indices.joinToString("|") { i ->
            val entry = entries[i]
            val bitmapDigest = drawable[i].second.bitmap.sha256Hex()
            "${entry.fontStackDigest}:${entry.codepoint}:${entry.width}x${entry.height}:" +
                "${entry.left},${entry.top},${entry.advance}:$bitmapDigest"
        }.sha256Hex()

        // An empty glyph set still needs a valid (non-zero-dimension) atlas: Skia rejects
        // a zero-dimension image, so a 1x1 fully transparent placeholder stands in.
        val width = maxOf(atlasWidth, 1)
        val height = maxOf(atlasHeight, 1)

        return PackedGlyphAtlas(
            entries = entries,
            pngBytes = encode(drawable, entries, width, height),
            width = width,
            height = height,
            contentKey = contentKey,
            indexOf = index,
        )
    }

    private fun encode(
        drawable: List<Pair<String, DecodedGlyph>>,
        entries: List<LabelGlyphEntry>,
        width: Int,
        height: Int,
    ): ByteArray {
        // RGB is opaque white and the signed distance goes in alpha, matching the
        // convention SDF sprites already use (see the tint path at
        // DefaultBasemapRasterizer.kt:1825) so a consumer has one sampling rule for
        // both atlases.
        val pixels = ByteArray(width * height * 4)
        drawable.forEachIndexed { index, (_, glyph) ->
            val entry = entries[index]
            for (row in 0 until entry.height) {
                val sourceOffset = row * entry.width
                val targetOffset = ((entry.y + row) * width + entry.x) * 4
                for (column in 0 until entry.width) {
                    val target = targetOffset + column * 4
                    pixels[target] = -1        // 0xFF red
                    pixels[target + 1] = -1    // 0xFF green
                    pixels[target + 2] = -1    // 0xFF blue
                    pixels[target + 3] = glyph.bitmap[sourceOffset + column]
                }
            }
        }
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val image = Image.makeRaster(info, pixels, width * 4)
        try {
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia could not encode the glyph atlas as PNG")
            try {
                return data.bytes
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
    }
}
