package com.rohittp.rentile.internal.glyph

/**
 * Whether text can be laid out by accumulating glyph advances alone.
 *
 * Rentile lays labels out from glyph metrics and never shapes through a platform
 * font stack (ADR 0025), so scripts requiring bidirectional reordering or
 * contextual joining cannot be rendered faithfully. Ranges below are listed by the
 * property that disqualifies them, not by language: a future addition should be
 * judged by whether it needs reordering or joining to read correctly, not by
 * where it comes from.
 */
internal object ScriptSupport {
    /**
     * Blocks that require bidirectional reordering (right-to-left scripts and their
     * presentation forms) or contextual/combining shaping (Brahmic and Southeast
     * Asian abugidas, where vowel signs and conjuncts reposition around the base
     * consonant). Advance-accumulation layout renders these illegibly.
     */
    private val UNSUPPORTED_RANGES: List<IntRange> = listOf(
        0x0590..0x05FF, // Hebrew: right-to-left
        0x0600..0x06FF, // Arabic: right-to-left, contextual joining
        0x0700..0x074F, // Syriac: right-to-left, contextual joining
        0x0750..0x077F, // Arabic Supplement: right-to-left, contextual joining
        0x0780..0x07BF, // Thaana: right-to-left
        0x07C0..0x08FF, // NKo, Samaritan, Mandaic, Arabic Extended-A: right-to-left / joining
        0x0900..0x0DFF, // Devanagari through Sinhala: Brahmic reordering and conjuncts
        0x0E00..0x0E7F, // Thai: combining vowel and tone-mark placement
        0x0E80..0x0EFF, // Lao: combining vowel and tone-mark placement
        0x0F00..0x0FFF, // Tibetan: stacking conjuncts
        0x1000..0x109F, // Myanmar: reordering vowel signs and stacked consonants
        0x1780..0x17FF, // Khmer: reordering vowel signs and stacked consonants
        0x1800..0x18AF, // Mongolian: contextual joining
        0xFB1D..0xFDFF, // Hebrew and Arabic presentation forms: right-to-left
        0xFE70..0xFEFF, // Arabic presentation forms-B: right-to-left, contextual joining
    )

    fun requiresComplexShaping(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAtCompat(index)
            if (UNSUPPORTED_RANGES.any { codepoint in it }) return true
            index += if (codepoint > 0xFFFF) 2 else 1
        }
        return false
    }

    fun isSupported(text: String): Boolean = !requiresComplexShaping(text)
}

/**
 * Returns the Unicode code point starting at [index], combining a UTF-16 surrogate
 * pair when present. `String.codePointAt` is JVM-only in Kotlin Multiplatform, so
 * this reimplements the surrogate check by hand.
 */
private fun String.codePointAtCompat(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
        }
    }
    return high.code
}
