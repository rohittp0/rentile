package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.LabelBox
import com.rohittp.rentile.LabelGlyphQuad
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.style.IconAnchor
import com.rohittp.rentile.internal.style.shift
import com.rohittp.rentile.internal.style.TextJustify

/**
 * Resolved text-layer style inputs [LabelLayout] needs to lay one label's text out.
 * `fontStackDigest` must match a [PackedGlyphAtlas.indexOf] key so glyph lookups land.
 */
internal data class LabelTextStyle(
    val fontStackDigest: String,
    val sizePx: Double,
    val anchor: IconAnchor,
    val offsetEm: Pair<Double, Double>,
    val justify: TextJustify,
    val maxWidthEm: Double,
    val letterSpacingEm: Double,
    val lineHeightEm: Double,
    val paddingPx: Double,
)

/** One label's text laid out into positioned glyph quads plus the box the quads occupy. */
internal data class LaidOutLabel(val quads: List<LabelGlyphQuad>, val box: LabelBox)

/**
 * Lays a label's text out into positioned glyph quads using only the signed-distance-field
 * glyph metrics carried by the atlas - advance, bearing and bitmap extent - never a platform
 * font stack or text shaper (ADR 0025). This can only accumulate advances left to right; a
 * script needing bidirectional reordering or contextual joining must be excluded upstream by
 * [ScriptSupport] before it ever reaches here.
 */
internal object LabelLayout {
    /**
     * One codepoint's contribution to a line: either a drawable glyph ([entryIndex] indexes
     * [PackedGlyphAtlas.entries]) or a non-drawable whitespace glyph, which still advances the
     * pen but is never [entryIndex]-addressable because the atlas holds no entry for it.
     */
    private data class Token(val entryIndex: Int?, val advance: Int, val isBreak: Boolean)

    /**
     * Lays [text] out against [atlas] and [style]. [whitespace] is [whitespaceAdvances] over the
     * same acquired glyph data the caller already used to build [atlas]; it is threaded through
     * separately (rather than added to [PackedGlyphAtlas] itself) because a space's advance has no
     * atlas entry at all - [GlyphAtlasPacker] deliberately drops glyphs with an empty bitmap - so
     * the only place that advance still lives is the [AcquiredGlyphRange] data the atlas was
     * packed from.
     *
     * The map is a parameter rather than something this function derives, because it depends only
     * on the batch's glyph ranges and never on the label: deriving it here rebuilt it once per
     * label, which at the `maxGlyphRangesPerBatch` ceiling is up to 64 x 256 map operations for
     * every label on the tile - landing hardest on exactly the dense-CJK case this design set out
     * to bound. The caller builds it once per batch.
     *
     * Returns null when [text] is empty after trimming, or when no codepoint in it resolves to
     * a drawable glyph or a known whitespace advance - either way there is nothing to lay out.
     */
    fun layOut(
        text: String,
        atlas: PackedGlyphAtlas,
        whitespace: Map<Pair<String, Int>, Int>,
        style: LabelTextStyle,
    ): LaidOutLabel? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val lines = wrap(trimmed, atlas, whitespace, style)
        val quads = place(lines, atlas, style)
        if (quads.isEmpty()) return null

