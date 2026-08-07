package com.rohittp.rentile.internal.mvt

import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.SecretContext
import com.rohittp.rentile.internal.style.CompiledVectorSource
import com.rohittp.rentile.internal.style.TileScheme
import com.rohittp.rentile.internal.style.SourceBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VectorSourceTest {
    @Test
    fun z22OutputUsesCoveringZ15SourceAndScalesItsChildWindow() {
        val context = SecretContext()
        try {
            val source = CompiledVectorSource(
                idDigest = "source",
                tileTemplates = listOf(context.protectUrl("https://example.test/{z}/{x}/{y}.pbf")),
                scheme = TileScheme.XYZ,
                minZoom = 0,
                maxZoom = 15,
            )
            val output = TileId(z = 22, x = 1_234_919, y = 1_576_977)
            val sample = source.sampleFor(output)!!

            assertEquals(15, sample.sourceZ)
            assertEquals(9_647, sample.sourceX)
            assertEquals(12_320, sample.sourceY)
            assertEquals(128, sample.childScale)
            assertEquals(103, sample.childX)
            assertEquals(17, sample.childY)
            assertEquals("https://example.test/15/9647/12320.pbf", sample.tileUrl())

            assertEquals(
                OutputPixelCoordinate(0.0, 0.0),
                sample.sourceCoordinateToOutputPixels(VectorCoordinate(3296, 544), extent = 4096, outputSizePx = 512),
            )
            assertEquals(
                OutputPixelCoordinate(512.0, 512.0),
                sample.sourceCoordinateToOutputPixels(VectorCoordinate(3328, 576), extent = 4096, outputSizePx = 512),
            )
        } finally {
            context.clear()
        }
    }

    @Test
    fun wrapsOutputXAndFlipsTmsYAtSourceZoom() {
        val context = SecretContext()
        try {
            val source = CompiledVectorSource(
                idDigest = "source",
                tileTemplates = listOf(context.protectUrl("https://example.test/{z}/{x}/{y}/{-y}.pbf")),
                scheme = TileScheme.TMS,
                minZoom = 0,
                maxZoom = 2,
            )
            val sample = source.sampleFor(TileId(z = 2, x = -1, y = 1))!!

            assertEquals(3, sample.sourceX)
            assertEquals("https://example.test/2/3/2/2.pbf", sample.tileUrl())
        } finally {
            context.clear()
        }
    }

    @Test
    fun sourceBoundsSuppressTilesOutsideTheDeclaredCoverage() {
        val context = SecretContext()
        try {
            val source = CompiledVectorSource(
                idDigest = "source",
                tileTemplates = listOf(context.protectUrl("https://example.test/{z}/{x}/{y}.pbf")),
                scheme = TileScheme.XYZ,
                minZoom = 0,
                maxZoom = 15,
                bounds = SourceBounds(west = -74.3, south = 40.4, east = -73.6, north = 41.0),
            )

            assertNotNull(source.sampleFor(TileId(z = 2, x = 1, y = 1)))
            assertNull(source.sampleFor(TileId(z = 2, x = 0, y = 1)))
            assertNull(source.sampleFor(TileId(z = 2, x = 2, y = 1)))
        } finally {
            context.clear()
        }
    }
}
