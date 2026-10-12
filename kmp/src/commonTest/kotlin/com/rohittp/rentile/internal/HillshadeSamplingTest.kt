package com.rohittp.rentile.internal

import com.rohittp.rentile.internal.style.CompiledColor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HillshadeSamplingTest {
    @Test
    fun overzoomedPixelsReuseIdenticalSourceSamplesWithoutChangingOutput() {
        val xs = intArrayOf(-1, 0, 0, 0, 1, 1, 2, 2)
        val ys = intArrayOf(10, 10, 11, 11, 11, 12)
        var calls = 0
        fun color(x: Int, y: Int) = CompiledColor((x + 1) * 50, y * 10, 255 - y, 123)
        val actual = rasterizeHillshadeSamples(xs, ys) { x, y -> calls++; color(x, y) }
        val expected = ByteArray(xs.size * ys.size * 4)
        for (y in ys.indices) for (x in xs.indices) {
            val c = color(xs[x], ys[y])
            val offset = (y * xs.size + x) * 4
            expected[offset] = c.red.toByte()
            expected[offset + 1] = c.green.toByte()
            expected[offset + 2] = c.blue.toByte()
            expected[offset + 3] = c.alpha.toByte()
        }
        assertContentEquals(expected, actual)
        assertEquals(xs.distinct().size * ys.distinct().size, calls)
    }

    @Test
    fun nativeResolutionEvaluatesEveryDistinctPixel() {
        val coords = IntArray(4) { it }
        var calls = 0
        val actual = rasterizeHillshadeSamples(coords, coords) { x, y ->
            calls++
            CompiledColor(x, y, x + y, 255)
        }
        assertEquals(16, calls)
        assertEquals(4 * 4 * 4, actual.size)
    }
}