        return LaidOutLabel(quads, bounds(quads, atlas, style))
    }

    /**
     * Advances for glyphs the atlas has no entry for - [GlyphAtlasPacker] never packs a glyph
     * with an empty bitmap, but such a glyph (a space, most often) still has a real advance the
     * provider measured, and layout must apply it rather than inventing a fallback constant.
     * Keyed the same way [PackedGlyphAtlas.indexOf] is, so a lookup miss there can fall back
     * to a lookup here before the codepoint is given up on entirely.
     *
     * Built once per batch by the caller and handed to every [layOut] call, never per label.
     */
    fun whitespaceAdvances(ranges: List<AcquiredGlyphRange>): Map<Pair<String, Int>, Int> {
        val result = LinkedHashMap<Pair<String, Int>, Int>()
        for (range in ranges) {
            val digest = range.fontStack.sha256Hex()
            for (glyph in range.glyphs) {
                // getOrPut, not putIfAbsent: the latter is a java.util.Map default method with no
                // common-source equivalent, so it compiles on JVM and Android and fails every
                // native target. Same first-writer-wins semantics.
                if (glyph.bitmap.isEmpty()) result.getOrPut(digest to glyph.codepoint) { glyph.advance }
            }
        }
        return result
    }

    /**
     * Greedy word wrap. Walks [text] as codepoints (surrogate-aware, so an astral-plane
     * codepoint is never split), resolves each to a [Token], groups tokens into word/break
     * runs, then packs words onto lines up to `maxWidthEm * EM_PX`, breaking at the last
     * whitespace run before a line would overflow. A single word longer than the limit is
     * never split - it is simply the only thing on its line.
     */
    private fun wrap(
        text: String,
        atlas: PackedGlyphAtlas,
        whitespace: Map<Pair<String, Int>, Int>,
        style: LabelTextStyle,
    ): List<List<Token>> {
        val letterSpacingPx = style.letterSpacingEm * GlyphRangeDecoder.EM_PX
        val maxWidthPx = style.maxWidthEm * GlyphRangeDecoder.EM_PX

        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAtCompat(index)
            index += if (codepoint > 0xFFFF) 2 else 1
            val key = style.fontStackDigest to codepoint
            val entryIndex = atlas.indexOf[key]
            if (entryIndex != null) {
                tokens += Token(entryIndex, atlas.entries[entryIndex].advance, isBreak = false)
            } else {
                val advance = whitespace[key] ?: continue // absent everywhere: skip entirely
                tokens += Token(null, advance, isBreak = true)
            }
        }
        if (tokens.isEmpty()) return emptyList()

        // Group consecutive tokens of the same kind into word/break runs.
        val runs = mutableListOf<List<Token>>()
        var runStart = 0
        for (i in tokens.indices) {
            if (i == tokens.lastIndex || tokens[i].isBreak != tokens[i + 1].isBreak) {
                runs += tokens.subList(runStart, i + 1)
                runStart = i + 1
            }
        }

        fun runWidth(run: List<Token>): Double = run.sumOf { it.advance + letterSpacingPx }

        val lines = mutableListOf<List<Token>>()
        var currentLine = mutableListOf<Token>()
        var currentWidth = 0.0
        var pendingBreak: List<Token>? = null

        for (run in runs) {
            if (run.first().isBreak) {
                // Held, not yet committed: it only counts if a following word joins this
                // line. A break at the very end of the text, or right after a forced
                // line start, is simply never committed and so never rendered.
                pendingBreak = run
                continue
            }
            val breakWidth = pendingBreak?.let(::runWidth) ?: 0.0
            val wordWidth = runWidth(run)
            when {
                currentLine.isEmpty() -> {
                    // First word on the line: always placed, never split, regardless of
                    // whether it alone exceeds maxWidthPx.
                    currentLine += run
                    currentWidth = wordWidth
                }
                currentWidth + breakWidth + wordWidth <= maxWidthPx -> {
                    pendingBreak?.let { currentLine += it }
                    currentLine += run
                    currentWidth += breakWidth + wordWidth
                }
                else -> {
                    lines += currentLine
                    currentLine = run.toMutableList()
                    currentWidth = wordWidth
                }
            }
            pendingBreak = null
        }
        if (currentLine.isNotEmpty()) lines += currentLine
        return lines
    }

    /**
     * Places each line's glyphs left to right, then aligns lines per [LabelTextStyle.justify],
     * centers the resulting block, shifts it so [LabelTextStyle.anchor] sits at the origin, and
     * displaces it by `offsetEm * EM_PX`. Each line occupies one `lineHeightEm * EM_PX` row and is
     * positioned from that row's **top edge**, never from a baseline.
     *
     * That is what the provider's `top` bearing is measured against. Glyph ranges encode `-top` as
     * the distance from the line's ascender down to the bitmap's top edge, so `-top` is directly
     * this row's downward offset, and no ascent or descent metric is needed to use it. Verified
     * against two live providers on 2026-08-19: a range's degenerate space glyph has `height == 0`
     * and pins the ascender, at `top == -23` for Stadia Maps' `Stadia Regular` and `top == -25` for
     * MapTiler's `Open Sans Regular`; a cap-height `A` then reports `top == -5` and `-7`
     * respectively with `height == 18`, putting its body's bottom edge exactly on the baseline in
     * both. Overshooting and descending glyphs fall out of the same rule.
     *
     * Reading `top` as an offset from a baseline - which this did until it was measured - is wrong in
     * both sign and reference. The old formula placed a glyph at `baselineY + top` with
     * `baselineY = (lineIndex + 0.5) * lineHeightPx - blockHeight / 2`, against
     * `lineTopY - top` with `lineTopY = lineIndex * lineHeightPx - blockHeight / 2`, so it sat
     * `lineHeightPx / 2 + 2 * top` too low - the block height and the line index both cancel, so the
     * error is the same on every line. For Open Sans at a 1.2 em line height that is
     * `14.4 + 2 * -7 = 0.4`, and for Stadia `14.4 + 2 * -5 = 4.4`. Nearly right for one font and
     * visibly wrong for the other, which is why nothing noticed: every fixture in the suite encoded
     * the assumption under test.
     */
    private fun place(lines: List<List<Token>>, atlas: PackedGlyphAtlas, style: LabelTextStyle): List<LabelGlyphQuad> {
        if (lines.isEmpty()) return emptyList()

        val letterSpacingPx = style.letterSpacingEm * GlyphRangeDecoder.EM_PX
        val lineHeightPx = style.lineHeightEm * GlyphRangeDecoder.EM_PX
        val scale = style.sizePx / GlyphRangeDecoder.EM_PX

        data class PlacedGlyph(val entryIndex: Int, val x: Double, val lineTopY: Double)

        val lineWidths = DoubleArray(lines.size)
        val perLine = lines.mapIndexed { lineIndex, line ->
            var penX = 0.0
            // The row's top edge, not its center and not a baseline: `-entry.top` is measured down
            // from exactly here, so the block spans [0, blockHeight) and vertical anchoring needs
            // only the block height, never a per-glyph bearing.
            val lineTopY = lineIndex * lineHeightPx
            val glyphs = mutableListOf<PlacedGlyph>()
            for (token in line) {
                if (token.entryIndex != null) glyphs += PlacedGlyph(token.entryIndex, penX, lineTopY)
                penX += token.advance + letterSpacingPx
            }
            // The line's own width excludes the trailing letter-spacing added after its
            // final glyph - there is no next glyph on this line to space away from.
            lineWidths[lineIndex] = (penX - letterSpacingPx).coerceAtLeast(0.0)
            glyphs
        }

        val blockWidth = lineWidths.maxOrNull() ?: 0.0
        val blockHeight = lines.size * lineHeightPx

        val justified = perLine.mapIndexed { lineIndex, glyphs ->
            val offset = when (style.justify) {
                TextJustify.LEFT -> 0.0
                TextJustify.CENTER -> (blockWidth - lineWidths[lineIndex]) / 2.0
                TextJustify.RIGHT -> blockWidth - lineWidths[lineIndex]
            }
            glyphs.map { it.copy(x = it.x + offset) }
        }

        val (anchorDx, anchorDy) = style.anchor.shift(blockWidth, blockHeight)
        val offsetDx = style.offsetEm.first * GlyphRangeDecoder.EM_PX
        val offsetDy = style.offsetEm.second * GlyphRangeDecoder.EM_PX

        val quads = mutableListOf<LabelGlyphQuad>()
        for (glyphs in justified) {
            for (glyph in glyphs) {
                val entry = atlas.entries[glyph.entryIndex]
                val penX = glyph.x - blockWidth / 2.0 + anchorDx + offsetDx
                val lineTopY = glyph.lineTopY - blockHeight / 2.0 + anchorDy + offsetDy
                // Two corrections, both taking the cell's corner off the body's position.
                //
                // `- entry.top` rather than `+`: the provider measures `-top` downward from the
                // line's top edge, which lineTopY is, so subtracting a negative bearing moves the
                // body down into the row. Adding it to a baseline instead - the reading this
                // carried before the wire format was measured - was wrong in sign and in
                // reference, and by a per-font amount.
                //
                // `- BUFFER_PX` on both axes because the quad is the *cell*, not the glyph body.
                // GlyphAtlasPacker sizes an entry as the buffered cell
                // (glyph.width + BUFFER_PX * 2) while carrying the provider's unbuffered bearings,
                // so placing the cell's corner at the bearing would put the body - which starts
                // BUFFER_PX inside the cell - that far down and right of where the bearing says.
                quads += LabelGlyphQuad(
                    entryIndex = glyph.entryIndex,
                    x = (penX + entry.left - GlyphRangeDecoder.BUFFER_PX) * scale,
                    y = (lineTopY - entry.top - GlyphRangeDecoder.BUFFER_PX) * scale,
                    scale = scale,
                )
            }
        }
        return quads
    }

    /** The union of every quad's own extent (not just its origin), expanded by [LabelTextStyle.paddingPx]. */
    private fun bounds(quads: List<LabelGlyphQuad>, atlas: PackedGlyphAtlas, style: LabelTextStyle): LabelBox {
        var left = Double.POSITIVE_INFINITY
        var top = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var bottom = Double.NEGATIVE_INFINITY
        for (quad in quads) {
            val entry = atlas.entries[quad.entryIndex]
            left = minOf(left, quad.x)
            top = minOf(top, quad.y)
            right = maxOf(right, quad.x + entry.width * quad.scale)
            bottom = maxOf(bottom, quad.y + entry.height * quad.scale)
        }
        return LabelBox(
            left = left - style.paddingPx,
            top = top - style.paddingPx,
            right = right + style.paddingPx,
            bottom = bottom + style.paddingPx,
        )
    }
}
