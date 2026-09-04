package com.rohittp.rentile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private const val STYLE_URL = "https://styles.example.test/basic.json?key=secret"
private const val FIRST_STYLE =
    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#102030"}}]}"""
private const val SECOND_STYLE =
    """{"version":8,"layers":[{"id":"base","type":"background","paint":{"background-color":"#a0b0c0"}}]}"""

/**
 * A style is a raw resource like any other, and a warm start should say so.
 *
 * Style acquisition went straight to the transport, past the raw store, so every process start
 * downloaded the style document again -- on the critical path of the first frame, before a single
 * tile could be planned. Sprite JSON, sprite image and TileJSON had been going through the store
 * all along.
 */
class StyleRevalidationTest {
    @Test
    fun aWarmStartRevalidatesTheCachedStyleAndReusesItOnNotModified() = runTest {
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == "\"v1\"") {
                TransportResponse(304, ByteArray(0))
            } else {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            }
        }

        val coldDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        // A second rasterizer over the same store is what a process restart looks like from here.
        val warmDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }

        assertEquals(coldDigest, warmDigest, "the 304 must yield the cached style unchanged")
        val requests = transport.requests()
        assertEquals(2, requests.size)
        assertNull(requests[0].ifNoneMatch, "nothing was cached yet")
        assertEquals("\"v1\"", requests[1].ifNoneMatch)
    }

    @Test
    fun aStyleThatChangedIsReplacedRatherThanRevalidatedAway() = runTest {
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { request ->
            when (request.metadata.ifNoneMatch) {
                null -> TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
                "\"v1\"" -> TransportResponse(200, SECOND_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v2\""))
                else -> TransportResponse(304, ByteArray(0))
            }
        }

        val firstDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        val replacedDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        val revalidatedDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }

        assertEquals(3, transport.requests().size)
        assertNotEquals(firstDigest, replacedDigest)
        // The replacement, not the original, is what the third start revalidates against.
        assertEquals("\"v2\"", transport.requests()[2].ifNoneMatch)
        assertEquals(replacedDigest, revalidatedDigest)
    }

    @Test
    fun aCachedStyleWithOnlyALastModifiedRevalidatesWithIfModifiedSince() = runTest {
        val store = InMemoryRawResourceStore()
        val lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"
        val transport = RecordingTransport { request ->
            if (request.metadata.ifModifiedSince == lastModified) {
                TransportResponse(304, ByteArray(0))
            } else {
                TransportResponse(
                    200,
                    FIRST_STYLE.encodeToByteArray(),
                    TransportResponseMetadata(lastModified = lastModified),
                )
            }
        }

        val coldDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }
        val warmDigest = withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)).digest }

        assertEquals(coldDigest, warmDigest)
        assertEquals(lastModified, transport.requests()[1].ifModifiedSince)
        assertNull(transport.requests()[1].ifNoneMatch)
    }

    @Test
    fun aStyleWithNoValidatorsIsFetchedUnconditionally() = runTest {
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { TransportResponse(200, FIRST_STYLE.encodeToByteArray()) }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }

        assertEquals(2, transport.requests().size)
        transport.requests().forEach { metadata ->
            assertNull(metadata.ifNoneMatch)
            assertNull(metadata.ifModifiedSince)
        }
    }

    @Test
    fun aFailedRevalidationFailsRatherThanServingTheStaleStyle() = runTest {
        // Tiles may be substituted when acquisition fails; a style may not. Reusing an entry the
        // origin declined to confirm would compile a rendering program nobody chose, and every
        // resource identity below it descends from that program.
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { request ->
            if (request.metadata.ifNoneMatch == null) {
                TransportResponse(200, FIRST_STYLE.encodeToByteArray(), TransportResponseMetadata(etag = "\"v1\""))
            } else {
                TransportResponse(503, ByteArray(0))
            }
        }

        withRasterizer(transport, store) { it.prepare(StyleInput.Remote(STYLE_URL)) }
        val failure = withRasterizer(transport, store) { rasterizer ->
            assertFailsWith<ResourceAcquisitionException> { rasterizer.prepare(StyleInput.Remote(STYLE_URL)) }
        }

        assertEquals(503, failure.statusCode)
        assertEquals(ResourceClass.STYLE, failure.resourceClass)
    }

    @Test
    fun anInlineStyleNeverTouchesTheStore() = runTest {
        val transport = RecordingTransport { error("An inline style must not reach the transport") }
        val store = InMemoryRawResourceStore()

        withRasterizer(transport, store) { it.prepare(StyleInput.InlineJson(FIRST_STYLE)) }
        withRasterizer(transport, store) { it.prepare(StyleInput.Prefetched(FIRST_STYLE.encodeToByteArray(), canonicalIdentity = "test-style")) }

        assertEquals(0, store.size())
        assertEquals(0, transport.requests().size)
    }

    private suspend fun <T> withRasterizer(
        transport: ResourceTransport,
        store: RawResourceStore,
        block: suspend (BasemapRasterizer) -> T,
    ): T {
        val rasterizer = Rentile.create(RentileConfiguration(transport = transport, rawResourceStore = store))
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
}

/** Records the request metadata each exchange carried, in order. */
private class RecordingTransport(
    private val respond: suspend (TransportRequest) -> TransportResponse,
) : ResourceTransport {
    private val mutex = Mutex()
    private val recorded = mutableListOf<TransportRequestMetadata>()

    override suspend fun execute(request: TransportRequest): TransportResponse {
        mutex.withLock { recorded += request.metadata }
        return respond(request)
    }

    fun requests(): List<TransportRequestMetadata> = recorded.toList()
}
