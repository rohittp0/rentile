package com.rohittp.rentile

import com.rohittp.rentile.internal.renderSyntheticPng
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TerrainDemNoContentTest {
    @Test
    fun empty204DemIsTypedAbsenceBeforeDecodeAndNeverStored() = runTest {
        val store = RecordingStore()
        val rasterizer = rasterizer(store) { TransportResponse(204, byteArrayOf()) }
        try {
            val style = rasterizer.prepare(terrainStyle())
            val failure = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.acquireTerrainTiles(style, listOf(tile))
            }
            assertEquals(ResourceClass.DEM_TILE, failure.resourceClass)
            assertEquals(204, failure.statusCode)
            assertEquals(PipelineStage.RESOURCE_ACQUISITION, failure.stage)
            assertEquals(listOf(tile), failure.affectedTiles)
            assertTrue(failure.sanitizedResourceId.matches(Regex("[a-f0-9]{64}")))
            assertEquals(0, store.writes)
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun warming204DemDoesNotStoreAnEmptyImageOrPoisonLaterAcquisition() = runTest {
        val store = RecordingStore()
        var requests = 0
        var warming = true
        val requestsMutex = Mutex()
        val rasterizer = rasterizer(store) {
            requestsMutex.withLock {
                requests++
                if (warming) TransportResponse(204, byteArrayOf())
                else TransportResponse(200, renderSyntheticPng(256))
            }
        }
        try {
            val style = rasterizer.prepare(terrainStyle())
            // At the northern edge, hillshade warms three wrapped columns and two valid rows.
            assertEquals(6, rasterizer.warmRawResources(style, listOf(tile)).failed)
            assertEquals(0, store.writes)
            assertEquals(6, requests)
            warming = false
            val dem = rasterizer.acquireTerrainTiles(style, listOf(tile)).single()
            assertEquals(tile, dem.requestedTile)
            assertEquals(7, requests)
            assertEquals(1, store.writes)
            assertEquals(256, dem.texels.width)
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun empty200AndCorrupt200DemRemainDecodeFailures() = runTest {
        for (body in listOf(byteArrayOf(), byteArrayOf(1, 2, 3))) {
            val store = RecordingStore()
            val rasterizer = rasterizer(store) { TransportResponse(200, body) }
            try {
                val style = rasterizer.prepare(terrainStyle())
                assertFailsWith<ResourceDecodeException> {
                    rasterizer.acquireTerrainTiles(style, listOf(tile))
                }
                assertEquals(0, store.writes)
            } finally { rasterizer.close(); rasterizer.awaitClosed() }
        }
    }

    @Test
    fun corruptNonempty204IsNotClassifiedAsNoContent() = runTest {
        val store = RecordingStore()
        val rasterizer = rasterizer(store) { TransportResponse(204, byteArrayOf(1, 2, 3)) }
        try {
            val style = rasterizer.prepare(terrainStyle())
            assertFailsWith<ResourceDecodeException> { rasterizer.acquireTerrainTiles(style, listOf(tile)) }
            assertEquals(0, store.writes)
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun nonDem204RemainsDecodeFailure() = runTest {
        val store = RecordingStore()
        val rasterizer = rasterizer(store) { TransportResponse(204, byteArrayOf()) }
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(
                """{"version":8,"sources":{"r":{"type":"raster","tiles":["https://fixture.invalid/{z}/{x}/{y}.png"]}},"layers":[{"id":"r","type":"raster","source":"r"}]}""",
            ))
            assertFailsWith<ResourceDecodeException> { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            assertEquals(0, store.writes)
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun mixed204AndCorruptDemBatchRetainsBothTypedFailures() = runTest {
        val rasterizer = rasterizer(RecordingStore()) { request ->
            if (request.url.endsWith("/0/0.png")) TransportResponse(204, byteArrayOf())
            else TransportResponse(200, byteArrayOf(1, 2, 3))
        }
        try {
            val style = rasterizer.prepare(terrainStyle())
            val failure = assertFailsWith<BatchRenderException> {
                rasterizer.acquireTerrainTiles(style, listOf(TileId(2, 0, 0), TileId(2, 1, 0)))
            }
            val failures = listOf(failure.primaryFailure) + failure.concurrentFailures
            assertEquals(2, failures.size)
            assertEquals(204, assertIs<ResourceAcquisitionException>(failures[0]).statusCode)
            assertIs<ResourceDecodeException>(failures[1])
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun sharedSource204NamesEveryRequestedOutputTile() = runTest {
        val joined = CompletableDeferred<Unit>()
        var requests = 0
        val rasterizer = Rentile.create(RentileConfiguration(
            transport = ResourceTransport {
                requests++
                joined.await()
                TransportResponse(204, byteArrayOf())
            },
            rawResourceStore = RecordingStore(),
            metricsSink = MetricsSink { if (it.name == MetricName.SINGLE_FLIGHT_JOIN) joined.complete(Unit) },
        ))
        try {
            val style = rasterizer.prepare(terrainStyle())
            val requested = listOf(TileId(3, 0, 0), TileId(3, 1, 0))
            val failure = assertFailsWith<BatchRenderException> { rasterizer.acquireTerrainTiles(style, requested) }
            assertEquals(1, requests)
            val failures = listOf(failure.primaryFailure) + failure.concurrentFailures
            assertEquals(requested, failures.map { assertIs<ResourceAcquisitionException>(it).affectedTiles.single() })
            assertTrue(failures.all { (it as ResourceAcquisitionException).statusCode == 204 })
        } finally { joined.complete(Unit); rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test
    fun demandAndWarmCancellationStillPropagate() = runTest {
        val rasterizer = rasterizer(RecordingStore()) { throw CancellationException("fixture") }
        try {
            val style = rasterizer.prepare(terrainStyle())
            assertFailsWith<CancellationException> { rasterizer.acquireTerrainTiles(style, listOf(tile)) }
            assertFailsWith<CancellationException> { rasterizer.warmRawResources(style, listOf(tile)) }
        } finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    private fun rasterizer(store: RawResourceStore, response: suspend (TransportRequest) -> TransportResponse): BasemapRasterizer =
        Rentile.create(RentileConfiguration(transport = ResourceTransport(response), rawResourceStore = store))

    private fun terrainStyle(): StyleInput.InlineJson = StyleInput.InlineJson(
        """{"version":8,"sources":{"d":{"type":"raster-dem","tiles":["https://fixture.invalid/{z}/{x}/{y}.png"],"encoding":"terrarium","tileSize":256,"maxzoom":2}},"terrain":{"source":"d"},"layers":[{"id":"hillshade","type":"hillshade","source":"d"}]}""",
    )

    private val tile = TileId(2, 0, 0)

    private class RecordingStore : RawResourceStore {
        private val delegate = InMemoryRawResourceStore()
        var writes = 0
        override suspend fun read(key: RawResourceKey): StoredRawResource? = delegate.read(key)
        override suspend fun write(key: RawResourceKey, resource: StoredRawResource) { writes++; delegate.write(key, resource) }
        override suspend fun remove(key: RawResourceKey) = delegate.remove(key)
    }
}
