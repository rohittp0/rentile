package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.LabelBox
import com.rohittp.rentile.LabelGlyphQuad
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.style.IconAnchor
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
     * Lays [text] out against [atlas] and [style]. [ranges] is the same acquired glyph data
     * the caller already used to build [atlas]; it is threaded through separately (rather than
     * added to [PackedGlyphAtlas] itself) because a space's advance has no atlas entry at all -
     * [GlyphAtlasPacker] deliberately drops glyphs with an empty bitmap - so the only place
     * that advance still lives is the [AcquiredGlyphRange] data the atlas was packed from.
     *
     * Returns null when [text] is empty after trimming, or when no codepoint in it resolves to
     * a drawable glyph or a known whitespace advance - either way there is nothing to lay out.
     */
    fun layOut(
        text: String,
        atlas: PackedGlyphAtlas,
        ranges: List<AcquiredGlyphRange>,
        style: LabelTextStyle,
    ): LaidOutLabel? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val whitespace = whitespaceAdvances(ranges)
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
     */
    private fun whitespaceAdvances(ranges: List<AcquiredGlyphRange>): Map<Pair<String, Int>, Int> {
        val result = LinkedHashMap<Pair<String, Int>, Int>()
        for (range in ranges) {
            val digest = range.fontStack.sha256Hex()
            for (glyph in range.glyphs) {
                if (glyph.bitmap.isEmpty()) result.putIfAbsent(digest to glyph.codepoint, glyph.advance)
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
     * displaces it by `offsetEm * EM_PX`. Each line occupies one `lineHeightEm * EM_PX` row,
     * with its baseline at the row's vertical center - the atlas carries no ascent/descent
     * metric to place it any more precisely than that.
     */
    private fun place(lines: List<List<Token>>, atlas: PackedGlyphAtlas, style: LabelTextStyle): List<LabelGlyphQuad> {
        if (lines.isEmpty()) return emptyList()

        val letterSpacingPx = style.letterSpacingEm * GlyphRangeDecoder.EM_PX
        val lineHeightPx = style.lineHeightEm * GlyphRangeDecoder.EM_PX
        val scale = style.sizePx / GlyphRangeDecoder.EM_PX

        data class PlacedGlyph(val entryIndex: Int, val x: Double, val y: Double)

        val lineWidths = DoubleArray(lines.size)
        val perLine = lines.mapIndexed { lineIndex, line ->
            var penX = 0.0
            val baselineY = (lineIndex + 0.5) * lineHeightPx
            val glyphs = mutableListOf<PlacedGlyph>()
            for (token in line) {
                if (token.entryIndex != null) glyphs += PlacedGlyph(token.entryIndex, penX, baselineY)
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

        val (anchorDx, anchorDy) = anchorShift(style.anchor, blockWidth, blockHeight)
        val offsetDx = style.offsetEm.first * GlyphRangeDecoder.EM_PX
        val offsetDy = style.offsetEm.second * GlyphRangeDecoder.EM_PX

        val quads = mutableListOf<LabelGlyphQuad>()
        for (glyphs in justified) {
            for (glyph in glyphs) {
                val entry = atlas.entries[glyph.entryIndex]
                val penX = glyph.x - blockWidth / 2.0 + anchorDx + offsetDx
                val baselineY = glyph.y - blockHeight / 2.0 + anchorDy + offsetDy
                quads += LabelGlyphQuad(
                    entryIndex = glyph.entryIndex,
                    x = (penX + entry.left) * scale,
                    y = (baselineY + entry.top) * scale,
                    scale = scale,
                )
            }
        }
        return quads
    }

    /**
     * The shift that moves a box centered at the origin so [anchor] sits at the origin
     * instead, mirroring the convention `DefaultBasemapRasterizer.iconAnchorShift` uses for
     * sprite icons: positive x is right, positive y is down, matching this codebase's Skia
     * canvas coordinates throughout.
     */
    private fun anchorShift(anchor: IconAnchor, width: Double, height: Double): Pair<Double, Double> = when (anchor) {
        IconAnchor.CENTER -> 0.0 to 0.0
        IconAnchor.LEFT -> width / 2.0 to 0.0
        IconAnchor.RIGHT -> -width / 2.0 to 0.0
        IconAnchor.TOP -> 0.0 to height / 2.0
        IconAnchor.BOTTOM -> 0.0 to -height / 2.0
        IconAnchor.TOP_LEFT -> width / 2.0 to height / 2.0
        IconAnchor.TOP_RIGHT -> -width / 2.0 to height / 2.0
        IconAnchor.BOTTOM_LEFT -> width / 2.0 to -height / 2.0
        IconAnchor.BOTTOM_RIGHT -> -width / 2.0 to -height / 2.0
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
