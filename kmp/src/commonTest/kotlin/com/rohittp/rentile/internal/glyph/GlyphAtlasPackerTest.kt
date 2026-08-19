package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlyphAtlasPackerTest {
    private fun glyph(id: Int, w: Int, h: Int) = DecodedGlyph(
        codepoint = id, width = w, height = h, left = 0, top = -h, advance = w + 2,
        bitmap = ByteArray((w + 6) * (h + 6)) { 1 },
    )

    private fun glyphWithFill(id: Int, w: Int, h: Int, fill: Byte) = DecodedGlyph(
        codepoint = id, width = w, height = h, left = 0, top = -h, advance = w + 2,
        bitmap = ByteArray((w + 6) * (h + 6)) { fill },
    )

    private fun range(stack: String, start: Int, vararg glyphs: DecodedGlyph) =
        AcquiredGlyphRange(stack, start, glyphs.toList(), "digest-$stack-$start")

    @Test
    fun packsEveryDrawableGlyphAndSkipsWhitespace() {
        val space = DecodedGlyph(32, 0, 0, 0, 0, 6, ByteArray(0))
        val atlas = GlyphAtlasPacker.pack(
            listOf(range("Open Sans Regular", 0, glyph(65, 10, 14), glyph(66, 9, 14), space)),
        )

        assertEquals(2, atlas.entries.size)
        assertTrue(atlas.entries.all { it.width > 0 && it.height > 0 })
        assertTrue(atlas.width > 0 && atlas.height > 0)
        assertTrue(atlas.pngBytes.isNotEmpty())
    }

    @Test
    fun isIndependentOfTheOrderRangesArriveIn() {
        val a = range("Open Sans Regular", 0, glyph(65, 10, 14))
        val b = range("Roboto Regular", 0, glyph(66, 9, 14))

        val forward = GlyphAtlasPacker.pack(listOf(a, b))
        val reversed = GlyphAtlasPacker.pack(listOf(b, a))

        assertEquals(forward.contentKey, reversed.contentKey)
        assertEquals(forward.entries, reversed.entries)
    }

    @Test
    fun contentKeyChangesWhenAGlyphMetricChanges() {
        val one = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, glyph(65, 10, 14))))
        val two = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, glyph(65, 11, 14))))

        assertNotEquals(one.contentKey, two.contentKey)
    }

    @Test
    fun contentKeyChangesWhenOnlyTheBitmapBytesChange() {
        // Same font stack, same codepoint, same box metrics/advance - only the SDF pixel
        // content differs (e.g. an upstream font revision touching distance values but not
        // the glyph's bounding box). contentKey must still change: a consumer deciding
        // whether to re-upload must never see a stale key for changed pixels.
        val one = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, glyphWithFill(65, 10, 14, 1))))
        val two = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, glyphWithFill(65, 10, 14, 2))))

        assertNotEquals(one.contentKey, two.contentKey)
    }

    @Test
    fun collidingGlyphsFromDifferentRangesResolveTheSameWayRegardlessOfArrivalOrder() {
        // Two ranges disagree about the exact same (fontStack, codepoint): identical box
        // metrics but different bitmap bytes. Whichever one "wins" the dedup must be a
        // function of glyph content, not of which range was iterated first - otherwise
        // pack(a, b) and pack(b, a) would silently disagree about a glyph's own pixels.
        val a = range("Open Sans Regular", 0, glyphWithFill(65, 10, 14, 1))
        val b = range("Open Sans Regular", 0, glyphWithFill(65, 10, 14, 2))

        val forward = GlyphAtlasPacker.pack(listOf(a, b))
        val reversed = GlyphAtlasPacker.pack(listOf(b, a))

        assertEquals(1, forward.entries.size)
        assertEquals(forward.contentKey, reversed.contentKey)
        assertEquals(forward.entries, reversed.entries)
    }

    @Test
    fun everyDrawableGlyphIsAddressableByFontStackAndCodepoint() {
        val atlas = GlyphAtlasPacker.pack(
            listOf(range("Open Sans Regular", 0, glyph(65, 10, 14))),
        )
        val stackDigest = atlas.entries.single().fontStackDigest
        assertEquals(0, atlas.indexOf.getValue(stackDigest to 65))
    }

    @Test
    fun anEmptyGlyphSetStillProducesAValidAtlas() {
        val atlas = GlyphAtlasPacker.pack(emptyList())

        assertEquals(0, atlas.entries.size)
        assertEquals(1, atlas.width)
        assertEquals(1, atlas.height)
        assertTrue(atlas.pngBytes.isNotEmpty())
    }
}
