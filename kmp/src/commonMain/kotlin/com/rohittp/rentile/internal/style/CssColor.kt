package com.rohittp.rentile.internal.style

import kotlin.math.floor
import kotlin.math.roundToInt

internal fun parseCssColor(value: String): CompiledColor? {
    val text = value.trim().lowercase()
    return when {
        text == "transparent" -> CompiledColor(0, 0, 0, 0)
        text == "black" -> CompiledColor(0, 0, 0, 255)
        text == "white" -> CompiledColor(255, 255, 255, 255)
        text.startsWith('#') -> parseHex(text)
        text.startsWith("rgb(") && text.endsWith(')') -> parseRgb(text, hasAlpha = false)
        text.startsWith("rgba(") && text.endsWith(')') -> parseRgb(text, hasAlpha = true)
        text.startsWith("hsl(") && text.endsWith(')') -> parseHsl(text, hasAlpha = false)
        text.startsWith("hsla(") && text.endsWith(')') -> parseHsl(text, hasAlpha = true)
        else -> null
    }
}

private fun parseHex(text: String): CompiledColor? {
    val digits = text.drop(1)
    if (digits.any { it.digitToIntOrNull(16) == null }) return null
    return when (digits.length) {
        3 -> CompiledColor(nibble(digits[0]), nibble(digits[1]), nibble(digits[2]), 255)
        4 -> CompiledColor(nibble(digits[0]), nibble(digits[1]), nibble(digits[2]), nibble(digits[3]))
        6 -> CompiledColor(byte(digits, 0), byte(digits, 2), byte(digits, 4), 255)
        8 -> CompiledColor(byte(digits, 0), byte(digits, 2), byte(digits, 4), byte(digits, 6))
        else -> null
    }
}

private fun nibble(value: Char): Int = value.digitToInt(16) * 17

private fun byte(value: String, offset: Int): Int = value.substring(offset, offset + 2).toInt(16)

private fun parseRgb(text: String, hasAlpha: Boolean): CompiledColor? {
    val values = arguments(text)
    if (values.size != if (hasAlpha) 4 else 3) return null
    val channels = values.take(3).map { channel ->
        if (channel.endsWith('%')) {
            channel.dropLast(1).toDoubleOrNull()?.let { (it * 2.55).roundToInt().coerceIn(0, 255) }
        } else {
            channel.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 255)
        }
    }
    if (channels.any { it == null }) return null
    val alpha = if (hasAlpha) parseAlpha(values[3]) ?: return null else 255
    return CompiledColor(channels[0]!!, channels[1]!!, channels[2]!!, alpha)
}

private fun parseHsl(text: String, hasAlpha: Boolean): CompiledColor? {
    val values = arguments(text)
    if (values.size != if (hasAlpha) 4 else 3) return null
    val hue = values[0].removeSuffix("deg").toDoubleOrNull() ?: return null
    val saturation = parsePercentage(values[1]) ?: return null
    val lightness = parsePercentage(values[2]) ?: return null
    val alpha = if (hasAlpha) parseAlpha(values[3]) ?: return null else 255

    val normalizedHue = ((hue % 360.0) + 360.0) % 360.0 / 360.0
    val red: Double
    val green: Double
    val blue: Double
    if (saturation == 0.0) {
        red = lightness
        green = lightness
        blue = lightness
    } else {
        val q = if (lightness < 0.5) lightness * (1.0 + saturation) else lightness + saturation - lightness * saturation
        val p = 2.0 * lightness - q
        red = hueToRgb(p, q, normalizedHue + 1.0 / 3.0)
        green = hueToRgb(p, q, normalizedHue)
        blue = hueToRgb(p, q, normalizedHue - 1.0 / 3.0)
    }
    return CompiledColor(toByte(red), toByte(green), toByte(blue), alpha)
}

private fun arguments(text: String): List<String> =
    text.substringAfter('(').dropLast(1).split(',').map(String::trim)

private fun parsePercentage(value: String): Double? {
    if (!value.endsWith('%')) return null
    return value.dropLast(1).toDoubleOrNull()?.div(100.0)?.coerceIn(0.0, 1.0)
}

private fun parseAlpha(value: String): Int? = if (value.endsWith('%')) {
    value.dropLast(1).toDoubleOrNull()?.let { (it * 2.55).roundToInt().coerceIn(0, 255) }
} else {
    value.toDoubleOrNull()?.let { (it * 255.0).roundToInt().coerceIn(0, 255) }
}

private fun hueToRgb(p: Double, q: Double, input: Double): Double {
    val hue = input - floor(input)
    return when {
        hue < 1.0 / 6.0 -> p + (q - p) * 6.0 * hue
        hue < 1.0 / 2.0 -> q
        hue < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - hue) * 6.0
        else -> p
    }
}

private fun toByte(value: Double): Int = (value * 255.0).roundToInt().coerceIn(0, 255)
