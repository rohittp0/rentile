package com.rohittp.rentile

import com.rohittp.rentile.internal.createBasemapRasterizer
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A prepared batch decodes each source tile it draws from once, and that changes no pixels.
 *
 * The count is the only thing that can see this. Decoding one DEM tile once or nine times produces
 * identical output, only slower, so the rasterizer carries a test-only decode recorder exactly as
 * it does for the sprite atlas (`SpriteAtlasDecodeTest`) and for render lanes
 * (`RenderPriorityTest`). What the count cannot see is whether sharing a decode between tiles that
 * draw concurrently changes what they draw, so that half is checked as bytes: the same tiles are
 * rendered one batch per tile on one worker — the arrangement in which nothing is shared, and each
 * draw decodes for itself — and again as one batch on four workers, and compared.
 */
@OptIn(ExperimentalAtomicApi::class)
class SourceTileDecodeTest {
    @Test
    fun oneBatchDecodesEachDemTileOnceHoweverManyOfItsTilesReadIt() = runTest(timeout = TIMEOUT) {
        // Nine output tiles, each reading its own DEM tile and that tile's eight neighbours: 81
        // reads of 25 distinct DEM tiles, because the neighbourhoods overlap.
        withRecordedDecodes(workers = 4) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(HILLSHADE_STYLE))
            val batch = rasterizer.prepareBatch(style, DEM_TILES, RenderOptions(OUTPUT_SIZE_PX))
            try {
                assertEquals(DEM_TILES.size, rasterizer.render(batch).tiles.size)
                assertEquals(
                    DISTINCT_DEM_TILES,
                    decodes(ResourceClass.DEM_TILE),
                    "81 DEM reads over 25 distinct DEM tiles must decode 25 of them",
                )
            } finally {
                batch.close()
            }
        }
    }

    @Test
    fun rerenderingOneBatchNeverDecodesItsSourceTilesAgain() = runTest(timeout = TIMEOUT) {
        // The decode has to survive between render calls, not merely within one: a consumer that
        // renders a prepared batch tile by tile so it can mark some of them URGENT makes exactly
        // this shape of call, and it is the shape a per-render hoist would leave unfixed.
        //
        // The tile rendered again is the middle of the block, and that is not incidental. Every DEM
        // the middle tile reads is read by another tile too, so all nine are shared and re-rendering
        // it decodes nothing. A tile at the *corner* of the block reads one DEM that no other tile
        // in the batch reads, which this batch deliberately does not hold a decode for — see
        // `aResourceOnlyOneDrawReadsIsNotHeld` — so re-rendering that one would decode it again.
        withRecordedDecodes(workers = 2) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(HILLSHADE_STYLE))
            val batch = rasterizer.prepareBatch(style, DEM_TILES, RenderOptions(OUTPUT_SIZE_PX))
            try {
                DEM_TILES.forEach { tile -> rasterizer.render(batch, listOf(tile)) }
                assertEquals(DISTINCT_DEM_TILES, decodes(ResourceClass.DEM_TILE))
                rasterizer.render(batch, listOf(MIDDLE_DEM_TILE), priority = RenderPriority.URGENT)
                rasterizer.renderRaw(batch, listOf(MIDDLE_DEM_TILE))
                assertEquals(
                    DISTINCT_DEM_TILES,
                    decodes(ResourceClass.DEM_TILE),
                    "every render of one batch shares its decodes",
                )
            } finally {
                batch.close()
            }
        }
    }

    @Test
    fun aResourceOnlyOneDrawReadsIsNotHeld() = runTest(timeout = TIMEOUT) {
        // The batch shares a decode only where a decode is reused, which is what keeps this from
        // being a memory regression dressed as a saving: a corner of the block reads one DEM tile
        // no other tile of the batch reads, so that one is decoded by the draw that needs it and
        // nothing is retained for it. Rendering that corner again therefore decodes it again --
        // once, not the nine its neighbourhood would have cost before any of this existed.
        withRecordedDecodes(workers = 1) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(HILLSHADE_STYLE))
            val batch = rasterizer.prepareBatch(style, DEM_TILES, RenderOptions(OUTPUT_SIZE_PX))
            try {
                rasterizer.render(batch, listOf(CORNER_DEM_TILE))
                val afterFirst = decodes(ResourceClass.DEM_TILE)
                assertEquals(NEIGHBOURHOOD, afterFirst, "a first draw decodes the whole neighbourhood")
                rasterizer.render(batch, listOf(CORNER_DEM_TILE))
                assertEquals(
                    afterFirst + 1,
                    decodes(ResourceClass.DEM_TILE),
                    "only the one DEM tile no other draw reads is decoded again",
                )
            } finally {
                batch.close()
            }
        }
    }

    @Test
    fun aRasterSourceIsDecodedOncePerSourceTileTheBatchReadsRatherThanOncePerDraw() =
        runTest(timeout = TIMEOUT) {
            withRecordedDecodes(workers = 4) { rasterizer, decodes ->
                val style = rasterizer.prepare(StyleInput.InlineJson(RASTER_STYLE))

                // At the source's own zoom every output tile has a source tile of its own, so
                // there is nothing to share and nothing is held: nine draws, nine decodes.
                val native = rasterizer.prepareBatch(style, RASTER_TILES, RenderOptions(OUTPUT_SIZE_PX))
                try {
                    rasterizer.render(native)
                    assertEquals(RASTER_TILES.size, decodes(ResourceClass.RASTER_TILE))
                } finally {
                    native.close()
                }

                // Four zoom levels above it, all nine draw the same source tile, which is decoded
                // once for all of them.
                val overzoomed = rasterizer.prepareBatch(style, OVERZOOMED_TILES, RenderOptions(OUTPUT_SIZE_PX))
                try {
                    rasterizer.render(overzoomed)
                    assertEquals(RASTER_TILES.size + 1, decodes(ResourceClass.RASTER_TILE))
                } finally {
                    overzoomed.close()
                }
            }
        }

    @Test
    fun eachPreparedBatchOwnsItsOwnDecodes() = runTest(timeout = TIMEOUT) {
        // The batch is the scope because it is the widest one that closes (ADR 0033). Two batches
        // of one style therefore decode twice, and that is the deliberate cost of not holding
        // decoded pixels for the life of a rasterizer.
        withRecordedDecodes(workers = 1) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(RASTER_STYLE))
            repeat(3) {
                val batch = rasterizer.prepareBatch(style, OVERZOOMED_TILES, RenderOptions(OUTPUT_SIZE_PX))
                try {
                    rasterizer.render(batch)
                } finally {
                    batch.close()
                }
            }
            assertEquals(3, decodes(ResourceClass.RASTER_TILE))
            assertEquals(0, decodes(ResourceClass.DEM_TILE), "a raster style decodes no DEM tile")
        }
    }

    @Test
    fun sharingDecodesAcrossConcurrentWorkersDrawsTheSameBytesAsADecodePerDraw() =
        runTest(timeout = TIMEOUT) {
            // Arrangement A reproduces what the renderer did before source tiles were shared: one
            // tile per batch, one worker, so nothing is shared and every draw decodes for itself.
            // Arrangement B is what it does now.
            val perDraw = withRecordedDecodes(workers = 1) { rasterizer, decodes ->
                val style = rasterizer.prepare(StyleInput.InlineJson(HILLSHADE_STYLE))
                val rendered = DEM_TILES.map { tile ->
                    val batch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(OUTPUT_SIZE_PX))
                    try {
                        rasterizer.render(batch).tiles.single().pngBytes
                    } finally {
                        batch.close()
                    }
                }
                assertEquals(
                    DEM_TILES.size * NEIGHBOURHOOD,
                    decodes(ResourceClass.DEM_TILE),
                    "a batch of one tile shares nothing, so every neighbour is decoded for it",
                )
                rendered
            }

            val shared = withRecordedDecodes(workers = 4) { rasterizer, decodes ->
                val style = rasterizer.prepare(StyleInput.InlineJson(HILLSHADE_STYLE))
                val batch = rasterizer.prepareBatch(style, DEM_TILES, RenderOptions(OUTPUT_SIZE_PX))
                try {
                    val rendered = rasterizer.render(batch).tiles
                    assertEquals(DISTINCT_DEM_TILES, decodes(ResourceClass.DEM_TILE))
                    DEM_TILES.map { tile -> rendered.single { it.id == tile }.pngBytes }
                } finally {
                    batch.close()
                }
            }

            val blank = withRecordedDecodes(workers = 1) { rasterizer, _ ->
                val style = rasterizer.prepare(StyleInput.InlineJson(BLANK_STYLE))
                rasterizer.render(style, listOf(DEM_TILES.first()), RenderOptions(OUTPUT_SIZE_PX))
                    .tiles.single().pngBytes
            }

            DEM_TILES.forEachIndexed { index, tile ->
                assertTrue(
                    perDraw[index].contentEquals(shared[index]),
                    "$tile differs between a decode per draw and one shared decode",
                )
                assertFalse(
                    perDraw[index].contentEquals(blank),
                    "$tile drew no terrain, so comparing it proves nothing",
                )
            }
        }

    /**
     * A rasterizer whose source-tile decodes are counted, over a transport that serves a different
     * tile for every URL.
     *
     * Distinct bytes per URL is the point: the shared decode is keyed by content digest, so a
     * transport handing every request the same tile would collapse a whole session into one entry
     * and make every count in this file meaningless.
     */
    private suspend fun <T> withRecordedDecodes(
        workers: Int,
        block: suspend (BasemapRasterizer, (ResourceClass) -> Int) -> T,
    ): T {
        val raster = AtomicInt(0)
        val dem = AtomicInt(0)
        val tiles = mutableMapOf<String, ByteArray>()
        val rasterizer = createBasemapRasterizer(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    TransportResponse(200, tiles.getOrPut(request.url) { tilePng(request.url.hashCode()) })
                },
                rawResourceStore = InMemoryRawResourceStore(),
                executionPolicy = ExecutionPolicy(maxConcurrentMetatileWorkers = workers),
            ),
            sourceTileDecodeRecorderForTest = { resourceClass ->
                when (resourceClass) {
                    ResourceClass.DEM_TILE -> dem.fetchAndAdd(1)
                    else -> raster.fetchAndAdd(1)
                }
            },
        )
        try {
            return block(rasterizer) { resourceClass ->
                if (resourceClass == ResourceClass.DEM_TILE) dem.load() else raster.load()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private companion object {
        val TIMEOUT = 5.minutes
        const val OUTPUT_SIZE_PX = 256
        const val TILE_SIZE_PX = 64
        const val NEIGHBOURHOOD = 9

        /** Three by three, so every neighbourhood below overlaps its neighbours'. */
        fun block(z: Int, x0: Int, y0: Int): List<TileId> =
            (0 until 3).flatMap { dy -> (0 until 3).map { dx -> TileId(z, x0 + dx, y0 + dy) } }

        val DEM_TILES: List<TileId> = block(12, 1000, 1500)

        /** Every DEM this one reads is read by another tile of the block too. */
        val MIDDLE_DEM_TILE = TileId(12, 1001, 1501)

        /** Reads one DEM tile — the block's outer corner — that no other tile of the block reads. */
        val CORNER_DEM_TILE = TileId(12, 1000, 1500)
        val RASTER_TILES: List<TileId> = block(14, 4000, 6000)

        /** Four zoom levels above the source's maximum, so all nine share one source tile. */
        val OVERZOOMED_TILES: List<TileId> = block(16, 16_000, 24_000)

        /** The 3x3 block's own tiles plus a one-tile margin all round. */
        const val DISTINCT_DEM_TILES = 5 * 5

        const val HILLSHADE_STYLE =
            """{"version":8,"sources":{"dem":{"type":"raster-dem",""" +
                """"tiles":["https://dem.example.test/{z}/{x}/{y}.png"],"tileSize":$TILE_SIZE_PX,"maxzoom":12,""" +
                """"encoding":"mapbox"}},"layers":[""" +
                """{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},""" +
                """{"id":"terrain","type":"hillshade","source":"dem","paint":{"hillshade-exaggeration":1.5,""" +
                """"hillshade-accent-color":"#202020","hillshade-highlight-color":"#ffff00",""" +
                """"hillshade-shadow-color":"#0000ff"}}]}"""

        /**
         * `raster-opacity` keeps this off the raster pass-through path, which returns the source
         * bytes without decoding anything and would make every count here zero.
         */
        const val RASTER_STYLE =
            """{"version":8,"sources":{"sat":{"type":"raster",""" +
                """"tiles":["https://sat.example.test/{z}/{x}/{y}.png"],"tileSize":$TILE_SIZE_PX,"maxzoom":14}},""" +
                """"layers":[{"id":"sat","type":"raster","source":"sat","paint":{"raster-opacity":0.9}}]}"""

        const val BLANK_STYLE =
            """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}}]}"""

        /**
         * A tile whose pixels depend on [seed], so that no two source tiles in a session share
         * bytes, and whose height field has a gradient in both axes, so hillshade draws something
         * a neighbour read from the wrong tile would change.
         */
        fun tilePng(seed: Int): ByteArray {
            val rgba = ByteArray(TILE_SIZE_PX * TILE_SIZE_PX * 4)
            var state = seed or 1
            for (y in 0 until TILE_SIZE_PX) {
                for (x in 0 until TILE_SIZE_PX) {
                    state = state * 1_664_525 + 1_013_904_223
                    // Terrain RGB: -10000 + (r * 65536 + g * 256 + b) * 0.1
                    val height = 900 + x * 37 + y * 53 + ((state ushr 20) and 0xff)
                    val packed = (height + 100_000) * 10 / 10 + 100_000
                    val offset = (y * TILE_SIZE_PX + x) * 4
                    rgba[offset] = (packed ushr 16 and 0xff).toByte()
                    rgba[offset + 1] = (packed ushr 8 and 0xff).toByte()
                    rgba[offset + 2] = (packed and 0xff).toByte()
                    rgba[offset + 3] = 0xff.toByte()
                }
            }
            val info = ImageInfo(TILE_SIZE_PX, TILE_SIZE_PX, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            val image = Image.makeRaster(info, rgba, TILE_SIZE_PX * 4)
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia could not encode the test source tile")
            val bytes = data.bytes
            data.close()
            image.close()
            return bytes
        }
    }
}
