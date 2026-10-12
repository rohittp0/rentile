package com.rohittp.rentile.internal

import com.rohittp.rentile.internal.style.CompiledColor

internal fun interface HillshadePixelSampler {
    fun shade(x: Int, y: Int): CompiledColor
}

/** Nearest DEM sampling repeats pixels when overzooming; shade each distinct sample once. */
internal fun rasterizeHillshadeSamples(
    xs: IntArray,
    ys: IntArray,
    shade: HillshadePixelSampler,
): ByteArray {
    val rowBytes = xs.size * 4
    val rgba = ByteArray(rowBytes * ys.size)
    for (y in ys.indices) {
        val row = y * rowBytes
        if (y > 0 && ys[y] == ys[y - 1]) {
            rgba.copyInto(rgba, row, row - rowBytes, row)
            continue
        }
        for (x in xs.indices) {
            val offset = row + x * 4
            if (x > 0 && xs[x] == xs[x - 1]) {
                for (channel in 0..3) rgba[offset + channel] = rgba[offset - 4 + channel]
                continue
            }
            val color = shade.shade(xs[x], ys[y])
            rgba[offset] = color.red.toByte()
            rgba[offset + 1] = color.green.toByte()
            rgba[offset + 2] = color.blue.toByte()
            rgba[offset + 3] = color.alpha.toByte()
        }
    }
    return rgba
}
