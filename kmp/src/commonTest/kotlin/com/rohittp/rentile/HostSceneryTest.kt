package com.rohittp.rentile

import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.mvt.Tile
import com.rohittp.rentile.internal.mvt.CompactExtrusionDecoder
import com.rohittp.rentile.internal.mvt.ExtrusionBudget
import com.rohittp.rentile.internal.mvt.MvtDecodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HostSceneryTest {
    private val tile = TileId(16, 100, 100)
    private val queries = listOf(
        SceneryLayerQuery("tree", SceneryGeometryType.POINT, setOf("class", "level", "indoor")),
        SceneryLayerQuery("pathway", SceneryGeometryType.LINE, setOf("access")),
        SceneryLayerQuery("pedestrian", SceneryGeometryType.POLYGON, setOf("access")),
    )
    private fun fixture(points: Int = 3): ByteArray {
        val pointAndLine = Tile.ADAPTER.decode(LabelFixtures.vectorTile(linkedMapOf(
            "tree" to listOf(LabelFixtures.Feature(mapOf("class" to "tree", "level" to 0, "indoor" to false, "name" to "omit"),
                List(points) { it * 5 to it * 3 }, id = -1L)),
            "pathway" to listOf(LabelFixtures.Feature(mapOf("access" to "yes"), listOf(0 to 0, 50 to 100), line = true)),
        ))).layers
        val polygon = Tile.ADAPTER.decode(ExtrusionFixtures.tile()).layers.single().copy(name = "pedestrian")
        return Tile.ADAPTER.encode(Tile(layers = pointAndLine + polygon))
    }
    private suspend fun <T> withRasterizer(bytes: ByteArray = fixture(), block: suspend (BasemapRasterizer, LabelFixtures.Transport, PreparedStyle) -> T): T {
        val transport = LabelFixtures.Transport(bytes)
        val r = LabelFixtures.rasterizer(transport)
        try {
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), CompatibilityPolicy.RentileV1HostSymbolsAndExtrusions)
            return block(r, transport, s)
        } finally { r.close(); r.awaitClosed() }
    }

    @Test fun multipointsOpenLinesAndPolygonHolesKeepCanonicalCoordinates() = runTest {
        withRasterizer { r, _, s ->
            val b = r.acquireSceneryCandidates(s, listOf(tile), queries)
            val tree = b.candidates.single { it.sourceLayer == "tree" }
            assertEquals(TileId(15, 50, 50), tree.sourceTile)
            assertEquals(-1L, tree.featureId)
            assertEquals(3, tree.geometry.vertexCount)
            assertEquals(1, tree.geometry.partCount)
            assertEquals(0, tree.geometry.polygonCount)
            assertEquals(10, tree.geometry.x(2)); assertEquals(6, tree.geometry.y(2))
            assertEquals(0, tree.geometry.partStart(0)); assertEquals(3, tree.geometry.partEnd(0))
            assertEquals("tree", tree.stringProperty("class"))
            assertEquals(0.0, tree.numberProperty("level")); assertEquals(false, tree.booleanProperty("indoor"))
            assertNull(tree.propertyKind("name")); assertNull(tree.stringProperty("level"))
            assertEquals(SceneryPropertyKind.NUMBER, tree.propertyKind("level"))
            assertNull(tree.propertyKind("missing"))
            val line = b.candidates.single { it.sourceLayer == "pathway" }.geometry
            assertEquals(2, line.vertexCount); assertEquals(1, line.partCount); assertEquals(100, line.y(1))
            val polygon = b.candidates.single { it.sourceLayer == "pedestrian" }.geometry
            assertEquals(12, polygon.vertexCount); assertEquals(3, polygon.partCount); assertEquals(2, polygon.polygonCount)
            assertEquals(2, polygon.polygonEnd(0)); assertEquals(-10, polygon.x(0))
            assertTrue(b.estimatedRetainedBytes >= b.candidates.sumOf { it.geometry.primitiveByteCount })
            assertFailsWith<IllegalArgumentException> { tree.geometry.x(3) }
        }
    }

    @Test fun overzoomChildrenDeduplicateAndQueryOrderingKeepsIdentity() = runTest {
        withRasterizer { r, t, s ->
            val children = listOf(tile, tile.copy(x = 101), tile.copy(y = 101), tile.copy(x = 101, y = 101))
            val a = r.acquireSceneryCandidates(s, children, queries)
            val b = r.acquireSceneryCandidates(s, children.reversed() + tile, queries.reversed())
            assertEquals(3, a.candidates.size); assertEquals(a.contentKey, b.contentKey)
            assertEquals(1, t.requestedClasses().count { it == ResourceClass.VECTOR_TILE })
            assertNotEquals(a.contentKey, r.acquireSceneryCandidates(s, children, queries.dropLast(1)).contentKey)
            val wrong = r.acquireSceneryCandidates(s, children, listOf(SceneryLayerQuery("tree", SceneryGeometryType.LINE)))
            assertTrue(wrong.candidates.isEmpty())
        }
    }

    @Test fun hugeMultipointCountsCoordinatesWithinOneFeatureBeforeAllocation() {
        val bytes = fixture(629)
        val budget = ExtrusionBudget(ExtrusionLimits())
        val failure = assertFailsWith<MvtDecodingException> {
            CompactExtrusionDecoder(ResourceLimits(maxMvtCoordinates = 100), budget) {}.decodeScenery(bytes, queries) { error("must reject before emitting") }
        }
        assertEquals("maxMvtCoordinates", failure.limitName)
        assertEquals(0, budget.retained)
    }

    @Test fun boundedAcquisitionDoesNotDestroyValidRawCache() = runTest {
        withRasterizer { r, t, s ->
            r.acquireSceneryCandidates(s, listOf(tile), queries)
            for (limits in listOf(SceneryLimits(maxEncodedTileBytes = 1), SceneryLimits(maxWorkingBytes = 1), SceneryLimits(maxRetainedBytes = 1))) {
                assertFailsWith<SafetyLimitException> { r.acquireSceneryCandidates(s, listOf(tile), queries, limits, ResourceAccessMode.CACHE_ONLY) }
            }
            assertEquals(3, r.acquireSceneryCandidates(s, listOf(tile), queries, resourceAccess = ResourceAccessMode.CACHE_ONLY).candidates.size)
            assertEquals(1, t.requestedClasses().count { it == ResourceClass.VECTOR_TILE })
            assertEquals("maxRequestedTiles", assertFailsWith<SafetyLimitException> {
                r.acquireSceneryCandidates(s, listOf(tile, tile), queries, SceneryLimits(maxRequestedTiles = 1))
            }.limitName)
        }
    }

    @Test fun cacheOnlyNeverFetchesAndReloadChangesContentIdentity() = runTest {
        var bytes = fixture()
        var fetches = 0
        val r = LabelFixtures.rasterizer(ResourceTransport { fetches++; TransportResponse(200, bytes) })
        try {
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), CompatibilityPolicy.RentileV1HostExtrusions)
            assertFailsWith<ResourceAcquisitionException> { r.acquireSceneryCandidates(s, listOf(tile), queries, resourceAccess = ResourceAccessMode.CACHE_ONLY) }
            assertEquals(0, fetches)
            val a = r.acquireSceneryCandidates(s, listOf(tile), queries)
            bytes = fixture(4)
            val cached = r.acquireSceneryCandidates(s, listOf(tile), queries, resourceAccess = ResourceAccessMode.CACHE_ONLY)
            assertEquals(a.contentKey, cached.contentKey)
            val b = r.acquireSceneryCandidates(s, listOf(tile), queries, resourceAccess = ResourceAccessMode.RELOAD)
            assertNotEquals(a.contentKey, b.contentKey); assertEquals(4, b.candidates.first().geometry.vertexCount)
            assertEquals(2, fetches)
        } finally { r.close(); r.awaitClosed() }
    }

    @Test fun emptyAndUnknownQueriesDoNotInventCandidates() = runTest {
        withRasterizer { r, t, s ->
            assertTrue(r.acquireSceneryCandidates(s, listOf(tile), emptyList()).candidates.isEmpty())
            assertEquals(0, t.requestedClasses().count { it == ResourceClass.VECTOR_TILE })
            assertTrue(r.acquireSceneryCandidates(s, listOf(tile), listOf(SceneryLayerQuery("absent", SceneryGeometryType.POINT))).candidates.isEmpty())
            assertFailsWith<IllegalArgumentException> { r.acquireSceneryCandidates(s, listOf(tile), queries + queries.first()) }
        }
    }

    @Test fun malformedCommandsAndCancellationRejectPartialAssembly() {
        val tree = Tile.ADAPTER.decode(fixture()).layers.first()
        for (commands in listOf(listOf(9, 0), listOf(10, 0, 0), listOf(15))) {
            val bytes = Tile.ADAPTER.encode(Tile(layers = listOf(tree.copy(features = listOf(tree.features.single().copy(geometry = commands))))))
            assertFailsWith<MvtDecodingException> {
                CompactExtrusionDecoder(ResourceLimits(), ExtrusionBudget(ExtrusionLimits())) {}.decodeScenery(bytes, queries) { }
            }
        }
        var calls = 0
        assertFailsWith<CancellationException> {
            CompactExtrusionDecoder(ResourceLimits(), ExtrusionBudget(ExtrusionLimits())) { if (++calls == 2) throw CancellationException("cancel") }
                .decodeScenery(fixture(1000), queries) { }
        }
        assertEquals(2, calls)
    }

    @Test fun malformedCachedPartialSceneryIsRolledBackBeforeRecovery() = runTest {
        val valid = fixture()
        val original = Tile.ADAPTER.decode(valid)
        val tree = original.layers.first()
        val malformed = Tile.ADAPTER.encode(original.copy(layers = listOf(tree.copy(features = listOf(
            tree.features.single(), tree.features.single().copy(geometry = listOf(9, 0)),
        ))) + original.layers.drop(1)))
        val store = InMemoryRawResourceStore()
        val url = "https://tiles.example.test/15/50/50.pbf"
        store.write(RawResourceKey(url.sha256Hex(), ResourceClass.VECTOR_TILE), StoredRawResource(
            malformed, malformed.sha256Hex(), RawResourceMetadata(storedAtEpochMillis = 0),
        ))
        var fetches = 0
        val r = Rentile.create(RentileConfiguration(ResourceTransport { fetches++; TransportResponse(200, valid) }, store))
        try {
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), CompatibilityPolicy.RentileV1HostExtrusions)
            val recovered = r.acquireSceneryCandidates(s, listOf(tile), queries)
            val cached = r.acquireSceneryCandidates(s, listOf(tile), queries, resourceAccess = ResourceAccessMode.CACHE_ONLY)
            assertEquals(3, recovered.candidates.size)
            assertEquals(cached.contentKey, recovered.contentKey)
            assertEquals(cached.estimatedRetainedBytes, recovered.estimatedRetainedBytes)
            assertEquals(1, fetches)
        } finally { r.close(); r.awaitClosed() }
    }

    @Test fun closedAndForeignRasterizersRejectSceneryOperations() = runTest {
        withRasterizer { r, _, s ->
            val other = LabelFixtures.rasterizer(LabelFixtures.Transport(fixture()))
            try { assertFailsWith<ForeignPreparedStyleException> { other.acquireSceneryCandidates(s, listOf(tile), queries) } }
            finally { other.close(); other.awaitClosed() }
            r.close()
            assertFailsWith<RasterizerClosedException> { r.acquireSceneryCandidates(s, listOf(tile), queries) }
        }
    }
}
