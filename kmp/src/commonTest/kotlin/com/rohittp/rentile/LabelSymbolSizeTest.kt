package com.rohittp.rentile

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `text-size` and `icon-size` at the camera's fractional zoom, exactly as Mapbox GL computes them.
 *
 * Every expected value below is worked by hand from Mapbox GL JS `src/symbol/symbol_size.ts`
 * (`getSizeData`, `evaluateSizeForZoom`, `evaluateSizeForFeature`) and the identical gl-native
 * `SymbolSizeBinder`s: a zoom-dependent size is interpolated between its values at the pair of
 * zoom stops covering `[z, z + 1]`, never evaluated at the camera zoom directly.
 */
class LabelSymbolSizeTest {
    private val tile = TileId(2, 1, 1)

    private fun textLayer(textSize: String, extraLayout: String = "") =
        """{"id":"labels","type":"symbol","source":"v","source-layer":"poi",""" +
            """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-size":$textSize$extraLayout}}"""

    private suspend fun candidates(
        layer: String,
        features: List<LabelFixtures.Feature> = listOf(LabelFixtures.Feature(mapOf("name" to "Cafe"))),
        tiles: List<TileId> = listOf(tile),
    ): List<LabelCandidate> {
        val vectorTile = LabelFixtures.vectorTile(mapOf("poi" to features))
        val rasterizer = LabelFixtures.rasterizer(LabelFixtures.Transport(vectorTile))
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LabelFixtures.style(layer)))
            return rasterizer.acquireLabelCandidates(style, tiles).candidates
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aConstantTextSizeIsTheSameAtEveryZoom() = runTest {
        val size = assertNotNull(candidates(textLayer("14")).single().textSize)

        assertEquals(SymbolSizeKind.CONSTANT, size.kind)
        assertEquals(14.0, size.tileZoomSize)
        assertEquals(14.0, size.sizeAt(2.0))
        assertEquals(14.0, size.sizeAt(2.99))
    }

    @Test
    fun aDataDrivenTextSizeIsPerFeatureAndIgnoresTheCamera() = runTest {
        val sizes = candidates(
            textLayer("""["get","size"]"""),
            listOf(
                LabelFixtures.Feature(mapOf("name" to "Small", "size" to 10.0), listOf(1024 to 1024)),
                LabelFixtures.Feature(mapOf("name" to "Large", "size" to 20.0), listOf(3000 to 3000)),
            ),
        ).map { assertNotNull(it.textSize) }

        assertEquals(listOf(SymbolSizeKind.SOURCE, SymbolSizeKind.SOURCE), sizes.map { it.kind })
        assertEquals(listOf(10.0, 20.0), sizes.map { it.tileZoomSize })
        assertEquals(listOf(10.0, 20.0), sizes.map { it.sizeAt(2.7) })
    }

    @Test
    fun aZoomInterpolatedTextSizeIsInterpolatedBetweenItsCoveringStops() = runTest {
        // Stops 1 and 5 cover [2, 3]: the last stop <= 2 and the first stop >= 3.
        val size = assertNotNull(
            candidates(textLayer("""["interpolate",["linear"],["zoom"],1,10,5,26]""")).single().textSize,
        )

        assertEquals(SymbolSizeKind.CAMERA, size.kind)
        assertEquals(1.0, size.lowerZoom)
        assertEquals(5.0, size.upperZoom)
        assertEquals(10.0, size.lowerSize)
        assertEquals(26.0, size.upperSize)
        assertEquals(1.0, size.interpolationBase)
        // What the glyphs were laid out at: the expression at the tile's own zoom.
        assertEquals(14.0, size.tileZoomSize)
        assertEquals(14.0, size.sizeAt(2.0))
        assertEquals(16.0, size.sizeAt(2.5))
        // The interpolation factor is clamped to [0, 1], as Mapbox clamps it.
        assertEquals(10.0, size.sizeAt(0.0))
        assertEquals(26.0, size.sizeAt(9.0))
    }

    @Test
    fun anExponentialCurveUsesItsBase() = runTest {
        val size = assertNotNull(
            candidates(textLayer("""["interpolate",["exponential",2],["zoom"],2,8,4,32]""")).single().textSize,
        )

        assertEquals(SymbolSizeKind.CAMERA, size.kind)
        assertEquals(2.0, size.interpolationBase)
        // (2^(3-2) - 1) / (2^(4-2) - 1) = 1/3 of the way from 8 to 32.
        assertClose(16.0, size.sizeAt(3.0))
    }

    @Test
    fun aStepCurveHoldsItsLowerCoveringValueUntilTheNextTile() = runTest {
        // A step's stops begin at negative infinity for its default output, exactly as Mapbox's
        // Step labels do, and a step never interpolates.
        val layer = textLayer("""["step",["zoom"],10,3,20]""")
        val atTwo = assertNotNull(candidates(layer).single().textSize)
        val atThree = assertNotNull(candidates(layer, tiles = listOf(TileId(3, 2, 2))).single().textSize)

        assertEquals(SymbolSizeKind.CAMERA, atTwo.kind)
        assertEquals(Double.NEGATIVE_INFINITY, atTwo.lowerZoom)
        assertEquals(3.0, atTwo.upperZoom)
        assertEquals(null, atTwo.interpolationBase)
        assertEquals(10.0, atTwo.sizeAt(2.0))
        assertEquals(10.0, atTwo.sizeAt(2.99))
        assertEquals(20.0, atThree.sizeAt(3.0))
        assertEquals(20.0, atThree.sizeAt(3.5))
    }

    @Test
    fun aStopInsideTheTileZoomIsSkippedExactlyAsMapboxSkipsIt() = runTest {
        // Mapbox interpolates between the covering stops 1 and 4, ignoring the 2.5 stop between
        // them. Rentile laid the glyphs out at the expression's own value at z2, which therefore
        // differs from what Mapbox draws at z2 - and the host must draw the latter.
        val size = assertNotNull(
            candidates(textLayer("""["interpolate",["linear"],["zoom"],1,10,2.5,40,4,16]""")).single().textSize,
        )

        assertEquals(1.0, size.lowerZoom)
        assertEquals(4.0, size.upperZoom)
        assertClose(30.0, size.tileZoomSize)
        assertClose(12.0, size.sizeAt(2.0))
    }

    @Test
    fun aFeatureDependentCurveIsCompositeWithPerFeatureCoveringSizes() = runTest {
        val sizes = candidates(
            textLayer("""["interpolate",["linear"],["zoom"],1,["get","small"],5,["get","big"]]"""),
            listOf(
                LabelFixtures.Feature(mapOf("name" to "One", "small" to 8.0, "big" to 24.0), listOf(1024 to 1024)),
                LabelFixtures.Feature(mapOf("name" to "Two", "small" to 12.0, "big" to 12.0), listOf(3000 to 3000)),
            ),
        ).map { assertNotNull(it.textSize) }

        assertEquals(listOf(SymbolSizeKind.COMPOSITE, SymbolSizeKind.COMPOSITE), sizes.map { it.kind })
        assertEquals(listOf(8.0, 12.0), sizes.map { it.lowerSize })
        assertEquals(listOf(24.0, 12.0), sizes.map { it.upperSize })
        assertEquals(listOf(12.0, 12.0), sizes.map { it.tileZoomSize })
        assertEquals(listOf(14.0, 12.0), sizes.map { it.sizeAt(2.5) })
    }

    @Test
    fun legacyZoomFunctionsAreCameraCurves() = runTest {
        val exponential = assertNotNull(candidates(textLayer("""{"stops":[[1,8],[6,20]]}""")).single().textSize)
        val interval = assertNotNull(
            candidates(textLayer("""{"type":"interval","stops":[[1,8],[3,20]]}""")).single().textSize,
        )

        assertEquals(SymbolSizeKind.CAMERA, exponential.kind)
        // An exponential legacy function without a base interpolates at base 1, which is linear.
        assertEquals(1.0, exponential.interpolationBase)
        assertEquals(1.0, exponential.lowerZoom)
        assertEquals(6.0, exponential.upperZoom)
        assertClose(8.0 + 12.0 * 1.5 / 5.0, exponential.sizeAt(2.5))
        // An interval function never interpolates, like a step.
        assertEquals(null, interval.interpolationBase)
        assertEquals(8.0, interval.sizeAt(2.9))
    }

    @Test
    fun aCurveInsideCoalesceIsStillFound() = runTest {
        val size = assertNotNull(
            candidates(textLayer("""["coalesce",["interpolate",["linear"],["zoom"],1,10,5,26],12]""")).single().textSize,
        )

        assertEquals(SymbolSizeKind.CAMERA, size.kind)
        assertEquals(16.0, size.sizeAt(2.5))
    }

    @Test
    fun aZoomUseMapboxWouldRejectStaysAtTheTileZoomSize() = runTest {
        // Mapbox refuses a style whose size uses zoom anywhere but as the input of one top-level
        // curve, so there is no Mapbox answer to reproduce. Rentile keeps what it has always done
        // for it - the size at the tile's own zoom - and says so through the kind.
        val size = assertNotNull(
            candidates(textLayer("""["*",2,["interpolate",["linear"],["zoom"],1,5,5,13]]""")).single().textSize,
        )

        assertEquals(SymbolSizeKind.CONSTANT, size.kind)
        assertEquals(14.0, size.tileZoomSize)
        assertEquals(14.0, size.sizeAt(2.6))
    }

    @Test
    fun scalingTheLaidOutQuadsReproducesTheLayoutAtTheCameraSize() = runTest {
        // The recipe documented on LabelSymbolSize: every glyph coordinate and the box less its
        // text-padding scale by sizeAt(zoom) / tileZoomSize. Proved against Rentile's own layout of
        // the same label at that size as a constant.
        val padding = ""","text-padding":3,"text-offset":[0.5,1],"text-letter-spacing":0.1"""
        val camera = candidates(textLayer("""["interpolate",["linear"],["zoom"],1,10,5,26]""", padding)).single()
        val size = assertNotNull(camera.textSize)
        val target = size.sizeAt(2.5)
        val reference = candidates(textLayer(target.toString(), padding)).single()
        val ratio = target / size.tileZoomSize

        assertEquals(reference.glyphs.size, camera.glyphs.size)
        camera.glyphs.zip(reference.glyphs).forEach { (scaled, expected) ->
            assertEquals(expected.entryIndex, scaled.entryIndex)
            assertClose(expected.x, scaled.x * ratio)
            assertClose(expected.y, scaled.y * ratio)
            assertClose(expected.scale, scaled.scale * ratio)
        }
        val box = camera.boundingBox
        assertClose(reference.boundingBox.left, (box.left + camera.padding) * ratio - camera.padding)
        assertClose(reference.boundingBox.top, (box.top + camera.padding) * ratio - camera.padding)
        assertClose(reference.boundingBox.right, (box.right - camera.padding) * ratio + camera.padding)
        assertClose(reference.boundingBox.bottom, (box.bottom - camera.padding) * ratio + camera.padding)
    }

    @Test
    fun anIconCarriesItsOwnSizeFunction() = runTest {
        val layer =
            """{"id":"labels","type":"symbol","source":"v","source-layer":"poi",""" +
                """"layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],""" +
                """"icon-image":"marker","icon-offset":[2,1],"icon-size":["interpolate",["linear"],["zoom"],1,1,5,3]}}"""
        val icon = assertNotNull(candidates(layer).single().icon)

        assertEquals(SymbolSizeKind.CAMERA, icon.size.kind)
        assertEquals(1.5, icon.size.tileZoomSize)
        // The 8px sprite, its offset and nothing else are in icon-size units.
        assertEquals(12.0, icon.width)
        assertEquals(3.0, icon.offsetX)
        val ratio = icon.size.sizeAt(2.5) / icon.size.tileZoomSize
        assertClose(14.0, icon.width * ratio)
        assertClose(3.5, icon.offsetX * ratio)
    }

    @Test
    fun theSizeFunctionItselfFollowsMapboxArithmetic() {
        val linear = LabelSymbolSize(SymbolSizeKind.CAMERA, 14.0, 1.0, 5.0, 10.0, 26.0, 1.0)
        val step = LabelSymbolSize(SymbolSizeKind.CAMERA, 10.0, Double.NEGATIVE_INFINITY, 3.0, 10.0, 20.0, null)
        val flat = LabelSymbolSize(SymbolSizeKind.COMPOSITE, 12.0, 4.0, 4.0, 12.0, 30.0, 1.0)

        assertEquals(18.0, linear.sizeAt(3.0))
        assertEquals(10.0, step.sizeAt(100.0))
        // Coincident covering stops interpolate nothing: the factor is zero, as Mapbox defines it.
        assertEquals(12.0, flat.sizeAt(4.5))
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected but was $actual")
    }
}
