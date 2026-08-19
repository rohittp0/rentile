package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlyphRangeDecoderTest {
    @Test
    fun decodesGlyphMetricsAndBitmapExtent() {
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(
                    name = "Open Sans Regular",
                    range = "0-255",
                    glyphs = listOf(
                        Glyph(id = 65, width = 10, height = 14, left = 1, top = -12, advance = 12,
                              bitmap = okio.ByteString.of(*ByteArray((10 + 6) * (14 + 6)))),
                        Glyph(id = 32, width = 0, height = 0, left = 0, top = 0, advance = 6),
                    ),
                ),
            ),
        ).encode()

        val decoded = GlyphRangeDecoder.decode(bytes, "Open Sans Regular")

        assertEquals(2, decoded.size)
        val a = decoded.single { it.codepoint == 65 }
        assertEquals(12, a.advance)
        assertEquals(1, a.left)
        assertEquals(-12, a.top)
        assertEquals((10 + 6) * (14 + 6), a.bitmap.size)
        val space = decoded.single { it.codepoint == 32 }
        assertEquals(6, space.advance)
        assertTrue(space.bitmap.isEmpty())
    }

    @Test
    fun rejectsABitmapThatDoesNotMatchItsDeclaredExtent() {
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack("Open Sans Regular", "0-255", listOf(
                    Glyph(id = 66, width = 10, height = 14, left = 0, top = 0, advance = 12,
                          bitmap = okio.ByteString.of(*ByteArray(4))),
                )),
            ),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "Open Sans Regular")
        }
    }

    @Test
    fun rejectsAStackThatIsNotTheOneRequested() {
        val bytes = Glyphs(
            stacks = listOf(Glyphs.Fontstack("Some Other Font", "0-255", emptyList())),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "Open Sans Regular")
        }
    }
}
