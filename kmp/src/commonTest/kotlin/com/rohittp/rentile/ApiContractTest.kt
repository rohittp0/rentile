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
        assertEquals(256, limits.maxGlyphRangesPerBatch)
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maxGlyphRangeBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maxGlyphRangesPerBatch = 0) }
    }

    @Test
    fun glyphRangeIsAResourceClass() {
        assertTrue(ResourceClass.entries.contains(ResourceClass.GLYPH_RANGE))
    }

    @Test
    fun labelCandidateGeometryCarriesNoScreenCoordinates() {
        // A compile-time contract check: a candidate exposes geography and label-local
        // geometry only. If someone later adds a screen-space field, this stops compiling
        // against the property list and the reviewer has to justify it.
        //
        // translateX/translateY were added and justified: text-translate is a pixel displacement
        // the style specification defines in pixels, so it joins padding, haloWidth and haloBlur as
        // a pixel-valued style scalar. It is an input to the consumer's screen placement, not a
        // screen position - Rentile cannot apply it, because the anchor it moves is geographic
        // until the consumer projects it. Ignoring it instead would silently misplace the label.
        val candidate = LabelCandidate(
            layerStyleIndex = 0,
            requestedTile = TileId(14, 14547, 6451),
            sourceTile = TileId(14, 14547, 6451),
            longitude = 139.6503, latitude = 35.6762,
            placement = LabelPlacement.POINT,
            line = emptyList(),
            rotationDegrees = 0.0,
            symbolSpacing = 250.0,
            keepUpright = true,
            avoidEdges = false,
            zOrder = SymbolZOrder.AUTO,
            textRotationDegrees = 0.0,
            maxAngleDegrees = 45.0,
            rotationAlignment = SymbolAlignment.AUTO,
            pitchAlignment = SymbolAlignment.AUTO,
            textOptional = false,
            glyphs = listOf(LabelGlyphQuad(entryIndex = 0, x = 0.0, y = 0.0, scale = 1.0)),
            boundingBox = LabelBox(left = -1.0, top = -1.0, right = 1.0, bottom = 1.0),
            icon = null,
            overlap = SymbolOverlap.NEVER, ignorePlacement = false,
            padding = 2.0, sortKey = 0.0,
            color = 0xff000000.toInt(), haloColor = 0x00000000,
            opacity = 1.0,
            haloWidth = 0.0, haloBlur = 0.0,
            translateX = 0.0, translateY = 0.0,
            translateAlignment = SymbolAlignment.MAP,
        )

        assertEquals(0, candidate.layerStyleIndex)
        assertEquals(139.6503, candidate.longitude)
        assertEquals(14, candidate.sourceTile.z)
    }

    @Test
    fun theGlyphAtlasComparesAndPrintsByValueNotByReference() {
        val one = LabelGlyphAtlas(byteArrayOf(1, 2, 3), 4, 4, "key", emptyList())
        val two = LabelGlyphAtlas(byteArrayOf(1, 2, 3), 4, 4, "key", emptyList())

        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        assertFalse(one.toString().contains("1, 2, 3"))
    }

    @Test
    fun labelCandidatePlanExceptionsCarryLifecycleCodes() {
        assertEquals(RentileErrorCode.FOREIGN_LABEL_CANDIDATE_PLAN, ForeignLabelCandidatePlanException().code)
        assertEquals(PipelineStage.LIFECYCLE, ForeignLabelCandidatePlanException().stage)
        assertEquals(RentileErrorCode.LABEL_CANDIDATE_PLAN_CLOSED, LabelCandidatePlanClosedException().code)
        assertEquals(PipelineStage.LIFECYCLE, LabelCandidatePlanClosedException().stage)
        assertEquals(RentileErrorCode.GLYPH_TEMPLATE_MISMATCH, GlyphTemplateMismatchException().code)
        assertEquals(PipelineStage.RESOURCE_ACQUISITION, GlyphTemplateMismatchException().stage)
    }

    @Test
    fun glyphTemplateMismatchNeverEchoesATemplate() {
        // The message is a fact, not a diff: echoing either template could print a credential.
        val message = GlyphTemplateMismatchException().message.orEmpty()
        assertFalse(message.contains("http"))
        assertFalse(message.contains("{fontstack}"))
    }
}
