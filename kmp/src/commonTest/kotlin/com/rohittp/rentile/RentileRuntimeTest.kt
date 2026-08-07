package com.rohittp.rentile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import com.rohittp.rentile.internal.renderSyntheticPng
import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import com.rohittp.rentile.internal.style.RasterDrawLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RentileRuntimeTest {
    @Test
    fun backgroundStyleRendersDeterministicPngThroughPublicInterface() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"hsl(120, 100%, 25%)"}}]}""",
                ),
            )
            val tile = TileId(2, 1, 3)
            val first = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()
            val second = rasterizer.render(style, listOf(tile), RenderOptions(256)).tiles.single()

            assertTrue(first.pngBytes.startsWithPngSignature())
            assertEquals(256, first.pngBytes.pngWidth())
            assertEquals(256, first.pngBytes.pngHeight())
            assertEquals(first.contentKey, second.contentKey)
            assertTrue(first.pngBytes.contentEquals(second.pngBytes))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun outputIdentityChangesWithTileAndSize() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"layers":[]}"""))
            val tile = TileId(1, 0, 0)
            val size256 = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256))
            val size512 = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(512))
            val otherTile = rasterizer.prepareBatch(style, listOf(TileId(1, 1, 0)), RenderOptions(256))
            try {
                assertNotEquals(size256.contentKeys.getValue(tile), size512.contentKeys.getValue(tile))
                assertNotEquals(size256.contentKeys.getValue(tile), otherTile.contentKeys.values.single())
            } finally {
                size256.close()
                size512.close()
                otherTile.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun closedPreparedBatchFailsWithTypedLifecycleError() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"layers":[]}"""))
            val batch = rasterizer.prepareBatch(style, listOf(TileId(0, 0, 0)))
            batch.close()

            val error = assertFailsWith<PreparedBatchClosedException> { rasterizer.render(batch) }
            assertEquals(RentileErrorCode.PREPARED_BATCH_CLOSED, error.code)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun unsupportedReachableLayerFailsAtPreparationWithFullDiagnostic() = runTest {
        val rasterizer = testRasterizer()
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"layers":[{"id":"roads","type":"line","source":"v","source-layer":"road"}]}""",
                    ),
                )
            }

            assertEquals(RentileErrorCode.STYLE_PREPARATION_FAILED, error.code)
            assertEquals(DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT, error.diagnostics.single().code)
            assertEquals("0", error.diagnostics.single().details["layerIndex"])
            assertTrue(error.diagnostics.single().details.getValue("layerIdDigest").length == 64)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun preparationCollectsAllSafeLayerDiagnosticsBeforeFailing() = runTest {
        val rasterizer = testRasterizer()
        try {
            val error = assertFailsWith<StylePreparationException> {
                rasterizer.prepare(
                    StyleInput.InlineJson(
                        """{"version":8,"layers":[{"id":"labels","type":"symbol","layout":{"text-field":"name"}},{"id":"land","type":"fill"},{"id":"roads","type":"line"}]}""",
                    ),
                )
            }

            assertEquals(3, error.diagnostics.size)
            assertEquals(2, error.diagnostics.count { it.severity == DiagnosticSeverity.ERROR })
            assertTrue(error.diagnostics.any { it.code == DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun textOnlyLayerIsExcludedBeforeTextPropertyValidation() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"layers":[{"id":"labels","type":"symbol","layout":{"text-field":["future-unsupported-op",1]}}]}""",
                ),
            )

            assertEquals(DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED, style.diagnostics.single().code)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun hiddenLayerDoesNotMakeItsSourceSyntaxReachable() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sources":{"hidden":{"type":"raster","url":"provider://unsupported"}},"layers":[{"id":"hidden","type":"raster","source":"hidden","layout":{"visibility":"none"}}]}""",
                ),
            )

            assertEquals(DiagnosticCode.HIDDEN_LAYER_NO_DRAW, style.diagnostics.single().code)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun callerCancellationCancelsInFlightTransport() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val rasterizer = testRasterizer(
            transport = ResourceTransport {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        try {
            val request = launch { rasterizer.prepare(StyleInput.Remote("https://example.test/style?key=secret")) }
            started.await()
            request.cancelAndJoin()
            cancelled.await()
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun closeCancelsInFlightWorkAndAwaitClosedJoinsIt() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val rasterizer = testRasterizer(
            transport = ResourceTransport {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            },
        )
        val request = launch { rasterizer.prepare(StyleInput.Remote("https://example.test/style")) }
        started.await()

        rasterizer.close()
        rasterizer.awaitClosed()
        request.join()
        assertTrue(cancelled.isCompleted)
        assertFailsWith<RasterizerClosedException> {
            rasterizer.prepare(StyleInput.InlineJson("""{"version":8,"layers":[]}"""))
        }
    }

    @Test
    fun rasterOnlyPngUsesPassThroughAndWarmRawCache() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val store = InMemoryRawResourceStore()
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    requests += 1
                    assertTrue(request.url.contains("/0/0/0.png"))
                    TransportResponse(
                        statusCode = 200,
                        body = sourcePng,
                        metadata = TransportResponseMetadata(contentType = "image/png"),
                    )
                },
                rawResourceStore = store,
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("first-secret"))
            val tile = TileId(0, 0, 0)
            val firstBatch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256))
            val first = try {
                rasterizer.render(firstBatch).tiles.single()
            } finally {
                firstBatch.close()
            }
            val warmBatch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256))
            val warm = try {
                rasterizer.render(warmBatch).tiles.single()
            } finally {
                warmBatch.close()
            }

            assertEquals(1, requests)
            assertTrue(first.pngBytes.contentEquals(sourcePng))
            assertTrue(warm.pngBytes.contentEquals(sourcePng))
            assertEquals(first.contentKey, warm.contentKey)
            assertTrue(first.diagnostics.any { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
            assertEquals(1, store.size())

            val reloadBatch = rasterizer.prepareBatch(
                style,
                listOf(tile),
                RenderOptions(256),
                ResourceAccessMode.RELOAD,
            )
            reloadBatch.close()
            assertEquals(2, requests)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun rasterApiKeyDoesNotChangeStyleOrOutputIdentity() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport {
                TransportResponse(200, sourcePng, TransportResponseMetadata(contentType = "image/png"))
            },
        )
        try {
            val first = rasterizer.prepare(rasterStyle("first-secret"))
            val second = rasterizer.prepare(rasterStyle("second-secret"))
            assertEquals(first.digest, second.digest)

            val tile = TileId(0, 0, 0)
            val firstBatch = rasterizer.prepareBatch(first, listOf(tile), RenderOptions(256))
            val secondBatch = rasterizer.prepareBatch(second, listOf(tile), RenderOptions(256))
            try {
                assertEquals(firstBatch.contentKeys, secondBatch.contentKeys)
            } finally {
                firstBatch.close()
                secondBatch.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun closeClearsCredentialBearingPreparedStyleUrls() = runTest {
        val rasterizer = testRasterizer()
        val protectedUrl = try {
            val style = rasterizer.prepare(rasterStyle("ephemeral-secret")) as CompiledPreparedStyle
            style.drawLayers.filterIsInstance<RasterDrawLayer>()
                .single()
                .source
                .tileTemplates
                .single()
                .also {
                    assertTrue(it.resolve().contains("ephemeral-secret"))
                    assertTrue(!it.toString().contains("ephemeral-secret"))
                }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }

        assertFailsWith<RasterizerClosedException> { protectedUrl.resolve() }
    }

    @Test
    fun rasterIsCompositedAndEncodedWhenOutputSizeDiffers() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { TransportResponse(200, sourcePng) },
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val output = rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(512)).tiles.single()

            assertTrue(output.pngBytes.startsWithPngSignature())
            assertEquals(512, output.pngBytes.pngWidth())
            assertEquals(512, output.pngBytes.pngHeight())
            assertTrue(output.diagnostics.none { it.code == DiagnosticCode.RASTER_PASSTHROUGH_USED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun failureOnOneTileKeepsCompletedRawCacheEntries() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val store = InMemoryRawResourceStore()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    if (request.url.contains("/1/0/0.png")) {
                        TransportResponse(200, sourcePng)
                    } else {
                        store.firstWrite.await()
                        TransportResponse(503, ByteArray(0), TransportResponseMetadata(retryAfterMillis = 1_000))
                    }
                },
                rawResourceStore = store,
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val error = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.prepareBatch(style, listOf(TileId(1, 0, 0), TileId(1, 1, 0)), RenderOptions(256))
            }

            assertEquals(503, error.statusCode)
            assertEquals(1, store.size())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun rasterFetchesUseConfiguredParallelismWithoutExceedingIt() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val stateMutex = Mutex()
        val twoStarted = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    stateMutex.withLock {
                        active += 1
                        requests += 1
                        maximumActive = maxOf(maximumActive, active)
                        if (active == 2) twoStarted.complete(Unit)
                    }
                    twoStarted.await()
                    try {
                        TransportResponse(200, sourcePng)
                    } finally {
                        stateMutex.withLock { active -= 1 }
                    }
                },
                rawResourceStore = InMemoryRawResourceStore(),
                executionPolicy = ExecutionPolicy(
                    maxConcurrentExchanges = 2,
                    maxConcurrentExchangesPerOrigin = 2,
                    maxConcurrentDecodes = 1,
                ),
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val batch = rasterizer.prepareBatch(
                style,
                listOf(TileId(2, 0, 0), TileId(2, 1, 0), TileId(2, 2, 0), TileId(2, 3, 0)),
                RenderOptions(256),
            )
            batch.close()

            assertEquals(4, requests)
            assertEquals(2, maximumActive)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun identicalConcurrentFetchesUseLastWaiterSingleFlight() = runTest {
        val sourcePng = renderSyntheticPng(256)
        val transportStarted = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    requests += 1
                    transportStarted.complete(Unit)
                    releaseTransport.await()
                    TransportResponse(200, sourcePng)
                },
                rawResourceStore = InMemoryRawResourceStore(),
                metricsSink = MetricsSink { metric ->
                    if (metric.name == MetricName.SINGLE_FLIGHT_JOIN) joined.complete(Unit)
                },
            ),
        )
        try {
            val style = rasterizer.prepare(rasterStyle("secret"))
            val tile = TileId(0, 0, 0)
            val first = async { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            transportStarted.await()
            val second = async { rasterizer.prepareBatch(style, listOf(tile), RenderOptions(256)) }
            joined.await()

            first.cancelAndJoin()
            releaseTransport.complete(Unit)
            val survivingBatch = second.await()
            survivingBatch.close()

            assertEquals(1, requests)
        } finally {
            releaseTransport.complete(Unit)
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun testRasterizer(
        transport: ResourceTransport = ResourceTransport { error("Unexpected transport request") },
    ): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = transport,
            rawResourceStore = InMemoryRawResourceStore(),
        ),
    )

    private fun rasterStyle(apiKey: String): StyleInput.InlineJson = StyleInput.InlineJson(
        """{"version":8,"sources":{"tiles":{"type":"raster","tiles":["https://tiles.example.test/{z}/{x}/{y}.png?key=$apiKey"],"tileSize":256}},"layers":[{"id":"raster","type":"raster","source":"tiles"}]}""",
    )
}

private class InMemoryRawResourceStore : RawResourceStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<RawResourceKey, StoredRawResource>()
    val firstWrite = CompletableDeferred<Unit>()

    override suspend fun read(key: RawResourceKey): StoredRawResource? = mutex.withLock { entries[key] }

    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) {
        mutex.withLock { entries[key] = resource }
        firstWrite.complete(Unit)
    }

    override suspend fun remove(key: RawResourceKey) {
        mutex.withLock { entries.remove(key) }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }
}

private fun ByteArray.startsWithPngSignature(): Boolean =
    size >= 8 && this[0] == 0x89.toByte() && decodeToString(1, 4) == "PNG"

private fun ByteArray.pngWidth(): Int = bigEndianInt(16)

private fun ByteArray.pngHeight(): Int = bigEndianInt(20)

private fun ByteArray.bigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 24) or
        ((this[offset + 1].toInt() and 0xff) shl 16) or
        ((this[offset + 2].toInt() and 0xff) shl 8) or
        (this[offset + 3].toInt() and 0xff)
