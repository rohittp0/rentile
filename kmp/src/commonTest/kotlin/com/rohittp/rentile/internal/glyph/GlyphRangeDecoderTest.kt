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

        val decoded = GlyphRangeDecoder.decode(bytes, "0-255")

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
            GlyphRangeDecoder.decode(bytes, "0-255")
        }
    }

    @Test
    fun acceptsAServedStackWhateverNameTheProviderGivesIt() {
        // Stadia Maps resolves the requested alias rather than echoing it: a request for
        // "Stadia Regular" is answered by the thirteen fonts it expands to, none of them the
        // requested name. MapTiler echoes the request but reformats the separator. The field is
        // metadata about what the stack resolved to, not an echo, so nothing about it is checked.
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(
                    "Roboto Regular, Open Sans Regular, Noto Sans Arabic Regular, Noto Sans Regular",
                    "0-255",
                    listOf(Glyph(id = 65, width = 0, height = 0, left = 0, top = 0, advance = 12)),
                ),
            ),
        ).encode()

        val decoded = GlyphRangeDecoder.decode(bytes, "0-255")

        assertEquals(1, decoded.size)
        assertEquals(65, decoded.single().codepoint)
    }

    @Test
    fun rejectsAPayloadCoveringADifferentBlock() {
        // range is a genuine echo of the URL's {range} token, so a mismatch means either this
        // library's own block arithmetic is off or the provider served the wrong block. Either
        // way the glyphs decode perfectly - they are simply the wrong ones - so nothing
        // downstream would notice.
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack("Open Sans Regular", "256-511", listOf(
                    Glyph(id = 300, width = 0, height = 0, left = 0, top = 0, advance = 12),
                )),
            ),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "0-255")
        }
    }

    @Test
    fun rejectsAPayloadCarryingMoreThanOneStack() {
        // One request, one stack. Two means the payload is not the one this URL asked for, and
        // whichever were picked would be a guess.
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack("Open Sans Regular", "0-255", emptyList()),
                Glyphs.Fontstack("Noto Sans Regular", "0-255", emptyList()),
            ),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "0-255")
        }
    }
}
