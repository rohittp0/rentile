package com.rohittp.rentile

import com.rohittp.rentile.internal.style.CompiledPreparedStyle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One `text-field` for every label layer, chosen by the host at acquisition time - the label
 * language setting Mapbox hosts implement by setting `text-field` on every symbol layer - without
 * touching the prepared style, so no Output Tile key moves.
 */
class LabelTextFieldOverrideTest {
    private val tile = TileId(2, 1, 1)
    // What Travel Animator's English mode passes: the English name, falling back to the local one
    // where the tiles omit name:en because it would equal name.
    private val english = LabelCandidateOptions(textFieldOverride = """["coalesce",["get","name:en"],["get","name"]]""")
    private val native = LabelCandidateOptions(textFieldOverride = """["get","name"]""")

    private val vectorTile = LabelFixtures.vectorTile(
        mapOf(
            "poi" to listOf(
                LabelFixtures.Feature(mapOf("name" to "Kafe", "name:en" to "Cafe", "ref" to "A1"), listOf(1024 to 1024), id = 1),
            ),
            "road" to listOf(
                LabelFixtures.Feature(mapOf("name" to "Hauptstrasse", "name:en" to "Main street", "ref" to "B2"), listOf(3000 to 3000), id = 2),
                LabelFixtures.Feature(mapOf("name" to "Oxford", "ref" to "C3"), listOf(1500 to 3500), id = 3),
            ),
        ),
    )
    private val poiLabels =
        """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
            """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"]}}"""
    private val roadRefs =
        """{"id":"road-refs","type":"symbol","source":"v","source-layer":"road",""" +
            """"layout":{"text-field":["concat",["get","ref"],"!"],"text-font":["Open Sans Regular"]}}"""
    private val poiIcons =
        """{"id":"poi-icons","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker"}}"""

