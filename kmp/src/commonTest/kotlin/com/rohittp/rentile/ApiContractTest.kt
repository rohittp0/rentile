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
        assertEquals(setOf(256, 512, 1024, 2048), RenderOptions.SUPPORTED_OUTPUT_SIZES)
        assertEquals(256, RenderOptions(256).outputSizePx)
        assertEquals(512, RenderOptions().outputSizePx)
        assertEquals(1024, RenderOptions(1024).outputSizePx)
        assertEquals(2048, RenderOptions(2048).outputSizePx)
        // The set is closed, and it is closed to powers of two either side of the supported run as
        // well as to anything between them: every size has to be an exact ratio of the style
        // reference size for `outputSizePx / 512` to be a pixel ratio rather than a resampling.
        for (rejected in listOf(0, -512, 128, 384, 768, 1000, 4096)) {
            assertFailsWith<IllegalArgumentException>("outputSizePx $rejected must be rejected") {
                RenderOptions(rejected)
            }
        }
    }

    @Test
    fun theStyleReferenceSizeIsFiveHundredAndTwelveIndependentlyOfTheDefault() {
        // `outputSizePx / 512` is the pixel ratio the whole draw path is built on, and 512 is the
        // size styles author their pixel values against. Asserting the literal keeps it from
        // drifting after the default, which is a separate decision that happens to agree today.
        assertEquals(512, STYLE_REFERENCE_TILE_SIZE_PX)
        assertEquals(STYLE_REFERENCE_TILE_SIZE_PX, RenderOptions.DEFAULT_OUTPUT_SIZE_PX)
        assertTrue(RenderOptions.SUPPORTED_OUTPUT_SIZES.all { it % 256 == 0 })
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

    @Test
    fun theSpriteAtlasComparesAndPrintsByValueNotByReference() {
        val entry = SpriteImageEntry(
            name = "shield", x = 0, y = 0, width = 8, height = 8, pixelRatio = 2.0, sdf = true,
            stretchX = listOf(SpriteStretchRange(2.0, 6.0)), stretchY = null,
            content = SpriteContentBox(1.0, 1.0, 7.0, 7.0),
        )
        val one = SpriteAtlas(byteArrayOf(1, 2, 3), 8, 8, 2, "key", mapOf("shield" to entry))
        val two = SpriteAtlas(byteArrayOf(1, 2, 3), 8, 8, 2, "key", mapOf("shield" to entry.copy()))

        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        assertFalse(one == SpriteAtlas(byteArrayOf(1, 2, 4), 8, 8, 2, "key", mapOf("shield" to entry)))
        assertFalse(one == two.copy(pixelRatio = 1))
        assertFalse(one.toString().contains("1, 2, 3"))
        assertTrue(one.toString().contains("entryCount=1"))
    }

    @Test
    fun anImplementerWrittenBeforeTheSpriteAtlasStillCompilesAndSaysItCannot() = runTest {
        // BasemapRasterizer is implemented outside this module - a consumer's test fake is the
        // usual case - so a new member must not break it. The default declines loudly rather
        // than returning null, which would claim the style declares no sprite.
        val legacy: BasemapRasterizer = LegacyRasterizer()
        val style = object : PreparedStyle {
            override val digest: String = "digest"
            override val policy: CompatibilityPolicy = CompatibilityPolicy.Default
            override val diagnostics: List<RenderDiagnostic> = emptyList()
        }

        assertFailsWith<UnsupportedOperationException> { legacy.acquireSpriteAtlas(style) }
    }

    @Test
    fun theGlyphAtlasPolicyDefaultsToWhatEveryEarlierReleasePacked() {
        val policy = LabelGlyphAtlasPolicy()

        assertEquals(LabelGlyphPacking.ACQUIRED_RANGES, policy.packing)
        assertEquals(null, policy.maxDimensionPx)
        assertEquals(policy, RentileConfiguration(TRANSPORT, STORE).labelGlyphAtlas)
        assertEquals(4096, LabelGlyphAtlasPolicy(maxDimensionPx = 4096).maxDimensionPx)
        for (rejected in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException>("maxDimensionPx $rejected must be rejected") {
                LabelGlyphAtlasPolicy(maxDimensionPx = rejected)
            }
        }
    }

    /** Overrides exactly the members `BasemapRasterizer` had before `acquireSpriteAtlas`. */
    private class LegacyRasterizer : BasemapRasterizer {
        override suspend fun prepare(style: StyleInput, policy: CompatibilityPolicy): PreparedStyle = TODO()
        override fun outputRequestKey(style: PreparedStyle, tile: TileId, options: RenderOptions): String = TODO()
        override suspend fun prepareBatch(
            style: PreparedStyle,
            tiles: List<TileId>,
            options: RenderOptions,
            resourceAccess: ResourceAccessMode,
            substitutionPolicy: TileSubstitutionPolicy,
        ): PreparedBatch = TODO()
        override suspend fun retryExact(batch: PreparedBatch): ExactRecoveryResult = TODO()
        override fun labelLayerDescriptors(style: PreparedStyle): List<LabelLayerDescriptor> = TODO()
        override suspend fun warmRawResources(
            style: PreparedStyle,
            tiles: List<TileId>,
            resourceAccess: ResourceAccessMode,
        ): RawWarmSummary = TODO()
        override suspend fun acquireLabelTiles(
            style: PreparedStyle,
            tiles: List<TileId>,
            resourceAccess: ResourceAccessMode,
        ): List<ValidatedMvtTile> = TODO()
        override fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String = TODO()
        override suspend fun planLabelCandidates(
            style: PreparedStyle,
            tiles: List<TileId>,
            resourceAccess: ResourceAccessMode,
        ): LabelCandidatePlan = TODO()
        override suspend fun acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch = TODO()
        override suspend fun acquireLabelCandidates(
            style: PreparedStyle,
            tiles: List<TileId>,
            resourceAccess: ResourceAccessMode,
        ): LabelCandidateBatch = TODO()
        override fun terrainSourceDescriptor(style: PreparedStyle): TerrainSourceDescriptor? = TODO()
        override fun groundRadianceDescriptor(style: PreparedStyle): GroundRadianceDescriptor? = TODO()
        override suspend fun acquireTerrainTiles(
            style: PreparedStyle,
            tiles: List<TileId>,
            resourceAccess: ResourceAccessMode,
        ): List<ValidatedDemTile> = TODO()
        override suspend fun render(batch: PreparedBatch, tiles: List<TileId>, priority: RenderPriority): RenderBatch =
            TODO()
        override suspend fun renderRaw(
            batch: PreparedBatch,
            tiles: List<TileId>,
            priority: RenderPriority,
        ): RawRenderBatch = TODO()
        override suspend fun render(
            style: PreparedStyle,
            tiles: List<TileId>,
            options: RenderOptions,
            resourceAccess: ResourceAccessMode,
            substitutionPolicy: TileSubstitutionPolicy,
            priority: RenderPriority,
        ): RenderBatch = TODO()
        override fun close() = Unit
        override suspend fun awaitClosed() = Unit
    }

    private companion object {
        val TRANSPORT = ResourceTransport { error("No transport is used") }
        val STORE = object : RawResourceStore {
            override suspend fun read(key: RawResourceKey): StoredRawResource? = null
            override suspend fun write(key: RawResourceKey, resource: StoredRawResource) = Unit
            override suspend fun remove(key: RawResourceKey) = Unit
        }
    }
}
