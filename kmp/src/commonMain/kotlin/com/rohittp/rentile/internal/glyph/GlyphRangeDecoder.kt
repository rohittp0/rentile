package com.rohittp.rentile.internal.glyph

/** One signed-distance-field glyph at the 24-pixel em with a 3-pixel buffer. */
internal data class DecodedGlyph(
    val codepoint: Int,
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val advance: Int,
    val bitmap: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DecodedGlyph && codepoint == other.codepoint &&
            width == other.width && height == other.height && left == other.left &&
            top == other.top && advance == other.advance && bitmap.contentEquals(other.bitmap))

    override fun hashCode(): Int = codepoint
}

internal object GlyphRangeDecoder {
    /** Signed-distance-field glyphs carry a three-pixel buffer on every side. */
    const val BUFFER_PX: Int = 3

    /** Signed-distance-field glyphs are rendered at this em size; text-size scales from it. */
    const val EM_PX: Double = 24.0

    /**
     * [expectedRange] is the `{range}` token the URL was built with, e.g. `"0-255"`.
     *
     * The payload's own `range` is checked and its `name` is deliberately not. `range` is a
     * genuine echo of what was asked for, so disagreement means either a mistake in this library's
     * block arithmetic or a provider serving the wrong block - both real, both otherwise silent,
     * since a wrong block still decodes into perfectly valid glyphs at perfectly valid metrics.
     *
     * `name` cannot serve that purpose because providers do not agree on what it means. MapTiler
     * echoes the requested stack, reformatting the separator. Stadia Maps resolves the requested
     * alias: a request for `Stadia Regular` is answered by a stack naming the thirteen fonts it
     * expands to, none of them the requested name. The field is metadata describing what the stack
     * resolved to, not an echo, and no comparison can reconcile those two behaviours. Requiring
     * equality blocked this release twice.
     *
     * "Are these the glyphs we asked for" therefore rests on URL identity from here, exactly as it
     * does for sprites, vector tiles and DEM tiles - none of which is asked to name itself either.
     */
    fun decode(bytes: ByteArray, expectedRange: String): List<DecodedGlyph> {
        val message = Glyphs.ADAPTER.decode(bytes)
        val stack = message.stacks.singleOrNull()
        require(stack != null) { "A glyph range must contain exactly one font stack" }
        require(stack.range.trim() == expectedRange) { "A glyph range covers an unexpected block" }
        return stack.glyphs.map { glyph ->
            val width = glyph.width.toInt()
            val height = glyph.height.toInt()
            val bitmap = glyph.bitmap?.toByteArray() ?: ByteArray(0)
            if (bitmap.isNotEmpty()) {
                val expected = (width + BUFFER_PX * 2) * (height + BUFFER_PX * 2)
                require(bitmap.size == expected) { "A glyph bitmap does not match its declared extent" }
            }
            DecodedGlyph(
                codepoint = glyph.id.toInt(),
                width = width,
                height = height,
                left = glyph.left,
                top = glyph.top,
                advance = glyph.advance.toInt(),
                bitmap = bitmap,
            )
        }.sortedBy(DecodedGlyph::codepoint)
    }
}