    private suspend fun <T> withRasterizer(block: suspend (BasemapRasterizer) -> T): T {
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(vectorTile))
        try {
            return block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun theOverrideReplacesEveryLabelLayersText() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels, roadRefs)))

            val own = rasterizer.acquireLabelCandidates(style, listOf(tile)).candidates.map { it.text }
            val overridden = rasterizer.acquireLabelCandidates(style, listOf(tile), english).candidates.map { it.text }

            assertEquals(listOf("Kafe", "B2!", "C3!"), own)
            // A feature with no name:en falls back to its local name, as the expression says.
            assertEquals(listOf("Cafe", "Main street", "Oxford"), overridden)
        }
    }

    @Test
    fun underTheHostPolicyAnOverrideGivesAnIconOnlyLayerText() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(
                StyleInput.InlineJson(LabelFixtures.style(poiIcons)),
                CompatibilityPolicy.RentileV1HostSymbols,
            )

            val own = rasterizer.acquireLabelCandidates(style, listOf(tile)).candidates.single()
            val overridden = rasterizer.acquireLabelCandidates(style, listOf(tile), english).candidates.single()

            assertEquals(null, own.text)
            // As a Mapbox host setting text-field on an icon-only layer would see it: the icon now
            // has text, in the style specification's default font stack and size.
            assertEquals("Cafe", overridden.text)
            assertTrue(overridden.glyphs.isNotEmpty())
            assertEquals(16.0, overridden.textSize?.tileZoomSize)
            assertEquals(own.icon, overridden.icon)
        }
    }

    @Test
    fun theDefaultOptionsChangeNothing() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels, roadRefs)))
            val tiles = listOf(tile)

            assertEquals(
                rasterizer.acquireLabelCandidates(style, tiles),
                rasterizer.acquireLabelCandidates(style, tiles, LabelCandidateOptions.Default),
            )
            assertEquals(
                rasterizer.labelCandidateRequestKey(style, tiles),
                rasterizer.labelCandidateRequestKey(style, tiles, LabelCandidateOptions()),
            )
        }
    }

    @Test
    fun anOverrideMovesOnlyTheLabelKeys() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels, roadRefs)))
            val tiles = listOf(tile)
            val digest = style.digest
            val outputKey = rasterizer.outputRequestKey(style, tile)
            val batch = rasterizer.prepareBatch(style, tiles)
            val contentKeys = batch.use { it.contentKeys }

            val own = rasterizer.acquireLabelCandidates(style, tiles)
            val overridden = rasterizer.acquireLabelCandidates(style, tiles, english)

            // Nothing about the prepared style or its Output Tiles moved.
            assertEquals(digest, style.digest)
            assertEquals(outputKey, rasterizer.outputRequestKey(style, tile))
            assertEquals(contentKeys, rasterizer.prepareBatch(style, tiles).use { it.contentKeys })
            // Both label keys did, and the request key is decided before any network.
            assertNotEquals(own.contentKey, overridden.contentKey)
            val ownKey = rasterizer.labelCandidateRequestKey(style, tiles)
            val englishKey = rasterizer.labelCandidateRequestKey(style, tiles, english)
            val nativeKey = rasterizer.labelCandidateRequestKey(style, tiles, native)
            assertNotEquals(ownKey, englishKey)
            assertNotEquals(englishKey, nativeKey)
            // An override that is the same expression spelled differently is the same override.
            assertEquals(
                englishKey,
                rasterizer.labelCandidateRequestKey(
                    style,
                    tiles,
                    LabelCandidateOptions("""[ "coalesce" , [ "get" , "name:en" ] , [ "get" , "name" ] ]"""),
                ),
            )
        }
    }

    @Test
    fun anOverrideIsCompiledOncePerPreparedStyle() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels, roadRefs)))
            val compiled = style as CompiledPreparedStyle

            val identity = assertNotNull(english.textFieldIdentity)
            rasterizer.acquireLabelCandidates(style, listOf(tile), english)
            val first = compiled.labelLayersForTextField(identity)
            rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 2, 1)), english)
            rasterizer.planLabelCandidates(style, listOf(tile), english).close()

            assertNotNull(first)
            assertSame(first, compiled.labelLayersForTextField(identity))
            assertEquals(1, compiled.textFieldOverrideCount)
        }
    }

    @Test
    fun anOverrideReplacesATextFieldTheProfileCannotCompile() = runTest {
        withRasterizer { rasterizer ->
            val formatted =
                """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
                    """"layout":{"text-field":["format",["get","name"],{}],"text-font":["Open Sans Regular"]}}"""
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(formatted)))

            val own = rasterizer.acquireLabelCandidates(style, listOf(tile))
            val overridden = rasterizer.acquireLabelCandidates(style, listOf(tile), english)

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT })
            assertTrue(own.candidates.isEmpty())
            assertTrue(own.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT })
            // The overridden layer compiles, so the prepare-time exclusion no longer describes it.
            assertEquals(listOf("Cafe"), overridden.candidates.map { it.text })
            assertTrue(overridden.diagnostics.none { it.code == DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT })
        }
    }

    @Test
    fun aPlanCarriesItsOverrideToTheAcquisition() = runTest {
        withRasterizer { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels)))

            val batch = rasterizer.planLabelCandidates(style, listOf(tile), english).use { plan ->
                rasterizer.acquireLabelCandidates(plan)
            }

            assertEquals(listOf("Cafe"), batch.candidates.map { it.text })
            assertEquals(rasterizer.acquireLabelCandidates(style, listOf(tile), english), batch)
        }
    }

    @Test
    fun anOverrideThatIsNotATextFieldIsRefused() {
        assertFailsWith<IllegalArgumentException> { LabelCandidateOptions(textFieldOverride = """["no-such-operator"]""") }
        assertFailsWith<IllegalArgumentException> { LabelCandidateOptions(textFieldOverride = """["get", """) }
        assertEquals(null, LabelCandidateOptions.Default.textFieldOverride)
    }

    @Test
    fun aRasterizerWithoutOptionsSupportStillServesTheDefaults() = runTest {
        // A consumer's own BasemapRasterizer written before the options existed keeps compiling,
        // and keeps working for every caller that does not ask for an override.
        val delegate = LabelFixtures.rasterizer(LabelFixtures.Transport(vectorTile))
        val legacy = object : BasemapRasterizer by delegate {
            override suspend fun planLabelCandidates(
                style: PreparedStyle,
                tiles: List<TileId>,
                options: LabelCandidateOptions,
                resourceAccess: ResourceAccessMode,
            ): LabelCandidatePlan = super.planLabelCandidates(style, tiles, options, resourceAccess)
        }
        try {
            val style = legacy.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels)))

            legacy.planLabelCandidates(style, listOf(tile), LabelCandidateOptions.Default).close()
            assertFailsWith<UnsupportedOperationException> { legacy.planLabelCandidates(style, listOf(tile), english) }
        } finally {
            delegate.close()
            delegate.awaitClosed()
        }
    }
}
