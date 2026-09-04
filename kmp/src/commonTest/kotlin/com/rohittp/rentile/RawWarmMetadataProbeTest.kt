package com.rohittp.rentile

import com.rohittp.rentile.internal.renderSyntheticPng
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Is this already cached?" is a question about the entry, not about its bytes.
 *
 * Warming asked it through [RawResourceStore.read], which hands back the whole payload and is then
 * re-hashed to check its integrity -- for every resource of every warmed frame, on a store that is
 * disk-backed in every production host. Warming a session that is already cached therefore read and
 * hashed the session, which is exactly the work a prefetch exists to have avoided.
 */
class RawWarmMetadataProbeTest {
    @Test
    fun warmingAlreadyCachedResourcesReadsNoPayloadAndHashesNothing() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val store = CountingRawResourceStore()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { TransportResponse(200, sourcePng) },
                rawResourceStore = store,
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle())
            val tiles = listOf(TileId(2, 0, 0), TileId(2, 1, 0), TileId(2, 0, 1), TileId(2, 1, 1))

            assertEquals(tiles.size, rasterizer.warmRawResources(style, tiles).fetched)
            store.resetCounts()
            val second = rasterizer.warmRawResources(style, tiles)

            assertEquals(tiles.size, second.alreadyCached)
            assertEquals(0, second.fetched)
            assertEquals(tiles.size, store.metadataProbes(), "one header probe per resource")
            // No payload left the store, so nothing was there to hash: sha256 over the entry is
            // only ever computed over bytes a read handed back.
            assertEquals(0, store.reads(), "the already-cached probe must not read payloads")
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aStoreThatOnlyImplementsReadStillAnswersTheProbe() = runTest {
        // The probe is a new member with a default, so a host store written against the old
        // interface keeps working -- it just keeps paying for the payload.
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { TransportResponse(200, sourcePng) },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle())
            val tiles = listOf(TileId(2, 0, 0))

            assertEquals(1, rasterizer.warmRawResources(style, tiles).fetched)
            assertEquals(1, rasterizer.warmRawResources(style, tiles).alreadyCached)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun rasterStyle(): StyleInput.InlineJson = StyleInput.InlineJson(
        """{"version":8,"sources":{"tiles":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"raster","type":"raster","source":"tiles"}]}""",
    )
}

/** Counts payload reads separately from header probes. */
private class CountingRawResourceStore : RawResourceStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<RawResourceKey, StoredRawResource>()
    private var reads = 0
    private var metadataProbes = 0

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock {
        reads += 1
        entries[key]
    }

    override suspend fun metadata(key: RawResourceKey): RawResourceMetadata? = mutex.withLock {
        metadataProbes += 1
        entries[key]?.metadata
    }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        mutex.withLock { entries[key] = resource }
    }

    override suspend fun remove(key: RawResourceKey) {
        mutex.withLock { entries.remove(key) }
    }

    suspend fun reads(): Int = mutex.withLock { reads }

    suspend fun metadataProbes(): Int = mutex.withLock { metadataProbes }

    suspend fun resetCounts() {
        mutex.withLock {
            reads = 0
            metadataProbes = 0
        }
    }
}
