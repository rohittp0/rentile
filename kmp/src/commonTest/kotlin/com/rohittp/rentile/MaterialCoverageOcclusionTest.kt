package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Material Coverage is the selected material as it is *visible* in the finished tile (ADR 0042):
 * later unselected fill and line layers erase it in proportion to the alpha they draw, later
 * selected layers add it back, and nothing before the first selected layer, nor any background,
 * raster, hillshade, color-relief or icon layer, can hide it.
 *
 * The fixture is a large water square (64..448 px at 512), a land patch (128..256 px square), an
 * island (320..384 x 128..256 px) and a road with a right-angle bend at (256, 384) px.
 */
class MaterialCoverageOcclusionTest {
    @Test
    fun laterOpaqueUnselectedFillsAndLinesHideTheMaterialBeneathThem() = runTest {
        val mask = maskOf(WATER_LAYER, opaquePatch(), opaqueRoad())
        assertEquals(255, mask.at(OPEN_WATER))
        assertEquals(0, mask.at(PATCH_CENTRE))
        assertEquals(0, mask.at(ROAD_POINT))
        // The road's right-angle join sits under line-round-limit 2, so it is drawn through the
        // round-join replacement layer, which must composite as an erase too.
        assertEquals(0, mask.at(ROAD_BEND))
    }

