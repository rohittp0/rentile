package com.rohittp.rentile.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class HillshadeHeightWindowTest {
    @Test
    fun neighbouringSamplesAreReadOnceAndKeepCrossTileCoordinates() {
        var reads = 0
        fun height(x: Int, y: Int): Double = x * 1000.0 + y / 10.0
        val sampler = cachedHillshadeHeights(intArrayOf(0, 1, 2), intArrayOf(7, 8), 3) { x, y ->
            reads++
            height(x, y)
        }
        assertEquals(5 * 4, reads)
        repeat(10) {
            for (y in 6..9) for (x in -1..3) assertEquals(height(x, y), sampler(x, y))
        }
        assertEquals(20, reads, "overlapping gradients must not locate the same neighbour repeatedly")
    }

    @Test
    fun aLargeDownsampledSourceDoesNotAllocateItsFullHeightField() {
        var reads = 0
        val sampler = cachedHillshadeHeights(intArrayOf(0, 1024), intArrayOf(0, 1024), 512) { x, y ->
            reads++
            x * 1000.0 + y
        }
        assertEquals(0, reads)
        assertEquals(1023002.0, sampler(1023, 2))
        assertEquals(1, reads)
    }
}
