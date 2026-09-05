package com.rohittp.rentile

import kotlinx.coroutines.CompletableDeferred
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.withRedactedAuthenticationQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MILLIS = 1_000_000L
private const val STYLE_URL = "https://styles.example.test/basic.json?key=secret"
private const val FIRST_STYLE =
    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#102030"}}]}"""
private const val CAPTIVE_PORTAL_HTML = "<html><body>Sign in to continue</body></html>"
private const val SECOND_STYLE =
    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#a0b0c0"}}]}"""

/**
 * A stored style is served at once and refreshed behind the caller.
 *
 * Style acquisition first went straight to the transport, so every process start downloaded the
 * document again; then it revalidated conditionally, which was correct and still put a round trip
 * in front of a style switch that is supposed to be instant — production sends no `Cache-Control`
 * on any of these documents, so the conditional request was never skippable and always answered
 * `304`. What a preparation waits for now is the store; the origin is asked afterwards, and a
 * changed style arrives at the next preparation.
 */
class StyleRevalidationTest {
    @Test
    fun aCachedStyleIsServedBeforeItsRefreshIsAnswered() = runTest {
        val store = InMemoryRawResourceStore()
        val refreshRequested = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            } else {
                refreshRequested.complete(Unit)
                releaseRefresh.await()
                TransportResponse(304, ByteArray(0))
            }
        }

        val coldDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        // A second rasterizer over the same store is what a process restart looks like from here.
        val warmDigest = withRasterizer(transport, store) { rasterizer ->
            // This returns while the refresh is still blocked in the transport. If preparation
            // waited for it, the test would never get here.
            val digest = rasterizer.prepare(StyleInput.Remote(STYLE_URL)).digest
            refreshRequested.await()
            releaseRefresh.complete(Unit)
            digest
        }

        assertEquals(coldDigest, warmDigest, "the stored style is what the caller was served")
        val requests = transport.requests()
        assertEquals(2, requests.size)
        assertNull(requests[0].metadata.ifNoneMatch, "nothing was cached yet")
        assertEquals("\"v1\"", requests[1].metadata.ifNoneMatch, "the refresh carries the stored validators")
    }

    @Test
    fun aBackgroundNotModifiedRewritesTheStoredEntry() = runTest {
        // A consumer's raw cache is trimmed by file age, so a style read on every start and never
        // written would become its oldest file and be evicted first.
        val store = WriteRecordingRawResourceStore()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == "\"v1\"") {
                TransportResponse(304, ByteArray(0))
            } else {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            }
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        store.clearWrites()
        withRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.Remote(STYLE_URL))
            store.awaitWrites(1)
        }

        assertEquals(listOf(ResourceClass.STYLE), store.writes(), "the 304 must refresh the entry's recency")
        assertTrue(store.everyWriteKeptItsBytes)
    }

    @Test
    fun aBackgroundReplacementIsWhatTheNextPreparationSees() = runTest {
        val store = WriteRecordingRawResourceStore()
        val transport = RecordingTransport { request ->
            when (request.metadata.ifNoneMatch) {
                null -> TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
                "\"v1\"" -> TransportResponse(200, SECOND_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v2\""))
                else -> TransportResponse(304, ByteArray(0))
            }
        }

        val firstDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        store.clearWrites()
        val servedStaleDigest = withRasterizer(transport, store) { rasterizer ->
            val digest = rasterizer.prepare(StyleInput.Remote(STYLE_URL)).digest
            store.awaitWrites(1)
            digest
        }
        val afterReplacementDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }

        assertEquals(firstDigest, servedStaleDigest, "the caller is served what was stored, not what arrives")
        assertNotEquals(firstDigest, afterReplacementDigest, "the replacement lands at the next preparation")
        assertEquals("\"v2\"", transport.requests()[2].metadata.ifNoneMatch, "which is then refreshed in turn")
    }

    @Test
    fun aFailedBackgroundRefreshNeverReachesTheCaller() = runTest {
        // The caller was already served from the store, so a refresh that cannot complete is not a
        // preparation failure. The metric is the only trace it leaves.
        val store = InMemoryRawResourceStore()
        val failed = CompletableDeferred<ResourceClass?>()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            } else {
                TransportResponse(503, ByteArray(0))
            }
        }
        val metrics = MetricsSink { metric ->
            if (metric.name == MetricName.BACKGROUND_REVALIDATION_FAILED) failed.complete(metric.resourceClass)
        }

        val coldDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        val warmDigest = withRasterizer(transport, store, metrics) { rasterizer ->
            val digest = rasterizer.prepare(StyleInput.Remote(STYLE_URL)).digest
            assertEquals(ResourceClass.STYLE, failed.await())
            digest
        }

        assertEquals(coldDigest, warmDigest)
    }

    @Test
    fun oneProcessRefreshesAStyleOnce() = runTest {
        val store = InMemoryRawResourceStore()
        val refreshed = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            } else {
                refreshed.complete(Unit)
                TransportResponse(304, ByteArray(0))
            }
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        withRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.Remote(STYLE_URL))
            rasterizer.prepare(StyleInput.Remote(STYLE_URL))
            rasterizer.prepare(StyleInput.Remote(STYLE_URL))
            refreshed.await()
        }

        // One cold fetch, and one refresh for the whole run however many preparations asked.
        assertEquals(2, transport.requests().size)
    }

    @Test
    fun twoConcurrentPreparationsRefreshAStyleOnce() = runTest {
        val store = InMemoryRawResourceStore()
        val refreshed = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            } else {
                refreshed.complete(Unit)
                TransportResponse(304, ByteArray(0))
            }
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        withRasterizer(transport, store) { rasterizer ->
            // Both readers find the same stale entry; the claim and the launch happen under one
            // lock, so only one of them starts a refresh.
            val first = async { rasterizer.prepare(StyleInput.Remote(STYLE_URL)) }
            val second = async { rasterizer.prepare(StyleInput.Remote(STYLE_URL)) }
            first.await()
            second.await()
            refreshed.await()
        }

        assertEquals(2, transport.requests().size, "one cold fetch and one refresh between the two")
    }

    @Test
    fun aFreshStyleIsServedWithNoRequestAtAll() = runTest {
        // ADR 0007's freshness shortcut, which the style now takes like everything else: an
        // explicit max-age that has not passed means there is nothing to ask about.
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport {
            TransportResponse(
                200,
                FIRST_STYLE.encodeToByteArray(),
                TransportResponseMetadata(etag = "\"v1\"", expiresAtEpochMillis = NOW_MILLIS + 3_600_000L),
            )
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }

        assertEquals(1, transport.requests().size)
    }

    @Test
    fun aCachedStyleWithOnlyALastModifiedIsRefreshedWithIfModifiedSince() = runTest {
        val store = InMemoryRawResourceStore()
        val lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
        val refreshed = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifModifiedSince == lastModified) {
                refreshed.complete(Unit)
                TransportResponse(304, ByteArray(0))
            } else {
                TransportResponse(
                    200,
                    FIRST_STYLE.encodeToByteArray(),
                    TransportResponseMetadata(lastModified = lastModified),
                )
            }
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        withRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.Remote(STYLE_URL))
            refreshed.await()
        }

        assertEquals(lastModified, transport.requests()[1].metadata.ifModifiedSince)
        assertNull(transport.requests()[1].metadata.ifNoneMatch)
    }

    @Test
    fun aStyleWithNoValidatorsIsStoredAndRefreshedUnconditionally() = runTest {
        // Nothing can revalidate it, but it is still worth keeping: it is served at once and
        // replaced behind the caller. That is production's case for every one of these documents.
        val store = InMemoryRawResourceStore()
        val refreshed = CompletableDeferred<Unit>()
        var calls = 0
        val transport = RecordingTransport {
            calls += 1
            if (calls >= 2) refreshed.complete(Unit)
            TransportResponse(200, FIRST_STYLE.encodeToByteArray())
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        assertEquals(1, store.size(), "an entry with no validators is still stored")
        withRasterizer(transport, store) { rasterizer ->
            rasterizer.prepare(StyleInput.Remote(STYLE_URL))
            refreshed.await()
        }

        assertEquals(2, transport.requests().size)
        assertNull(transport.requests()[1].metadata.ifNoneMatch, "there was nothing to revalidate against")
        assertNull(transport.requests()[1].metadata.ifModifiedSince)
    }

    @Test
    fun anUncachedStyleIsFetchedInTheForegroundAndItsFailureIsRaised() = runTest {
        // The one path that still reaches the transport in front of the caller: there is nothing to
        // serve, so there is nothing to fall back to either.
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { TransportResponse(503, ByteArray(0)) }

        val failure = withRasterizer(transport, store) { rasterizer ->
            assertFailsWith<ResourceAcquisitionException> { rasterizer.prepare(StyleInput.Remote(STYLE_URL)) }
        }

        assertEquals(503, failure.statusCode)
        assertEquals(ResourceClass.STYLE, failure.resourceClass)
    }

    @Test
    fun aRefreshStillRunningWhenTheRasterizerClosesIsCancelled() = runTest {
        val store = InMemoryRawResourceStore()
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshCancelled = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            } else {
                refreshStarted.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    refreshCancelled.complete(Unit)
                }
            }
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        val rasterizer = rasterizerFor(transport, store)
        rasterizer.prepare(StyleInput.Remote(STYLE_URL))
        refreshStarted.await()

        rasterizer.close()
        rasterizer.awaitClosed()

        // It runs in the rasterizer's scope, so closing takes it with everything else; the permit it
        // held is returned by PriorityGate's non-cancellable release.
        refreshCancelled.await()
        assertTrue(refreshCancelled.isCompleted)
    }

    @Test
    fun aCaptivePortalPageIsNeitherStoredNorServedAsAStyle() = runTest {
        // A sign-in page answers 200 with HTML and no validators. Stored, it would be served to
        // every later preparation, fail to compile every time, and -- being the newest file -- be
        // the last thing an age-trimmed cache evicted.
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { TransportResponse(200, CAPTIVE_PORTAL_HTML.encodeToByteArray()) }

        withRasterizer(transport, store) { rasterizer ->
            assertFailsWith<ResourceDecodeException> { rasterizer.prepare(StyleInput.Remote(STYLE_URL)) }
        }

        assertEquals(0, store.size(), "nothing that is not a style document is stored")
    }

    @Test
    fun anAlreadyPoisonedStyleEntryIsEvictedOnReadAndRefetched() = runTest {
        // The guard cannot un-store what an earlier version wrote, so a poisoned entry has to heal
        // on its own: the read that finds it drops it and fetches in the foreground.
        val store = InMemoryRawResourceStore()
        store.write(
            styleKey(),
            StoredRawResource(
                bytes = CAPTIVE_PORTAL_HTML.encodeToByteArray(),
                contentDigest = CAPTIVE_PORTAL_HTML.encodeToByteArray().sha256Hex(),
                metadata = RawResourceMetadata(etag = "\"portal\"", storedAtEpochMillis = NOW_MILLIS),
            ),
        )
        val transport = RecordingTransport {
            TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
        }

        val digest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }

        assertTrue(digest.isNotEmpty())
        assertEquals(1, transport.requests().size, "the poisoned entry was refetched, not revalidated")
        assertNull(transport.requests()[0].metadata.ifNoneMatch, "its validators went with it")
    }

    @Test
    fun aBackgroundReplacementThatIsNotAStyleIsRejected() = runTest {
        val store = InMemoryRawResourceStore()
        val rejected = CompletableDeferred<Unit>()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            } else {
                TransportResponse(200, CAPTIVE_PORTAL_HTML.encodeToByteArray())
            }
        }
        val metrics = MetricsSink { metric ->
            if (metric.name == MetricName.BACKGROUND_REVALIDATION_FAILED) rejected.complete(Unit)
        }

        val coldDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        withRasterizer(transport, store, metrics) { rasterizer ->
            rasterizer.prepare(StyleInput.Remote(STYLE_URL))
            rejected.await()
        }
        val afterDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }

        assertEquals(coldDigest, afterDigest, "the store still holds the style, not the portal page")
    }

    @Test
    fun anInlineStyleNeverTouchesTheStore() = runTest {
        val transport = RecordingTransport { error("An inline style must not reach the transport") }
        val store = InMemoryRawResourceStore()

        withRasterizer(transport, store) { it.prepare(StyleInput.InlineJson(FIRST_STYLE)) }
        withRasterizer(transport, store) {
            it.prepare(StyleInput.Prefetched(FIRST_STYLE.encodeToByteArray(), canonicalIdentity = "test-style"))
        }

        assertEquals(0, store.size())
        assertEquals(0, transport.requests().size)
    }

    private fun styleKey(): RawResourceKey =
        RawResourceKey(STYLE_URL.withRedactedAuthenticationQuery().sha256Hex(), ResourceClass.STYLE)

    private fun rasterizerFor(
        transport: ResourceTransport,
        store: RawResourceStore,
        metricsSink: MetricsSink = MetricsSink.None,
    ): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = transport,
            rawResourceStore = store,
            metricsSink = metricsSink,
            clock = RentileClock { NOW_MILLIS },
        ),
    )

    private suspend fun <T> withRasterizer(
        transport: ResourceTransport,
        store: RawResourceStore,
        metricsSink: MetricsSink = MetricsSink.None,
        block: suspend (BasemapRasterizer) -> T,
    ): T {
        val rasterizer = rasterizerFor(transport, store, metricsSink)
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
}
