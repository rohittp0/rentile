package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.ResourceLimits
import com.rohittp.rentile.SafetyLimitException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun rejectsAnAtlasTallerThanTheDimensionCeiling() {
        // Glyph extents are provider-declared. maxGlyphRangeBytes bounds one range, but nothing
        // bounded the sum: enough tall glyphs shelf-pack into an arbitrarily tall atlas, whose
        // width * height * 4 pixel buffer overflowed Int into a bare NegativeArraySizeException.
        // 206-pixel cells: four per 1024-wide shelf, so 40 glyphs need ten shelves.
        val tall = (0 until 40).map { index -> glyph(index, 200, 200) }
        val limits = ResourceLimits(maxRasterDimensionPx = 1024)

        val failure = assertFailsWith<SafetyLimitException> {
            GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, *tall.toTypedArray())), limits)
        }

        assertEquals("maxRasterDimensionPx", failure.limitName)
    }

    @Test
    fun rejectsAnAtlasOverTheDecodedByteCeiling() {
        // The dimensions are individually fine; it is their product that is not.
        val wide = (0 until 60).map { index -> glyph(index, 100, 100) }
        val limits = ResourceLimits(maxDecodedRasterBytes = 4096L)

        val failure = assertFailsWith<SafetyLimitException> {
            GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, *wide.toTypedArray())), limits)
        }

        assertEquals("maxDecodedRasterBytes", failure.limitName)
    }

    @Test
    fun packsWithinTheDefaultCeilings() {
        // The ceilings must not fire on ordinary input: 200 Latin-sized glyphs is a realistic
        // single-range atlas and stays far inside both defaults.
        val ordinary = (0 until 200).map { index -> glyph(index, 10, 14) }

        val atlas = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, *ordinary.toTypedArray())))

        assertEquals(200, atlas.entries.size)
        assertTrue(atlas.width in 1..4096)
    }

    @Test
    fun packsAsManyDenseRangesAsTheBatchCeilingAllows() {
        // The test that keeps two numbers agreeing. maxGlyphRangesPerBatch promises 64 ranges as
        // headroom over a measured 15, and the packer must actually hold them: a shelf too narrow
        // makes the range check pass, every range get fetched, and the packer then throw - paying
        // the whole network cost for a failure. A 1024 shelf held about 41.
        //
        // 64 full ranges of 256 dense CJK-sized glyphs is the worst case that ceiling admits.
        val limits = ResourceLimits()
        val dense = (0 until limits.maxGlyphRangesPerBatch).map { rangeIndex ->
            val start = rangeIndex * 256
            range(
                "Open Sans Regular",
                start,
                *(0 until 256).map { glyph(start + it, 22, 22) }.toTypedArray(),
            )
        }

        val atlas = GlyphAtlasPacker.pack(dense, limits)

        assertEquals(limits.maxGlyphRangesPerBatch * 256, atlas.entries.size)
        assertTrue(
            atlas.width <= limits.maxRasterDimensionPx && atlas.height <= limits.maxRasterDimensionPx,
            "atlas was ${atlas.width}x${atlas.height}, over maxRasterDimensionPx",
        )
        assertTrue(
            atlas.width.toLong() * atlas.height * 4L <= limits.maxDecodedRasterBytes,
            "atlas was ${atlas.width}x${atlas.height}, over maxDecodedRasterBytes",
        )
    }

    @Test
    fun neverExceedsACallersOwnLoweredDimensionCeiling() {
        // A caller lowering maxRasterDimensionPx has declared a texture ceiling. The shelf width was
        // a hard 1024, so a caller asking for 512 still received a 1024-wide atlas - their own limit
        // breached silently, with no exception and no diagnostic.
        val limits = ResourceLimits(maxRasterDimensionPx = 512)
        val glyphs = (0 until 120).map { glyph(it, 40, 40) }

        val atlas = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, *glyphs.toTypedArray())), limits)

        assertTrue(atlas.width <= 512, "atlas width ${atlas.width} exceeds the caller's 512 ceiling")
        assertTrue(atlas.height <= 512, "atlas height ${atlas.height} exceeds the caller's 512 ceiling")
    }

    @Test
    fun theContentKeyCoversTheAtlasDimensions() {
        // The shelf width is bounded by maxRasterDimensionPx, so the same glyph set lays out
        // differently under different limits. contentKey answers "must I re-upload the texture?",
        // so it has to change when the layout does, or a consumer keeps a cached texture while
        // reading coordinates for a different one.
        val glyphs = (0 until 120).map { glyph(it, 40, 40) }
        val ranges = listOf(range("Open Sans Regular", 0, *glyphs.toTypedArray()))

        val wide = GlyphAtlasPacker.pack(ranges)
        val narrow = GlyphAtlasPacker.pack(ranges, ResourceLimits(maxRasterDimensionPx = 512))

        assertNotEquals(wide.width, narrow.width)
        assertNotEquals(wide.contentKey, narrow.contentKey)
    }
}
