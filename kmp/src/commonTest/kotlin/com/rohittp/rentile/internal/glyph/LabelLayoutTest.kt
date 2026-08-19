package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.internal.style.IconAnchor
import com.rohittp.rentile.internal.style.TextJustify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelLayoutTest {
    // layOut takes a whitespace-advance map alongside the packed atlas, not atlas alone as
    // originally sketched: PackedGlyphAtlas has no entry - and so no advance - for a glyph with
    // an empty bitmap (a space, most often), since GlyphAtlasPacker deliberately drops those.
    // The acquired ranges are the only place that advance still lives. The map is derived from
    // them once per batch rather than inside layOut, which would rebuild it for every label.
    private fun rangesOf(vararg codepoints: Int): List<AcquiredGlyphRange> = listOf(
        AcquiredGlyphRange(
            "Test Font", 0,
            codepoints.map { cp ->
                DecodedGlyph(cp, 10, 14, 1, -14, 12, ByteArray((10 + 6) * (14 + 6)) { 1 })
            } + DecodedGlyph(32, 0, 0, 0, 0, 6, ByteArray(0)),
            "digest",
        ),
    )

    private fun setUp(vararg codepoints: Int): Pair<Map<Pair<String, Int>, Int>, PackedGlyphAtlas> {
        val ranges = rangesOf(*codepoints)
        return LabelLayout.whitespaceAdvances(ranges) to GlyphAtlasPacker.pack(ranges)
    }

    private fun styleFor(atlas: PackedGlyphAtlas, maxWidthEm: Double = 10.0) = LabelTextStyle(
        fontStackDigest = atlas.entries.first().fontStackDigest,
        sizePx = 24.0, anchor = IconAnchor.CENTER, offsetEm = 0.0 to 0.0,
        justify = TextJustify.CENTER, maxWidthEm = maxWidthEm,
        letterSpacingEm = 0.0, lineHeightEm = 1.2, paddingPx = 2.0,
    )

    @Test
    fun oneQuadPerDrawableGlyph() {
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        val laid = LabelLayout.layOut("AB", atlas, whitespace, styleFor(atlas))!!

        assertEquals(2, laid.quads.size)
        // Advance is 12 em-units and sizePx equals EM_PX, so scale is 1.0. Assert the
        // relative advance, which is independent of where the anchor puts the block.
        assertEquals(12.0, laid.quads[1].x - laid.quads[0].x)
        assertEquals(laid.quads[0].y, laid.quads[1].y)
        assertTrue(laid.quads.all { it.scale == 1.0 })
    }

    @Test
    fun whitespaceAdvancesWithoutAQuad() {
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        val laid = LabelLayout.layOut("A B", atlas, whitespace, styleFor(atlas))!!

        assertEquals(2, laid.quads.size)
        // 12 for 'A' plus 6 for the space.
        assertEquals(18.0, laid.quads[1].x - laid.quads[0].x)
    }

    @Test
    fun wrapsAtTheWidthLimitOnWordBoundaries() {
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        // Two two-glyph words are 24 em-units each; a 2-em limit forces a break.
        val laid = LabelLayout.layOut("AB AB", atlas, whitespace, styleFor(atlas, maxWidthEm = 2.0))!!

        val rows = laid.quads.map { it.y }.distinct()
        assertEquals(2, rows.size)
        assertEquals(1.2 * 24.0, rows[1] - rows[0])
    }

    @Test
    fun aSingleWordLongerThanTheLimitIsNotSplit() {
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        // "AB" alone is 24 em-units, already over a 1-em (24px) limit, but it is one word
        // with no space to break at, so it stays whole on its own line.
        val laid = LabelLayout.layOut("AB", atlas, whitespace, styleFor(atlas, maxWidthEm = 1.0))!!

        assertEquals(2, laid.quads.size)
        assertEquals(laid.quads[0].y, laid.quads[1].y)
        assertEquals(12.0, laid.quads[1].x - laid.quads[0].x)
    }

    @Test
    fun scalesGeometryByTextSize() {
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        val small = LabelLayout.layOut("AB", atlas, whitespace, styleFor(atlas))!!
        val large = LabelLayout.layOut("AB", atlas, whitespace, styleFor(atlas).copy(sizePx = 48.0))!!

        assertEquals(2.0, large.quads[1].x / small.quads[1].x)
        assertTrue(large.quads.all { it.scale == 2.0 })
    }

    @Test
    fun theBoundingBoxCoversEveryQuadPlusPadding() {
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        val laid = LabelLayout.layOut("AB", atlas, whitespace, styleFor(atlas))!!

        assertTrue(laid.box.left <= laid.quads.minOf { it.x } - 2.0)
        assertTrue(laid.box.right >= laid.quads.maxOf { it.x } + 2.0)
    }

    @Test
    fun emptyTextYieldsNoLabel() {
        val (whitespace, atlas) = setUp('A'.code)
        assertNull(LabelLayout.layOut("   ", atlas, whitespace, styleFor(atlas)))
    }

    @Test
    fun aCodepointMissingFromTheAtlasEntirelyIsSkipped() {
        // 'Z' is not in the atlas at all - not drawable, and not whitespace either - so it
        // must contribute neither a quad nor an advance, leaving "AZB" laid out exactly as
        // "AB" would be.
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        val withZ = LabelLayout.layOut("AZB", atlas, whitespace, styleFor(atlas))!!
        val withoutZ = LabelLayout.layOut("AB", atlas, whitespace, styleFor(atlas))!!

        assertEquals(withoutZ.quads, withZ.quads)
    }

    @Test
    fun isDeterministicAcrossRepeatedCalls() {
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        val style = styleFor(atlas)

        assertEquals(
            LabelLayout.layOut("AB AB", atlas, whitespace, style),
            LabelLayout.layOut("AB AB", atlas, whitespace, style),
        )
    }

    @Test
    fun centeredSingleLineAnchorsTheFirstGlyphAtAKnownAbsolutePosition() {
        // Pins the anchor/justify arithmetic itself, not just a relative distance, so a
        // future change to it is caught here rather than only failing at some consumer far
        // downstream. For "AB" centered both ways: the line (and the block) is exactly 24
        // em-units wide, so justification contributes no offset and the anchor shift alone
        // centers the block, putting the first glyph's origin at x = -12 + left(1) = -11,
        // and the single line's baseline sits exactly at the vertical center, so
        // y = 0 + top(-14) = -14.
        val (whitespace, atlas) = setUp('A'.code, 'B'.code)
        val laid = LabelLayout.layOut("AB", atlas, whitespace, styleFor(atlas))!!

        assertEquals(-11.0, laid.quads[0].x)
        assertEquals(-14.0, laid.quads[0].y)
    }
}
