package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.LabelGlyphEntry
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.PngEncodingException
import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.SafetyLimitException
import com.rohittp.rentile.internal.sha256Hex
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * What [LabelLayout] needs from an atlas: each glyph's metrics, and where a (font stack digest,
 * codepoint) sits among them. Positions in the texture play no part in layout.
 */
internal interface GlyphMetricsLookup {
    val entries: List<LabelGlyphEntry>
    val indexOf: Map<Pair<String, Int>, Int>
}

/** A packed glyph atlas: the entries a label layout consults plus the texture bytes to upload. */
internal data class PackedGlyphAtlas(
    override val entries: List<LabelGlyphEntry>,
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val contentKey: String,
    override val indexOf: Map<Pair<String, Int>, Int>,
) : GlyphMetricsLookup

/**
 * The glyphs of a batch in canonical order with their metrics and nothing else - no texture, and
 * every cell at the origin. Referenced-only packing lays labels out against this, so the full
 * atlas's pixels are never allocated, and packs only what layout used afterwards.
 */
internal class GlyphMetricsIndex(
    override val entries: List<LabelGlyphEntry>,
    override val indexOf: Map<Pair<String, Int>, Int>,
) : GlyphMetricsLookup

/**
 * Packs decoded signed-distance-field glyph ranges into a single atlas texture.
 *
 * Packing order is canonicalized to `(fontStackDigest, codepoint)` so the same glyph set always
 * produces the same layout regardless of the order ranges arrived in - otherwise [PackedGlyphAtlas.contentKey]
 * would change between runs and a consumer would needlessly re-upload an unchanged texture.
 */
internal object GlyphAtlasPacker {
    /**
     * Shelf width, and therefore the atlas's own width once more than one shelf is needed.
     *
     * Chosen so that `maxGlyphRangesPerBatch` genuinely fits, because the two ceilings must agree:
     * that limit's KDoc promises 256 ranges as measured corpus headroom, and a shelf that cannot
     * hold 256 ordinary dense ranges turns the promise into a `SafetyLimitException` thrown after every
     * range has been fetched - a network cost paid for a failure. The previous 1024 held about 41
     * dense ranges, so a 30-tile Tokyo viewport across two or three font stacks passed the range
     * check and then failed in the packer.
     *
     * 256 dense CJK ranges are 65536 cells. At 8192 wide, 28-pixel cells occupy about 6300 rows
     * and 197 MiB; 32-pixel cells exactly reach the independent 8192-pixel and 256 MiB defaults.
     * Larger provider-declared glyph metrics can still trip those separate typed limits, as they
     * should. 4096 was rejected because even the 28-pixel corpus shape would breach the default
     * height ceiling before reaching the range ceiling.
     *
     * That is a statement about the default, which packs every glyph of every acquired range. A
     * host whose GPU cannot sample an 8192-wide texture sets `LabelGlyphAtlasPolicy.maxDimensionPx`
     * and packs `REFERENCED_GLYPHS`, and so pays for the glyphs it draws rather than for every
     * 256-codepoint block they came from.
     */
    private const val SHELF_WIDTH_PX = 8192

    /**
     * A total-order string over a glyph's own content: its metrics and its decoded bitmap
     * digest. Used only to break ties deterministically when two ranges disagree about the
     * same (fontStackDigest, codepoint) - the tiebreak must depend on glyph content, never on
     * which range happened to be iterated first.
     */
    private fun DecodedGlyph.contentSignature(): String =
        "$width:$height:$left:$top:$advance:${bitmap.sha256Hex()}"

    /**
     * [limits] bounds the texture this builds. The extents come from provider-declared glyph
     * metrics, and while `maxGlyphRangeBytes` bounds any single range, nothing bounded the sum:
     * `maxGlyphRangesPerBatch` ranges of tall glyphs shelf-pack into an arbitrarily tall atlas,
     * whose `width * height * 4` byte buffer overflowed Int into a bare
     * `NegativeArraySizeException`. The same two ceilings the raster and sprite decode paths
     * already enforce apply here, reported the same way.
     */
    fun pack(
        ranges: List<AcquiredGlyphRange>,
        limits: ResourceLimits = ResourceLimits(),
        maxDimensionPx: Int? = null,
    ): PackedGlyphAtlas = packGlyphs(canonicalGlyphs(ranges), limits, maxDimensionPx)

