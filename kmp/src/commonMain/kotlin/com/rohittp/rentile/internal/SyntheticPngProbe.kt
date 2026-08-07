package com.rohittp.rentile.internal

import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

internal fun renderSyntheticPng(sizePx: Int = 32): ByteArray {
    require(sizePx > 0)
    val surface = Surface.makeRasterN32Premul(sizePx, sizePx)
    try {
        surface.canvas.clear(Color.makeARGB(255, 22, 76, 60))
        val image = surface.makeImageSnapshot()
        try {
            val encoded = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia could not encode the synthetic raster surface as PNG")
            try {
                return encoded.bytes
            } finally {
                encoded.close()
            }
        } finally {
            image.close()
        }
    } finally {
        surface.close()
    }
}
