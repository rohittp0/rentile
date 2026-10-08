package com.rohittp.rentile

import com.rohittp.rentile.internal.renderSyntheticPng
import com.rohittp.rentile.internal.sha256Hex
import com.rohittp.rentile.internal.sprite.toPublicSpriteAtlas
import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host-owned-symbols profile (ADR 0035) together with the two atlases a host draws it from
 * (ADR 0036): the sprite sheet and the label glyph atlas under each packing and cap.
 *
 * Each feature was proved on its own branch; this proves them together. An icon-only candidate has
 * no glyphs, so referenced-only packing must leave it alone while re-indexing every text candidate
 * beside it; a `text-field` override can give an icon-only layer glyphs to pack; and the sheet
 * `acquireSpriteAtlas` hands back must be the one every icon-only candidate was sized from.
 */
class HostSymbolsAtlasesTest {
    private val host = CompatibilityPolicy.RentileV1HostSymbols
    private val tile = TileId(2, 1, 1)
    private val referenced = LabelGlyphAtlasPolicy(packing = LabelGlyphPacking.REFERENCED_GLYPHS)
    private val english = LabelCandidateOptions(textFieldOverride = """["coalesce",["get","name:en"],["get","name"]]""")

    private val iconOnly =
        """{"id":"poi-icons","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-size":2}}"""
    private val textAndIcon =
        """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
            """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"icon-image":"marker"}}"""

    /** A localized name, a feature with no name at all, and a name with no `name:en`. */
    private val poiTile = LabelFixtures.vectorTile(
        mapOf(
            "poi" to listOf(
                LabelFixtures.Feature(mapOf("name" to "Kafe", "name:en" to "Cafe"), listOf(1024 to 1024), id = 1),
                LabelFixtures.Feature(mapOf("kind" to "bench"), listOf(3000 to 3000), id = 2),
                LabelFixtures.Feature(mapOf("name" to "Oslo"), listOf(1500 to 3500), id = 3),
            ),
        ),
    )
    private val glyphRange = LabelFixtures.glyphRange()

    @Test
    fun referencedPackingUnderTheHostProfilePacksTextAndLeavesIconOnlyCandidatesGlyphless() = runTest {
        val styleJson = LabelFixtures.style(iconOnly, textAndIcon)
        val (allStyle, all) = acquire(styleJson)
        val (style, packed) = acquire(styleJson, policy = referenced)

        // Three icon-only candidates from the icon layer, and from the text layer two with text and
        // the nameless bench's icon alone, its text lost.
        val iconAlone = packed.candidates.filter { it.glyphs.isEmpty() }
        val withText = packed.candidates.filter { it.glyphs.isNotEmpty() }
        assertEquals(6, packed.candidates.size)
        assertEquals(4, iconAlone.size)
        assertEquals(listOf("Kafe", "Oslo"), withText.map { it.text })
        assertTrue(iconAlone.all { it.text == null && it.textSize == null && it.icon != null })

        // Only the glyphs the two names draw are packed, one entry each, and every quad indexes a
        // real entry naming the glyph the full atlas gave it.
        assertEquals("KafeOslo".map { it.code }.toSet(), packed.atlas.entries.map { it.codepoint }.toSet())
        assertEquals(8, packed.atlas.entries.size)
        assertTrue(packed.candidates.flatMap { it.glyphs }.all { it.entryIndex in packed.atlas.entries.indices })
        assertEquals(all.candidates.size, packed.candidates.size)
        for ((before, after) in all.candidates.zip(packed.candidates)) {
            assertEquals(before.copy(glyphs = emptyList()), after.copy(glyphs = emptyList()))
            assertEquals(before.glyphs.map { Triple(it.x, it.y, it.scale) }, after.glyphs.map { Triple(it.x, it.y, it.scale) })
            assertEquals(
                before.glyphs.map { all.atlas.entries[it.entryIndex].codepoint },
                after.glyphs.map { packed.atlas.entries[it.entryIndex].codepoint },
            )
        }
        // An icon-only candidate is identical under both packings: there is nothing to re-index.
        assertEquals(all.candidates.filter { it.glyphs.isEmpty() }, iconAlone)

        // Both keys carry the 0.12.1 markers, and only the referenced packing adds its part.
        assertEquals(allStyle.digest, style.digest)
        assertEquals("label-candidates-4|${style.digest}|2/1/1|glyph-packing:referenced".sha256Hex(), packed.requestKey)
        assertEquals("label-candidates-4|${style.digest}|2/1/1".sha256Hex(), all.requestKey)
        val contentBase = "rentile-label-candidates-4\n${style.digest}\n${poiTile.sha256Hex()}\n${glyphRange.sha256Hex()}\n2/1/1"
        assertEquals("$contentBase\nglyph-packing:referenced".sha256Hex(), packed.batch.contentKey)
        assertEquals(contentBase.sha256Hex(), all.batch.contentKey)
    }

    @Test
    fun anOverrideGivesIconOnlyLayersTextAndReferencedPackingPacksOnlyWhatItDraws() = runTest {
        val styleJson = LabelFixtures.style(iconOnly, textAndIcon)
        val (style, packed) = acquire(styleJson, policy = referenced, options = english)
        val (_, unpacked) = acquire(styleJson, options = english)
        val (_, ownText) = acquire(styleJson, policy = referenced)

        // The icon layer now has text where the feature has a name in either key; the bench has
        // none, so it stays an icon alone in both layers.
        val iconLayerIndex = packed.batch.layerStyles.indexOfFirst { it.layerId == "poi-icons" }
        val iconLayer = packed.candidates.filter { it.layerStyleIndex == iconLayerIndex }
        assertEquals(listOf("Cafe", null, "Oslo"), iconLayer.map { it.text })
        assertTrue(iconLayer.filter { it.text != null }.all { it.glyphs.isNotEmpty() && it.icon != null })
        assertEquals(listOf("Cafe", "Oslo", "Cafe", "Oslo"), packed.candidates.mapNotNull { it.text })
        assertTrue(packed.candidates.filter { it.text == null }.all { it.glyphs.isEmpty() })

        // Exactly the referenced glyphs: "Kafe" is no longer drawn, so its K is not packed even
        // though its range was acquired, and each glyph appears once per font stack drawing it.
        val drawn = packed.candidates.flatMap { candidate -> candidate.glyphs.map { packed.atlas.entries[it.entryIndex] } }
        assertEquals("CafeOslo".map { it.code }.toSet(), packed.atlas.entries.map { it.codepoint }.toSet())
        assertEquals(drawn.map { it.fontStackDigest to it.codepoint }.toSet(), packed.atlas.entries.map { it.fontStackDigest to it.codepoint }.toSet())
        assertEquals(packed.atlas.entries.size, packed.atlas.entries.map { it.fontStackDigest to it.codepoint }.toSet().size)
        assertTrue(unpacked.atlas.entries.size > packed.atlas.entries.size)
        assertTrue(unpacked.atlas.entries.any { it.codepoint == 'K'.code })

        // The request key folds in the override, then the packing; each combination is distinct.
        val identity = assertNotNull(english.textFieldIdentity)
        assertEquals(
            "label-candidates-4|${style.digest}|2/1/1|text-field:${identity.sha256Hex()}|glyph-packing:referenced".sha256Hex(),
            packed.requestKey,
        )
        val contentKeys = listOf(packed, unpacked, ownText).map { it.batch.contentKey }
        assertEquals(3, contentKeys.toSet().size)
        assertEquals(3, listOf(packed, unpacked, ownText).map { it.requestKey }.toSet().size)
    }

    @Test
    fun ratioOneIsTheSheetTheIconOnlyCandidatesWereSizedFromAndRatioTwoIsTheProvidersAt2x() = runTest {
        val transport = transport()
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(iconOnly, textAndIcon)), host)
            val batch = rasterizer.acquireLabelCandidates(style, listOf(tile))
            val requestKey = rasterizer.labelCandidateRequestKey(style, listOf(tile))
            val spriteRequests = transport.spriteRequests()

            val sheet = assertNotNull(rasterizer.acquireSpriteAtlas(style, pixelRatio = 1))
            // The very sheet preparation resolved - the one the candidates were sized from - and
            // handed back without another request.
            assertEquals(spriteRequests, transport.spriteRequests())
            val prepared = assertNotNull((style as CompiledPreparedStyle).spriteAtlas)
            assertEquals(prepared.toPublicSpriteAtlas(1), sheet)
            assertContentEquals(SHEET_1X_PNG, sheet.pngBytes)

            val sheet2x = assertNotNull(rasterizer.acquireSpriteAtlas(style, pixelRatio = 2))
            assertEquals(2, sheet2x.pixelRatio)
            assertContentEquals(SHEET_2X_PNG, sheet2x.pngBytes)
            // The JSON and the PNG are fetched concurrently, so only the set is fixed.
            assertEquals(
                setOf("${LabelFixtures.SPRITE}@2x.json", "${LabelFixtures.SPRITE}@2x.png"),
                transport.spriteRequests().drop(spriteRequests.size).toSet(),
            )
            assertEquals(spriteRequests.size + 2, transport.spriteRequests().size)

            val iconAlone = batch.candidates.filter { it.glyphs.isEmpty() }
            assertEquals(4, iconAlone.size)
            for (candidate in batch.candidates) {
                val icon = assertNotNull(candidate.icon)
                val one = sheet.entries.getValue(icon.imageName)
                val two = sheet2x.entries.getValue(icon.imageName)
                assertEquals(1.0, one.pixelRatio)
                assertEquals(2.0, two.pixelRatio)
                // Style pixels at ratio one times icon-size, which both sheets cover equally.
                assertEquals(one.width / one.pixelRatio * icon.size.tileZoomSize, icon.width)
                assertEquals(two.width / two.pixelRatio * icon.size.tileZoomSize, icon.width)
            }
            assertEquals(listOf(16.0, 16.0, 16.0), iconAlone.take(3).map { it.icon?.width })

            // Nothing about the candidates depends on which sheet the host draws from.
            assertEquals(requestKey, rasterizer.labelCandidateRequestKey(style, listOf(tile)))
            assertEquals(batch, rasterizer.acquireLabelCandidates(style, listOf(tile)))
        }
    }

    @Test
    fun theGlyphAtlasCapBindsUnderTheHostProfileWithoutTouchingItsIcons() = runTest {
        val styleJson = LabelFixtures.style(iconOnly, textAndIcon)
        val wideSheet = renderSyntheticPng(512)
        val (_, uncapped) = acquire(styleJson, spritePng = wideSheet)
        val (_, capped) = acquire(styleJson, policy = LabelGlyphAtlasPolicy(maxDimensionPx = 256), spritePng = wideSheet)
        val (_, rasterLimited) = acquire(
            styleJson,
            resourceLimits = ResourceLimits(maxRasterDimensionPx = 256),
            spritePng = wideSheet,
        )
        val (_, both) = acquire(
            styleJson,
            policy = LabelGlyphAtlasPolicy(LabelGlyphPacking.REFERENCED_GLYPHS, maxDimensionPx = 256),
            spritePng = wideSheet,
        )

        assertTrue(uncapped.atlas.width > 256, "the fixture's 94 glyphs fill a shelf wider than the cap")
        assertTrue(capped.atlas.width <= 256 && capped.atlas.height <= 256, "${capped.atlas.width}x${capped.atlas.height}")
        // The cap lays the glyph atlas out as lowering the raster limit would...
        assertEquals(rasterLimited.atlas, capped.atlas)
        // ...but the 512 px sheet stays admissible, so every icon-only candidate survives. Under the
        // lowered raster limit the sheet fails, and the host profile skips icons rather than failing.
        assertEquals(4, capped.candidates.count { it.glyphs.isEmpty() && it.icon != null })
        assertTrue(capped.candidates.all { it.icon != null })
        assertEquals(0, rasterLimited.candidates.count { it.glyphs.isEmpty() })
        assertTrue(rasterLimited.candidates.all { it.icon == null })
        assertTrue(rasterLimited.batch.diagnostics.any { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED })

        // A cap moves cells, never indices, so neither label key moves with it.
        assertEquals(uncapped.requestKey, capped.requestKey)
        assertEquals(uncapped.batch.contentKey, capped.batch.contentKey)
        assertEquals(uncapped.candidates, capped.candidates)

        // Referenced packing under the same cap: the eight drawn glyphs, inside the cap.
        assertEquals(8, both.atlas.entries.size)
        assertTrue(both.atlas.width <= 256 && both.atlas.height <= 256)
        assertEquals(4, both.candidates.count { it.glyphs.isEmpty() && it.icon != null })
    }

    @Test
    fun aCapTooSmallForTheTextFailsNamingItAndIconsAloneNeedNoGlyphAtlas() = runTest {
        val tiny = LabelGlyphAtlasPolicy(LabelGlyphPacking.REFERENCED_GLYPHS, maxDimensionPx = 8)

        val failure = assertFailsWith<SafetyLimitException> {
            acquire(LabelFixtures.style(iconOnly, textAndIcon), policy = tiny)
        }
        assertEquals("labelGlyphAtlas.maxDimensionPx", failure.limitName)
        assertEquals(8L, failure.limit)

        // An icon-only style plans no Glyph Range, so referenced packing packs nothing and no cap
        // can fail it; the icons are all there.
        val (_, icons) = acquire(LabelFixtures.style(iconOnly), policy = tiny)
        assertEquals(3, icons.candidates.size)
        assertTrue(icons.candidates.all { it.glyphs.isEmpty() && it.icon != null })
        assertTrue(icons.atlas.entries.isEmpty())
    }

    /** One acquisition's batch with the request key that guards it. */
    private class Keyed(val batch: LabelCandidateBatch, val requestKey: String) {
        val candidates: List<LabelCandidate> get() = batch.candidates
        val atlas: LabelGlyphAtlas get() = batch.atlas
    }

    private suspend fun acquire(
        styleJson: String,
        policy: LabelGlyphAtlasPolicy = LabelGlyphAtlasPolicy(),
        options: LabelCandidateOptions = LabelCandidateOptions.Default,
        resourceLimits: ResourceLimits = ResourceLimits(),
        spritePng: ByteArray = SHEET_1X_PNG,
    ): Pair<PreparedStyle, Keyed> {
        val configuration = RentileConfiguration(
            transport = transport(spritePng),
            rawResourceStore = InMemoryRawResourceStore(),
            resourceLimits = resourceLimits,
            labelGlyphAtlas = policy,
        )
        val rasterizer = Rentile.create(configuration)
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(styleJson), host)
            val batch = rasterizer.acquireLabelCandidates(style, listOf(tile), options)
            return style to Keyed(batch, rasterizer.labelCandidateRequestKey(style, listOf(tile), options))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private suspend fun <T> withRasterizer(transport: ResourceTransport, block: suspend (BasemapRasterizer) -> T): T {
        val rasterizer = Rentile.create(RentileConfiguration(transport = transport, rawResourceStore = InMemoryRawResourceStore()))
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    /** Serves the 1x and `@2x` sheets by URL, and the glyph range and POI tile by class. */
    private fun transport(spritePng: ByteArray = SHEET_1X_PNG): RecordingTransport = RecordingTransport { request ->
        when (request.url) {
            "${LabelFixtures.SPRITE}.json" -> TransportResponse(200, SHEET_1X_JSON.encodeToByteArray())
            "${LabelFixtures.SPRITE}.png" -> TransportResponse(200, spritePng)
            "${LabelFixtures.SPRITE}@2x.json" -> TransportResponse(200, SHEET_2X_JSON.encodeToByteArray())
            "${LabelFixtures.SPRITE}@2x.png" -> TransportResponse(200, SHEET_2X_PNG)
            else -> when (request.resourceClass) {
                ResourceClass.GLYPH_RANGE -> TransportResponse(200, glyphRange)
                ResourceClass.VECTOR_TILE -> TransportResponse(200, poiTile)
                else -> TransportResponse(404, ByteArray(0))
            }
        }
    }

    private fun RecordingTransport.spriteRequests(): List<String> =
        requests().filter { it.resourceClass == ResourceClass.SPRITE_JSON || it.resourceClass == ResourceClass.SPRITE_IMAGE }
            .map { it.url }

    private companion object {
        const val SHEET_1X_JSON = """{"marker":{"x":0,"y":0,"width":8,"height":8,"pixelRatio":1,"sdf":true}}"""
        const val SHEET_2X_JSON = """{"marker":{"x":0,"y":0,"width":16,"height":16,"pixelRatio":2,"sdf":true}}"""
        val SHEET_1X_PNG: ByteArray = renderSyntheticPng(8)
        val SHEET_2X_PNG: ByteArray = renderSyntheticPng(16)
    }
}