    /**
     * Every drawable glyph of [ranges] as (font stack digest, glyph), in the canonical order
     * every atlas is packed in. An entry index is a position in this list, for the full atlas,
     * for [metricsIndex], and - restricted to a subsequence - for a referenced-only atlas.
     */
    fun canonicalGlyphs(ranges: List<AcquiredGlyphRange>): List<Pair<String, DecodedGlyph>> =
        // Flatten to (fontStackDigest, glyph), then order canonically so layout is
        // independent of arrival order. Whitespace glyphs carry no bitmap and are dropped.
        //
        // Sort BEFORE dedup, with a content-signature tiebreak. If two ranges disagree about
        // one (fontStackDigest, codepoint) - different metrics or bitmap bytes - distinctBy
        // keeps whichever sorts first, which is now a function of glyph content, not of which
        // range happened to arrive first. Deduping before sorting would let arrival order pick
        // the survivor, defeating determinism whenever two ranges collide on the same glyph.
        ranges
            .flatMap { range -> range.glyphs.map { range.fontStack.sha256Hex() to it } }
            .filter { (_, glyph) -> glyph.bitmap.isNotEmpty() }
            .sortedWith(compareBy({ it.first }, { it.second.codepoint }, { it.second.contentSignature() }))
            .distinctBy { (digest, glyph) -> digest to glyph.codepoint }

    /** [drawable]'s metrics in its own order, for layout before anything is packed. */
    fun metricsIndex(drawable: List<Pair<String, DecodedGlyph>>): GlyphMetricsIndex {
        val entries = ArrayList<LabelGlyphEntry>(drawable.size)
        val index = HashMap<Pair<String, Int>, Int>(drawable.size)
        for ((digest, glyph) in drawable) {
            index[digest to glyph.codepoint] = entries.size
            entries += glyph.entryAt(digest, x = 0, y = 0)
        }
        return GlyphMetricsIndex(entries, index)
    }

