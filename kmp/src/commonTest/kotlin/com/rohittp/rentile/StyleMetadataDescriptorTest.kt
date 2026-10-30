package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BasemapRasterizer.styleMetadataDescriptor] (ADR 0044): the style's root `metadata` and the
 * `metadata` of every layer drawn into the Output Tile, in style order, with exactly the Material
 * Coverage eligibility the coverage pass uses.
 */
class StyleMetadataDescriptorTest {
    @Test
    fun reportsRootAndDrawnLayerMetadataInDeclarationOrder() = runTest {
        withRasterizer { rasterizer ->
            val descriptor = rasterizer.styleMetadataDescriptor(rasterizer.prepare(StyleInput.InlineJson(MIXED_STYLE)))
            assertEquals("""{"ta:look":"glossy","z":{"b":1,"a":[true,null]}}""", descriptor.rootMetadataJson)
            assertEquals(
                listOf(
                    LayerMetadataDescriptor("bg", 0, "background", """{"ta:material":"water"}""", 0.0, 31.0, false),
                    LayerMetadataDescriptor("lakes", 2, "fill", """{"ta:material":"water","note":"lakes"}""", 0.0, 31.0, true),
                    LayerMetadataDescriptor("rivers", 3, "line", """{"ta:material":"water","kind":"river"}""", 2.0, 12.0, true),
                    LayerMetadataDescriptor("piers", 4, "fill-extrusion", """{"ta:material":"wood"}""", 0.0, 31.0, true),
                    LayerMetadataDescriptor("relief", 5, "color-relief", """{"ta:material":"water"}""", 0.0, 31.0, false),
                    LayerMetadataDescriptor("hills", 6, "hillshade", "\"shading\"", 0.0, 31.0, false),
                ),
                descriptor.layers,
            )
        }
    }

    @Test
    fun undrawnLayersAreNeverListed() = runTest {
        withRasterizer { rasterizer ->
            val listed = rasterizer.styleMetadataDescriptor(rasterizer.prepare(StyleInput.InlineJson(MIXED_STYLE)))
                .layers.map { it.id }
            // Without metadata, hidden, and a text-only Label layer handed to the host.
            for (absent in listOf("land", "hidden-sea", "names")) {
                assertTrue(absent !in listed, "$absent must not be listed")
            }
            // Host-owned extrusions are not drawn into the Output Tile, so they are not listed.
            val hostExtrusions = rasterizer.styleMetadataDescriptor(
                rasterizer.prepare(StyleInput.InlineJson(MIXED_STYLE), CompatibilityPolicy.RentileV1HostExtrusions),
            )
            assertTrue("piers" !in hostExtrusions.layers.map { it.id })
            assertTrue("lakes" in hostExtrusions.layers.map { it.id })
        }
    }

