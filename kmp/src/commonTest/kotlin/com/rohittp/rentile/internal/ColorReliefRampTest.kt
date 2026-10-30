package com.rohittp.rentile.internal

import com.rohittp.rentile.internal.style.CompiledColor
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorReliefRampTest {
    private val stops = doubleArrayOf(-100.0, 0.0, 1000.0)
    private val colors = listOf(
        CompiledColor(0, 0, 255, 255),
        CompiledColor(0, 255, 0, 255),
        CompiledColor(255, 255, 255, 0),
    )

    @Test
    fun clampsToTheEndStopsBeyondTheRamp() {
        assertEquals(listOf(0, 0, 255, 255), pixel(-11_000.0))
        assertEquals(listOf(0, 0, 255, 255), pixel(-100.0))
        assertEquals(listOf(255, 255, 255, 0), pixel(1000.0))
        assertEquals(listOf(255, 255, 255, 0), pixel(8_849.0))
    }

    @Test
    fun interpolatesStraightRgbaBetweenTheTwoSurroundingStops() {
        assertEquals(listOf(0, 128, 128, 255), pixel(-50.0))
        assertEquals(listOf(0, 255, 0, 255), pixel(0.0))
        assertEquals(listOf(64, 255, 64, 191), pixel(250.0))
    }

    @Test
    fun opacityScalesOnlyAlpha() {
        assertEquals(listOf(0, 255, 0, 64), pixel(0.0, opacity = 0.25))
    }

    @Test
    fun aSingleStopIsOneConstantColour() {
        val rgba = ByteArray(4)
        writeColorReliefPixel(rgba, 0, 5_000.0, doubleArrayOf(0.0), listOf(CompiledColor(10, 20, 30, 40)), 1.0)
        assertEquals(listOf(10, 20, 30, 40), rgba.map { it.toInt() and 0xff })
    }

    private fun pixel(elevation: Double, opacity: Double = 1.0): List<Int> {
        val rgba = ByteArray(8)
        writeColorReliefPixel(rgba, 4, elevation, stops, colors, opacity)
        return rgba.drop(4).map { it.toInt() and 0xff }
    }
}
