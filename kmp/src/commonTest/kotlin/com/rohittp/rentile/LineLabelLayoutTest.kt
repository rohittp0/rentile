package com.rohittp.rentile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `text-max-width` wraps point text only. Mapbox GL JS reads it for `symbol-placement: point` alone
 * and shapes line and line-center text with an unbounded width (`src/symbol/symbol_layout.ts`
 * lines 400-402 at v3.32.0), so a road or river name is one row along its line however long it is.
 */
class LineLabelLayoutTest {
    // "Main" is 40, "Street" 60 and "North" 50 em-pixels in the fixture font, a space 6. A two-em
    // (48 px) max width puts each word on a row of its own wherever text-max-width applies.
    private val tile = LabelFixtures.vectorTile(
        mapOf(
            "road" to listOf(
                LabelFixtures.Feature(
                    mapOf("name" to "Main Street North", "width" to "wide"),
                    listOf(100 to 2000, 2000 to 2100, 4000 to 2200),
                    line = true,
                    id = 7,
                ),
            ),
        ),
    )

    private fun roadLabels(placement: String, maxWidth: String = "2") =
        """{"id":"road-labels","type":"symbol","source":"v","source-layer":"road",""" +
            """"layout":{"symbol-placement":"$placement","text-field":["get","name"],""" +
            """"text-font":["Open Sans Regular"],"text-size":24,"text-max-width":$maxWidth}}"""

    private suspend fun candidatesFor(layer: String, policy: CompatibilityPolicy = CompatibilityPolicy.RentileV1): LabelCandidateBatch {
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(tile))
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(layer, sprite = false)), policy)
            return rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1)))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun LabelCandidate.rowCount(): Int = glyphs.map { it.y }.distinct().size

    @Test
    fun aLongLinePlacedLabelStaysOnOneRow() = runTest {
        val candidate = candidatesFor(roadLabels("line")).candidates.single()

        assertEquals(LabelPlacement.LINE, candidate.placement)
        assertEquals(15, candidate.glyphs.size)
        assertEquals(1, candidate.rowCount())
        // One row, in reading order: every glyph sits right of the one before it.
        assertTrue(candidate.glyphs.zipWithNext().all { (a, b) -> b.x > a.x })
    }

    @Test
    fun aLongLineCenterPlacedLabelStaysOnOneRow() = runTest {
        val candidate = candidatesFor(roadLabels("line-center")).candidates.single()

        assertEquals(LabelPlacement.LINE_CENTER, candidate.placement)
        assertEquals(15, candidate.glyphs.size)
        assertEquals(1, candidate.rowCount())
    }

    @Test
    fun aPointLabelStillWrapsAtItsMaxWidth() = runTest {
        val candidate = candidatesFor(roadLabels("point")).candidates.single()

        assertEquals(LabelPlacement.POINT, candidate.placement)
        assertEquals(15, candidate.glyphs.size)
        assertEquals(3, candidate.rowCount())
    }

    @Test
    fun theOneRowIsTheTextLaidOutWithNoWidthLimit() = runTest {
        // The same feature as a point label whose max width no word reaches: one row, with the same
        // spaces, letter spacing, anchor and box. A line label is that layout, not a re-joined wrap.
        val line = candidatesFor(roadLabels("line")).candidates.single()
        val unbounded = candidatesFor(roadLabels("point", maxWidth = "100")).candidates.single()

        assertEquals(unbounded.glyphs, line.glyphs)
        assertEquals(unbounded.boundingBox, line.boundingBox)
    }

    @Test
    fun aLineLabelIsOneRowUnderTheHostSymbolsProfileToo() = runTest {
        val candidate = candidatesFor(roadLabels("line"), CompatibilityPolicy.RentileV1HostSymbols)
            .candidates.single()

        assertEquals(15, candidate.glyphs.size)
        assertEquals(1, candidate.rowCount())
    }

    @Test
    fun aLineLabelDoesNotReadTextMaxWidth() = runTest {
        // Mapbox never evaluates text-max-width for a line label, so a value no point label could use
        // cannot cost one. The same layer placed at a point does read it, and loses the feature.
        val unusable = """["get","width"]"""

        val line = candidatesFor(roadLabels("line", maxWidth = unusable))
        val point = candidatesFor(roadLabels("point", maxWidth = unusable))

        assertEquals(1, line.candidates.single().rowCount())
        assertTrue(line.diagnostics.none { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED })
        assertTrue(point.candidates.isEmpty())
        assertTrue(point.diagnostics.any { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED })
    }
}
