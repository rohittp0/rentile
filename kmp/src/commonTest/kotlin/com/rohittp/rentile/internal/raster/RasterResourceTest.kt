package com.rohittp.rentile.internal.raster

import com.rohittp.rentile.TileId
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.internal.SecretContext
import com.rohittp.rentile.internal.style.CompiledRasterSource
import com.rohittp.rentile.internal.style.TileScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.SamplingMode

class RasterResourceTest {
    @Test
    fun demChildrenUseNearestSamplingSoEncodedHeightChannelsAreNotBlended() {
        assertEquals(SamplingMode.DEFAULT, rasterSubstitutionSampling(ResourceClass.DEM_TILE))
        assertEquals(SamplingMode.LINEAR, rasterSubstitutionSampling(ResourceClass.RASTER_TILE))
    }

    @Test
    fun childAndAncestorSamplesPreserveTheRequestedOutputWindow() {
        val context = SecretContext()
        try {
            val source = CompiledRasterSource(
                idDigest = "source",
                tileTemplates = listOf(context.protectUrl("https://example.test/{z}/{x}/{y}.png")),
                tileSize = 256,
                scheme = TileScheme.XYZ,
                minZoom = 0,
                maxZoom = 22,
            )
            val requested = source.sampleFor(TileId(z = 4, x = 13, y = 10))!!

            assertEquals(
                listOf(
                    Triple(5, 26, 20),
                    Triple(5, 27, 20),
                    Triple(5, 26, 21),
                    Triple(5, 27, 21),
                ),
                requested.immediateChildren().map { Triple(it.sourceZ, it.sourceX, it.sourceY) },
            )
            val ancestor = requested.ancestor(2)!!
            assertEquals(2, ancestor.sourceZ)
            assertEquals(3, ancestor.sourceX)
            assertEquals(2, ancestor.sourceY)
            assertEquals(4, ancestor.childScale)
            assertEquals(1, ancestor.childX)
            assertEquals(2, ancestor.childY)
            assertEquals(requested.outputTile, ancestor.outputTile)
        } finally {
            context.clear()
        }
    }
}
