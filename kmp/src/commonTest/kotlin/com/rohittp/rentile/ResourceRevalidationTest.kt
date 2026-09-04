package com.rohittp.rentile

import com.rohittp.rentile.internal.renderSyntheticPng
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

private const val NOW_MILLIS = 1_000_000L
private val CLOSURE_CLASSES = listOf(ResourceClass.SPRITE_JSON, ResourceClass.SPRITE_IMAGE, ResourceClass.TILE_JSON)

/**
 * The documents a style names are revalidated, not trusted forever.
 *
 * The sprite JSON, the sprite image and every source's TileJSON went through the raw store from the
 * start, but once written they were reused unconditionally and for good: a corrected icon sheet or
 * a source whose tile templates, zoom range or bounds moved upstream never reached a consumer that
 * had fetched the old document once. They now take the same path the style takes, with ADR 0007's
 * freshness shortcut the style deliberately declines.
 */
class ResourceRevalidationTest {
    @Test
    fun aWarmStartRevalidatesEveryClosureDocumentAndReusesItOnNotModified() = runTest {
        val store = InMemoryRawResourceStore()
        val transport = validatorBearingTransport()

        prepareClosure(transport, store)
        prepareClosure(transport, store)

        for (resourceClass in CLOSURE_CLASSES) {
            val requests = transport.requests(resourceClass)
            assertEquals(2, requests.size, "$resourceClass")
            assertNull(requests[0].metadata.ifNoneMatch, "$resourceClass had nothing to revalidate against")
            assertEquals("\"$resourceClass-1\"", requests[1].metadata.ifNoneMatch, "$resourceClass")
        }
    }

    @Test
    fun aChangedClosureDocumentIsReplacedAndTheNextStartRevalidatesTheReplacement() = runTest {
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { request ->
            when (request.metadata.ifNoneMatch) {
                null -> ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
                "\"${request.resourceClass}-1\"" ->
                    ok(request.resourceClass, changed = true, etag = "\"${request.resourceClass}-2\"")
                else -> TransportResponse(304, ByteArray(0))
            }
        }

        prepareClosure(transport, store)
        prepareClosure(transport, store)
        prepareClosure(transport, store)

        for (resourceClass in CLOSURE_CLASSES) {
            val requests = transport.requests(resourceClass)
            assertEquals(3, requests.size, "$resourceClass")
            assertEquals("\"$resourceClass-2\"", requests[2].metadata.ifNoneMatch, "$resourceClass revalidated the replacement")
        }
    }

    @Test
    fun aClosureDocumentWithNoValidatorsIsRefetchedRatherThanStored() = runTest {
        // Nothing could ever revalidate it, so storing it would only cost a payload read and a
        // SHA-256 on every later start.
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { request -> ok(request.resourceClass, changed = false, etag = null) }

        prepareClosure(transport, store)
        prepareClosure(transport, store)

        for (resourceClass in CLOSURE_CLASSES) {
            val requests = transport.requests(resourceClass)
            assertEquals(2, requests.size, "$resourceClass")
            requests.forEach { assertNull(it.metadata.ifNoneMatch, "$resourceClass") }
        }
        assertEquals(0, store.size(), "an unusable entry is not written")
    }

    @Test
    fun aFreshClosureDocumentIsUsedWithoutAskingTheOrigin() = runTest {
        // ADR 0007's shortcut, which these three may take and the style may not: they are not the
        // root of the closure.
        val store = InMemoryRawResourceStore()
        val transport = RecordingTransport { request ->
            ok(
                request.resourceClass,
                changed = false,
                etag = "\"${request.resourceClass}-1\"",
                expiresAtEpochMillis = NOW_MILLIS + 3_600_000L,
            )
        }

        prepareClosure(transport, store)
        assertEquals(3, transport.requests().size)
        transport.clear()
        prepareClosure(transport, store)

        assertEquals(emptyList(), transport.requests().map { it.resourceClass }, "a fresh entry needs no exchange")
    }

    @Test
    fun aFailedRevalidationFailsRatherThanServingTheStoredDocument() = runTest {
        for (failing in CLOSURE_CLASSES) {
            val store = InMemoryRawResourceStore()
            val transport = RecordingTransport { request ->
                when {
                    request.metadata.ifNoneMatch == null ->
                        ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
                    request.resourceClass == failing -> TransportResponse(503, ByteArray(0))
                    else -> TransportResponse(304, ByteArray(0))
                }
            }

            prepareClosure(transport, store)
            val failure = assertFailsWith<ResourceAcquisitionException>("$failing must not be served stale") {
                prepareClosure(transport, store)
            }

            assertEquals(503, failure.statusCode, "$failing")
            assertEquals(failing, failure.resourceClass, "$failing")
        }
    }

    @Test
    fun reusingAStoredDocumentRewritesItSoAnAgeTrimmedCacheDoesNotEvictIt() = runTest {
        // A consumer's raw cache is trimmed by file age. An entry read on every start and never
        // written becomes the oldest file in it, so the documents most worth keeping would be the
        // first to go.
        val store = WriteRecordingRawResourceStore()
        val transport = validatorBearingTransport()

        prepareClosure(transport, store)
        val afterFirstStart = store.writes()
        store.clearWrites()
        prepareClosure(transport, store)

        assertEquals(CLOSURE_CLASSES.toSet(), afterFirstStart.toSet())
        assertEquals(
            CLOSURE_CLASSES.toSet(),
            store.writes().toSet(),
            "a 304 must refresh the stored entry's recency",
        )
        assertTrue(store.everyWriteKeptItsBytes, "the rewrite must not change what is stored")
    }

    private fun validatorBearingTransport(): RecordingTransport = RecordingTransport { request ->
        if (request.metadata.ifNoneMatch != null) {
            TransportResponse(304, ByteArray(0))
        } else {
            ok(request.resourceClass, changed = false, etag = "\"${request.resourceClass}-1\"")
        }
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
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = transport,
                rawResourceStore = store,
                clock = RentileClock { NOW_MILLIS },
            ),
        )
        try {
            rasterizer.prepare(StyleInput.InlineJson(CLOSURE_STYLE))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
}
