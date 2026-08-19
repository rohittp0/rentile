package com.rohittp.rentile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ApiContractTest {
    @Test
    fun outputSizeAcceptsOnlyVersionOneSizes() {
        assertEquals(256, RenderOptions(256).outputSizePx)
        assertEquals(512, RenderOptions().outputSizePx)
        assertFailsWith<IllegalArgumentException> { RenderOptions(1024) }
    }

    @Test
    fun transportLoggingRedactsCredentialBearingUrlAndBody() {
        val request = TransportRequest(
            url = "https://tiles.example.test/style.json?key=top-secret",
            resourceClass = ResourceClass.STYLE,
            maxResponseBytes = 1024,
        )
        val response = TransportResponse(
            statusCode = 200,
            body = "private style body".encodeToByteArray(),
            metadata = TransportResponseMetadata(
                redirectLocation = "https://other.example.test/path?token=secret",
            ),
        )

        assertFalse(request.toString().contains("top-secret"))
        assertFalse(response.toString().contains("private style body"))
        assertFalse(response.toString().contains("token=secret"))
    }

    @Test
    fun credentialLoggingRedactsValue() {
        val credential = ProviderCredential(
            origin = "https://tiles.example.test",
            queryParameterName = "key",
            value = "top-secret",
        )

        assertFalse(credential.toString().contains("top-secret"))
        assertFalse(MapSession("session-secret", 1234).toString().contains("session-secret"))
        assertFalse(
            StyleInput.Prefetched(ByteArray(0), "https://example.test/style?key=top-secret")
                .toString()
                .contains("top-secret"),
        )
    }

    @Test
    fun styleInputLoggingNeverIncludesStyleOrUrlSecrets() {
        val inline = StyleInput.InlineJson("""{"secret":"top-secret"}""")
        val remote = StyleInput.Remote("https://tiles.example.test/style.json?key=top-secret")

        assertFalse(inline.toString().contains("top-secret"))
        assertFalse(remote.toString().contains("top-secret"))
    }

    @Test
    fun externalTransportFailureCannotLeakItsMessageThroughRentileException() = runTest {
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    throw IllegalStateException("request failed: https://example.test?key=top-secret")
                },
                rawResourceStore = object : RawResourceStore {
                    override suspend fun read(key: RawResourceKey): StoredRawResource? = null
                    override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = Unit
                    override suspend fun remove(key: RawResourceKey) = Unit
                },
            ),
        )
        try {
            val error = assertFailsWith<ResourceAcquisitionException> {
                rasterizer.prepare(StyleInput.Remote("https://example.test/style?key=top-secret"))
            }
            assertFalse(error.stackTraceToString().contains("top-secret"))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun glyphLimitsAreValidatedAndDefaulted() {
        val limits = ResourceLimits()

        assertEquals(1L * 1024L * 1024L, limits.maxGlyphRangeBytes)
        assertEquals(64, limits.maxGlyphRangesPerBatch)
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maxGlyphRangeBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maxGlyphRangesPerBatch = 0) }
    }

    @Test
    fun glyphRangeIsAResourceClass() {
        assertTrue(ResourceClass.entries.contains(ResourceClass.GLYPH_RANGE))
    }
}
