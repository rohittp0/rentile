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
    fun acceptsAServedStackThatDiffersOnlyBySeparatorWhitespace() {
        // api.maptiler.com is asked for "Roboto Italic,Noto Sans Italic" and answers with
        // "Roboto Italic, Noto Sans Italic". Every corpus font stack is a multi-font chain, so a
        // string equality here failed every label acquisition against every corpus style.
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack("Roboto Italic, Noto Sans Italic", "0-255", listOf(
                    Glyph(id = 65, width = 0, height = 0, left = 0, top = 0, advance = 12),
                )),
            ),
        ).encode()

        val decoded = GlyphRangeDecoder.decode(bytes, "Roboto Italic,Noto Sans Italic")

        assertEquals(1, decoded.size)
        assertEquals(65, decoded.single().codepoint)
    }

    @Test
    fun rejectsAStackWhoseNamesDifferRatherThanItsSeparators() {
        // The normalisation must stay narrow: a provider serving a genuinely different chain would
        // draw the wrong glyphs at the right metrics, which is invisible in the output and is the
        // reason this check exists at all.
        val bytes = Glyphs(
            stacks = listOf(Glyphs.Fontstack("Roboto Italic, Noto Serif Italic", "0-255", emptyList())),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "Roboto Italic,Noto Sans Italic")
        }
    }

    @Test
    fun rejectsAChainServedInADifferentFallbackOrder() {
        // Order is the fallback order, so it is significant and normalisation must not sort it.
        val bytes = Glyphs(
            stacks = listOf(Glyphs.Fontstack("Noto Sans Italic, Roboto Italic", "0-255", emptyList())),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "Roboto Italic,Noto Sans Italic")
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
