package com.rohittp.rentile

import com.rohittp.rentile.internal.renderSyntheticPng
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RasterImageryDetailCeilingTest {
    @Test
    fun satelliteBecomesKnownAfterValidationWithoutQueryIoOrRendering() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(512, 18))
            assertEquals(RasterImageryDetailCeiling(null, false), rasterizer.rasterImageryDetailCeiling(style))
            assertEquals(0, fixture.requests().size)
            val options = RenderOptions(maximumRasterSourceZoomDelta = 1)
            val batch = rasterizer.prepareBatch(style, listOf(TILE), options)
            try {
                assertEquals(RasterImageryDetailCeiling(18, false), rasterizer.rasterImageryDetailCeiling(style, options))
                assertEquals(1, fixture.requests().size)
                assertTrue(fixture.requests().single().contains("/18/"))
                val requestKey = rasterizer.outputRequestKey(style, TILE, options)
                val before = rasterizer.render(batch).tiles.single()
                repeat(3) { rasterizer.rasterImageryDetailCeiling(style, options) }
                val after = rasterizer.render(batch).tiles.single()
                assertEquals(requestKey, rasterizer.outputRequestKey(style, TILE, options))
                assertEquals(before.contentKey, after.contentKey)
                assertContentEquals(before.pngBytes, after.pngBytes)
                assertEquals(1, fixture.requests().size)
            } finally { batch.close() }
        }
    }

    @Test
    fun terrainUsesActual256PayloadRatherThanDeclared64AndHonorsAcquisitionCap() = runTest {
        val fixture = Fixture(256)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(64, 19))
            val options = RenderOptions(maximumRasterSourceZoomDelta = 1)
            rasterizer.prepareBatch(style, listOf(TILE), options).close()
            assertEquals(4, fixture.requests().size)
            assertTrue(fixture.requests().all { it.contains("/19/") })
            assertEquals(18, rasterizer.rasterImageryDetailCeiling(style, options)?.maximumOutputZoom)
            assertEquals(18, rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
            assertEquals(19, rasterizer.rasterImageryDetailCeiling(style,
                RenderOptions(maximumRasterSourceZoomDelta = 0))?.maximumOutputZoom)
            assertEquals(19, rasterizer.rasterImageryDetailCeiling(style,
                RenderOptions(rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM,
                    maximumRasterSourceZoomDelta = 1))?.maximumOutputZoom)
            assertEquals(19, rasterizer.rasterImageryDetailCeiling(style,
                RenderOptions(outputSizePx = 256, maximumRasterSourceZoomDelta = 1))?.maximumOutputZoom)
            assertEquals(18, rasterizer.rasterImageryDetailCeiling(style,
                RenderOptions(outputSizePx = 1024, maximumRasterSourceZoomDelta = 1))?.maximumOutputZoom)
        }
    }

    @Test
    fun negativeDeclaredDensityDeltaNeedsTheNextOutputLevelToReachSourceMaxzoom() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(512, 18))
            val options = RenderOptions(256, maximumRasterSourceZoomDelta = 1)
            rasterizer.prepareBatch(style, listOf(TILE), options).close()
            assertTrue(fixture.requests().single().contains("/17/"))
            assertEquals(19, rasterizer.rasterImageryDetailCeiling(style, options)?.maximumOutputZoom)
        }
    }

    @Test
    fun retinaPayloadRetainsItsExtraTexelsEvenAfterDeclaredSourceZoomSaturates() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(256, 18))
            rasterizer.prepareBatch(style, listOf(TILE)).close()
            assertEquals(18, rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
        }
    }

    @Test
    fun nonPowerOfTwoAndRectangularPayloadsRoundUpConservatively() = runTest {
        for ((width, height, ceiling) in listOf(Triple(513, 256, 19), Triple(256, 1024, 19), Triple(256, 256, 18))) {
            val fixture = Fixture(width, height)
            fixture.use { rasterizer ->
                val style = rasterizer.prepare(imageryStyle(512, 18))
                rasterizer.prepareBatch(style, listOf(TILE)).close()
                assertEquals(ceiling, rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
            }
        }
    }

    @Test
    fun everyImagerySourceMustBeObservedAndTheHighestCeilingWins() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"sources":{
                "one":{"type":"raster","tileSize":512,"maxzoom":18,"tiles":["https://fixture.invalid/one/{z}/{x}/{y}.png"]},
                "two":{"type":"raster","tileSize":512,"maxzoom":20,"tiles":["https://fixture.invalid/two/{z}/{x}/{y}.png"]}},
                "layers":[{"id":"one","type":"raster","source":"one"},
                {"id":"two","type":"raster","source":"two","minzoom":20}]}"""))
            rasterizer.prepareBatch(style, listOf(TILE)).close()
            assertNull(rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
            rasterizer.prepareBatch(style, listOf(TileId(20, 3, 5))).close()
            assertEquals(20, rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
        }
    }

    @Test
    fun aFreshPreparedStyleObservesValidatedRawStoreHitsWithoutNetwork() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val first = rasterizer.prepare(imageryStyle(512, 18))
            rasterizer.prepareBatch(first, listOf(TILE)).close()
            val second = rasterizer.prepare(imageryStyle(512, 18))
            assertNull(rasterizer.rasterImageryDetailCeiling(second)?.maximumOutputZoom)
            rasterizer.prepareBatch(second, listOf(TILE), resourceAccess = ResourceAccessMode.CACHE_ONLY).close()
            assertEquals(18, rasterizer.rasterImageryDetailCeiling(second)?.maximumOutputZoom)
            assertEquals(1, fixture.requests().size)
        }
    }

    @Test
    fun rawWarmingAndInvalidPayloadsNeverClaimValidatedDimensions() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(512, 18))
            rasterizer.warmRawResources(style, listOf(TILE))
            assertNull(rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
            fixture.invalid = true
            assertFailsWith<ResourceDecodeException> {
                rasterizer.prepareBatch(style, listOf(TILE), resourceAccess = ResourceAccessMode.RELOAD)
            }
            assertNull(rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
        }
    }

    @Test
    fun largerObservedPayloadsCanRaiseButSmallerOnesCannotLowerTheCeiling() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(512, 18))
            for ((dimension, ceiling) in listOf(512 to 18, 1024 to 19, 256 to 19)) {
                fixture.payload = png(dimension, dimension)
                rasterizer.prepareBatch(style, listOf(TILE), resourceAccess = ResourceAccessMode.RELOAD).close()
                assertEquals(ceiling, rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
            }
        }
    }

    @Test
    fun mixedVectorDrawLayersAreReportedRegardlessOfTheirCurrentZoomRange() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(512, 18, extraSources =
                """, "v":{"type":"vector","tiles":["https://fixture.invalid/{z}/{x}/{y}.pbf"]}""",
                extraLayers = """, {"id":"line","type":"line","source":"v","source-layer":"road","minzoom":20,
                "paint":{"line-width":["interpolate",["linear"],["zoom"],0,1,22,8]}}"""))
            val report = rasterizer.rasterImageryDetailCeiling(style)
            assertTrue(report?.hasRasterizedVectorLayers == true)
            assertNull(report?.maximumOutputZoom)
            assertTrue(fixture.requests().isEmpty())
        }
    }

    @Test
    fun hostOwnedSymbolsDoNotClaimToBeRasterizedVectorLayers() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val input = imageryStyle(512, 18, extraSources =
                """, "v":{"type":"vector","tiles":["https://fixture.invalid/{z}/{x}/{y}.pbf"]}""",
                extraRoot = """, "sprite":"https://fixture.invalid/sprite" """,
                extraLayers = """, {"id":"icon","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker"}}""")
            val legacy = rasterizer.prepare(input)
            assertTrue(rasterizer.rasterImageryDetailCeiling(legacy)?.hasRasterizedVectorLayers == true)
            val host = rasterizer.prepare(input, CompatibilityPolicy.RentileV1HostSymbols)
            assertFalse(rasterizer.rasterImageryDetailCeiling(host)?.hasRasterizedVectorLayers ?: true)
        }
    }

    @Test
    fun demAndHillshadeWithoutImageryHaveNoImageryCeiling() = runTest {
        val fixture = Fixture(512)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"sources":{
                "dem":{"type":"raster-dem","tileSize":256,"maxzoom":12,"tiles":["https://fixture.invalid/dem/{z}/{x}/{y}.png"]}},
                "terrain":{"source":"dem"},"layers":[{"id":"hill","type":"hillshade","source":"dem"}]}"""))
            assertNull(rasterizer.rasterImageryDetailCeiling(style))
            assertTrue(fixture.requests().isEmpty())
        }
    }

    @Test
    fun reportedZoomRemainsInsideThePreparedCompatibilityProfile() = runTest {
        val fixture = Fixture(1024)
        fixture.use { rasterizer ->
            val style = rasterizer.prepare(imageryStyle(512, 30))
            rasterizer.prepareBatch(style, listOf(TILE)).close()
            assertEquals(style.policy.maximumOutputZoom, rasterizer.rasterImageryDetailCeiling(style)?.maximumOutputZoom)
        }
    }

    private class Fixture(width: Int, height: Int = width) {
        var payload = png(width, height)
        var invalid = false
        private val recorded = mutableListOf<String>()
        private val mutex = Mutex()
        private val rasterizer = Rentile.create(RentileConfiguration(
            transport = ResourceTransport { request ->
                mutex.withLock { recorded += request.url }
                val bytes = when {
                    request.url.endsWith("sprite.json") -> """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1}}""".encodeToByteArray()
                    request.url.endsWith("sprite.png") -> renderSyntheticPng(8)
                    invalid -> byteArrayOf(1, 2, 3)
                    else -> payload
                }
                TransportResponse(200, bytes)
            },
            rawResourceStore = InMemoryRawResourceStore(),
        ))
        suspend fun requests(): List<String> = mutex.withLock { recorded.toList() }
        suspend fun use(block: suspend (BasemapRasterizer) -> Unit) {
            try { block(rasterizer) } finally { rasterizer.close(); rasterizer.awaitClosed() }
        }
    }

    private companion object {
        val TILE = TileId(18, 3, 5)
        fun imageryStyle(tileSize: Int, maxZoom: Int, extraSources: String = "", extraLayers: String = "", extraRoot: String = ""): StyleInput =
            StyleInput.InlineJson("""{"version":8,"sources":{"r":{"type":"raster","tileSize":$tileSize,"maxzoom":$maxZoom,
                "tiles":["https://fixture.invalid/r/{z}/{x}/{y}.png"]}$extraSources},
                "layers":[{"id":"r","type":"raster","source":"r"}$extraLayers]$extraRoot}""")
        fun png(width: Int, height: Int): ByteArray {
            val surface = checkNotNull(Surface.makeRasterN32Premul(width, height))
            try {
                surface.canvas.clear(0xff446688.toInt())
                val image = surface.makeImageSnapshot()
                try {
                    val encoded = checkNotNull(image.encodeToData(EncodedImageFormat.PNG))
                    try { return encoded.bytes } finally { encoded.close() }
                } finally { image.close() }
            } finally { surface.close() }
        }
    }
}
