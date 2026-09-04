package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import com.rohittp.rentile.internal.renderSyntheticPng
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A prefetch and an acquisition of one raw resource are one exchange, whichever starts first.
 *
 * Warming and acquiring used to be single-flighted apart: two coroutines could hold two responses
 * for the same URL in the air at once, which is exactly the collision a cursor-ordered prefetch
 * running alongside playback produces -- the prefetch reaches the tile the renderer is about to
 * ask for. The wasted request is charged to the same connection budget the prefetch exists to
 * spend well.
 */
class WarmSingleFlightTest {
    @Test
    fun anAcquisitionJoinsARasterPrefetchAlreadyInFlight() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val requests = mutableListOf<String>()
        val requestsMutex = Mutex()
        val warmInFlight = CompletableDeferred<Unit>()
        val releaseWarm = CompletableDeferred<Unit>()
        val acquisitionJoined = CompletableDeferred<Unit>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requestsMutex.withLock { requests += request.url }
                    warmInFlight.complete(Unit)
                    releaseWarm.await()
                    TransportResponse(200, sourcePng)
                },
                rawResourceStore = InMemoryRawResourceStore(),
                metricsSink = MetricsSink { metric ->
                    if (metric.name == MetricName.SINGLE_FLIGHT_JOIN &&
                        metric.resourceClass == ResourceClass.RASTER_TILE
                    ) {
                        acquisitionJoined.complete(Unit)
                    }
                },
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle())
            val tile = TileId(1, 0, 0)

            val warm = async { rasterizer.warmRawResources(style, listOf(tile)) }
            warmInFlight.await()
            val batch = async { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            // The acquisition reached the prefetch's flight rather than the transport: releasing
            // before this point would let a plain cache hit pass for a join.
            acquisitionJoined.await()
            releaseWarm.complete(Unit)

            assertEquals(1, warm.await().fetched)
            val prepared = batch.await()
            try {
                assertTrue(rasterizer.render(prepared).tiles.single().pngBytes.isNotEmpty())
            } finally {
                prepared.close()
            }
            assertEquals(1, requestsMutex.withLock { requests.size }, "one exchange, not two")
        } finally {
            releaseWarm.complete(Unit)
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anAcquisitionJoinsAVectorPrefetchAlreadyInFlight() = runTest {
        val vectorTile = roadsVectorTile()
        val requests = mutableListOf<String>()
        val requestsMutex = Mutex()
        val warmInFlight = CompletableDeferred<Unit>()
        val releaseWarm = CompletableDeferred<Unit>()
        val acquisitionJoined = CompletableDeferred<Unit>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requestsMutex.withLock { requests += request.url }
                    warmInFlight.complete(Unit)
                    releaseWarm.await()
                    TransportResponse(200, vectorTile)
                },
                rawResourceStore = InMemoryRawResourceStore(),
                metricsSink = MetricsSink { metric ->
                    if (metric.name == MetricName.SINGLE_FLIGHT_JOIN &&
                        metric.resourceClass == ResourceClass.VECTOR_TILE
                    ) {
                        acquisitionJoined.complete(Unit)
                    }
                },
            ),
        )
        try {
            val style = rasterizer.prepare(vectorStyle())
            val tile = TileId(1, 0, 0)

            val warm = async { rasterizer.warmRawResources(style, listOf(tile)) }
            warmInFlight.await()
            val batch = async { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            acquisitionJoined.await()
            releaseWarm.complete(Unit)

            assertEquals(1, warm.await().fetched)
            val prepared = batch.await()
            try {
                assertTrue(rasterizer.render(prepared).tiles.single().pngBytes.isNotEmpty())
            } finally {
                prepared.close()
            }
            assertEquals(1, requestsMutex.withLock { requests.size }, "one exchange, not two")
        } finally {
            releaseWarm.complete(Unit)
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aPrefetchDoesNotRefetchWhatAnAcquisitionIsAlreadyFetching() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val requests = mutableListOf<String>()
        val requestsMutex = Mutex()
        val acquisitionInFlight = CompletableDeferred<Unit>()
        val releaseAcquisition = CompletableDeferred<Unit>()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requestsMutex.withLock { requests += request.url }
                    acquisitionInFlight.complete(Unit)
                    releaseAcquisition.await()
                    TransportResponse(200, sourcePng)
                },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle())
            val tile = TileId(1, 0, 0)

            val batch = async { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            acquisitionInFlight.await()
            // The prefetch must return without waiting: it holds an exchange permit while it runs,
            // so waiting here for work that needs a permit of its own could park the whole gate.
            val summary = rasterizer.warmRawResources(style, listOf(tile))
            assertEquals(0, summary.failed)

            releaseAcquisition.complete(Unit)
            val prepared = batch.await()
            try {
                assertTrue(rasterizer.render(prepared).tiles.single().pngBytes.isNotEmpty())
            } finally {
                prepared.close()
            }
            assertEquals(1, requestsMutex.withLock { requests.size }, "one exchange, not two")
        } finally {
            releaseAcquisition.complete(Unit)
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aPrefetchOfAResourceNobodyElseWantsStillFetchesIt() = runTest {
        val sourcePng = renderSyntheticPng(256)
        var requests = 0
        val requestsMutex = Mutex()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    requestsMutex.withLock { requests++ }
                    TransportResponse(200, sourcePng)
                },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle())

            val summary = rasterizer.warmRawResources(style, listOf(TileId(1, 0, 0)))

            assertEquals(1, summary.fetched)
            assertEquals(0, summary.alreadyCached)
            assertEquals(0, summary.failed)
            assertEquals(1, requestsMutex.withLock { requests })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun rasterStyle(): StyleInput.InlineJson = StyleInput.InlineJson(
        """{"version":8,"sources":{"tiles":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"raster","type":"raster","source":"tiles"}]}""",
    )

    private fun vectorStyle(): StyleInput.InlineJson = StyleInput.InlineJson(
        """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"roads","type":"line","source":"v","source-layer":"roads","paint":{"line-color":"#ff0000","line-width":4}}]}""",
    )

    /** One "roads" layer holding a single short linestring across the tile's centre. */
    private fun roadsVectorTile(): ByteArray = Tile.ADAPTER.encode(
        Tile(
            layers = listOf(
                Tile.Layer(
                    version = 2,
                    name = "roads",
                    features = listOf(
                        Tile.Feature(
                            type = Tile.GeomType.LINESTRING,
                            geometry = listOf(
                                moveTo(1), zigZag(1024), zigZag(2048),
                                lineTo(1), zigZag(2048), zigZag(0),
                            ),
                        ),
                    ),
                    extent = 4096,
                ),
            ),
        ),
    )

    private fun moveTo(count: Int): Int = (count shl 3) or 1

    private fun lineTo(count: Int): Int = (count shl 3) or 2

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)
}
