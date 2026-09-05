package com.rohittp.rentile

import com.rohittp.rentile.internal.renderSyntheticPng
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLOSURE_STYLE =
    """{"version":8,"sprite":"https://sprite.example.test/atlas","sources":{"v":{"type":"vector","url":"https://meta.example.test/tiles.json"}},"layers":[{"id":"bg","type":"background","paint":{"background-pattern":"pattern"}},{"id":"land","type":"fill","source":"v","source-layer":"land","paint":{"fill-color":"#00ff00"}}]}"""

private const val SPRITE_JSON = """{"pattern":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":2,"sdf":false}}"""
private const val CHANGED_SPRITE_JSON = """{"pattern":{"x":0,"y":0,"width":4,"height":4,"pixelRatio":1,"sdf":false}}"""
private const val TILE_JSON =
    """{"tilejson":"3.0.0","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":15,"scheme":"xyz"}"""
private const val CHANGED_TILE_JSON =
    """{"tilejson":"3.0.0","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14,"scheme":"xyz"}"""

private const val CAPTIVE_PORTAL_HTML = "<html><body>Sign in to continue</body></html>"
private const val CLOSURE_NOW_MILLIS = 1_000_000L
private val CLOSURE_CLASSES = listOf(ResourceClass.SPRITE_JSON, ResourceClass.SPRITE_IMAGE, ResourceClass.TILE_JSON)

/**
 * The documents a style names are served from the store and refreshed behind the caller.
 *
 * They were once reused unconditionally and for good, so a corrected icon sheet or a source whose
 * tile templates moved upstream never reached a consumer that had fetched the old document; then
 * they were revalidated in front of the caller, which was correct and put three more conditional
 * round trips on the path to the first tile — production sends no `Cache-Control` on any of them,
 * so none of those requests could ever be skipped and all of them answered `304`.
 */
