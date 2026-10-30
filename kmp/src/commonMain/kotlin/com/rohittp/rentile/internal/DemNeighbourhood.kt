package com.rohittp.rentile.internal

import com.rohittp.rentile.internal.raster.RasterSample
import com.rohittp.rentile.internal.style.CompiledColor
import com.rohittp.rentile.internal.style.DemEncoding
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The decoded DEM tiles around one centre sample, read as heights in source pixels relative to the
 * centre tile's top-left. Coordinates past an edge read the neighbour that holds them, wrapping
 * across the antimeridian; a missing neighbour falls back to the centre tile exactly as hillshade
 * always has. Shared by hillshade and color-relief so both read one elevation model.
 */
internal class DemNeighbourhood(
    private val center: RasterSample,
    private val centerPixels: HillshadeDemPixels,
    private val decoded: Map<Pair<Int, Int>, HillshadeDemPixels>,
    private val encoding: DemEncoding,
) {
    val width: Int get() = centerPixels.width
    val height: Int get() = centerPixels.height
    private val sourceDimension = 1L shl center.sourceZ

    fun heightAt(sourcePixelX: Int, sourcePixelY: Int): Double {
        val deltaX = floor(sourcePixelX.toDouble() / width).toInt()
        val deltaY = floor(sourcePixelY.toDouble() / height).toInt()
        val sourceX = floorMod(center.sourceX.toLong() + deltaX, sourceDimension).toInt()
        val sourceY = center.sourceY + deltaY
        val bitmap = decoded[sourceX to sourceY] ?: centerPixels
        val localX = floorMod(sourcePixelX.toLong(), width.toLong()).toInt()
        val localY = sourcePixelY.coerceIn(0, height - 1)
            .let { value ->
                if (bitmap === centerPixels && sourceY != center.sourceY) {
                    value
                } else {
                    floorMod(sourcePixelY.toLong(), height.toLong()).toInt()
                }
            }
        return bitmap.heightAt(localX, localY, encoding)
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val remainder = value % divisor
        return if (remainder < 0) remainder + divisor else remainder
    }
}

/**
 * Writes one straight-alpha RGBA color-relief pixel at [offset]: [elevation] placed on the ramp of
 * strictly ascending [stops], clamped to the first and last colour beyond them and linearly
 * interpolated in straight RGBA between the two stops around it, then its alpha scaled by
 * [opacity]. This is the colour-ramp texture MapLibre GL samples for `color-relief` (ADR 0043).
 */
internal fun writeColorReliefPixel(
    rgba: ByteArray,
    offset: Int,
    elevation: Double,
    stops: DoubleArray,
    colors: List<CompiledColor>,
    opacity: Double,
) {
    val last = stops.size - 1
    val red: Double
    val green: Double
    val blue: Double
    val alpha: Double
    if (elevation <= stops[0] || last == 0) {
        val color = colors[0]
        red = color.red.toDouble(); green = color.green.toDouble()
        blue = color.blue.toDouble(); alpha = color.alpha.toDouble()
    } else if (elevation >= stops[last]) {
        val color = colors[last]
        red = color.red.toDouble(); green = color.green.toDouble()
        blue = color.blue.toDouble(); alpha = color.alpha.toDouble()
    } else {
        // The largest stop at or below the elevation; the ends are handled above.
        var low = 0
        var high = last
        while (high - low > 1) {
            val middle = (low + high) ushr 1
            if (elevation < stops[middle]) high = middle else low = middle
        }
        val t = (elevation - stops[low]) / (stops[high] - stops[low])
        val from = colors[low]
        val to = colors[high]
        red = from.red + (to.red - from.red) * t
        green = from.green + (to.green - from.green) * t
        blue = from.blue + (to.blue - from.blue) * t
        alpha = from.alpha + (to.alpha - from.alpha) * t
    }
    rgba[offset] = red.roundToInt().coerceIn(0, 255).toByte()
    rgba[offset + 1] = green.roundToInt().coerceIn(0, 255).toByte()
    rgba[offset + 2] = blue.roundToInt().coerceIn(0, 255).toByte()
    rgba[offset + 3] = (alpha * opacity).roundToInt().coerceIn(0, 255).toByte()
}
