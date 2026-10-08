package com.rohittp.rentile

import com.rohittp.rentile.internal.sha256Hex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A host de-duplicates line and polygon candidates across tiles by what the feature *is*, not by
 * where a tile happened to anchor it. These pin the two fields that make that possible, and the
 * semantic markers their arrival had to advance.
 */
class LabelCandidateIdentityTest {
    private val poiLabels =
        """{"id":"poi-labels","type":"symbol","source":"v","source-layer":"poi",""" +
            """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-transform":"uppercase"}}"""

    @Test
    fun aCandidateCarriesItsMvtFeatureIdAndItsEvaluatedText() = runTest {
        val tile = LabelFixtures.vectorTile(
            mapOf(
                "poi" to listOf(
                    LabelFixtures.Feature(mapOf("name" to " Cafe "), listOf(1024 to 1024), id = 42),
                    LabelFixtures.Feature(mapOf("name" to "Park"), listOf(3000 to 3000)),
                ),
            ),
        )
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(tile))
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels, sprite = false)))

            val candidates = rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1))).candidates

            assertEquals(2, candidates.size)
            // The id the tile declared, and the text after expansion, text-transform and trimming:
            // exactly the string the glyphs were laid out from.
            assertEquals(42L, candidates[0].featureId)
            assertEquals("CAFE", candidates[0].text)
            // MVT ids are optional; an absent one is null, never a fabricated zero.
            assertNull(candidates[1].featureId)
            assertEquals("PARK", candidates[1].text)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun anUnsignedFeatureIdAboveLongMaxKeepsItsBits() = runTest {
        // MVT declares the id uint64. Kotlin has no unsigned type on the public surface here, so
        // the value is carried as the same 64 bits in a Long: toULong() recovers it exactly.
        val tile = LabelFixtures.vectorTile(
            mapOf("poi" to listOf(LabelFixtures.Feature(mapOf("name" to "Far"), id = -2L))),
        )
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(tile))
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels, sprite = false)))

            val candidate = rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1))).candidates.single()

            assertEquals(ULong.MAX_VALUE - 1uL, candidate.featureId?.toULong())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun oneLineFeatureAnchoredInTwoTilesCarriesOneIdentity() = runTest {
        val tile = LabelFixtures.vectorTile(
            mapOf(
                "road" to listOf(
                    LabelFixtures.Feature(
                        mapOf("name" to "Main st"),
                        listOf(100 to 2000, 2000 to 2100, 4000 to 2200),
                        line = true,
                        id = 3,
                    ),
                ),
            ),
        )
        val roadLabels =
            """{"id":"road-labels","type":"symbol","source":"v","source-layer":"road",""" +
                """"layout":{"symbol-placement":"line","text-field":["get","name"],"text-font":["Open Sans Regular"]}}"""
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(tile))
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(roadLabels, sprite = false)))

            val candidates = rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1), TileId(2, 2, 1)))
                .candidates

            assertEquals(2, candidates.size)
            assertEquals(listOf(3L, 3L), candidates.map { it.featureId })
            assertEquals(listOf("Main st", "Main st"), candidates.map { it.text })
            assertEquals(listOf(TileId(2, 1, 1), TileId(2, 2, 1)), candidates.map { it.requestedTile })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun theLabelRequestKeyCarriesTheThirdSemanticsMarker() = runTest {
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(ByteArray(0)))
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(poiLabels, sprite = false)))

            assertEquals(
                "label-candidates-3|${style.digest}|2/1/1".sha256Hex(),
                rasterizer.labelCandidateRequestKey(style, listOf(TileId(2, 1, 1))),
            )
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun theCandidateContentKeyCarriesTheThirdSemanticsMarker() = runTest {
        // A style with no glyphs template and no icon layers yields the empty batch, whose content
        // key is the marker over the style digest alone - the one content key a test can derive
        // without re-implementing the resource digests.
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(ByteArray(0)))
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(LabelFixtures.style(poiLabels, glyphs = false, sprite = false)),
            )

            val batch = rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1)))

            assertEquals("rentile-label-candidates-3\n${style.digest}\n\n".sha256Hex(), batch.contentKey)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
}
