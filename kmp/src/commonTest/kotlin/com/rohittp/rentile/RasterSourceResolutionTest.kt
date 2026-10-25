package com.rohittp.rentile

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RasterSourceResolutionTest {
    @Test
    fun raster256At512AcquiresAndPlacesFourNativeChildren() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            val style = rasterizer.prepare(style(256))
            val batch = rasterizer.prepareBatch(style, listOf(TileId(4, 3, 5)), RenderOptions(512))
            try {
                assertEquals(setOf("5/6/10", "5/7/10", "5/6/11", "5/7/11"), requests.toSet())
                val rendered = rasterizer.render(batch).tiles.single()
                assertPixels(rendered.pngBytes, listOf(64 to 64, 448 to 64, 64 to 448, 448 to 448),
                    listOf(color(6, 10), color(7, 10), color(6, 11), color(7, 11)))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun adjustedSourceMinzoomWarmsAndRendersBelowItsNumericOutputZoom() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            val style = rasterizer.prepare(style(256, sourceMinZoom = 10))
            val tiles = listOf(TileId(9, 3, 5))
            val options = RenderOptions(512)
            assertEquals(4, rasterizer.warmRawResources(style, tiles, options).fetched)
            assertEquals(setOf("10/6/10", "10/7/10", "10/6/11", "10/7/11"), requests.toSet())
            requests.clear()
            val batch = rasterizer.prepareBatch(style, tiles, options, ResourceAccessMode.CACHE_ONLY)
            try {
                assertTrue(requests.isEmpty())
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(64 to 64, 448 to 448), listOf(color(6, 10), color(7, 11)))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun adjustedSourceMinzoomDoesNotBypassSourceFloorOrLayerZoomRange() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            for ((input, outputSize, tile) in listOf(
                Triple(style(256, sourceMinZoom = 10), 512, TileId(8, 3, 5)),
                Triple(style(512, sourceMinZoom = 10), 256, TileId(10, 3, 5)),
                Triple(style(256, sourceMinZoom = 10, layerMinZoom = 10), 512, TileId(9, 3, 5)),
                Triple(style(256, sourceMinZoom = 10, layerMaxZoom = 9), 512, TileId(9, 3, 5)),
            )) {
                val style = rasterizer.prepare(input)
                val batch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(outputSize))
                try {
                    assertTrue(requests.isEmpty())
                    assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                        listOf(32 to 32), listOf(Color.TRANSPARENT))
                } finally { batch.close() }
            }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun highResolutionRaster512AcquiresSixteenChildrenWithoutShiftingStyleZoom() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, payloadSize = 512)
        try {
            // The layer is inactive at source z16, but active at output z14.
            val style = rasterizer.prepare(style(512, layerMaxZoom = 15))
            val batch = rasterizer.prepareBatch(style, listOf(TileId(14, 7, 9)), RenderOptions(2048))
            try {
                assertEquals((0..3).flatMap { y -> (0..3).map { x -> "16/${28+x}/${36+y}" } }.toSet(), requests.toSet())
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(128 to 128, 1920 to 1920), listOf(color(28, 36), color(31, 39)))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun declared64ControlsSourceZoomEvenWhenThePayloadIs256() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            val style = rasterizer.prepare(style(64))
            val batch = rasterizer.prepareBatch(style, listOf(TileId(4, 3, 5)), RenderOptions(512))
            try {
                assertEquals(64, requests.size)
                assertTrue(requests.all { it.startsWith("7/") })
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(32 to 32, 480 to 480), listOf(color(24, 40), color(31, 47)))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun highZoomUsesSourceMaxzoomAndCropsTheCorrectAncestorWindow() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, quadrantPayload = true)
        try {
            val style = rasterizer.prepare(style(256, sourceMaxZoom = 14))
            val batch = rasterizer.prepareBatch(style, listOf(TileId(15, 7, 9)), RenderOptions(512))
            try {
                assertEquals(listOf("14/3/4"), requests)
                // Output x/y are odd: bottom-right quarter of the z14 ancestor.
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(64 to 64, 448 to 448), listOf(Color.YELLOW, Color.YELLOW))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun smallOutputUsesOneNativeAncestorAndCropsTheOutputCoverage() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, quadrantPayload = true, payloadSize = 512)
        try {
            val style = rasterizer.prepare(style(512))
            val batch = rasterizer.prepareBatch(style, listOf(TileId(4, 3, 5)), RenderOptions(256))
            try {
                assertEquals(listOf("3/1/2"), requests)
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(32 to 32, 224 to 224), listOf(Color.YELLOW, Color.YELLOW))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun excessiveBatchFanoutFailsBeforeAnyTileRequest() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            val style = rasterizer.prepare(style(64))
            assertFailsWith<SafetyLimitException> {
                rasterizer.prepareBatch(style, (0..4).map { TileId(4, it, 0) }, RenderOptions(2048))
            }
            assertTrue(requests.isEmpty(), "reject the resource closure before starting acquisition")
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun actualPayloadDimensionsAndOutputPlaneCountAgainstTheWorkingBudget() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, executionPolicy = ExecutionPolicy(maxResidentDecodedBytes = 4L * 1024 * 1024))
        try {
            val style = rasterizer.prepare(style(64))
            assertFailsWith<SafetyLimitException> {
                // Declared source pixels total1MiB, but the actual256px payloads total16MiB.
                rasterizer.prepareBatch(style, listOf(TileId(4, 0, 0)), RenderOptions(512))
            }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun outputAwareWarmingFreezesTheSameClosureAsCacheOnlyPreparation() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            val style = rasterizer.prepare(style(256))
            val tiles = listOf(TileId(4, 3, 5))
            val options = RenderOptions(1024)
            assertEquals(16, rasterizer.warmRawResources(style, tiles, options).fetched)
            assertEquals(16, rasterizer.warmRawResources(style, tiles, options).alreadyCached)
            requests.clear()
            val batch = rasterizer.prepareBatch(style, tiles, options, ResourceAccessMode.CACHE_ONLY)
            try {
                assertTrue(requests.isEmpty())
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(128 to 128, 896 to 896), listOf(color(12, 20), color(15, 23)))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun missingMosaicCellSubstitutesAndRecoversWithoutMovingItsDestination() = runTest {
        var blocked = true
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, unavailable = { path -> blocked && (path == "5/7/11" || path.startsWith("6/")) })
        try {
            val style = rasterizer.prepare(style(256))
            val tile = TileId(4, 3, 5)
            val batch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(512),
                substitutionPolicy = TileSubstitutionPolicy(1))
            try {
                assertTrue(batch.substitutions.getValue(tile).isNotEmpty())
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(64 to 64, 448 to 448), listOf(color(6, 10), color(3, 5)))
                blocked = false
                val recovered = rasterizer.retryExact(batch)
                assertEquals(setOf(tile), recovered.upgradedTiles)
                assertTrue(recovered.remainingSubstitutedTiles.isEmpty())
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(64 to 64, 448 to 448), listOf(color(6, 10), color(7, 11)))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun rasterPresenceExcludesUnusedHiddenAndDemSources() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            assertTrue(rasterizer.hasRasterImagery(rasterizer.prepare(style(256))))
            val hidden = (style(256) as StyleInput.InlineJson).let {
                // Keep the same valid source, changing only the layer's authored visibility.
                StyleInput.InlineJson(it.json.replace("\"paint\":", "\"layout\":{\"visibility\":\"none\"},\"paint\":"))
            }
            assertEquals(false, rasterizer.hasRasterImagery(rasterizer.prepare(hidden)))
            val unused = StyleInput.InlineJson("""{"version":8,"sources":{"r":{"type":"raster","tileSize":256,"tiles":["https://raster.test/{z}/{x}/{y}.png"]}},"layers":[{"id":"b","type":"background"}]}""")
            assertEquals(false, rasterizer.hasRasterImagery(rasterizer.prepare(unused)))
            val dem = StyleInput.InlineJson("""{"version":8,"sources":{"d":{"type":"raster-dem","tileSize":256,"maxzoom":12,"tiles":["https://raster.test/{z}/{x}/{y}.png"]}},"terrain":{"source":"d"},"layers":[{"id":"h","type":"hillshade","source":"d"}]}""")
            val prepared = rasterizer.prepare(dem)
            assertEquals(false, rasterizer.hasRasterImagery(prepared))
            rasterizer.warmRawResources(prepared, listOf(TileId(14, 3, 5)), RenderOptions(1024, includeHillshade = false))
            assertTrue(requests.isEmpty(), "omitted hillshade must not warm DEM inputs")
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun boundedSourceOnlyAcquiresCoveredChildrenAndLeavesOtherCellsTransparent() = runTest {
        val requests = mutableListOf<String>()
        // A single covered child's payload matches the full output dimensions: it must still
        // occupy only its own quadrant, never enter the whole-output PNG pass-through path.
        val rasterizer = renderer(requests, payloadSize = 512, unavailable = { it != "2/0/0" })
        try {
            val bounded = style(256, sourceBounds = "[-179,67,-91,84]")
            val style = rasterizer.prepare(bounded)
            val batch = rasterizer.prepareBatch(style, listOf(TileId(1, 0, 0)), RenderOptions(512))
            try {
                assertEquals(listOf("2/0/0"), requests)
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                    listOf(64 to 64, 448 to 64, 64 to 448, 448 to 448),
                    listOf(color(0, 0), Color.TRANSPARENT, Color.TRANSPARENT, Color.TRANSPARENT))
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun overzoomedOutputsChargeAndWarmTheSharedNativeParentOnlyOnce() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, payloadSize = 512)
        try {
            val style = rasterizer.prepare(style(512, sourceMaxZoom = 0))
            val tiles = (0..7).flatMap { y -> (0..15).map { x -> TileId(4, x, y) } }
            assertEquals(1, rasterizer.warmRawResources(style, tiles).fetched)
            assertEquals(listOf("0/0/0"), requests)
            requests.clear()
            val batch = rasterizer.prepareBatch(style, tiles, resourceAccess = ResourceAccessMode.CACHE_ONLY)
            try {
                assertTrue(requests.isEmpty())
                assertEquals(tiles.size, batch.contentKeys.size)
                assertTrue(rasterizer.render(batch, listOf(tiles.first())).tiles.single().pngBytes.isNotEmpty())
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun rawWarmingDoesNotSpendADecodedPlaneBudget() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, executionPolicy = ExecutionPolicy(maxResidentDecodedBytes = 64L * 1024))
        try {
            val style = rasterizer.prepare(style(256))
            assertEquals(4, rasterizer.warmRawResources(style, listOf(TileId(4, 3, 5))).fetched)
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun terrainDeclared64With256PayloadFitsEightOutputsAndTwoWorkersWithinDefaultBudget() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests,
            executionPolicy = ExecutionPolicy(maxConcurrentMetatileWorkers = 2),
            // 512 sources form256 distinct images, each reused twice: this maximizes shared
            // image residency for this batch rather than making all512 images unshareable.
            payloadColor = { x, y -> color(x % 32, y) },
        )
        try {
            val style = rasterizer.prepare(style(64))
            val tiles = (0..7).map { TileId(16, it, 0) }
            val batch = rasterizer.prepareBatch(style, tiles, RenderOptions(512))
            try {
                assertEquals(512, requests.size)
                assertTrue(requests.all { it.startsWith("19/") })
                assertEquals(tiles.size, rasterizer.render(batch).tiles.size)
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun outputZoomIgnoresDeclaredSizeAndOutputSizeForAcquisitionAndWarming() = runTest {
        for (declaredSize in listOf(64, 512)) for (outputSize in listOf(256, 512)) {
            val requests = mutableListOf<String>()
            val rasterizer = renderer(requests)
            try {
                val style = rasterizer.prepare(style(declaredSize))
                val options = RenderOptions(outputSize, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
                val tiles = listOf(TileId(14, 3, 5))
                assertEquals(1, rasterizer.warmRawResources(style, tiles, options).fetched)
                assertEquals(listOf("14/3/5"), requests)
                requests.clear()
                val batch = rasterizer.prepareBatch(style, tiles, options, ResourceAccessMode.CACHE_ONLY)
                try {
                    assertTrue(requests.isEmpty())
                    assertPixels(rasterizer.render(batch).tiles.single().pngBytes,
                        listOf(32 to 32), listOf(color(3, 5)))
                } finally { batch.close() }
            } finally { rasterizer.close(); rasterizer.awaitClosed() }
        }
    }

    @Test
    fun outputZoomPreservesLegacyMinzoomAvailabilityAndLayerRange() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            val options = RenderOptions(512, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
            for ((input, tile, expected) in listOf(
                Triple(style(64, sourceMinZoom = 10), TileId(9, 3, 5), emptyList()),
                Triple(style(512, sourceMinZoom = 10), TileId(10, 3, 5), listOf("10/3/5")),
                Triple(style(64, layerMinZoom = 10), TileId(9, 3, 5), emptyList()),
                Triple(style(64, layerMaxZoom = 10), TileId(10, 3, 5), emptyList()),
            )) {
                requests.clear()
                val style = rasterizer.prepare(input)
                val batch = rasterizer.prepareBatch(style, listOf(tile), options, ResourceAccessMode.RELOAD)
                try {
                    assertEquals(expected, requests)
                    assertPixels(rasterizer.render(batch).tiles.single().pngBytes, listOf(32 to 32),
                        listOf(if (expected.isEmpty()) Color.TRANSPARENT else color(3, 5)))
                } finally { batch.close() }
            }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun outputZoomReturnsIdenticalValidatedPngOnlyWhenPayloadMatchesOutputSize() = runTest {
        for (declaredSize in listOf(64, 512)) for (outputSize in listOf(256, 512)) {
            val requests = mutableListOf<String>()
            val rasterizer = renderer(requests, quadrantPayload = true, payloadSize = outputSize)
            try {
                val style = rasterizer.prepare(style(declaredSize))
                val options = RenderOptions(outputSize, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
                val batch = rasterizer.prepareBatch(style, listOf(TileId(14, 3, 5)), options)
                try {
                    val tile = rasterizer.render(batch).tiles.single()
                    assertContentEquals(png(null, outputSize), tile.pngBytes)
                    assertTrue(tile.diagnostics.any { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
                } finally { batch.close() }
            } finally { rasterizer.close(); rasterizer.awaitClosed() }
        }
    }

    @Test
    fun outputZoomDoesNotForwardWrongSizedPngAndPreservesLegacyMagnification() = runTest {
        for ((payloadSize, outputSize) in listOf(256 to 512, 512 to 256)) {
            val requests = mutableListOf<String>()
            val rasterizer = renderer(requests, quadrantPayload = true, payloadSize = payloadSize)
            try {
                val style = rasterizer.prepare(style(64))
                val options = RenderOptions(outputSize, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
                val batch = rasterizer.prepareBatch(style, listOf(TileId(14, 3, 5)), options)
                try {
                    val tile = rasterizer.render(batch).tiles.single()
                    assertEquals(listOf("14/3/5"), requests)
                    assertTrue(tile.diagnostics.none { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
                    // The 0.15.1 nearest-neighbour draw magnifies/downsamples this same quadrant image.
                    assertContentEquals(png(null, outputSize), tile.pngBytes)
                } finally { batch.close() }
            } finally { rasterizer.close(); rasterizer.awaitClosed() }
        }
    }

    @Test
    fun outputZoomCropsMaxzoomAncestorAndWrapsOutputXWithoutPassthrough() = runTest {
        for (declaredSize in listOf(64, 512)) {
            val requests = mutableListOf<String>()
            val rasterizer = renderer(requests, quadrantPayload = true, payloadSize = 512)
            try {
                val style = rasterizer.prepare(style(declaredSize, sourceMaxZoom = 14))
                val options = RenderOptions(512, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
                val batch = rasterizer.prepareBatch(style, listOf(TileId(15, (1 shl 15) + 7, 9)), options)
                try {
                    val tile = rasterizer.render(batch).tiles.single()
                    assertEquals(listOf("14/3/4"), requests)
                    assertContentEquals(png(Color.YELLOW, 512), tile.pngBytes)
                    assertTrue(tile.diagnostics.none { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
                } finally { batch.close() }
            } finally { rasterizer.close(); rasterizer.awaitClosed() }
        }
    }

    @Test
    fun acquisitionModesHaveSeparateRequestAndContentKeysEvenWhenClosureMatches() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, payloadSize = 512)
        try {
            val style = rasterizer.prepare(style(512))
            val tile = TileId(14, 3, 5)
            val native = RenderOptions(512)
            val legacy = native.copy(rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
            assertEquals(RasterAcquisitionDensity.NATIVE, native.rasterAcquisitionDensity)
            assertNotEquals(rasterizer.outputRequestKey(style, tile, native), rasterizer.outputRequestKey(style, tile, legacy))
            val nativeBatch = rasterizer.prepareBatch(style, listOf(tile), native)
            val legacyBatch = rasterizer.prepareBatch(style, listOf(tile), legacy, ResourceAccessMode.CACHE_ONLY)
            try {
                assertEquals(listOf("14/3/5"), requests, "identical raw URLs remain shareable across density modes")
                assertNotEquals(nativeBatch.contentKeys.getValue(tile), legacyBatch.contentKeys.getValue(tile))
                assertContentEquals(rasterizer.render(nativeBatch).tiles.single().pngBytes,
                    rasterizer.render(legacyBatch).tiles.single().pngBytes)
            } finally { nativeBatch.close(); legacyBatch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun nativeRequestAndContentSemanticsRemainUnchangedFrom016() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"sources":{},"layers":[{"id":"b","type":"background"}]}"""))
            val tile = TileId(14, 3, 5)
            for (includeHillshade in listOf(true, false)) {
                val options = RenderOptions(512, includeHillshade)
                val suffix = if (includeHillshade) "" else "\nhillshade:omit"
                assertEquals("rentile-output-request-4\n${style.digest}\n14/3/5\n512$suffix".encodeUtf8().sha256().hex(),
                    rasterizer.outputRequestKey(style, tile, options))
                val batch = rasterizer.prepareBatch(style, listOf(tile), options)
                try {
                    assertEquals("rentile-output-5\n${style.digest}\n14/3/5\n512$suffix".encodeUtf8().sha256().hex(),
                        batch.contentKeys.getValue(tile))
                } finally { batch.close() }
            }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun outputZoomRetryRetainsItsOriginalAcquisitionInsteadOfSwitchingToNative() = runTest {
        var blocked = true
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, unavailable = { blocked && it.startsWith("4/") || it.startsWith("5/") })
        try {
            val style = rasterizer.prepare(style(64))
            val tile = TileId(4, 3, 5)
            val options = RenderOptions(512, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
            val batch = rasterizer.prepareBatch(style, listOf(tile), options, substitutionPolicy = TileSubstitutionPolicy(1))
            try {
                assertTrue(batch.substitutions.getValue(tile).isNotEmpty())
                val before = batch.contentKeys.getValue(tile)
                blocked = false
                requests.clear()
                val recovered = rasterizer.retryExact(batch)
                assertEquals(setOf(tile), recovered.upgradedTiles)
                assertTrue(recovered.remainingSubstitutedTiles.isEmpty())
                assertEquals(listOf("4/3/5"), requests)
                assertNotEquals(before, batch.contentKeys.getValue(tile))
                assertPixels(rasterizer.render(batch).tiles.single().pngBytes, listOf(32 to 32), listOf(color(3, 5)))
                val fresh = rasterizer.prepareBatch(style, listOf(tile), options, ResourceAccessMode.CACHE_ONLY)
                try { assertEquals(fresh.contentKeys, batch.contentKeys) } finally { fresh.close() }
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun outputZoomDoesNotEvaluateInactiveSourcePaintInPngOrRawOutput() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests)
        try {
            // Valid at source minzoom and above, deliberately out of range below it. 0.15.1
            // excluded this raster layer before paint evaluation in both output transports.
            val input = (style(64, sourceMinZoom = 10) as StyleInput.InlineJson).let {
                StyleInput.InlineJson(it.json.replace("\"raster-resampling\":\"nearest\"",
                    "\"raster-opacity\":[\"interpolate\",[\"linear\"],[\"zoom\"],0,2,10,1]"))
            }
            val style = rasterizer.prepare(input)
            val options = RenderOptions(512, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
            val batch = rasterizer.prepareBatch(style, listOf(TileId(9, 3, 5)), options)
            try {
                assertTrue(requests.isEmpty())
                assertContentEquals(png(Color.TRANSPARENT, 512), rasterizer.render(batch).tiles.single().pngBytes)
                assertTrue(rasterizer.renderRaw(batch).tiles.single().rgbaBytes.all { it == 0.toByte() })
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun outputZoomInactiveRasterLayerDoesNotDisableLegacyPngPassthrough() = runTest {
        val requests = mutableListOf<String>()
        val rasterizer = renderer(requests, quadrantPayload = true, payloadSize = 512)
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"sources":{
                "inactive":{"type":"raster","tileSize":64,"minzoom":10,"tiles":["https://raster.test/{z}/{x}/{y}.png"]},
                "active":{"type":"raster","tileSize":512,"tiles":["https://raster.test/{z}/{x}/{y}.png"]}},
                "layers":[{"id":"inactive","type":"raster","source":"inactive"},
                {"id":"active","type":"raster","source":"active"}]}"""))
            val options = RenderOptions(512, rasterAcquisitionDensity = RasterAcquisitionDensity.OUTPUT_ZOOM)
            val batch = rasterizer.prepareBatch(style, listOf(TileId(9, 3, 5)), options)
            try {
                assertEquals(listOf("9/3/5"), requests)
                val output = rasterizer.render(batch).tiles.single()
                assertContentEquals(png(null, 512), output.pngBytes)
                assertTrue(output.diagnostics.any { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED },
                    "a below-minzoom source layer must be absent from the active-layer count as in 0.15.1")
            } finally { batch.close() }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    private fun renderer(
        requests: MutableList<String>,
        quadrantPayload: Boolean = false,
        payloadSize: Int = 256,
        executionPolicy: ExecutionPolicy = ExecutionPolicy(),
        unavailable: (String) -> Boolean = { false },
        payloadColor: ((Int, Int) -> Int)? = null,
    ): BasemapRasterizer {
        val requestsMutex = Mutex()
        return Rentile.create(RentileConfiguration(transport = ResourceTransport { request ->
            val path = request.url.removePrefix("https://raster.test/").removeSuffix(".png")
            requestsMutex.withLock { requests += path }
            if (unavailable(path)) return@ResourceTransport TransportResponse(404, ByteArray(0))
            val (_, x, y) = path.split('/').map(String::toInt)
            TransportResponse(200, png(if (quadrantPayload) null else payloadColor?.invoke(x, y) ?: color(x, y), payloadSize))
        }, rawResourceStore = InMemoryRawResourceStore(), executionPolicy = executionPolicy))
    }

    private fun style(tileSize: Int, sourceMaxZoom: Int = 22, layerMaxZoom: Int = 31, sourceBounds: String? = null, sourceMinZoom: Int = 0, layerMinZoom: Int = 0): StyleInput =
        StyleInput.InlineJson("""{"version":8,"sources":{"r":{"type":"raster","tileSize":$tileSize,"minzoom":$sourceMinZoom,"maxzoom":$sourceMaxZoom${sourceBounds?.let { ",\"bounds\":$it" }.orEmpty()},"tiles":["https://raster.test/{z}/{x}/{y}.png"]}},"layers":[{"id":"r","type":"raster","source":"r","minzoom":$layerMinZoom,"maxzoom":$layerMaxZoom,"paint":{"raster-resampling":"nearest"}}]}""")

    private fun color(x: Int, y: Int): Int = Color.makeRGB((x * 17) % 256, (y * 19) % 256, 60)

    private fun png(color: Int?, size: Int = 256): ByteArray {
        val surface = Surface.makeRasterN32Premul(size, size)
        try {
            if (color != null) surface.canvas.clear(color) else {
                surface.canvas.clear(Color.RED)
                val paint = org.jetbrains.skia.Paint()
                val half = size / 2f
                try {
                    paint.color = Color.GREEN
                    surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(half, 0f, half, half), paint)
                    paint.color = Color.BLUE
                    surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, half, half, half), paint)
                    paint.color = Color.YELLOW
                    surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(half, half, half, half), paint)
                } finally { paint.close() }
            }
            val image = surface.makeImageSnapshot()
            try {
                val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
                try { return data.bytes } finally { data.close() }
            } finally { image.close() }
        } finally { surface.close() }
    }

    private fun assertPixels(bytes: ByteArray, points: List<Pair<Int, Int>>, expected: List<Int>) {
        val image = Image.makeFromEncoded(bytes)
        val bitmap = Bitmap()
        try {
            check(bitmap.allocN32Pixels(image.width, image.height, false))
            check(image.readPixels(bitmap))
            assertEquals(expected, points.map { (x, y) -> bitmap.getColor(x, y) })
        }
        finally { bitmap.close(); image.close() }
    }
}
