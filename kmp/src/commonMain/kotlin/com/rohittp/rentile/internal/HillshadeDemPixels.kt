package com.rohittp.rentile.internal

import com.rohittp.rentile.internal.style.DemEncoding
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** A draw-owned bulk copy of the same straight colours Bitmap.getColor would return. */
internal class HillshadeDemPixels private constructor(
    val width: Int,
    val height: Int,
    private val rgba: ByteArray,
) {
    fun heightAt(x: Int, y: Int, encoding: DemEncoding): Double {
        val offset = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4
        val red = rgba[offset].toInt() and 0xff
        val green = rgba[offset + 1].toInt() and 0xff
        val blue = rgba[offset + 2].toInt() and 0xff
        return when (encoding) {
            DemEncoding.MAPBOX -> -10_000.0 + (red * 65_536 + green * 256 + blue) * 0.1
            DemEncoding.TERRARIUM -> red * 256.0 + green + blue / 256.0 - 32_768.0
        }
    }

    companion object {
        fun read(bitmap: Bitmap): HillshadeDemPixels? {
            val width = bitmap.width
            val height = bitmap.height
            // Explicit RGBA handles BGRA N32 platforms. Read premultiplied bytes and apply
            // Skia's integer getColor unpremultiplication: readPixels(UNPREMUL) uses a different
            // rounding path for low alpha, which changes packed elevations.
            // https://github.com/google/skia/blob/main/src/core/SkUnPreMultiply.cpp
            val rgba = bitmap.readPixels(
                ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                width * 4,
            ) ?: return null
            for (offset in rgba.indices step 4) {
                val alpha = rgba[offset + 3].toInt() and 0xff
                if (alpha == 255) continue
                val scale = if (alpha == 0) 0L else ((255L shl 24) + (alpha shr 1)) / alpha
                for (channel in 0..2) {
                    val value = rgba[offset + channel].toInt() and 0xff
                    rgba[offset + channel] = ((value * scale + (1L shl 23)) shr 24).toByte()
                }
            }
            return HillshadeDemPixels(width, height, rgba)
        }
    }
}