    /**
     * Packs [drawable], which must already be in canonical order - all of [canonicalGlyphs], or a
     * subsequence of it.
     *
     * [maxDimensionPx] is the host's own ceiling on this texture
     * (`LabelGlyphAtlasPolicy.maxDimensionPx`). Where it is the tighter bound it stands in for
     * `maxRasterDimensionPx` exactly - the same shelf width, the same checks, so the same layout
     * and key a lowered `maxRasterDimensionPx` would produce - and failures name it instead.
     */
    fun packGlyphs(
        drawable: List<Pair<String, DecodedGlyph>>,
        limits: ResourceLimits = ResourceLimits(),
        maxDimensionPx: Int? = null,
    ): PackedGlyphAtlas {
        // Shelf-pack left to right, wrapping at the shelf width. Each cell is the buffered
        // bitmap extent, so the consumer samples the same padding the provider encoded.
        val entries = ArrayList<LabelGlyphEntry>(drawable.size)
        val index = HashMap<Pair<String, Int>, Int>(drawable.size)
        var penX = 0
        var penY = 0L
        var shelfHeight = 0
        var atlasWidth = 0
        // The host's cap, only where it is the tighter of the two bounds.
        val bindingCap = maxDimensionPx?.takeIf { it < limits.maxRasterDimensionPx }
        val maxDimension = (bindingCap ?: limits.maxRasterDimensionPx).toLong()
        val dimensionLimitName = if (bindingCap != null) "labelGlyphAtlas.maxDimensionPx" else "maxRasterDimensionPx"
        // A caller that lowers maxRasterDimensionPx below the shelf width has declared a texture
        // ceiling, and handing them a wider atlas than they asked for would breach it silently.
        val shelfWidth = minOf(SHELF_WIDTH_PX.toLong(), maxDimension)
        for ((digest, glyph) in drawable) {
            val cellWidth = glyph.width + GlyphRangeDecoder.BUFFER_PX * 2
            val cellHeight = glyph.height + GlyphRangeDecoder.BUFFER_PX * 2
            if (penX + cellWidth > shelfWidth && penX > 0) {
                penY += shelfHeight
                penX = 0
                shelfHeight = 0
            }
            // Checked per cell, before an entry exists for it, so a payload that would build a
            // texture beyond the ceiling is rejected instead of first accumulating millions of
            // entries rather than failing on the first one that cannot fit. cellWidth is bounded
            // here as well as the running height, because a cell wider than the shelf sits alone
            // and sets the atlas width by itself - the wrap above cannot move it off a shelf it is
            // already alone on.
            val observedDimension = maxOf(cellWidth.toLong(), penY + cellHeight)
            if (observedDimension > maxDimension) {
                throw SafetyLimitException(
                    message = "Glyph atlas dimensions exceed the configured limit",
                    limitName = dimensionLimitName,
                    limit = maxDimension,
                    observed = observedDimension,
                    stage = PipelineStage.RESOURCE_DECODING,
                )
            }
            index[digest to glyph.codepoint] = entries.size
            // Narrowing is safe: the per-cell check above already rejected anything whose shelf
            // position exceeds maxDimension, which is itself an Int.
            entries += glyph.entryAt(digest, x = penX, y = penY.toInt())
            penX += cellWidth
            atlasWidth = maxOf(atlasWidth, penX)
            shelfHeight = maxOf(shelfHeight, cellHeight)
        }
        val atlasHeight = penY + shelfHeight

        // A post-condition on what actually ships, not a restatement of the per-cell check. Given
        // the shelf bound above, the running pen can never carry the width past the ceiling, so
        // this is unreachable today - which is the point: it is what fails if a later change
        // re-introduces a shelf width that ignores the caller's limit, instead of that change
        // silently handing them a texture wider than they declared.
        val observedAtlasDimension = maxOf(atlasWidth.toLong(), atlasHeight)
        if (observedAtlasDimension > maxDimension) {
            throw SafetyLimitException(
                message = "Glyph atlas dimensions exceed the configured limit",
                limitName = dimensionLimitName,
                limit = maxDimension,
                observed = observedAtlasDimension,
                stage = PipelineStage.RESOURCE_DECODING,
            )
        }

        // contentKey covers the glyph set, its metrics, AND its decoded bitmap bytes - never
        // the encoded PNG. ADR 0010 forbids keying on encoded PNG bytes because encoders are
        // not byte-identical across platforms; it does not forbid keying on the decoded SDF
        // bitmap, which arrives verbatim from the provider and is identical everywhere. Without
        // the bitmap in the key, an upstream font revision that changes distance values without
        // changing box metrics or advance would silently keep the old contentKey, and a
        // consumer would never re-upload the changed texture.
        //
        // The atlas dimensions are in the key too, because the shelf width is no longer a constant:
        // it is bounded by maxRasterDimensionPx or the host's tighter labelGlyphAtlas cap, so the
        // same glyph set packed under two different limit configurations lays out differently. Without the dimensions a consumer holding a
        // texture cached under the old configuration would keep it while reading coordinates from
        // the new one, and sample the wrong cells.
        val contentKey = (
            entries.indices.joinToString("|") { i ->
                val entry = entries[i]
                val bitmapDigest = drawable[i].second.bitmap.sha256Hex()
                "${entry.fontStackDigest}:${entry.codepoint}:${entry.width}x${entry.height}:" +
                    "${entry.left},${entry.top},${entry.advance}:$bitmapDigest"
            } + "|atlas:${maxOf(atlasWidth.toLong(), 1L)}x${maxOf(atlasHeight, 1L)}"
            ).sha256Hex()

        // An empty glyph set still needs a valid (non-zero-dimension) atlas: Skia rejects
        // a zero-dimension image, so a 1x1 fully transparent placeholder stands in.
        val width = maxOf(atlasWidth.toLong(), 1L)
        val height = maxOf(atlasHeight, 1L)
        // Int.MAX_VALUE caps the effective ceiling because the pixel buffer below is a ByteArray,
        // whose length is an Int: a caller raising maxDecodedRasterBytes past 2 GiB must still get
        // a typed limit failure rather than an allocation overflow.
        val decodedLimit = minOf(limits.maxDecodedRasterBytes, Int.MAX_VALUE.toLong())
        val decodedBytes = width * height * 4L
        if (decodedBytes > decodedLimit) {
            throw SafetyLimitException(
                message = "Glyph atlas exceeds the configured memory limit",
                limitName = "maxDecodedRasterBytes",
                limit = decodedLimit,
                observed = decodedBytes,
                stage = PipelineStage.RESOURCE_DECODING,
            )
        }

        return PackedGlyphAtlas(
            entries = entries,
            pngBytes = encode(drawable, entries, width.toInt(), height.toInt()),
            width = width.toInt(),
            height = height.toInt(),
            contentKey = contentKey,
            indexOf = index,
        )
    }

    /** The entry for this glyph's buffered cell with its top-left corner at ([x], [y]). */
    private fun DecodedGlyph.entryAt(digest: String, x: Int, y: Int): LabelGlyphEntry = LabelGlyphEntry(
        fontStackDigest = digest,
        codepoint = codepoint,
        x = x, y = y,
        width = width + GlyphRangeDecoder.BUFFER_PX * 2, height = height + GlyphRangeDecoder.BUFFER_PX * 2,
        left = left, top = top,
        advance = advance,
    )

    private fun encode(
        drawable: List<Pair<String, DecodedGlyph>>,
        entries: List<LabelGlyphEntry>,
        width: Int,
        height: Int,
    ): ByteArray {
        // RGB is opaque white and the signed distance goes in alpha. A provider's SDF sprite
        // keeps its distance in alpha too, while its RGB is whatever the provider wrote (black
        // in MapTiler's sheets), so a consumer sampling alpha alone has one rule for both
        // textures (ADR 0036).
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
                // Typed, not a bare error(): this escapes through the public
                // acquireLabelCandidates, and every other encode failure in this library already
                // surfaces as PngEncodingException rather than an IllegalStateException.
                ?: throw PngEncodingException("Skia could not encode the glyph atlas as PNG")
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
