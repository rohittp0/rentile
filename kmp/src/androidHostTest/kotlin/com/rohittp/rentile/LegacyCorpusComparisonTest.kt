package com.rohittp.rentile

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LegacyCorpusComparisonTest {
    @Test
    fun captureFreezesResponseAndReplayNeverCallsNetwork(): Unit = runBlocking {
        withDirectories { snapshot, captureReport, replayReport ->
            var calls = 0
            val capture = LegacyCorpusComparison("CAPTURE", snapshot, captureReport)
            val transport = capture.wrap(ResourceTransport {
                calls++
                TransportResponse(200, byteArrayOf(1, 2, 3), TransportResponseMetadata(
                    contentType = "image/png", etag = "private-etag", lastModified = "today",
                    cacheControl = "max-age=600", expiresAtEpochMillis = 123, vary = listOf("Accept"),
                    retryAfterMillis = 456, redirectLocation = null, wireByteCount = 99,
                ))
            })
            val request = request()
            transport.execute(request)
            transport.execute(request)
            capture.finish()
            assertEquals(1, calls)
            val replay = LegacyCorpusComparison("REPLAY", snapshot, replayReport)
            val response = replay.wrap(ResourceTransport { error("Replay accessed network") }).execute(request)
            replay.finish()
            assertEquals(listOf<Byte>(1, 2, 3), response.body.toList())
            assertEquals("image/png", response.metadata.contentType)
            assertEquals("private-etag", response.metadata.etag)
            assertEquals("today", response.metadata.lastModified)
            assertEquals("max-age=600", response.metadata.cacheControl)
            assertEquals(123L, response.metadata.expiresAtEpochMillis)
            assertEquals(listOf("Accept"), response.metadata.vary)
            assertEquals(456L, response.metadata.retryAfterMillis)
            assertEquals(99L, response.metadata.wireByteCount)
            assertFalse(Files.readString(captureReport.resolve("acquisition.tsv")).contains("secret"))
            assertFalse(Files.readString(captureReport.resolve("acquisition.tsv")).contains("private-etag"))
        }
    }

    @Test
    fun replayRejectsMissingRequestWithoutTransportFallback(): Unit = runBlocking {
        withDirectories { snapshot, _, report ->
            LegacyCorpusComparison("CAPTURE", snapshot, report)
            val transport = LegacyCorpusComparison("REPLAY", snapshot, report)
                .wrap(ResourceTransport { error("Unexpected fallback") })
            assertFailsWith<IllegalStateException> { transport.execute(request()) }
        }
    }

    @Test
    fun replayRejectsCorruptedFrozenBody(): Unit = runBlocking {
        withDirectories { snapshot, captureReport, replayReport ->
            LegacyCorpusComparison("CAPTURE", snapshot, captureReport)
                .wrap(ResourceTransport { TransportResponse(200, byteArrayOf(1, 2, 3)) }).execute(request())
            val body = Files.list(snapshot).use { files -> files.filter { it.toString().endsWith(".body") }.findFirst().get() }
            Files.write(body, byteArrayOf(9))
            val replay = LegacyCorpusComparison("REPLAY", snapshot, replayReport)
                .wrap(ResourceTransport { error("Unexpected fallback") })
            assertFailsWith<IllegalStateException> { replay.execute(request()) }
        }
    }

    @Test
    fun replayRetainsRequestResponseByteLimit(): Unit = runBlocking {
        withDirectories { snapshot, captureReport, replayReport ->
            LegacyCorpusComparison("CAPTURE", snapshot, captureReport)
                .wrap(ResourceTransport { TransportResponse(200, byteArrayOf(1, 2, 3)) }).execute(request())
            val replay = LegacyCorpusComparison("REPLAY", snapshot, replayReport)
                .wrap(ResourceTransport { error("Unexpected fallback") })
            assertFailsWith<IllegalStateException> { replay.execute(request(2)) }
        }
    }

    @Test
    fun legacyOptionsReflectionWorksOnBothSidesOfTheApiChange() {
        val supportsSetting = RenderOptions::class.java.methods.any { it.name == "getRasterAcquisitionDensity" }
        for (size in listOf(256, 512)) for (hillshade in listOf(false, true)) {
            if (supportsSetting) {
                val result = LegacyCorpusComparison.options(size, hillshade, "OUTPUT_ZOOM")
                assertEquals(size, result.outputSizePx)
                assertEquals(hillshade, result.includeHillshade)
                assertEquals("OUTPUT_ZOOM", RenderOptions::class.java.getMethod("getRasterAcquisitionDensity")
                    .invoke(result).toString())
                assertFailsWith<IllegalStateException> { LegacyCorpusComparison.options(size, hillshade, "BASELINE") }
            } else {
                assertEquals(RenderOptions(size, hillshade), LegacyCorpusComparison.options(size, hillshade, "BASELINE"))
                assertFailsWith<NoSuchMethodException> { LegacyCorpusComparison.options(size, hillshade, "OUTPUT_ZOOM") }
            }
        }
    }

    @Test
    fun snapshotClockIgnoresElapsedWallTimeAndCaptureCannotBeReused(): Unit = runBlocking {
        withDirectories { snapshot, captureReport, replayReport ->
            val capture = LegacyCorpusComparison("CAPTURE", snapshot, captureReport, RentileClock { 100 })
            val replay = LegacyCorpusComparison("REPLAY", snapshot, replayReport, RentileClock { 999999 })
            repeat(3) {
                assertEquals(100L, capture.clock.nowEpochMillis())
                assertEquals(100L, replay.clock.nowEpochMillis())
            }
            assertFailsWith<IllegalArgumentException> {
                LegacyCorpusComparison("CAPTURE", snapshot, captureReport, RentileClock { 200 })
            }
            assertEquals("100\n", Files.readString(snapshot.resolve("clock-epoch-millis.txt")))
        }
    }

    @Test
    fun replayRejectsMissingInvalidOrOverflowingClockAndCaptureRejectsDirtySnapshot(): Unit = runBlocking {
        withDirectories { snapshot, captureReport, replayReport ->
            assertFailsWith<IllegalArgumentException> { LegacyCorpusComparison("REPLAY", snapshot, replayReport) }
            val path = snapshot.resolve("clock-epoch-millis.txt")
            for (text in listOf("", "0", "-1", "NaN", "1.5", "9223372036854775808")) {
                Files.writeString(path, text)
                assertFailsWith<IllegalArgumentException> { LegacyCorpusComparison("REPLAY", snapshot, replayReport) }
            }
            assertFailsWith<IllegalArgumentException> { LegacyCorpusComparison("CAPTURE", snapshot, captureReport) }
            Files.delete(path)
            Files.writeString(snapshot.resolve("unexpected.body"), "existing")
            assertFailsWith<IllegalArgumentException> { LegacyCorpusComparison("CAPTURE", snapshot, captureReport) }
        }
    }

    private fun request(maxResponseBytes: Long = 1024): TransportRequest = TransportRequest(
        "https://example.test/tile?token=secret", ResourceClass.RASTER_TILE, maxResponseBytes,
    )

    private suspend fun withDirectories(block: suspend (Path, Path, Path) -> Unit) {
        val root = Files.createTempDirectory("rentile-legacy-corpus-test")
        try {
            block(Files.createDirectory(root.resolve("private")), Files.createDirectory(root.resolve("capture")),
                Files.createDirectory(root.resolve("replay")))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
