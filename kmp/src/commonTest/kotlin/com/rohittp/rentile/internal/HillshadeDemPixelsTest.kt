package com.rohittp.rentile.internal

import com.rohittp.rentile.internal.style.DemEncoding
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class HillshadeDemPixelsTest {
    @Test
    fun bulkSamplesMatchNativeGetColorForBothEncodingsAndEveryAlpha() {
        // Unequal dimensions and all alpha values expose row stride, channel order and the
        // unpremultiplication rounding performed by the former native getter.
        val width = 17
        val height = 256
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = (y * width + x) * 4
            rgba[offset] = (x * 13 + y).toByte()
            rgba[offset + 1] = (200 - x * 5 + y).toByte()
            rgba[offset + 2] = (x * 7 - y).toByte()
            rgba[offset + 3] = y.toByte()
        }
        val image = Image.makeRaster(
            ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
            rgba, width * 4,
        )
        val bitmap = Bitmap()
        try {
            check(bitmap.allocN32Pixels(width, height, false))
            check(image.readPixels(bitmap))
            val pixels = checkNotNull(HillshadeDemPixels.read(bitmap))
            assertEquals(width, pixels.width)
            assertEquals(height, pixels.height)
            for (encoding in DemEncoding.entries) {
                for (y in -1..height) for (x in -1..width) {
                    val color = bitmap.getColor(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
                    val r = color ushr 16 and 255
                    val g = color ushr 8 and 255
                    val b = color and 255
                    val expected = when (encoding) {
                        DemEncoding.MAPBOX -> -10_000.0 + (r * 65_536 + g * 256 + b) * 0.1
                        DemEncoding.TERRARIUM -> r * 256.0 + g + b / 256.0 - 32_768.0
                    }
                    assertEquals(expected, pixels.heightAt(x, y, encoding), "$encoding at $x,$y")
                }
            }
        } finally {
            bitmap.close()
            image.close()
        }
    }
}
