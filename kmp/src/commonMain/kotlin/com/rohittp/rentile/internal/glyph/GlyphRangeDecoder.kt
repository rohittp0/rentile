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

    fun decode(bytes: ByteArray, expectedFontStack: String): List<DecodedGlyph> {
        val message = Glyphs.ADAPTER.decode(bytes)
        val stack = message.stacks.singleOrNull()
        require(stack != null) { "A glyph range must contain exactly one font stack" }
        require(stack.name == expectedFontStack) { "A glyph range declared an unexpected font stack" }
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
