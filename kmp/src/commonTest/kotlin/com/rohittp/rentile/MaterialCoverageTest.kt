package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Material Coverage (ADR 0042): an 8-bit mask of the fill and line layers whose `metadata` maps a
 * requested key to a requested value, aligned with the Output Tile pixel for pixel.
 *
 * The alignment tests are the strong ones. A style that draws only the selected layer in opaque
 * white over nothing has, in its straight-alpha raw pixels, an alpha channel that *is* that layer's
 * antialiased coverage; the mask must match it, to the blitters' rounding, at every supported size.
 */
class MaterialCoverageTest {
    @Test
    fun aRequestReportsTheCoverageOfMatchingLayersOnly() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE))
            val tile = TileId(0, 0, 0)
            val rendered = rasterizer.render(style, listOf(tile), RenderOptions(512, materialCoverage = WATER))
                .tiles.single()
            val mask = assertIs<MaterialCoverage.Mask>(rendered.materialCoverage)
            assertEquals(512, mask.widthPx)
            assertEquals(512, mask.heightPx)
            // Inside the lake triangle: covered although the lake is painted at 40% opacity.
            assertEquals(255, mask.at(LAKE_INTERIOR))
            // Inside the pool square: same source layer, but the pool layer carries no metadata.
            assertEquals(0, mask.at(POOL_INTERIOR))
            // Far from every feature.
            assertEquals(0, mask.at(EMPTY_CORNER))
            // On the river's centreline, outside the lake.
            assertEquals(255, mask.at(RIVER_POINT))
            // Antialiased edges: some pixels are partially covered.
            assertTrue(mask.coverage.any { it.toInt() and 0xff in 1..254 }, "expected antialiased coverage edges")
        }
    }

    @Test
    fun coverageMatchesTheAlphaOfTheSameLayerDrawnOpaqueAtEverySize() = runTest {
        for ((name, layer) in ALIGNMENT_LAYERS) {
            for (size in RenderOptions.SUPPORTED_OUTPUT_SIZES.sorted()) {
                withRasterizer { rasterizer ->
                    val tile = TileId(0, 0, 0)
                    val tagged = rasterizer.prepare(StyleInput.InlineJson(styleWith(layer(true, "#3366ff", 0.35))))
                    val mask = assertIs<MaterialCoverage.Mask>(
                        rasterizer.render(tagged, listOf(tile), RenderOptions(size, materialCoverage = WATER))
                            .tiles.single().materialCoverage,
                        "$name at $size",
                    )
                    val opaque = rasterizer.prepare(StyleInput.InlineJson(styleWith(layer(false, "#ffffff", 1.0))))
                    val reference = rasterizer.prepareBatch(opaque, listOf(tile), RenderOptions(size)).use { batch ->
                        rasterizer.renderRaw(batch).tiles.single()
                    }
                    assertEquals(size * size, mask.coverage.size)
                    val covered = mask.coverage.count { it != 0.toByte() }
                    assertTrue(covered > size, "$name at $size covers only $covered pixels")
                    // The mask is drawn by Skia's 8-bit alpha blitter and the reference by its
                    // premultiplied N32 one; they round the same coverage independently, by at
                    // most one step per draw (the outlined fill draws twice). Any misalignment -
                    // a half-pixel shift, a missing translate or offset - moves edge pixels by
                    // tens of steps, which is what this bound rules out.
                    val tolerance = if (name == "fill") 2 else 1
                    val worst = (0 until size * size).maxOf { pixel ->
                        abs((mask.coverage[pixel].toInt() and 0xff) - (reference.rgbaBytes[pixel * 4 + 3].toInt() and 0xff))
                    }
                    assertTrue(worst <= tolerance, "$name at $size: coverage differs from drawn alpha by $worst")
                }
            }
        }
    }

    @Test
    fun pngAndRawRendersCarryTheSameCoverage() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE))
            val tile = TileId(0, 0, 0)
            rasterizer.prepareBatch(style, listOf(tile), RenderOptions(1024, materialCoverage = WATER)).use { batch ->
                val png = rasterizer.render(batch).tiles.single()
                val raw = rasterizer.renderRaw(batch).tiles.single()
                assertEquals(png.contentKey, raw.contentKey)
                assertIs<MaterialCoverage.Mask>(png.materialCoverage)
                assertEquals(png.materialCoverage, raw.materialCoverage)
            }
        }
    }

    @Test
    fun aRequestLeavesTheColourTileUnchanged() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE))
            val tile = TileId(0, 0, 0)
            val plain = rasterizer.render(style, listOf(tile), RenderOptions(512)).tiles.single()
            val withCoverage = rasterizer.render(style, listOf(tile), RenderOptions(512, materialCoverage = WATER))
                .tiles.single()
            assertNull(plain.materialCoverage)
            assertTrue(plain.pngBytes.contentEquals(withCoverage.pngBytes))
            rasterizer.prepareBatch(style, listOf(tile), RenderOptions(512)).use { batch ->
                assertNull(rasterizer.renderRaw(batch).tiles.single().materialCoverage)
            }
        }
    }

    @Test
    fun nothingMatchingReportsNone() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE))
            val tile = TileId(0, 0, 0)
            for (request in listOf(
                MaterialCoverageRequest("ta:material", "sand"),
                MaterialCoverageRequest("other:key", "water"),
            )) {
                val rendered = rasterizer.render(style, listOf(tile), RenderOptions(512, materialCoverage = request))
                assertSame(MaterialCoverage.None, rendered.tiles.single().materialCoverage, request.toString())
            }
        }
    }

    @Test
    fun visibilityZoomRangeFilterAndMetadataTypeAreRespected() = runTest {
        val cases = mapOf(
            "hidden" to """{"id":"lakes","type":"fill","source":"v","source-layer":"water","layout":{"visibility":"none"},"metadata":{"ta:material":"water"}}""",
            "below its minzoom" to """{"id":"lakes","type":"fill","source":"v","source-layer":"water","minzoom":3,"metadata":{"ta:material":"water"}}""",
            "filtered out" to """{"id":"lakes","type":"fill","source":"v","source-layer":"water","filter":["==",["get","kind"],"sea"],"metadata":{"ta:material":"water"}}""",
            "non-string metadata" to """{"id":"lakes","type":"fill","source":"v","source-layer":"water","metadata":{"ta:material":true}}""",
            "a background layer" to """{"id":"bg","type":"background","metadata":{"ta:material":"water"},"paint":{"background-color":"#0000ff"}}""",
        )
        withRasterizer { rasterizer ->
            for ((name, layer) in cases) {
                val style = rasterizer.prepare(StyleInput.InlineJson(styleWith(layer)))
                val rendered = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256, materialCoverage = WATER))
                assertSame(MaterialCoverage.None, rendered.tiles.single().materialCoverage, name)
            }
        }
    }

    @Test
    fun theFilteredFeatureAloneIsCovered() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    styleWith(
                        """{"id":"pools","type":"fill","source":"v","source-layer":"water","filter":["==",["get","kind"],"pool"],"metadata":{"ta:material":"water"}}""",
                    ),
                ),
            )
            val mask = assertIs<MaterialCoverage.Mask>(
                rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(512, materialCoverage = WATER))
                    .tiles.single().materialCoverage,
            )
            assertEquals(255, mask.at(POOL_INTERIOR))
            assertEquals(0, mask.at(LAKE_INTERIOR))
        }
    }

    @Test
    fun aRequestJoinsBothOutputKeysAndNoRequestLeavesThemUnchanged() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE))
            val tile = TileId(0, 0, 0)
            val legacyRequestKey = "rentile-output-request-4\n${style.digest}\n0/0/0\n512".encodeUtf8().sha256().hex()
            val coverageRequestKey = (
                "rentile-output-request-4\n${style.digest}\n0/0/0\n512" +
                    "\nmaterial-coverage:[\"ta:material\", \"water\"]"
                ).encodeUtf8().sha256().hex()
            assertEquals(legacyRequestKey, rasterizer.outputRequestKey(style, tile, RenderOptions(512)))
            assertEquals(
                coverageRequestKey,
                rasterizer.outputRequestKey(style, tile, RenderOptions(512, materialCoverage = WATER)),
            )
            assertNotEquals(
                rasterizer.outputRequestKey(style, tile, RenderOptions(512, materialCoverage = WATER)),
                rasterizer.outputRequestKey(
                    style,
                    tile,
                    RenderOptions(512, materialCoverage = MaterialCoverageRequest("ta:material", "sand")),
                ),
            )
            // The pair is encoded as JSON, so moving a delimiter between key and value is a new key.
            assertNotEquals(
                rasterizer.outputRequestKey(
                    style,
                    tile,
                    RenderOptions(512, materialCoverage = MaterialCoverageRequest("a\", \"b", "c")),
                ),
                rasterizer.outputRequestKey(
                    style,
                    tile,
                    RenderOptions(512, materialCoverage = MaterialCoverageRequest("a", "b\", \"c")),
                ),
            )

            val plain = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(512)).use { it.contentKeys.getValue(tile) }
            val water = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(512, materialCoverage = WATER))
                .use { it.contentKeys.getValue(tile) }
            val waterAgain = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(512, materialCoverage = WATER))
                .use { it.contentKeys.getValue(tile) }
            assertNotEquals(plain, water)
            assertEquals(water, waterAgain)
        }
    }

    @Test
    fun aPassThroughRasterTileReportsNoCoverage() = runTest {
        val png = com.rohittp.rentile.internal.renderSyntheticPng(256)
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { TransportResponse(200, png) },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"r":{"type":"raster","tiles":["https://r.test/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"r","type":"raster","source":"r","metadata":{"ta:material":"water"}}]}""",
                ),
            )
            val rendered = rasterizer.render(
                style,
                listOf(TileId(0, 0, 0)),
                RenderOptions(256, materialCoverage = WATER),
            ).tiles.single()
            assertTrue(rendered.diagnostics.any { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
            assertSame(MaterialCoverage.None, rendered.materialCoverage)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anOverzoomedTileCoversTheOverzoomedGeometry() = runTest {
        val tile = sceneTile()
        val requested = mutableListOf<String>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request -> requested += request.url; TransportResponse(200, tile) },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            // Source maxzoom 0, output z2: the z0 tile's geometry is cut and scaled by four.
            val output = TileId(2, 1, 1)
            val layer = ALIGNMENT_LAYERS.getValue("plain fill")
            val tagged = rasterizer.prepare(StyleInput.InlineJson(overzoomStyleWith(layer(true, "#3366ff", 0.35))))
            val mask = assertIs<MaterialCoverage.Mask>(
                rasterizer.render(tagged, listOf(output), RenderOptions(512, materialCoverage = WATER))
                    .tiles.single().materialCoverage,
            )
            val opaque = rasterizer.prepare(StyleInput.InlineJson(overzoomStyleWith(layer(false, "#ffffff", 1.0))))
            val reference = rasterizer.prepareBatch(opaque, listOf(output), RenderOptions(512)).use { batch ->
                rasterizer.renderRaw(batch).tiles.single().rgbaBytes
            }
            // The lake interior at extent (1638, 1434) is at (0.6, 0.4) of tile 2/1/1, translated.
            assertEquals(255, mask.at(0.6 to 0.4))
            val worst = (0 until 512 * 512).maxOf { pixel ->
                abs((mask.coverage[pixel].toInt() and 0xff) - (reference[pixel * 4 + 3].toInt() and 0xff))
            }
            assertTrue(worst <= 1, "overzoomed coverage differs from drawn alpha by $worst")
            assertEquals(setOf("https://v.test/0/0/0.pbf"), requested.toSet())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anEmptyOrOutOfBoundsVectorTileReportsNoCoverageAndAMissingOneFailsAsWithoutARequest() = runTest {
        var status = 204
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { requests++; TransportResponse(status, ByteArray(0)) },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            val tile = TileId(0, 0, 0)
            // 204 No Content is an empty vector tile: the colour tile renders, nothing is covered.
            val empty = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE))
            val rendered = rasterizer.render(empty, listOf(tile), RenderOptions(256, materialCoverage = WATER)).tiles.single()
            assertTrue(rendered.pngBytes.isNotEmpty())
            assertSame(MaterialCoverage.None, rendered.materialCoverage)

            // A source whose bounds exclude the tile is never fetched and covers nothing.
            val before = requests
            val bounded = rasterizer.prepare(
                StyleInput.InlineJson(SCENE_STYLE.replace("\"maxzoom\":14", "\"maxzoom\":14,\"bounds\":[10,10,11,11]")),
            )
            val far = TileId(4, 0, 0)
            val outside = rasterizer.render(bounded, listOf(far), RenderOptions(256, materialCoverage = WATER)).tiles.single()
            assertSame(MaterialCoverage.None, outside.materialCoverage)
            assertEquals(before, requests)

            // A missing tile is a typed acquisition failure, exactly as for the colour tile alone:
            // a mask must never report "no water" for data it could not read.
            status = 404
            val missing = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE.replace("https://v.test/", "https://v.test/missing/")))
            for (options in listOf(RenderOptions(256), RenderOptions(256, materialCoverage = WATER))) {
                val failure = assertFailsWith<ResourceAcquisitionException> {
                    rasterizer.render(missing, listOf(tile), options)
                }
                assertEquals(404, failure.statusCode)
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun theOutputSurfaceCeilingCountsTheCoverageMask() = runTest {
        val tile = sceneTile()
        // Room for a 512 px RGBA surface (1 MiB) but not for it plus a one-byte-per-pixel mask.
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { TransportResponse(200, tile) },
                rawResourceStore = InMemoryRawResourceStore(),
                resourceLimits = ResourceLimits(maxDecodedRasterBytes = 512L * 512L * 4L + 1024L),
            ),
        )
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(SCENE_STYLE))
            val output = TileId(0, 0, 0)
            assertNull(rasterizer.render(style, listOf(output), RenderOptions(512)).tiles.single().materialCoverage)
            val failure = assertFailsWith<SafetyLimitException> {
                rasterizer.render(style, listOf(output), RenderOptions(512, materialCoverage = WATER))
            }
            assertEquals(512L * 512L * 5L, failure.observed)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun requestAndMaskValidateTheirShape() {
        assertFailsWith<IllegalArgumentException> { MaterialCoverageRequest("", "water") }
        assertFailsWith<IllegalArgumentException> { MaterialCoverage.Mask(2, 2, ByteArray(3)) }
        assertFailsWith<IllegalArgumentException> { MaterialCoverage.Mask(0, 0, ByteArray(0)) }
        assertEquals(
            MaterialCoverage.Mask(1, 2, byteArrayOf(1, 2)),
            MaterialCoverage.Mask(1, 2, byteArrayOf(1, 2)),
        )
        assertNotEquals(
            MaterialCoverage.Mask(1, 2, byteArrayOf(1, 2)),
            MaterialCoverage.Mask(2, 1, byteArrayOf(1, 2)),
        )
        assertEquals("MaterialCoverage.Mask(widthPx=1, heightPx=2)", MaterialCoverage.Mask(1, 2, ByteArray(2)).toString())
    }

    private suspend fun withRasterizer(block: suspend (BasemapRasterizer) -> Unit) {
        val tile = sceneTile()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { TransportResponse(200, tile) },
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

    private fun MaterialCoverage.Mask.at(point: Pair<Double, Double>): Int {
        val x = (point.first * widthPx).toInt()
        val y = (point.second * heightPx).toInt()
        return coverage[y * widthPx + x].toInt() and 0xff
    }

    private fun sceneTile(): ByteArray {
        val lake = Tile.Feature(
            tags = listOf(0, 0),
            type = Tile.GeomType.POLYGON,
            geometry = ring(listOf(500 to 500, 3000 to 800, 1200 to 3200)),
        )
        val pool = Tile.Feature(
            tags = listOf(0, 1),
            type = Tile.GeomType.POLYGON,
            geometry = ring(listOf(3400 to 3400, 3800 to 3400, 3800 to 3800, 3400 to 3800)),
        )
        val river = Tile.Feature(
            type = Tile.GeomType.LINESTRING,
            geometry = line(listOf(100 to 3700, 1700 to 3300, 2300 to 3900)),
        )
        return Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = "water",
                        features = listOf(lake, pool),
                        keys = listOf("kind"),
                        values = listOf(Tile.Value(string_value = "lake"), Tile.Value(string_value = "pool")),
                        extent = EXTENT,
                    ),
                    Tile.Layer(version = 2, name = "rivers", features = listOf(river), extent = EXTENT),
                ),
            ),
        )
    }

    private fun ring(points: List<Pair<Int, Int>>): List<Int> = path(points) + command(7, 1)

    private fun line(points: List<Pair<Int, Int>>): List<Int> = path(points)

    private fun path(points: List<Pair<Int, Int>>): List<Int> = buildList {
        var cursor = 0 to 0
        add(command(1, 1))
        add(zigZag(points.first().first))
        add(zigZag(points.first().second))
        cursor = points.first()
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

        // Fractions of the tile, chosen well inside or well outside the fixture's features.
        val LAKE_INTERIOR = 0.4 to 0.35
        val POOL_INTERIOR = fraction(3600) to fraction(3600)
        val EMPTY_CORNER = 0.97 to 0.03
        val RIVER_POINT = fraction(900) to fraction(3500)

        private fun fraction(coordinate: Int): Double = (coordinate + 0.5) / EXTENT

        fun overzoomStyleWith(vararg layers: String): String =
            styleWith(*layers).replace("\"maxzoom\":14", "\"maxzoom\":0")

        fun styleWith(vararg layers: String): String =
            """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://v.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},""" +
                """"layers":[${layers.joinToString(",")}]}"""

        val SCENE_STYLE = styleWith(
            """{"id":"bg","type":"background","paint":{"background-color":"#e0e0d0"}}""",
            """{"id":"lakes","type":"fill","source":"v","source-layer":"water","filter":["==",["get","kind"],"lake"],"metadata":{"ta:material":"water","other":1},"paint":{"fill-color":"#3366ff","fill-opacity":0.4}}""",
            """{"id":"pools","type":"fill","source":"v","source-layer":"water","filter":["==",["get","kind"],"pool"],"paint":{"fill-color":"#00ffff"}}""",
            """{"id":"rivers","type":"line","source":"v","source-layer":"rivers","metadata":{"ta:material":"water"},"layout":{"line-join":"round","line-cap":"round"},"paint":{"line-color":"#3366ff","line-width":6}}""",
        )

        /**
         * One selected layer per entry, built either tagged and translucent (the coverage request)
         * or untagged, white and opaque (the alpha reference). Each exercises different geometry:
         * fill antialiasing, translate and outline; line width, gap, offset, dash, blur, caps and
         * round-join replacement.
         */
        val ALIGNMENT_LAYERS: Map<String, (Boolean, String, Double) -> String> = mapOf(
            "plain fill" to { tagged, color, opacity ->
                """{"id":"lakes","type":"fill","source":"v","source-layer":"water","filter":["==",["get","kind"],"lake"],""" +
                    metadata(tagged) +
                    """"paint":{"fill-color":"$color","fill-opacity":$opacity,"fill-translate":[3.25,-1.5]}}"""
            },
            "fill" to { tagged, color, opacity ->
                """{"id":"lakes","type":"fill","source":"v","source-layer":"water","filter":["==",["get","kind"],"lake"],""" +
                    metadata(tagged) +
                    """"paint":{"fill-color":"$color","fill-opacity":$opacity,"fill-translate":[3.25,-1.5],"fill-outline-color":"$color"}}"""
            },
            "line" to { tagged, color, opacity ->
                """{"id":"rivers","type":"line","source":"v","source-layer":"rivers",""" + metadata(tagged) +
                    """"layout":{"line-join":"round","line-cap":"round"},""" +
                    """"paint":{"line-color":"$color","line-opacity":$opacity,"line-width":["interpolate",["linear"],["zoom"],0,7.5,10,20]}}"""
            },
            "styled line" to { tagged, color, opacity ->
                """{"id":"rivers","type":"line","source":"v","source-layer":"rivers",""" + metadata(tagged) +
                    """"layout":{"line-join":"miter","line-cap":"square"},""" +
                    """"paint":{"line-color":"$color","line-opacity":$opacity,"line-width":3,"line-gap-width":4,""" +
                    """"line-offset":2.5,"line-dasharray":[2,1],"line-blur":1.5}}"""
            },
        )

        private fun metadata(tagged: Boolean): String = if (tagged) """"metadata":{"ta:material":"water"},""" else ""
    }
}
