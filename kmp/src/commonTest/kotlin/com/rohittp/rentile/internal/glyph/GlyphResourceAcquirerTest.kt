package com.rohittp.rentile.internal.glyph

import com.rohittp.rentile.InMemoryRawResourceStore
import com.rohittp.rentile.RentileConfiguration
import com.rohittp.rentile.ResourceDecodeException
import com.rohittp.rentile.ResourceTransport
import com.rohittp.rentile.TransportResponse
import com.rohittp.rentile.internal.ResourceWorkCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GlyphResourceAcquirerTest {
    @Test
    fun mapsCodepointsToTheirRangeStart() {
        assertEquals(0, GlyphResourceAcquirer.rangeStartFor('A'.code))
        assertEquals(0, GlyphResourceAcquirer.rangeStartFor(255))
        assertEquals(256, GlyphResourceAcquirer.rangeStartFor(256))
        assertEquals(19968, GlyphResourceAcquirer.rangeStartFor(0x4E2D))
    }

    @Test
    fun substitutesTheFontStackAndRangeIntoTheTemplate() {
        val url = GlyphResourceAcquirer.resolveUrl(
            template = "https://glyphs.example.test/fonts/{fontstack}/{range}.pbf?key=secret",
            fontStack = "Open Sans Regular,Noto Sans Regular",
            rangeStart = 256,
        )

        assertEquals(
            "https://glyphs.example.test/fonts/Open%20Sans%20Regular,Noto%20Sans%20Regular/256-511.pbf?key=secret",
            url,
        )
    }

    @Test
    fun acquiresOnceForConcurrentRequestsAndSharesTheContentDigest() = runTest {
        // Mirrors RentileRuntimeTest's identicalConcurrentFetchesUseLastWaiterSingleFlight: the
        // second request is only launched once the first has reached the transport, and the
        // transport does not return until both are in flight, so single-flight joining - not
        // scheduler luck - is what keeps requestCount at 1.
        var requestCount = 0
        val requestsMutex = Mutex()
        val transportStarted = CompletableDeferred<Unit>()
        val releaseTransport = CompletableDeferred<Unit>()
        val payload = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(
                    name = "Open Sans Regular",
                    range = "0-255",
                    glyphs = listOf(
                        Glyph(id = 65, width = 0, height = 0, left = 0, top = 0, advance = 12),
                    ),
                ),
            ),
        ).encode()
        val configuration = RentileConfiguration(
            transport = ResourceTransport {
                requestsMutex.withLock { requestCount += 1 }
                transportStarted.complete(Unit)
                releaseTransport.await()
                TransportResponse(200, payload)
            },
            rawResourceStore = InMemoryRawResourceStore(),
        )

        // supervisorScope, not coroutineScope: DefaultBasemapRasterizer always hands acquirers a
        // supervisor-rooted scope so one acquisition's SingleFlight work cannot cancel its
        // scope-mates by failing; a plain coroutineScope here would make that failure propagate
        // and race with (or replace) whatever this test is asserting.
        val (first, second) = supervisorScope {
            val acquirer = GlyphResourceAcquirer(
                configuration = configuration,
                scope = this,
                workCoordinator = ResourceWorkCoordinator(configuration.executionPolicy),
            )
            val firstRequest = async {
                acquirer.acquire(
                    template = "https://glyphs.example.test/fonts/{fontstack}/{range}.pbf",
                    fontStack = "Open Sans Regular",
                    rangeStart = 0,
                )
            }
            transportStarted.await()
            val secondRequest = async {
                acquirer.acquire(
                    template = "https://glyphs.example.test/fonts/{fontstack}/{range}.pbf",
                    fontStack = "Open Sans Regular",
                    rangeStart = 0,
                )
            }
            releaseTransport.complete(Unit)
            firstRequest.await() to secondRequest.await()
        }

        assertEquals(1, requestsMutex.withLock { requestCount })
        assertEquals(first.contentDigest, second.contentDigest)
    }

    @Test
    fun wrapsAMalformedGlyphPayloadInATypedException() = runTest {
        // The bitmap's declared extent (10 + 6) * (14 + 6) does not match its actual size (4
        // bytes), which GlyphRangeDecoder.decode already rejects via `require` - a bare
        // IllegalArgumentException that must not escape acquire() untyped.
        val malformedPayload = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(
                    name = "Open Sans Regular",
                    range = "0-255",
                    glyphs = listOf(
                        Glyph(
                            id = 66, width = 10, height = 14, left = 0, top = 0, advance = 12,
                            bitmap = okio.ByteString.of(*ByteArray(4)),
                        ),
                    ),
                ),
            ),
        ).encode()
        val configuration = RentileConfiguration(
            transport = ResourceTransport { TransportResponse(200, malformedPayload) },
            rawResourceStore = InMemoryRawResourceStore(),
        )

        supervisorScope {
            val acquirer = GlyphResourceAcquirer(
                configuration = configuration,
                scope = this,
                workCoordinator = ResourceWorkCoordinator(configuration.executionPolicy),
            )

            assertFailsWith<ResourceDecodeException> {
                acquirer.acquire(
                    template = "https://glyphs.example.test/fonts/{fontstack}/{range}.pbf",
                    fontStack = "Open Sans Regular",
                    rangeStart = 0,
                )
            }
        }
    }
}
