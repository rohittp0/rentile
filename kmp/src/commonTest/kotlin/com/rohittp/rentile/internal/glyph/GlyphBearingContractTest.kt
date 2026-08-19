package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.internal.style.IconAnchor
import com.rohittp.rentile.internal.style.TextJustify
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how a glyph range's `top` bearing is actually measured, against metrics taken from live
 * providers rather than from Rentile's own assumptions.
 *
 * This test exists because a whole test suite and a 34-of-34 corpus gate both passed while layout
 * read `top` as an offset from a baseline, which is wrong in sign and in reference. Every other
 * fixture in the suite was hand-built from that same assumption, so none of them could contradict
 * it, and the corpus gate asserts candidate counts and diagnostics but never geometry. A fixture
 * built from what we believe can only ever confirm what we believe.
 *
 * The numbers below are measured, not invented. Two ranges were fetched and their zigzag-encoded
 * bearings decoded on 2026-08-19:
 *
 * ```
 * Stadia Maps  "Stadia Regular":      space top=-23   A top=-5, height=18
 * MapTiler     "Open Sans Regular":   space top=-25   A top=-7, height=18
 * ```
 *
 * A range's space glyph has `height == 0`, which pins the line's ascender: `-top` for that glyph is
 * the whole distance from the line's top edge to its baseline, 23 for Stadia and 25 for Open Sans.
 * Every other glyph's body then occupies `-top` to `-top + height` below the line top, which puts a
 * cap-height `A` exactly on the baseline in both fonts. The Open Sans values are used here; the
 * `o` and `p` metrics are derived from that same rule to reproduce the overshoot and descent the
 * provider data shows, since only `space` and `A` were read off the wire directly.
 *
 * The bitmap bytes are synthetic, deliberately: bitmap *content* cannot affect geometry, and the
 * decoder only requires the declared extent to match. What must come from the provider, and does,
 * is the metrics.
 */
class GlyphBearingContractTest {
    private companion object {
        /** Measured from MapTiler's `Open Sans Regular`, range 0-255. */
        const val SPACE_TOP = -25
        const val CAP_TOP = -7
        const val CAP_HEIGHT = 18

        /** Same rule, reproducing the one-unit overshoot the provider data shows for `o`. */
        const val ROUND_TOP = -12
        const val ROUND_HEIGHT = 14

        /** Same rule, reproducing the six-unit descent the provider data shows for `p`. */
        const val DESCENDER_TOP = -12
        const val DESCENDER_HEIGHT = 19

        const val LINE_HEIGHT_EM = 1.2
        const val TOLERANCE = 1e-9
    }

    private fun glyph(codepoint: Int, width: Int, height: Int, top: Int, advance: Int) = Glyph(
        id = codepoint,
        width = width,
        height = height,
        left = 0,
        top = top,
        advance = advance,
        bitmap = ByteArray((width + 6) * (height + 6)) { 1 }.toByteString()
            .takeIf { width > 0 && height > 0 },
    )

    /** The encoded range, built once and decoded through the real decoder rather than bypassed. */
    private fun openSansRange(): ByteArray = Glyphs(
        stacks = listOf(
            Glyphs.Fontstack(
                name = "Open Sans Regular",
                range = "0-255",
                glyphs = listOf(
                    glyph(' '.code, 0, 0, SPACE_TOP, 6),
                    glyph('A'.code, 13, CAP_HEIGHT, CAP_TOP, 16),
                    glyph('o'.code, 13, ROUND_HEIGHT, ROUND_TOP, 14),
                    glyph('p'.code, 13, DESCENDER_HEIGHT, DESCENDER_TOP, 14),
                ),
            ),
        ),
    ).encode()

    private class Laid(
        val baselineY: Double,
        val bodyBottoms: Map<Int, Double>,
        val bodyTops: Map<Int, Double>,
    )

    private fun layOutOpenSans(text: String): Laid {
        val decoded = GlyphRangeDecoder.decode(openSansRange(), "0-255")
        val range = AcquiredGlyphRange("Open Sans Regular", 0, decoded, "digest")
        val atlas = GlyphAtlasPacker.pack(listOf(range))
        val style = LabelTextStyle(
            fontStackDigest = atlas.entries.first().fontStackDigest,
            sizePx = GlyphRangeDecoder.EM_PX,
            anchor = IconAnchor.CENTER,
            offsetEm = 0.0 to 0.0,
            justify = TextJustify.CENTER,
            maxWidthEm = 10.0,
            letterSpacingEm = 0.0,
            lineHeightEm = LINE_HEIGHT_EM,
            paddingPx = 0.0,
        )
        val laid = LabelLayout.layOut(text, atlas, LabelLayout.whitespaceAdvances(listOf(range)), style)!!

        // The ascender comes off the decoded range itself, not a constant here: the space glyph has
        // height 0, so -top is the entire distance from the line's top edge to its baseline.
        val ascender = -decoded.single { it.codepoint == ' '.code }.top.toDouble()
        // One line, centred, no anchor shift and no offset, so the line's top edge sits half a line
        // height above the label's origin.
        val lineTopY = -(LINE_HEIGHT_EM * GlyphRangeDecoder.EM_PX) / 2.0

        val tops = mutableMapOf<Int, Double>()
        val bottoms = mutableMapOf<Int, Double>()
        for (quad in laid.quads) {
            val entry = atlas.entries[quad.entryIndex]
            val bodyTop = quad.y + GlyphRangeDecoder.BUFFER_PX * quad.scale
            tops[entry.codepoint] = bodyTop
            bottoms[entry.codepoint] =
                bodyTop + (entry.height - GlyphRangeDecoder.BUFFER_PX * 2) * quad.scale
        }
        return Laid(baselineY = lineTopY + ascender, bodyTops = tops, bodyBottoms = bottoms)
    }

    @Test
    fun aCapHeightLetterRestsExactlyOnItsLinesBaseline() {
        val laid = layOutOpenSans("Aop")

        assertEquals(laid.baselineY, laid.bodyBottoms.getValue('A'.code), TOLERANCE)
    }

    @Test
    fun aDescenderExtendsBelowTheBaselineAndARoundLetterOvershootsIt() {
        val laid = layOutOpenSans("Aop")

        // Measured shapes: `o` passes one unit below the baseline, `p` six.
        assertEquals(laid.baselineY + 1.0, laid.bodyBottoms.getValue('o'.code), TOLERANCE)
        assertEquals(laid.baselineY + 6.0, laid.bodyBottoms.getValue('p'.code), TOLERANCE)
        // The relationship, stated independently of the exact amounts, is the load-bearing claim.
        assertTrue(laid.bodyBottoms.getValue('p'.code) > laid.bodyBottoms.getValue('o'.code))
        assertTrue(laid.bodyBottoms.getValue('o'.code) > laid.bodyBottoms.getValue('A'.code))
    }

    @Test
    fun theCapHeightBodyTopSitsAtTheAscenderOffsetTheProviderDeclared() {
        val laid = layOutOpenSans("Aop")

        // -top below the line's top edge, which is the baseline less the cap height.
        assertEquals(laid.baselineY - CAP_HEIGHT, laid.bodyTops.getValue('A'.code), TOLERANCE)
        // `o` and `p` share an x-height top, as the provider metrics show.
        assertEquals(laid.bodyTops.getValue('o'.code), laid.bodyTops.getValue('p'.code), TOLERANCE)
    }
}
