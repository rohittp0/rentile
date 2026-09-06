package com.rohittp.rentile

import com.rohittp.rentile.internal.createBasemapRasterizer
import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A sprite atlas is decoded once for a whole prepared batch, and that changes no pixels.
 *
 * Both halves need proving and neither proves the other. The cost is invisible from the output —
 * decoding the same PNG once or fifty-two times produces the same tiles, only slower — so the
 * decode count is observed through the rasterizer's test recorder, the way `RenderPriorityTest`
 * observes a lane. Sharing a decoded image across concurrently drawing workers, on the other hand,
 * is exactly the kind of change that shows up as pixels rather than as a count, so the tiles a
 * shared atlas draws are compared byte for byte against the tiles the previous arrangement drew:
 * one tile per batch, one worker, a fresh decode each time.
 */
@OptIn(ExperimentalAtomicApi::class)
class SpriteAtlasDecodeTest {
    @Test
    fun oneBatchDecodesItsAtlasOnceHoweverManyTilesItDraws() = runTest {
        withRecordedAtlasDecodes(workers = 4) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SPRITE_STYLE))
            val batch = rasterizer.prepareBatch(style, TILES)
            try {
                val rendered = rasterizer.render(batch).tiles
                assertEquals(TILES.size, rendered.size)
                assertEquals(1, decodes(), "one prepared batch must decode its sprite atlas once")
            } finally {
                batch.close()
            }
        }
    }

    @Test
    fun rerenderingOneBatchNeverDecodesItsAtlasAgain() = runTest {
        // The decode has to survive between render calls, not merely within one: a consumer that
        // renders a prepared batch tile by tile so it can mark some of them URGENT makes exactly
        // this shape of call, and it is the shape a per-render hoist would leave unfixed.
        withRecordedAtlasDecodes(workers = 2) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SPRITE_STYLE))
            val batch = rasterizer.prepareBatch(style, TILES)
            try {
                TILES.forEach { tile -> rasterizer.render(batch, listOf(tile)) }
                rasterizer.render(batch, listOf(TILES.first()), priority = RenderPriority.URGENT)
                rasterizer.renderRaw(batch, listOf(TILES.first()))
                assertEquals(1, decodes(), "every render of one batch shares one decoded atlas")
            } finally {
                batch.close()
            }
        }
    }

    @Test
    fun eachPreparedBatchOwnsItsOwnAtlasAndAStyleWithoutOneDecodesNothing() = runTest {
        // The batch is the scope because it is the widest one that closes. Two batches of one
        // style therefore decode twice, and that is the deliberate cost of not holding decoded
        // pixels for the life of a rasterizer.
        withRecordedAtlasDecodes(workers = 1) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SPRITE_STYLE))
            repeat(3) {
                val batch = rasterizer.prepareBatch(style, TILES)
                try {
                    rasterizer.render(batch)
                } finally {
                    batch.close()
                }
            }
            assertEquals(3, decodes())

            val spriteless = rasterizer.prepare(StyleInput.InlineJson(BLANK_STYLE))
            rasterizer.render(spriteless, TILES, RenderOptions(OUTPUT_SIZE_PX))
            assertEquals(3, decodes(), "a style with no sprite must decode no atlas")
        }
    }

    @Test
    fun sharingOneAtlasAcrossConcurrentWorkersDrawsTheSameBytesAsADecodePerTile() = runTest {
        // Arrangement A reproduces what the renderer did before the atlas was hoisted: one tile
        // per batch on one worker, so every tile decodes the sheet for itself and nothing is
        // shared. Arrangement B is what it does now.
        val perTile = withRecordedAtlasDecodes(workers = 1) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SPRITE_STYLE))
            val rendered = TILES.map { tile ->
                val batch = rasterizer.prepareBatch(style, listOf(tile))
                try {
                    rasterizer.render(batch).tiles.single().pngBytes
                } finally {
                    batch.close()
                }
            }
            assertEquals(TILES.size, decodes())
            rendered
        }

        val shared = withRecordedAtlasDecodes(workers = 4) { rasterizer, decodes ->
            val style = rasterizer.prepare(StyleInput.InlineJson(SPRITE_STYLE))
            val batch = rasterizer.prepareBatch(style, TILES)
            try {
                val rendered = rasterizer.render(batch).tiles
                assertEquals(1, decodes())
                TILES.map { tile -> rendered.single { it.id == tile }.pngBytes }
            } finally {
                batch.close()
            }
        }

        val blank = withRecordedAtlasDecodes(workers = 1) { rasterizer, _ ->
            val style = rasterizer.prepare(StyleInput.InlineJson(BLANK_STYLE))
            rasterizer.render(style, listOf(TILES.first()), RenderOptions(OUTPUT_SIZE_PX)).tiles.single().pngBytes
        }

        TILES.forEachIndexed { index, tile ->
            assertTrue(
                perTile[index].contentEquals(shared[index]),
                "$tile differs between a decode per tile and one shared decode",
            )
            assertFalse(
                perTile[index].contentEquals(blank),
                "$tile drew no sprite ink, so comparing it proves nothing",
            )
        }
    }

    /**
     * A rasterizer whose sprite-atlas decodes are counted, over a transport that serves the same
     * multi-coloured sheet and vector tile for every request.
     */
    private suspend fun <T> withRecordedAtlasDecodes(
        workers: Int,
        block: suspend (BasemapRasterizer, () -> Int) -> T,
    ): T {
        val decodes = AtomicInt(0)
        val rasterizer = createBasemapRasterizer(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    when (request.resourceClass) {
                        ResourceClass.SPRITE_JSON -> TransportResponse(200, SPRITE_JSON.encodeToByteArray())
                        ResourceClass.SPRITE_IMAGE -> TransportResponse(200, ATLAS_PNG)
                        ResourceClass.VECTOR_TILE -> TransportResponse(200, VECTOR_TILE)
                        else -> error("Unexpected resource class ${request.resourceClass}")
                    }
                },
                rawResourceStore = InMemoryRawResourceStore(),
                executionPolicy = ExecutionPolicy(maxConcurrentMetatileWorkers = workers),
            ),
            spriteAtlasDecodeRecorderForTest = { decodes.fetchAndAdd(1) },
        )
        try {
            return block(rasterizer) { decodes.load() }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private companion object {
        const val OUTPUT_SIZE_PX = 256
        val TILES: List<TileId> = listOf(
            TileId(2, 0, 0), TileId(2, 1, 0), TileId(2, 2, 0), TileId(2, 3, 0),
            TileId(2, 0, 1), TileId(2, 1, 1), TileId(2, 2, 1), TileId(2, 3, 1),
        )

        /**
         * Four differently coloured 8 px quadrants, so a sprite read from the wrong offset - or
         * from a sheet decoded into the wrong colour type - changes the tile rather than hiding in
         * a flat fill.
         */
        val ATLAS_PNG: ByteArray = run {
            val surface = Surface.makeRasterN32Premul(16, 16)
            val paint = Paint()
            val quadrants = listOf(
                0 to 0 to Color.makeARGB(255, 220, 40, 40),
                8 to 0 to Color.makeARGB(255, 40, 200, 60),
                0 to 8 to Color.makeARGB(255, 40, 80, 230),
                8 to 8 to Color.makeARGB(255, 240, 210, 30),
            )
            for ((origin, color) in quadrants) {
                paint.color = color
                surface.canvas.drawRect(
                    Rect.makeXYWH(origin.first.toFloat(), origin.second.toFloat(), 8f, 8f),
                    paint,
                )
            }
            val image = surface.makeImageSnapshot()
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia could not encode the test sprite sheet")
            val bytes = data.bytes
            data.close()
            image.close()
            paint.close()
            surface.close()
            bytes
        }

        const val SPRITE_JSON =
            """{"tile-pattern":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":false},""" +
                """"land-pattern":{"x":8,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":false},""" +
                """"marker":{"x":0,"y":8,"width":8,"height":8,"pixelRatio":1,"sdf":false}}"""

        /**
         * Every way the draw path reaches the sheet at once: a background pattern, a fill pattern
         * and an icon. All three go through one `SpriteRenderContext`, which is what now borrows
         * the batch's decoded image instead of making its own.
         */
        const val SPRITE_STYLE =
            """{"version":8,"sprite":"https://sprite.example.test/atlas",""" +
                """"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},""" +
                """"layers":[""" +
                """{"id":"bg","type":"background","paint":{"background-pattern":"tile-pattern"}},""" +
                """{"id":"land","type":"fill","source":"v","source-layer":"land","paint":{"fill-pattern":"land-pattern"}},""" +
                """{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-size":3}}]}"""

        const val BLANK_STYLE =
            """{"version":8,"layers":[{"id":"bg","type":"background","paint":{"background-color":"#101010"}}]}"""

        const val EXTENT = 4096

        val VECTOR_TILE: ByteArray = Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = "land",
                        features = listOf(
                            Tile.Feature(
                                type = Tile.GeomType.POLYGON,
                                geometry = polygon(
                                    listOf(
                                        EXTENT / 8 to EXTENT / 8,
                                        EXTENT * 7 / 8 to EXTENT / 8,
                                        EXTENT * 7 / 8 to EXTENT * 5 / 8,
                                        EXTENT / 8 to EXTENT * 5 / 8,
                                    ),
                                ),
                            ),
                        ),
                        extent = EXTENT,
                    ),
                    Tile.Layer(
                        version = 2,
                        name = "poi",
                        features = listOf(
                            Tile.Feature(
                                type = Tile.GeomType.POINT,
                                geometry = listOf(command(1, 1), zigZag(EXTENT / 2), zigZag(EXTENT / 2)),
                            ),
                        ),
                        extent = EXTENT,
                    ),
                ),
            ),
        )

        private fun polygon(points: List<Pair<Int, Int>>): List<Int> = buildList {
            val first = points.first()
            add(command(1, 1))
            add(zigZag(first.first))
            add(zigZag(first.second))
            var cursor = first
            add(command(2, points.size - 1))
            for (point in points.drop(1)) {
                add(zigZag(point.first - cursor.first))
                add(zigZag(point.second - cursor.second))
                cursor = point
            }
            add(command(7, 1))
        }

        private fun command(id: Int, count: Int): Int = (count shl 3) or id

        private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)
    }
}
