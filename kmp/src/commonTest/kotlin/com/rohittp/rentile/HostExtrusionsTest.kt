package com.rohittp.rentile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import com.rohittp.rentile.internal.mvt.Tile
import com.rohittp.rentile.internal.sha256Hex
import kotlin.test.*

class HostExtrusionsTest {
    private val host = CompatibilityPolicy.RentileV1HostSymbolsAndExtrusions
    private val tile = TileId(16, 100, 100)

    private suspend fun <T> withRasterizer(
        bytes: ByteArray = ExtrusionFixtures.tile(),
        block: suspend (BasemapRasterizer, LabelFixtures.Transport) -> T,
    ): T {
        val transport = LabelFixtures.Transport(bytes)
        val rasterizer = LabelFixtures.rasterizer(transport)
        try { return block(rasterizer, transport) }
        finally { rasterizer.close(); rasterizer.awaitClosed() }
    }

    @Test fun profilesAreOptInAndKeepTheDefault() {
        assertEquals("rentile-v1-host-extrusions", CompatibilityPolicy.RentileV1HostExtrusions.id)
        assertEquals("rentile-v1-host-symbols-and-extrusions", host.id)
        assertEquals(CompatibilityPolicy.RentileV1, CompatibilityPolicy.Default)
    }

    @Test fun hostRasterOmitsFootprintsWhileDefaultStillFlattens() = runTest {
        withRasterizer { r, transport ->
            val bg = """{"id":"bg","type":"background","paint":{"background-color":"white"}}"""
            val json = ExtrusionFixtures.style(bg, ExtrusionFixtures.layer())
            val hosted = r.prepare(StyleInput.InlineJson(json), host)
            val legacy = r.prepare(StyleInput.InlineJson(json))
            val bare = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(bg)), host)
            suspend fun pixels(s: PreparedStyle): ByteArray {
                val b = r.prepareBatch(s, listOf(tile))
                try { return r.renderRaw(b).tiles.single().rgbaBytes } finally { b.close() }
            }
            assertContentEquals(pixels(bare), pixels(hosted))
            assertEquals(0, transport.requestedClasses().count { it == ResourceClass.VECTOR_TILE })
            assertFalse(pixels(legacy).contentEquals(pixels(bare)))
            assertTrue(legacy.diagnostics.any { it.code == DiagnosticCode.EXTRUSION_FLATTENED })
            assertTrue(hosted.diagnostics.none { it.code == DiagnosticCode.EXTRUSION_FLATTENED })
            assertTrue(r.extrusionLayerDescriptors(legacy).isEmpty())
            assertNotEquals(legacy.digest, hosted.digest)
        }
    }

    @Test fun canonicalPolygonsPreserveHolesBufferCoordinatesAndUnsignedIds() = runTest {
        withRasterizer(ExtrusionFixtures.tile(id = -1L)) { r, _ ->
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
            val b = r.acquireExtrusionCandidates(s, listOf(tile))
            val c = b.candidates.single()
            assertEquals(TileId(15, 50, 50), c.sourceTile)
            assertEquals(-1L, c.featureId)
            assertEquals(0, c.featureIndex)
            assertEquals(4096, c.extent)
            assertEquals(12, c.geometry.vertexCount)
            assertEquals(3, c.geometry.ringCount)
            assertEquals(2, c.geometry.polygonCount)
            assertEquals(2, c.geometry.polygonEnd(0))
            assertEquals(2, c.geometry.polygonStart(1))
            assertEquals(-10, c.geometry.x(0))
            assertEquals(4, c.geometry.ringEnd(0))
            assertEquals(ExtrusionPaint(12.0, 80.0, 0xff0a141e.toInt()), c.paintAtZoom(15.5))
            assertSame(c.paintAtZoom(15.1), c.paintAtZoom(16.2))
            assertFalse(c.paintIsZoomDependent)
            assertNull(c.paintAtZoom(14.99)); assertNull(c.paintAtZoom(21.0))
            val copy = c.geometry.copyCoordinates(); copy[0] = 42
            assertEquals(-10, c.geometry.x(0))
            assertTrue(b.estimatedRetainedBytes >= c.geometry.primitiveByteCount)
            assertTrue(b.layerStyles.single().descriptor.verticalGradient)
        }
    }

    @Test fun overzoomChildrenDeduplicateAndLayersShareGeometry() = runTest {
        withRasterizer { r, t ->
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(
                ExtrusionFixtures.layer("first"), ExtrusionFixtures.layer("second", paint = ",\"fill-extrusion-opacity\":0.4"),
                ExtrusionFixtures.layer("hidden", extra = ",\"layout\":{\"visibility\":\"none\"}"),
            )), host)
            val tiles = listOf(tile, tile.copy(x = 101), tile.copy(y = 101), tile.copy(x = 101, y = 101))
            val b = r.acquireExtrusionCandidates(s, tiles)
            assertEquals(2, b.candidates.size)
            assertSame(b.candidates[0].geometry, b.candidates[1].geometry)
            assertEquals(listOf("first", "second"), b.layerStyles.map { it.descriptor.id })
            assertEquals(listOf(0, 1), b.layerStyles.map { it.descriptor.styleLayerIndex })
            assertEquals(0.4, b.layerStyles[1].opacityAtZoom(16.0))
            assertEquals(0.0, b.layerStyles[1].opacityAtZoom(14.9))
            assertEquals(1, t.requestedClasses().count { it == ResourceClass.VECTOR_TILE })
            assertNull(b.candidates.first().featureId)
            assertEquals(b.contentKey, r.acquireExtrusionCandidates(s, tiles.reversed()).contentKey)
            assertEquals(r.extrusionCandidateRequestKey(s, tiles), r.extrusionCandidateRequestKey(s, tiles + tile))
        }
    }

    @Test fun paintUsesFractionalZoomAndFilterUsesIntegerZoom() = runTest {
        withRasterizer { r, _ ->
            val layer = ExtrusionFixtures.layer(
                paint = ",\"fill-extrusion-height\":[\"interpolate\",[\"linear\"],[\"zoom\"],15,0,16,100],\"fill-extrusion-base\":0,\"fill-extrusion-opacity\":[\"interpolate\",[\"linear\"],[\"zoom\"],15,0,16,1]",
                filter = ",\"filter\":[\"<\",[\"zoom\"],15.5]",
            )
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(layer)), host)
            val b = r.acquireExtrusionCandidates(s, listOf(tile))
            assertEquals(50.0, b.candidates.single().paintAtZoom(15.5)?.heightMetres)
            assertEquals(90.0, assertNotNull(b.candidates.single().paintAtZoom(15.9)).heightMetres, 0.0001)
            assertNull(b.candidates.single().paintAtZoom(16.0))
            assertEquals(0.5, b.layerStyles.single().opacityAtZoom(15.5))
        }
    }

    @Test fun defaultHeightIsZeroAndBadPaintDoesNotFabricateBuildings() = runTest {
        withRasterizer(ExtrusionFixtures.tile(height = -5.0, base = -10.0)) { r, _ ->
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
            assertEquals(ExtrusionPaint(0.0, 0.0, 0xff0a141e.toInt()), r.acquireExtrusionCandidates(s, listOf(tile)).candidates.single().paintAtZoom(16.0))
            val plain = """{"id":"plain","type":"fill-extrusion","source":"v","source-layer":"building"}"""
            val p = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(plain)), host)
            assertEquals(ExtrusionPaint(0.0, 0.0, 0xff000000.toInt()), r.acquireExtrusionCandidates(p, listOf(tile)).candidates.single().paintAtZoom(16.0))
            val missing = ExtrusionFixtures.layer(paint = ",\"fill-extrusion-height\":[\"get\",\"missing\"]")
            val m = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(missing)), host)
            assertNull(r.acquireExtrusionCandidates(m, listOf(tile)).candidates.single().paintAtZoom(16.0))
        }
    }

    @Test fun baseIsClampedToHeightAndVerticalGradientIsPreserved() = runTest {
        withRasterizer(ExtrusionFixtures.tile(height = 5.0, base = 10.0)) { r, _ ->
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer(
                paint = ",\"fill-extrusion-vertical-gradient\":false",
            ))), host)
            val b = r.acquireExtrusionCandidates(s, listOf(tile))
            assertEquals(5.0, b.candidates.single().paintAtZoom(16.0)?.baseMetres)
            assertFalse(b.layerStyles.single().descriptor.verticalGradient)
        }
    }

    @Test fun unsupportedPaintAndFeatureOpacityFailPreparation() = runTest {
        withRasterizer { r, _ ->
            for (paint in listOf(",\"fill-extrusion-pattern\":\"brick\"", ",\"fill-extrusion-opacity\":[\"get\",\"opacity\"]")) {
                assertFailsWith<StylePreparationException> {
                    r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer(paint = paint))), host)
                }
            }
        }
    }

    @Test fun limitsFailExplicitlyAndDoNotEvictValidCachedData() = runTest {
        withRasterizer { r, t ->
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
            r.acquireExtrusionCandidates(s, listOf(tile))
            for (limits in listOf(ExtrusionLimits(maxEncodedTileBytes = 1), ExtrusionLimits(maxWorkingBytes = 1), ExtrusionLimits(maxRetainedBytes = 300))) {
                assertFailsWith<SafetyLimitException> { r.acquireExtrusionCandidates(s, listOf(tile), limits, ResourceAccessMode.CACHE_ONLY) }
            }
            assertEquals(1, r.acquireExtrusionCandidates(s, listOf(tile), resourceAccess = ResourceAccessMode.CACHE_ONLY).candidates.size)
            assertEquals(1, t.requestedClasses().count { it == ResourceClass.VECTOR_TILE })
            assertEquals("maxRequestedTiles", assertFailsWith<SafetyLimitException> {
                r.acquireExtrusionCandidates(s, listOf(tile, tile), ExtrusionLimits(maxRequestedTiles = 1))
            }.limitName)
        }
    }

    @Test fun cacheOnlyNeverFetchesAndReloadHonorsNewContent() = runTest {
        var revision = 80.0
        val t = RecordingTransport { TransportResponse(200, ExtrusionFixtures.tile(height = revision)) }
        val r = LabelFixtures.rasterizer(t)
        try {
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
            assertFailsWith<ResourceAcquisitionException> { r.acquireExtrusionCandidates(s, listOf(tile), resourceAccess = ResourceAccessMode.CACHE_ONLY) }
            assertTrue(t.requests().isEmpty())
            val a = r.acquireExtrusionCandidates(s, listOf(tile))
            revision = 90.0
            val b = r.acquireExtrusionCandidates(s, listOf(tile), resourceAccess = ResourceAccessMode.RELOAD)
            assertNotEquals(a.contentKey, b.contentKey)
            assertEquals(90.0, b.candidates.single().paintAtZoom(16.0)?.heightMetres)
        } finally { r.close(); r.awaitClosed() }
    }

    @Test fun malformedCachedPartialAssemblyIsDiscardedBeforeRecovery() = runTest {
        val valid = ExtrusionFixtures.tile()
        val original = Tile.ADAPTER.decode(valid).layers.single()
        val malformed = Tile.ADAPTER.encode(Tile(layers = listOf(original.copy(features = listOf(
            original.features.single(), original.features.single().copy(geometry = listOf(9, 0, 0)),
        )))))
        val store = InMemoryRawResourceStore()
        val url = "https://tiles.example.test/15/50/50.pbf"
        store.write(RawResourceKey(url.sha256Hex(), ResourceClass.VECTOR_TILE), StoredRawResource(
            malformed, malformed.sha256Hex(), RawResourceMetadata(storedAtEpochMillis = 0),
        ))
        val transport = RecordingTransport { TransportResponse(200, valid) }
        val r = Rentile.create(RentileConfiguration(transport, store))
        try {
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
            val recovered = r.acquireExtrusionCandidates(s, listOf(tile))
            val cached = r.acquireExtrusionCandidates(s, listOf(tile), resourceAccess = ResourceAccessMode.CACHE_ONLY)
            assertEquals(1, recovered.candidates.size)
            assertEquals(cached.contentKey, recovered.contentKey)
            assertEquals(cached.estimatedRetainedBytes, recovered.estimatedRetainedBytes)
            assertEquals(1, transport.requests().size)
        } finally { r.close(); r.awaitClosed() }
    }

    @Test fun cachedInputStillObeysTheRasterizersGlobalTransportByteLimit() = runTest {
        val bytes = ExtrusionFixtures.tile()
        val store = InMemoryRawResourceStore()
        val url = "https://tiles.example.test/15/50/50.pbf"
        store.write(RawResourceKey(url.sha256Hex(), ResourceClass.VECTOR_TILE), StoredRawResource(
            bytes, bytes.sha256Hex(), RawResourceMetadata(storedAtEpochMillis = 0),
        ))
        val r = Rentile.create(RentileConfiguration(ResourceTransport { error("must not fetch") }, store,
            resourceLimits = ResourceLimits(maxTileBytes = bytes.size.toLong() - 1)))
        try {
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
            assertEquals("maxTileBytes", assertFailsWith<SafetyLimitException> {
                r.acquireExtrusionCandidates(s, listOf(tile), resourceAccess = ResourceAccessMode.CACHE_ONLY)
            }.limitName)
            assertNotNull(store.read(RawResourceKey(url.sha256Hex(), ResourceClass.VECTOR_TILE)))
        } finally { r.close(); r.awaitClosed() }
    }

    @Test fun eitherHostProfilePreservesTheExpectedSymbolOwnership() = runTest {
        withRasterizer { r, _ ->
            val icon = """{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker"}}"""
            val json = LabelFixtures.style(icon, ExtrusionFixtures.layer())
            val extrusionOnly = r.prepare(StyleInput.InlineJson(json), CompatibilityPolicy.RentileV1HostExtrusions)
            val combined = r.prepare(StyleInput.InlineJson(json), host)
            assertTrue(r.labelLayerDescriptors(extrusionOnly).isEmpty())
            assertEquals(listOf("poi"), r.labelLayerDescriptors(combined).map { it.id })
            assertEquals(1, r.extrusionLayerDescriptors(extrusionOnly).size)
            assertEquals(1, r.extrusionLayerDescriptors(combined).size)
        }
    }

    @Test fun errorsAreRedactedAndLifecycleIsEnforced() = runTest {
        val r = LabelFixtures.rasterizer(ResourceTransport { error("secret-url?key=SECRET") })
        val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
        val e = assertFailsWith<ResourceAcquisitionException> { r.acquireExtrusionCandidates(s, listOf(tile)) }
        assertFalse(e.toString().contains("SECRET")); assertNull(e.cause)
        r.close(); r.awaitClosed()
        assertFailsWith<RasterizerClosedException> { r.acquireExtrusionCandidates(s, listOf(tile)) }
    }

    @Test fun cancellingAcquisitionCancelsTheTransportAndAllowsClose() = runTest {
        val entered = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val r = LabelFixtures.rasterizer(ResourceTransport {
            entered.complete(Unit)
            try { CompletableDeferred<TransportResponse>().await() } finally { cancelled.complete(Unit) }
        })
        try {
            val s = r.prepare(StyleInput.InlineJson(ExtrusionFixtures.style(ExtrusionFixtures.layer())), host)
            val work = async { r.acquireExtrusionCandidates(s, listOf(tile)) }
            entered.await(); work.cancel(); work.join(); cancelled.await()
        } finally { r.close(); r.awaitClosed() }
    }
}