class ResourceRevalidationTest {
    @Test
    fun aCachedClosureDocumentIsServedBeforeItsRefreshIsAnswered() = runTest {
        val store = InMemoryRawResourceStore()
        val refreshesRequested = CompletableDeferred<Unit>()
        val releaseRefreshes = CompletableDeferred<Unit>()
        val requestedRefreshes = mutableListOf<ResourceClass>()
        val requestedMutex = Mutex()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
            } else {
                val complete = requestedMutex.withLock {
                    requestedRefreshes += request.resourceClass
                    requestedRefreshes.size == CLOSURE_CLASSES.size
                }
                if (complete) refreshesRequested.complete(Unit)
                releaseRefreshes.await()
                TransportResponse(304, ByteArray(0))
            }
        }

        prepareClosure(transport, store)
        // Preparation returns while all three refreshes are still blocked in the transport. If any
        // of them were in front of the caller, this would never come back.
        withClosureRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
            refreshesRequested.await()
            releaseRefreshes.complete(Unit)
        }

        for (resourceClass in CLOSURE_CLASSES) {
            val requests = transport.requests(resourceClass)
            assertEquals(2, requests.size, "$resourceClass")
            assertNull(requests[0].metadata.ifNoneMatch, "$resourceClass had nothing to revalidate against")
            assertEquals("\"$resourceClass-1\"", requests[1].metadata.ifNoneMatch, "$resourceClass")
        }
    }

    @Test
    fun aBackgroundNotModifiedRewritesEveryStoredEntry() = runTest {
        // A consumer's raw cache is trimmed by file age, so a document read on every start and never
        // written becomes its oldest file and is evicted first.
        val store = WriteRecordingRawResourceStore()
        val transport = validatorBearingTransport()

        prepareClosure(transport, store)
        assertEquals(CLOSURE_CLASSES.toSet(), store.writes().toSet())
        store.clearWrites()
        withClosureRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
            store.awaitWrites(CLOSURE_CLASSES.size)
        }

        assertEquals(CLOSURE_CLASSES.toSet(), store.writes().toSet(), "each 304 refreshes its entry's recency")
        assertTrue(store.everyWriteKeptItsBytes(), "the rewrite must not change what is stored")
    }

    @Test
    fun aBackgroundReplacementIsWhatTheNextPreparationSees() = runTest {
        val store = WriteRecordingRawResourceStore()
        val replacementsRefreshed = CompletableDeferred<Unit>()
        val refreshedReplacements = mutableListOf<ResourceClass>()
        val refreshedMutex = Mutex()
        val transport = RecordingTransport { request ->
            when (request.metadata.ifNoneMatch) {
                null -> ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
                "\"${request.resourceClass}-1\"" ->
                    ok(request.resourceClass, changed = true, etag = "\"${request.resourceClass}-2\"")
                else -> {
                    val complete = refreshedMutex.withLock {
                        refreshedReplacements += request.resourceClass
                        refreshedReplacements.size == CLOSURE_CLASSES.size
                    }
                    if (complete) replacementsRefreshed.complete(Unit)
                    TransportResponse(304, ByteArray(0))
                }
            }
        }

        prepareClosure(transport, store)
        store.clearWrites()
        withClosureRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
            store.awaitWrites(CLOSURE_CLASSES.size)
        }
        withClosureRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
            // Waited for, not assumed: these refreshes are the third request of each kind, and
            // closing the rasterizer before they went out would leave each list one short.
            replacementsRefreshed.await()
        }

        for (resourceClass in CLOSURE_CLASSES) {
            val requests = transport.requests(resourceClass)
            assertEquals(3, requests.size, "$resourceClass")
            assertEquals(
                "\"$resourceClass-2\"",
                requests[2].metadata.ifNoneMatch,
                "$resourceClass: the replacement is what the next preparation read and refreshed",
            )
        }
    }

    @Test
    fun aFailedBackgroundRefreshNeverReachesTheCaller() = runTest {
        for (failing in CLOSURE_CLASSES) {
            val store = InMemoryRawResourceStore()
            val failed = CompletableDeferred<Unit>()
            val transport = RecordingTransport { request ->
                when {
                    request.metadata.ifNoneMatch == null ->
                        ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
                    request.resourceClass == failing -> TransportResponse(503, ByteArray(0))
                    else -> TransportResponse(304, ByteArray(0))
                }
            }
            val metrics = MetricsSink { metric ->
                if (metric.name == MetricName.BACKGROUND_REVALIDATION_FAILED && metric.resourceClass == failing) {
                    failed.complete(Unit)
                }
            }

            prepareClosure(transport, store)
            withClosureRasterizer(transport, store, metrics) { rasterizer ->
                // No exception: the caller already has the stored document, and a refresh that
                // cannot complete is not a preparation failure.
                rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
                failed.await()
            }
        }
    }

    @Test
    fun oneProcessRefreshesEachDocumentOnce() = runTest {
        val store = InMemoryRawResourceStore()
        val refreshed = CompletableDeferred<Unit>()
        val seen = mutableListOf<ResourceClass>()
        val seenMutex = Mutex()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
            } else {
                val complete = seenMutex.withLock {
                    seen += request.resourceClass
                    seen.size == CLOSURE_CLASSES.size
                }
                if (complete) refreshed.complete(Unit)
                TransportResponse(304, ByteArray(0))
            }
        }

        prepareClosure(transport, store)
        withClosureRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
            refreshed.await()
        }

        for (resourceClass in CLOSURE_CLASSES) {
            assertEquals(2, transport.requests(resourceClass).size, "$resourceClass: one fetch, one refresh")
        }
    }

    @Test
    fun aFreshClosureDocumentIsUsedWithoutAskingTheOrigin() = runTest {
        // ADR 0007's shortcut: an explicit max-age that has not passed means there is nothing to
        // refresh, so not even a background request goes out.
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { request ->
            ok(
                request.resourceClass,
                changed = false,
                etag = "\"${request.resourceClass}-1\"",
                expiresAtEpochMillis = CLOSURE_NOW_MILLIS + 3_600_000L,
            )
        }

        prepareClosure(transport, store)
        assertEquals(3, transport.requests().size)
        transport.clear()
        prepareClosure(transport, store)

        assertEquals(emptyList(), transport.requests().map { it.resourceClass }, "a fresh entry needs no exchange")
    }

    @Test
    fun aClosureDocumentWithNoValidatorsIsStoredAndRefreshedUnconditionally() = runTest {
        // Nothing can revalidate it, but it is still worth keeping: it is served at once and
        // replaced behind the caller, which is what production's header-less documents need.
        val store = InMemoryRawResourceStore()
        val refreshed = CompletableDeferred<Unit>()
        val seen = mutableListOf<ResourceClass>()
        val seenMutex = Mutex()
        val transport = RecordingTransport { request ->
            val refreshes = seenMutex.withLock {
                seen += request.resourceClass
                seen.size
            }
            if (refreshes == CLOSURE_CLASSES.size * 2) refreshed.complete(Unit)
            ok(request.resourceClass, changed = false, etag = null)
        }

        prepareClosure(transport, store)
        assertEquals(CLOSURE_CLASSES.size, store.size(), "an entry with no validators is still stored")
        withClosureRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
            refreshed.await()
        }

        for (resourceClass in CLOSURE_CLASSES) {
            val requests = transport.requests(resourceClass)
            assertEquals(2, requests.size, "$resourceClass")
            requests.forEach { assertNull(it.metadata.ifNoneMatch, "$resourceClass had nothing to send") }
        }
    }

    @Test
    fun anUncachedDocumentIsFetchedInTheForegroundAndItsFailureIsRaised() = runTest {
        for (failing in CLOSURE_CLASSES) {
            val store = InMemoryRawResourceStore()
            val transport = RecordingTransport { request ->
                if (request.resourceClass == failing) {
                    TransportResponse(503, ByteArray(0))
                } else {
                    ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
                }
            }

            val failure = assertFailsWith<ResourceAcquisitionException>("$failing") {
                prepareClosure(transport, store)
            }

            assertEquals(503, failure.statusCode, "$failing")
            assertEquals(failing, failure.resourceClass, "$failing")
        }
    }

    @Test
    fun aCaptivePortalPageIsNeitherStoredNorServedAsAClosureDocument() = runTest {
        // A sign-in page answers 200 with HTML and no validators. Stored, it would be served to
        // every later preparation, fail wherever it is parsed, and -- being the newest file -- be
        // the last thing an age-trimmed cache evicted.
        for (poisoned in CLOSURE_CLASSES) {
            val store = InMemoryRawResourceStore()
            val transport = RecordingTransport { request ->
                if (request.resourceClass == poisoned) {
                    TransportResponse(200, CAPTIVE_PORTAL_HTML.encodeToByteArray())
                } else {
                    ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
                }
            }

            assertFailsWith<ResourceDecodeException>("$poisoned") { prepareClosure(transport, store) }

            assertTrue(
                store.size() < CLOSURE_CLASSES.size,
                "$poisoned: nothing that is not a $poisoned document is stored",
            )
        }
    }

    @Test
    fun anAlreadyPoisonedEntryIsEvictedOnReadAndRefetched() = runTest {
        // The guard cannot un-store what an earlier version wrote, so a poisoned entry heals on the
        // read that finds it: dropped, then fetched in the foreground with no validators to send.
        for (poisoned in CLOSURE_CLASSES) {
            val store = InMemoryRawResourceStore()
            val junk = CAPTIVE_PORTAL_HTML.encodeToByteArray()
            store.write(
                RawResourceKey(closureUrl(poisoned).withRedactedAuthenticationQuery().sha256Hex(), poisoned),
                StoredRawResource(
                    bytes = junk,
                    contentDigest = junk.sha256Hex(),
                    metadata = RawResourceMetadata(etag = "\"portal\"", storedAtEpochMillis = CLOSURE_NOW_MILLIS),
                ),
            )
            val transport = validatorBearingTransport()

            prepareClosure(transport, store)

            val requests = transport.requests(poisoned)
            assertEquals(1, requests.size, "$poisoned")
            assertNull(requests[0].metadata.ifNoneMatch, "$poisoned: the poisoned entry's validators went with it")
        }
    }

    @Test
    fun aBackgroundReplacementThatIsNotTheRightDocumentIsRejected() = runTest {
        for (poisoned in CLOSURE_CLASSES) {
            val store = InMemoryRawResourceStore()
            val rejected = CompletableDeferred<Unit>()
            val transport = RecordingTransport { request ->
                when {
                    request.metadata.ifNoneMatch == null ->
                        ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
                    request.resourceClass == poisoned ->
                        TransportResponse(200, CAPTIVE_PORTAL_HTML.encodeToByteArray())
                    else -> TransportResponse(304, ByteArray(0))
                }
            }
            val metrics = MetricsSink { metric ->
                if (metric.name == MetricName.BACKGROUND_REVALIDATION_FAILED && metric.resourceClass == poisoned) {
                    rejected.complete(Unit)
                }
            }

            prepareClosure(transport, store)
            withClosureRasterizer(transport, store, metrics) { rasterizer ->
                rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
                rejected.await()
            }

            // Asserted against the store rather than against a later preparation's request, which
            // would have meant reading a list a background refresh was still appending to.
            val stored = store.read(
                RawResourceKey(closureUrl(poisoned).withRedactedAuthenticationQuery().sha256Hex(), poisoned),
            )
            assertNotNull(stored, "$poisoned")
            assertFalse(
                stored.bytes.decodeToString().startsWith("<html"),
                "$poisoned: the store still holds the document, not the portal page",
            )
            assertEquals("\"$poisoned-1\"", stored.metadata.etag, "$poisoned")
        }
    }

    private fun validatorBearingTransport(): RecordingTransport = RecordingTransport { request ->
        if (request.metadata.ifNoneMatch != null) {
            TransportResponse(304, ByteArray(0))
        } else {
            ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
        }
    }

    private fun closureUrl(resourceClass: ResourceClass): String = when (resourceClass) {
        ResourceClass.SPRITE_JSON -> "https://sprite.example.test/atlas.json"
        ResourceClass.SPRITE_IMAGE -> "https://sprite.example.test/atlas.png"
        ResourceClass.TILE_JSON -> "https://meta.example.test/tiles.json"
        else -> error("Unexpected resource class $resourceClass")
    }

    private fun ok(
        resourceClass: ResourceClass,
        changed: Boolean,
        etag: String?,
        expiresAtEpochMillis: Long? = null,
    ): TransportResponse {
        val body = when (resourceClass) {
            ResourceClass.SPRITE_JSON -> (if (changed) CHANGED_SPRITE_JSON else SPRITE_JSON).encodeToByteArray()
            ResourceClass.SPRITE_IMAGE -> renderSyntheticPng(if (changed) 16 else 8)
            ResourceClass.TILE_JSON -> (if (changed) CHANGED_TILE_JSON else TILE_JSON).encodeToByteArray()
            else -> error("Unexpected resource class $resourceClass")
        }
        return TransportResponse(
            200,
            body,
            TransportResponseMetadata(etag = etag, expiresAtEpochMillis = expiresAtEpochMillis),
        )
    }

    private suspend fun prepareClosure(transport: ResourceTransport, store: RawResourceStore) {
        withClosureRasterizer(transport, store) { it.prepare(StyleInput.InlineJson(CLOSURE_STYLE)) }
    }

    private suspend fun <T> withClosureRasterizer(
        transport: ResourceTransport,
        store: RawResourceStore,
        metricsSink: MetricsSink = MetricsSink.None,
        block: suspend (BasemapRasterizer) -> T,
    ): T {
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = transport,
                rawResourceStore = store,
                metricsSink = metricsSink,
                clock = RentileClock { CLOSURE_NOW_MILLIS },
            ),
        )
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
}