    @Test
    fun aStyleWithoutMetadataReportsNone() = runTest {
        withRasterizer { rasterizer ->
            val descriptor = rasterizer.styleMetadataDescriptor(
                rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"layers":[{"id":"bg","type":"background"}]}""")),
            )
            assertNull(descriptor.rootMetadataJson)
            assertEquals(emptyList(), descriptor.layers)
        }
    }

    @Test
    fun credentialsInMetadataAreRedactedAsInTheDigest() = runTest {
        withRasterizer { rasterizer ->
            val descriptor = rasterizer.styleMetadataDescriptor(
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"metadata":{"logo":"https://x.test/logo.png?key=secret&v=2"},"layers":[{"id":"bg","type":"background","metadata":{"src":"https://x.test/a?token=t0p"}}]}""",
                    ),
                ),
            )
            assertEquals("""{"logo":"https://x.test/logo.png?key=<redacted>&v=2"}""", descriptor.rootMetadataJson)
            assertEquals("""{"src":"https://x.test/a?token=<redacted>"}""", descriptor.layers.single().metadataJson)
        }
    }

    /**
     * A host predicts that coverage is possible for a request at zoom z exactly when an eligible
     * listed layer at that zoom declares the pair. With geometry present and nothing drawn over it,
     * the prediction and the rendered result agree at every zoom and for every request, so the
     * descriptor never reports a material that coverage would skip, and never misses one it draws.
     */
    @Test
    fun theDescriptorPredictsWhichLayersMaterialCoverageSelects() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(MIXED_STYLE))
            val descriptor = rasterizer.styleMetadataDescriptor(style)
            val requests = listOf(
                MaterialCoverageRequest("ta:material", "water"),
                MaterialCoverageRequest("ta:material", "wood"),
                MaterialCoverageRequest("note", "lakes"),
                // Only the river, which is drawn at z2..11 alone.
                MaterialCoverageRequest("kind", "river"),
                MaterialCoverageRequest("ta:material", "sand"),
            )
            for (z in listOf(0, 1, 2, 5, 12, 13)) {
                val tile = TileId(z, 0, 0)
                for (request in requests) {
                    val predicted = descriptor.layers.any { layer ->
                        layer.materialCoverageEligible &&
                            layer.minimumZoom <= z && z < layer.maximumZoom &&
                            Regex("\"${Regex.escape(request.metadataKey)}\":\"${Regex.escape(request.metadataValue)}\"")
                                .containsMatchIn(layer.metadataJson)
                    }
                    val rendered = rasterizer.render(style, listOf(tile), RenderOptions(256, materialCoverage = request))
                        .tiles.single().materialCoverage
                    assertEquals(predicted, rendered is MaterialCoverage.Mask, "z$z $request")
                }
            }
        }
    }

    @Test
    fun callingItChangesNoKeyOrPixel() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(MIXED_STYLE))
            val tile = TileId(2, 0, 0)
            val keyBefore = rasterizer.outputRequestKey(style, tile)
            val before = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()
            rasterizer.styleMetadataDescriptor(style)
            val after = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()
            assertEquals(keyBefore, rasterizer.outputRequestKey(style, tile))
            assertEquals(before.contentKey, after.contentKey)
            assertTrue(before.pngBytes.contentEquals(after.pngBytes))
            assertEquals(style.digest, rasterizer.prepare(StyleInput.InlineJson(MIXED_STYLE)).digest)
        }
    }

    @Test
    fun aStyleFromAnotherRasterizerIsRejected() = runTest {
        withRasterizer { first ->
            val style = first.prepare(StyleInput.InlineJson(MIXED_STYLE))
            withRasterizer { second ->
                assertFailsWith<ForeignPreparedStyleException> { second.styleMetadataDescriptor(style) }
            }
        }
    }

    private suspend fun withRasterizer(block: suspend (BasemapRasterizer) -> Unit) {
        val vector = vectorTile()
        val dem = com.rohittp.rentile.internal.renderSyntheticPng(64)
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    TransportResponse(200, if (request.resourceClass == ResourceClass.DEM_TILE) dem else vector)
                },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    /** A water polygon and a river near the world's north-west corner, so every z/0/0 tile has both. */
    private fun vectorTile(): ByteArray {
        val water = Tile.Feature(type = Tile.GeomType.POLYGON, geometry = ring(listOf(0 to 0, 64 to 0, 64 to 64, 0 to 64)))
        val river = Tile.Feature(type = Tile.GeomType.LINESTRING, geometry = path(listOf(0 to 2, 60 to 2)))
        val pier = Tile.Feature(type = Tile.GeomType.POLYGON, geometry = ring(listOf(0 to 0, 48 to 0, 48 to 48, 0 to 48)))
        return Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(version = 2, name = "water", features = listOf(water), extent = 4096),
                    Tile.Layer(version = 2, name = "rivers", features = listOf(river), extent = 4096),
                    Tile.Layer(version = 2, name = "piers", features = listOf(pier), extent = 4096),
                ),
            ),
        )
    }

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
        // Layer order is deliberate: the river (line) is drawn after the lake, the pier fill after
        // both, so in the coverage test nothing unselected is drawn over a selected layer's sample
        // pixels except where the request itself selects the later layer.
        const val MIXED_STYLE =
            """{"version":8,"metadata":{"ta:look":"glossy","z":{"b":1,"a":[true,null]}},""" +
                """"sources":{"v":{"type":"vector","tiles":["https://v.test/{z}/{x}/{y}.pbf"],"maxzoom":0},""" +
                """"dem":{"type":"raster-dem","tiles":["https://dem.test/{z}/{x}/{y}.png"],"tileSize":64,"maxzoom":0}},""" +
                """"layers":[""" +
                """{"id":"bg","type":"background","metadata":{"ta:material":"water"},"paint":{"background-color":"#ffffff"}},""" +
                """{"id":"land","type":"fill","source":"v","source-layer":"water","paint":{"fill-color":"#00ff00","fill-opacity":0}},""" +
                """{"id":"lakes","type":"fill","source":"v","source-layer":"water","metadata":{"ta:material":"water","note":"lakes"}},""" +
                """{"id":"rivers","type":"line","source":"v","source-layer":"rivers","minzoom":2,"maxzoom":12,"metadata":{"ta:material":"water","kind":"river"},"paint":{"line-width":2,"line-opacity":0}},""" +
                """{"id":"piers","type":"fill-extrusion","source":"v","source-layer":"piers","metadata":{"ta:material":"wood"},"paint":{"fill-extrusion-opacity":0}},""" +
                """{"id":"relief","type":"color-relief","source":"dem","metadata":{"ta:material":"water"},"paint":{"color-relief-opacity":0,"color-relief-color":["interpolate",["linear"],["elevation"],0,"#000000"]}},""" +
                """{"id":"hills","type":"hillshade","source":"dem","metadata":"shading"},""" +
                """{"id":"hidden-sea","type":"fill","source":"v","source-layer":"water","layout":{"visibility":"none"},"metadata":{"ta:material":"sand"}},""" +
                """{"id":"names","type":"symbol","source":"v","source-layer":"water","layout":{"text-field":"Sea"},"metadata":{"ta:material":"sand"}}""" +
                """]}"""
    }
}