    @Test
    fun anOpaqueEraseMatchesTheAlphaTheLayerDraws() = runTest {
        val mask = maskOf(WATER_LAYER, opaquePatch(fillTranslate = "[1.75,-2.5]"))
        // The same patch drawn alone, opaque white over nothing: its alpha is what it hides.
        val patchAlpha = withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(styleWith(opaquePatch(fillTranslate = "[1.75,-2.5]"))))
            rasterizer.prepareBatch(style, listOf(TileId(0, 0, 0)), RenderOptions(512)).use { batch ->
                rasterizer.renderRaw(batch).tiles.single().rgbaBytes
            }
        }
        var worst = 0
        for (y in 70 until 440) for (x in 70 until 440) {
            val visible = mask.coverage[y * 512 + x].toInt() and 0xff
            val hidden = patchAlpha[(y * 512 + x) * 4 + 3].toInt() and 0xff
            worst = maxOf(worst, abs(visible + hidden - 255))
        }
        assertTrue(worst <= 1, "erased coverage differs from the patch's drawn alpha by $worst")
    }

    @Test
    fun translucentLayersHideInProportionToTheirDrawnAlpha() = runTest {
        val byOpacity = maskOf(WATER_LAYER, opaquePatch(opacity = 0.25))
        assertClose(191, byOpacity.at(PATCH_CENTRE))
        val byColourAlpha = maskOf(WATER_LAYER, opaquePatch(color = "rgba(10,20,30,0.5)"))
        assertClose(128, byColourAlpha.at(PATCH_CENTRE))
        val byLineOpacity = maskOf(WATER_LAYER, opaqueRoad(opacity = 0.6))
        assertClose(102, byLineOpacity.at(ROAD_POINT))
    }

    @Test
    fun aLaterSelectedLayerAddsTheMaterialBack() = runTest {
        val mask = maskOf(
            WATER_LAYER,
            """{"id":"island","type":"fill","source":"v","source-layer":"land","filter":["==",["get","kind"],"island"],"paint":{"fill-color":"#80a060"}}""",
            """{"id":"lagoon","type":"fill","source":"v","source-layer":"land","filter":["==",["get","kind"],"island"],"metadata":{"ta:material":"water"},"paint":{"fill-color":"#3366ff","fill-opacity":0.2}}""",
        )
        assertEquals(255, mask.at(ISLAND_CENTRE))
    }

    @Test
    fun layersBeforeTheFirstSelectedLayerAndNonVectorLayersHideNothing() = runTest {
        val mask = maskOf(
            opaquePatch(),
            opaqueRoad(),
            WATER_LAYER,
            """{"id":"wash","type":"background","paint":{"background-color":"#000000"}}""",
        )
        assertEquals(255, mask.at(PATCH_CENTRE))
        assertEquals(255, mask.at(ROAD_POINT))
        assertEquals(255, mask.at(OPEN_WATER))
    }

    @Test
    fun aMaterialHiddenByTheSameGeometryKeepsOnlyItsAntialiasedFringe() = runTest {
        val rendered = withRasterizer { rasterizer ->
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    styleWith(
                        """{"id":"pond","type":"fill","source":"v","source-layer":"land","filter":["==",["get","kind"],"patch"],"metadata":{"ta:material":"water"}}""",
                        opaquePatch(),
                    ),
                ),
            )
            rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(512, materialCoverage = WATER))
                .tiles.single()
        }
        // An edge pixel the pond covers by a and the patch hides by a keeps a(1 - a), at most a
        // quarter - exactly the pond colour the colour tile still shows there. The interior is gone.
        // This square's edges fall on pixel boundaries, so nothing is left at all.
        assertEquals(MaterialCoverage.None, rendered.materialCoverage)
        // Translated by a fraction of a pixel, its edges are antialiased and a fringe remains.
        val fringe = maskOf(
            """{"id":"pond","type":"fill","source":"v","source-layer":"land","filter":["==",["get","kind"],"patch"],"metadata":{"ta:material":"water"},"paint":{"fill-translate":[0.5,0.25]}}""",
            opaquePatch(fillTranslate = "[0.5,0.25]"),
        )
        assertEquals(0, fringe.at(PATCH_CENTRE))
        assertTrue(fringe.coverage.any { it != 0.toByte() }, "an antialiased edge keeps a fringe")
        assertTrue(fringe.coverage.all { (it.toInt() and 0xff) <= 64 }, "hidden pond left more than its fringe")
    }

    private suspend fun maskOf(vararg layers: String): MaterialCoverage.Mask = withRasterizer { rasterizer ->
        val style = rasterizer.prepare(StyleInput.InlineJson(styleWith(*layers)))
        assertIs<MaterialCoverage.Mask>(
            rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(512, materialCoverage = WATER))
                .tiles.single().materialCoverage,
        )
    }

    private suspend fun <T> withRasterizer(block: suspend (BasemapRasterizer) -> T): T {
        val tile = fixtureTile()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { TransportResponse(200, tile) },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun MaterialCoverage.Mask.at(point: Pair<Int, Int>): Int =
        coverage[point.second * widthPx + point.first].toInt() and 0xff

    private fun assertClose(expected: Int, actual: Int) =
        assertTrue(abs(expected - actual) <= 1, "expected coverage $expected, got $actual")

    private fun fixtureTile(): ByteArray {
        val water = Tile.Feature(type = Tile.GeomType.POLYGON, geometry = ring(square(512, 512, 3584, 3584)))
        val patch = Tile.Feature(tags = listOf(0, 0), type = Tile.GeomType.POLYGON, geometry = ring(square(1024, 1024, 2048, 2048)))
        val island = Tile.Feature(tags = listOf(0, 1), type = Tile.GeomType.POLYGON, geometry = ring(square(2560, 1024, 3072, 2048)))
        val road = Tile.Feature(type = Tile.GeomType.LINESTRING, geometry = path(listOf(256 to 3072, 2048 to 3072, 2048 to 2304)))
        return Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(version = 2, name = "water", features = listOf(water), extent = EXTENT),
                    Tile.Layer(
                        version = 2,
                        name = "land",
                        features = listOf(patch, island),
                        keys = listOf("kind"),
                        values = listOf(Tile.Value(string_value = "patch"), Tile.Value(string_value = "island")),
                        extent = EXTENT,
                    ),
                    Tile.Layer(version = 2, name = "roads", features = listOf(road), extent = EXTENT),
                ),
            ),
        )
    }

    private fun square(left: Int, top: Int, right: Int, bottom: Int) =
        listOf(left to top, right to top, right to bottom, left to bottom)

    private fun ring(points: List<Pair<Int, Int>>): List<Int> = path(points) + command(7, 1)

    private fun path(points: List<Pair<Int, Int>>): List<Int> = buildList {
        var cursor = points.first()
        add(command(1, 1))
        add(zigZag(cursor.first))
        add(zigZag(cursor.second))
        add(command(2, points.size - 1))
        for (point in points.drop(1)) {
            add(zigZag(point.first - cursor.first))
            add(zigZag(point.second - cursor.second))
            cursor = point
        }
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private companion object {
        const val EXTENT = 4096
        val WATER = MaterialCoverageRequest("ta:material", "water")

        // Output pixels at 512.
        val OPEN_WATER = 400 to 300
        val PATCH_CENTRE = 192 to 192
        val ROAD_POINT = 150 to 384
        val ROAD_BEND = 256 to 384
        val ISLAND_CENTRE = 352 to 192

        const val WATER_LAYER =
            """{"id":"water","type":"fill","source":"v","source-layer":"water","metadata":{"ta:material":"water"},"paint":{"fill-color":"#3366ff","fill-opacity":0.5}}"""

        fun opaquePatch(opacity: Double = 1.0, color: String = "#ffffff", fillTranslate: String = "[0,0]"): String =
            """{"id":"patch","type":"fill","source":"v","source-layer":"land","filter":["==",["get","kind"],"patch"],""" +
                """"paint":{"fill-color":"$color","fill-opacity":$opacity,"fill-translate":$fillTranslate}}"""

        fun opaqueRoad(opacity: Double = 1.0): String =
            """{"id":"road","type":"line","source":"v","source-layer":"roads","layout":{"line-join":"round","line-cap":"round","line-round-limit":2},""" +
                """"paint":{"line-color":"#ffffff","line-opacity":$opacity,"line-width":10}}"""

        fun styleWith(vararg layers: String): String =
            """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://v.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},""" +
                """"layers":[${layers.joinToString(",")}]}"""
    }
}
