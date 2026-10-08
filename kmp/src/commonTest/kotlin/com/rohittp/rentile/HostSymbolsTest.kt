package com.rohittp.rentile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CompatibilityPolicy.RentileV1HostSymbols]: every symbol layer belongs to the host. Output Tiles
 * carry no symbol at all, and every visible vector symbol layer with text or an icon is a label
 * layer, so a host draws icons crisp in screen space with the text they belong to.
 */
class HostSymbolsTest {
    private val host = CompatibilityPolicy.RentileV1HostSymbols
    private val tile = TileId(2, 1, 1)

    private val iconOnly =
        """{"id":"poi-icons","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker"}}"""
    private val textAndIcon =
        """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
            """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"icon-image":"marker"}}"""
    private val background = """{"id":"bg","type":"background","paint":{"background-color":"#ddeeff"}}"""

    private fun poiTile(vararg features: LabelFixtures.Feature): ByteArray =
        LabelFixtures.vectorTile(mapOf("poi" to features.toList()))

    private val namedAndNameless = poiTile(
        LabelFixtures.Feature(mapOf("name" to "Cafe", "kind" to "food"), listOf(1024 to 1024), id = 7),
        LabelFixtures.Feature(mapOf("kind" to "bench"), listOf(3000 to 3000), id = 8),
    )

    private suspend fun <T> withRasterizer(
        transport: LabelFixtures.Transport,
        block: suspend (BasemapRasterizer) -> T,
    ): T {
        val rasterizer = LabelFixtures.rasterizer(transport)
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun theHostPolicyIsItsOwnProfileAndNotTheDefault() {
        assertEquals("rentile-v1-host-symbols", host.id)
        assertEquals(0, host.minimumOutputZoom)
        assertEquals(22, host.maximumOutputZoom)
        assertNotEquals(CompatibilityPolicy.RentileV1, host)
        assertEquals(CompatibilityPolicy.RentileV1, CompatibilityPolicy.Default)
    }

    @Test
    fun outputTilesCarryNoSymbolIconUnderTheHostPolicy() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val withSymbols = LabelFixtures.style(background, iconOnly, textAndIcon)
            val withoutSymbols = LabelFixtures.style(background)
            suspend fun pixels(style: PreparedStyle): ByteArray {
                val batch = rasterizer.prepareBatch(style, listOf(tile))
                try {
                    return rasterizer.renderRaw(batch).tiles.single().rgbaBytes
                } finally {
                    batch.close()
                }
            }

            val hosted = pixels(rasterizer.prepare(StyleInput.InlineJson(withSymbols), host))
            val baked = pixels(rasterizer.prepare(StyleInput.InlineJson(withSymbols)))
            val bare = pixels(rasterizer.prepare(StyleInput.InlineJson(withoutSymbols)))

            // The default profile bakes both layers' icons into the ground texture; the host
            // profile draws exactly what the style draws with no symbol layer at all.
            assertFalse(baked.contentEquals(bare))
            assertContentEquals(bare, hosted)
        }
    }

    @Test
    fun everySymbolLayerReportsThatTheHostOwnsIt() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val geoJsonIcon =
                """{"id":"route-arrows","type":"symbol","source":"route","layout":{"icon-image":"marker","symbol-placement":"line"}}"""
            val hidden =
                """{"id":"hidden","type":"symbol","source":"v","source-layer":"poi","layout":{"visibility":"none","icon-image":"marker"}}"""
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    LabelFixtures.style(
                        background, iconOnly, textAndIcon, geoJsonIcon, hidden,
                        extraSources = ""","route":{"type":"geojson","data":"https://geojson.example.test/route.json"}""",
                    ),
                ),
                host,
            )

            val owned = style.diagnostics.filter { it.code == DiagnosticCode.SYMBOL_LAYER_HOST_OWNED }
            assertEquals(listOf("1", "2", "3"), owned.map { it.details["layerIndex"] })
            assertTrue(owned.all { it.severity == DiagnosticSeverity.INFO })
            // A GeoJSON source has no Label Tiles, so nothing represents that layer to the host;
            // the diagnostic says so instead of leaving the loss to be discovered.
            assertEquals(listOf("true", "true", "false"), owned.map { it.details["labelLayer"] })
            assertEquals(
                listOf("4"),
                style.diagnostics.filter { it.code == DiagnosticCode.HIDDEN_LAYER_NO_DRAW }.map { it.details["layerIndex"] },
            )
            // Nothing of the default profile's symbol repair applies.
            assertTrue(
                style.diagnostics.none {
                    it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED ||
                        it.code == DiagnosticCode.TEXT_ONLY_LAYER_EXCLUDED ||
                        it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED
                },
            )
        }
    }

    @Test
    fun aSourceOnlyIconLayersReadIsNotFetchedForOutputTilesUnderTheHostPolicy() = runTest {
        val transport = LabelFixtures.Transport(namedAndNameless)
        withRasterizer(transport) { rasterizer ->
            val style = LabelFixtures.style(background, iconOnly)

            rasterizer.render(rasterizer.prepare(StyleInput.InlineJson(style), host), listOf(tile))
            val hostRequests = transport.requestedClasses().count { it == ResourceClass.VECTOR_TILE }
            rasterizer.render(rasterizer.prepare(StyleInput.InlineJson(style)), listOf(tile))
            val defaultRequests = transport.requestedClasses().count { it == ResourceClass.VECTOR_TILE } - hostRequests

            assertEquals(0, hostRequests)
            assertEquals(1, defaultRequests)
        }
    }

    @Test
    fun anIconOnlyLayerIsALabelLayerOnlyUnderTheHostPolicy() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val style = LabelFixtures.style(iconOnly, textAndIcon)

            val hosted = rasterizer.labelLayerDescriptors(rasterizer.prepare(StyleInput.InlineJson(style), host))
            val baked = rasterizer.labelLayerDescriptors(rasterizer.prepare(StyleInput.InlineJson(style)))

            assertEquals(listOf("poi-icons", "poi-labels"), hosted.map { it.id })
            assertEquals(listOf("poi-labels"), baked.map { it.id })
        }
    }

    @Test
    fun anIconOnlyFeatureYieldsAnIconOnlyCandidate() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    LabelFixtures.style(
                        """{"id":"poi-icons","type":"symbol","source":"v","source-layer":"poi",""" +
                            """"layout":{"icon-image":"marker","icon-size":2,"symbol-sort-key":5,"symbol-z-order":"source"}}""",
                    ),
                ),
                host,
            )

            val batch = rasterizer.acquireLabelCandidates(style, listOf(tile))

            assertEquals(2, batch.candidates.size)
            val candidate = batch.candidates.first()
            assertEquals("poi-icons", batch.layerStyles[candidate.layerStyleIndex].layerId)
            assertEquals(7L, candidate.featureId)
            assertEquals(LabelPlacement.POINT, candidate.placement)
            assertEquals(5.0, candidate.sortKey)
            assertEquals(SymbolZOrder.SOURCE, candidate.zOrder)
            // No text: no glyphs, a zero-area box at the anchor, no text and no text size.
            assertTrue(candidate.glyphs.isEmpty())
            assertEquals(LabelBox(0.0, 0.0, 0.0, 0.0), candidate.boundingBox)
            assertNull(candidate.text)
            assertNull(candidate.textSize)
            // The icon stands alone, exactly as text-optional would let it.
            assertTrue(candidate.textOptional)
            val icon = assertNotNull(candidate.icon)
            assertEquals("marker", icon.imageName)
            assertEquals(16.0, icon.width)
            // The inert text half can neither block nor be blocked, and draws nothing.
            assertEquals(SymbolOverlap.ALWAYS, candidate.overlap)
            assertTrue(candidate.ignorePlacement)
            assertEquals(0.0, candidate.padding)
            assertEquals(0.0, candidate.opacity)
            // Nothing was laid out, so nothing was fetched for it.
            assertEquals(0, batch.atlas.entries.size)
        }
    }

    @Test
    fun aFeatureWithoutTextOnATextAndIconLayerKeepsItsIcon() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val source = StyleInput.InlineJson(LabelFixtures.style(textAndIcon))

            val hosted = rasterizer.acquireLabelCandidates(rasterizer.prepare(source, host), listOf(tile)).candidates
            val baked = rasterizer.acquireLabelCandidates(rasterizer.prepare(source), listOf(tile)).candidates

            assertEquals(listOf("Cafe", null), hosted.map { it.text })
            assertEquals(listOf(7L, 8L), hosted.map { it.featureId })
            assertTrue(hosted.all { it.icon != null })
            // The default profile draws that icon into the tile instead, so it has no candidate.
            assertEquals(listOf("Cafe"), baked.map { it.text })
        }
    }

    @Test
    fun anIconOnlyCandidateIsNotFittedToTextItDoesNotHave() = runTest {
        // Mapbox fits an icon to its text only when there is shaped text (symbol_layout's
        // fitIconToText runs under `if (defaultHorizontalShaping)`); without text the icon is
        // drawn at its sprite size, so the candidate must not ask the host to fit it to nothing.
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val fitted =
                """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
                    """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],""" +
                    """"icon-image":"marker","icon-text-fit":"both","icon-text-fit-padding":[1,2,3,4]}}"""
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(fitted)), host)

            val (withText, withoutText) = rasterizer.acquireLabelCandidates(style, listOf(tile)).candidates

            assertEquals("Cafe", withText.text)
            assertEquals(IconTextFit.BOTH, withText.icon?.textFit)
            assertNull(withoutText.text)
            assertEquals(IconTextFit.NONE, withoutText.icon?.textFit)
            assertEquals(8.0, withoutText.icon?.width)
        }
    }

    @Test
    fun aTextAndIconFeatureWhoseTextIsAnUnsupportedScriptKeepsItsIcon() = runTest {
        val arabic = poiTile(LabelFixtures.Feature(mapOf("name" to "القاهرة"), id = 1))
        withRasterizer(LabelFixtures.Transport(arabic)) { rasterizer ->
            val source = StyleInput.InlineJson(LabelFixtures.style(textAndIcon))

            val hosted = rasterizer.acquireLabelCandidates(rasterizer.prepare(source, host), listOf(tile))
            val baked = rasterizer.acquireLabelCandidates(rasterizer.prepare(source), listOf(tile))

            val candidate = hosted.candidates.single()
            assertTrue(candidate.glyphs.isEmpty())
            assertNull(candidate.text)
            assertNotNull(candidate.icon)
            // The text loss is still reported, and so is the fact that the icon carried on.
            val excluded = hosted.diagnostics.single { it.code == DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED }
            assertEquals("1", excluded.details["excludedFeatures"])
            val skips = hosted.diagnostics.single { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED }
            assertEquals("1", skips.details["textLostIconRetained"])
            assertEquals("0", skips.details["skippedFeatures"])
            assertTrue(baked.candidates.isEmpty())
        }
    }

    @Test
    fun aTextAndIconFeatureWhoseTextHasNoGlyphsKeepsItsIcon() = runTest {
        // Glyph endpoints stop at the Basic Multilingual Plane, so an all-astral name lays out to
        // nothing - which is only discovered after the atlas is packed.
        val astral = poiTile(LabelFixtures.Feature(mapOf("name" to "😀"), id = 1))
        withRasterizer(LabelFixtures.Transport(astral)) { rasterizer ->
            val source = StyleInput.InlineJson(LabelFixtures.style(textAndIcon))

            val hosted = rasterizer.acquireLabelCandidates(rasterizer.prepare(source, host), listOf(tile))
            val baked = rasterizer.acquireLabelCandidates(rasterizer.prepare(source), listOf(tile))

            val candidate = hosted.candidates.single()
            assertTrue(candidate.glyphs.isEmpty())
            assertNull(candidate.text)
            assertNotNull(candidate.icon)
            val skips = hosted.diagnostics.single { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED }
            assertEquals("1", skips.details["textLostIconRetained"])
            assertEquals("0", skips.details["skippedNoGlyphs"])
            // The default profile counts the same label as lost for want of glyphs, unchanged.
            assertTrue(baked.candidates.isEmpty())
            assertEquals(
                "1",
                baked.diagnostics.single { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED }.details["skippedNoGlyphs"],
            )
            assertNull(baked.diagnostics.single { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED }.details["textLostIconRetained"])
        }
    }

    @Test
    fun aTextConstructTheProfileCannotCompileKeepsTheIconUnderTheHostPolicy() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val variableAnchor =
                """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
                    """"layout":{"text-field":["get","name"],"text-variable-anchor":["top","bottom"],"icon-image":"marker"}}"""
            val source = StyleInput.InlineJson(LabelFixtures.style(variableAnchor))

            val hostedStyle = rasterizer.prepare(source, host)
            val hosted = rasterizer.acquireLabelCandidates(hostedStyle, listOf(tile))
            val baked = rasterizer.acquireLabelCandidates(rasterizer.prepare(source), listOf(tile))

            assertTrue(hostedStyle.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT })
            assertEquals(2, hosted.candidates.size)
            assertTrue(hosted.candidates.all { it.text == null && it.icon != null })
            assertTrue(baked.candidates.isEmpty())
        }
    }

    @Test
    fun iconOnlyCandidatesSurviveAStyleWithoutGlyphs() = runTest {
        val transport = LabelFixtures.Transport(namedAndNameless)
        withRasterizer(transport) { rasterizer ->
            val style = rasterizer.prepare(
                StyleInput.InlineJson(LabelFixtures.style(iconOnly, textAndIcon, glyphs = false)),
                host,
            )

            val plan = rasterizer.planLabelCandidates(style, listOf(tile))
            val batch = plan.use { rasterizer.acquireLabelCandidates(it) }

            assertTrue(plan.glyphClosure.isEmpty())
            // Two icon-only features, and the text-and-icon layer's two features as icons alone.
            assertEquals(4, batch.candidates.size)
            assertTrue(batch.candidates.all { it.text == null && it.icon != null })
            assertTrue(batch.diagnostics.any { it.code == DiagnosticCode.GLYPH_RANGE_UNAVAILABLE })
            assertTrue(plan.diagnostics.any { it.code == DiagnosticCode.GLYPH_RANGE_UNAVAILABLE })
            assertTrue(transport.requestedClasses().none { it == ResourceClass.GLYPH_RANGE })
        }
    }

    @Test
    fun aMissingSpriteSkipsIconOnlyFeaturesInsteadOfFailing() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless, spriteJson = null)) { rasterizer ->
            val source = StyleInput.InlineJson(LabelFixtures.style(iconOnly, sprite = false))

            // The default profile draws that icon itself, so its missing sprite is a failure there.
            assertFailsWith<StylePreparationException> { rasterizer.prepare(source) }
            val style = rasterizer.prepare(source, host)
            val batch = rasterizer.acquireLabelCandidates(style, listOf(tile))

            assertTrue(batch.candidates.isEmpty())
            val icons = batch.diagnostics.single { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED }
            assertEquals("2", icons.details["skippedMissingSprite"])
            val skips = batch.diagnostics.single { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED }
            assertEquals("2", skips.details["candidateFeatures"])
            assertEquals("2", skips.details["skippedFeatures"])
        }
    }

    @Test
    fun anUnfetchableSpriteSkipsIconOnlyFeaturesInsteadOfFailing() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless, spriteJson = null)) { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(iconOnly)), host)

            val batch = rasterizer.acquireLabelCandidates(style, listOf(tile))

            assertTrue(batch.candidates.isEmpty())
            assertEquals(
                "2",
                batch.diagnostics.single { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED }.details["skippedMissingSprite"],
            )
        }
    }

    @Test
    fun anIconOnlyLayerWhoseSourceCannotResolveIsExcludedInsteadOfFailing() = runTest {
        // The fixture transport answers a TileJSON request with tile bytes, which do not decode.
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val unresolvable =
                """{"id":"poi-icons","type":"symbol","source":"p","source-layer":"poi","layout":{"icon-image":"marker"}}"""
            val source = StyleInput.InlineJson(
                LabelFixtures.style(
                    background, unresolvable,
                    extraSources = ""","p":{"type":"vector","url":"https://tilejson.example.test/p.json"}""",
                ),
            )

            // The default profile draws that layer itself, so its source is required there.
            assertFailsWith<RentileException> { rasterizer.prepare(source) }
            val style = rasterizer.prepare(source, host)

            val unavailable = style.diagnostics.single { it.code == DiagnosticCode.LABEL_SOURCE_UNAVAILABLE }
            assertEquals("1", unavailable.details["layerIndex"])
            assertTrue(rasterizer.labelLayerDescriptors(style).isEmpty())
            assertEquals(
                "false",
                style.diagnostics.single { it.code == DiagnosticCode.SYMBOL_LAYER_HOST_OWNED }.details["labelLayer"],
            )
            assertEquals(1, rasterizer.render(style, listOf(tile)).tiles.size)
        }
    }

    @Test
    fun aZeroTextSizeLeavesTheIconAloneWithoutReportingALoss() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val hiddenText =
                """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
                    """"layout":{"text-field":["get","name"],"text-size":0,"icon-image":"marker"}}"""
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(hiddenText)), host)

            val batch = rasterizer.acquireLabelCandidates(style, listOf(tile))

            assertEquals(2, batch.candidates.size)
            assertTrue(batch.candidates.all { it.text == null && it.icon != null })
            assertTrue(batch.diagnostics.none { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED })
        }
    }

    @Test
    fun aZeroIconSizeLeavesAnIconOnlyFeatureOutWithoutReportingALoss() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val hiddenIcon =
                """{"id":"poi-icons","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-size":0}}"""
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(hiddenIcon)), host)

            val batch = rasterizer.acquireLabelCandidates(style, listOf(tile))

            assertTrue(batch.candidates.isEmpty())
            assertTrue(batch.diagnostics.none { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED })
            assertTrue(batch.diagnostics.none { it.code == DiagnosticCode.ICON_FEATURE_SKIPPED })
        }
    }

    @Test
    fun aTextAndIconCandidateIsUnchangedByTheHostPolicy() = runTest {
        withRasterizer(LabelFixtures.Transport(namedAndNameless)) { rasterizer ->
            val source = StyleInput.InlineJson(LabelFixtures.style(textAndIcon))

            val hosted = rasterizer.acquireLabelCandidates(rasterizer.prepare(source, host), listOf(tile))
            val baked = rasterizer.acquireLabelCandidates(rasterizer.prepare(source), listOf(tile))

            // Same text, same glyphs, same icon: the policy decides what is drawn where, not how a
            // label is laid out.
            assertEquals(baked.candidates.single(), hosted.candidates.first())
        }
    }
}
