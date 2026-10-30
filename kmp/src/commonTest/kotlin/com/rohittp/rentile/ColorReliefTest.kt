package com.rohittp.rentile

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MapLibre `color-relief` layers (ADR 0043), rendered from a synthetic Terrain-RGB DEM whose left
 * half is 0 m and right half 1000 m.
 */
class ColorReliefTest {
    @Test
    fun nearestResamplingColoursEachHalfByItsStop() = runTest {
        withDem { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(style(NEAREST, RAMP_RED_TO_BLUE)))
            val pixels = renderRaw(rasterizer, style, 256)
            assertPixel(pixels, 256, 64, 128, 255, 0, 0, 255)
            assertPixel(pixels, 256, 192, 128, 0, 0, 255, 255)
            // Nearest sampling has no blend: the boundary is a hard step between the stop colours.
            for (x in 0 until 256) {
                val red = pixels[(128 * 256 + x) * 4].toInt() and 0xff
                assertTrue(red == 0 || red == 255, "nearest relief blended at x=$x: red=$red")
            }
        }
    }

    @Test
    fun linearResamplingBlendsAcrossTheElevationStepAndIsTheDefault() = runTest {
        withDem { rasterizer ->
            val explicit = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(style(LINEAR, RAMP_RED_TO_BLUE))), 256)
            val default = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(style("", RAMP_RED_TO_BLUE))), 256)
            assertTrue(explicit.contentEquals(default), "linear must be the default resampling")
            assertPixel(explicit, 256, 32, 128, 255, 0, 0, 255)
            assertPixel(explicit, 256, 224, 128, 0, 0, 255, 255)
            val blended = (0 until 256).count { x ->
                val red = explicit[(128 * 256 + x) * 4].toInt() and 0xff
                red in 1..254
            }
            assertTrue(blended > 0, "linear relief must blend across the 0 m / 1000 m step")
        }
    }

    @Test
    fun theRampInterpolatesBetweenStopsAndClampsBeyondThem() = runTest {
        withDem { rasterizer ->
            // 0 m is below the first stop; 1000 m is halfway from 500 to 1500 m.
            val ramp = """["interpolate",["linear"],["elevation"],500,"#00ff00",1500,"rgba(0,0,0,0)"]"""
            val pixels = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(style(NEAREST, ramp))), 256)
            assertPixel(pixels, 256, 64, 128, 0, 255, 0, 255)
            // Straight-alpha interpolation: half of the green channel, half of the alpha.
            assertPixel(pixels, 256, 192, 128, 0, 128, 0, 128, tolerance = 1)
        }
    }

    @Test
    fun opacityScalesAlphaAndZoomExpressionsEvaluateAtTheTileZoom() = runTest {
        withDem { rasterizer ->
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    style(NEAREST, RAMP_RED_TO_BLUE, opacity = """["interpolate",["linear"],["zoom"],0,0.5,10,1]"""),
                ),
            )
            val pixels = renderRaw(rasterizer, style, 256)
            assertPixel(pixels, 256, 64, 128, 255, 0, 0, 128)
        }
    }

    @Test
    fun theReliefIsComputedPerOutputPixelAtEverySize() = runTest {
        withDem { rasterizer ->
            val style = rasterizer.prepare(StyleInput.InlineJson(style(LINEAR, RAMP_RED_TO_BLUE)))
            for (size in RenderOptions.SUPPORTED_OUTPUT_SIZES.sorted()) {
                val pixels = renderRaw(rasterizer, style, size)
                assertPixel(pixels, size, size / 8, size / 2, 255, 0, 0, 255)
                assertPixel(pixels, size, size * 7 / 8, size / 2, 0, 0, 255, 255)
                // A 2048 px tile from a 64 px DEM is a smooth ramp, not 512-grid blocks: every
                // output pixel across the step has its own value.
                if (size == 2048) {
                    val row = (0 until size).map { x -> pixels[(size / 2 * size + x) * 4].toInt() and 0xff }
                    val distinctBlends = row.filter { it in 1..254 }.toSet().size
                    assertTrue(distinctBlends > 16, "expected a per-pixel ramp, saw $distinctBlends blend values")
                }
            }
        }
    }

    @Test
    fun reliefCompositesSourceOverEarlierLayers() = runTest {
        withDem { rasterizer ->
            val json = """{"version":8,"sources":{"dem":{"type":"raster-dem","tiles":["https://dem.test/{z}/{x}/{y}.png"],"tileSize":64,"maxzoom":0}},""" +
                """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
                """{"id":"relief","type":"color-relief","source":"dem","paint":{"resampling":"nearest","color-relief-opacity":0.5,"color-relief-color":$RAMP_RED_TO_BLUE}},""" +
                """{"id":"hills","type":"hillshade","source":"dem"}]}"""
            val rendered = rasterizer.render(rasterizer.prepare(StyleInput.InlineJson(json)), listOf(TileId(0, 0, 0)), RenderOptions(256))
            assertTrue(rendered.tiles.single().pngBytes.isNotEmpty())
            val pixels = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(json)), 256)
            // Half-opaque red over white, with flat terrain hillshade adding nothing.
            assertPixel(pixels, 256, 64, 128, 255, 127, 127, 255, tolerance = 1)
        }
    }

    @Test
    fun unsupportedColorReliefConstructsFailPreparation() = runTest {
        withDem { rasterizer ->
            val rejected = mapOf(
                "absent colour" to style(NEAREST, null),
                "constant colour" to style(NEAREST, "\"#ff0000\""),
                "step expression" to style(NEAREST, """["step",["elevation"],"#ff0000",500,"#0000ff"]"""),
                "zoom input" to style(NEAREST, """["interpolate",["linear"],["zoom"],0,"#ff0000",10,"#0000ff"]"""),
                "descending stops" to style(NEAREST, """["interpolate",["linear"],["elevation"],500,"#ff0000",0,"#0000ff"]"""),
                "unknown interpolation" to style(NEAREST, """["interpolate",["smooth"],["elevation"],0,"#ff0000",500,"#0000ff"]"""),
                "invalid colour" to style(NEAREST, """["interpolate",["linear"],["elevation"],0,"not-a-colour",500,"#0000ff"]"""),
                "unknown resampling" to style(""""resampling":"cubic",""", RAMP_RED_TO_BLUE),
                "unknown paint property" to style(""""color-relief-banding":1,""", RAMP_RED_TO_BLUE),
                "raster source" to """{"version":8,"sources":{"img":{"type":"raster","tiles":["https://img.test/{z}/{x}/{y}.png"]}},"layers":[{"id":"relief","type":"color-relief","source":"img","paint":{"color-relief-color":$RAMP_RED_TO_BLUE}}]}""",
            )
            for ((name, json) in rejected) {
                val error = assertFailsWith<StylePreparationException>(name) {
                    rasterizer.prepare(StyleInput.InlineJson(json))
                }
                assertTrue(
                    error.diagnostics.any { it.code == DiagnosticCode.UNSUPPORTED_RETAINED_CONSTRUCT },
                    "$name must be reported as an unsupported retained construct",
                )
            }
        }
    }

    @Test
    fun everyInterpolationFormAndOperatorUsesOnlyItsStopColours() = runTest {
        withDem { rasterizer ->
            // Linear resampling, so the step's blended pixels sit between the stops, where an
            // exponential or bezier curve would differ if it were applied.
            val reference = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(style(LINEAR, RAMP_RED_TO_BLUE))), 256)
            for (ramp in listOf(
                """["interpolate",["exponential",2],["elevation"],0,"#ff0000",1000,"#0000ff"]""",
                """["interpolate",["cubic-bezier",0.4,0,0.6,1],["elevation"],0,"#ff0000",1000,"#0000ff"]""",
                """["interpolate-hcl",["linear"],["elevation"],0,"#ff0000",1000,"#0000ff"]""",
                """["interpolate-lab",["linear"],["elevation"],0,"#ff0000",1000,"#0000ff"]""",
            )) {
                val pixels = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(style(LINEAR, ramp))), 256)
                assertTrue(pixels.contentEquals(reference), ramp)
            }
        }
    }

    @Test
    fun aHiddenOrOutOfRangeReliefDrawsNothingAndFetchesNoDem() = runTest {
        var requests = 0
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { requests++; error("no DEM is needed") },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            for (layout in listOf(""""layout":{"visibility":"none"},""", """"minzoom":4,""")) {
                val json = """{"version":8,"sources":{"dem":{"type":"raster-dem","tiles":["https://dem.test/{z}/{x}/{y}.png"],"tileSize":64}},""" +
                    """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
                    """{"id":"relief","type":"color-relief","source":"dem",$layout"paint":{"color-relief-color":$RAMP_RED_TO_BLUE}}]}"""
                val plain = """{"version":8,"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}}]}"""
                val withRelief = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(json)), 256)
                val without = renderRaw(rasterizer, rasterizer.prepare(StyleInput.InlineJson(plain)), 256)
                assertTrue(withRelief.contentEquals(without), layout)
            }
            assertEquals(0, requests)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private suspend fun renderRaw(rasterizer: BasemapRasterizer, style: PreparedStyle, size: Int): ByteArray =
        rasterizer.prepareBatch(style, listOf(TileId(0, 0, 0)), RenderOptions(size)).use { batch ->
            rasterizer.renderRaw(batch).tiles.single().rgbaBytes
        }

    private fun assertPixel(
        rgba: ByteArray,
        size: Int,
        x: Int,
        y: Int,
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int,
        tolerance: Int = 0,
    ) {
        val offset = (y * size + x) * 4
        val actual = (0..3).map { rgba[offset + it].toInt() and 0xff }
        val expected = listOf(red, green, blue, alpha)
        assertTrue(
            actual.zip(expected).all { (a, e) -> kotlin.math.abs(a - e) <= tolerance },
            "pixel ($x, $y) at $size: expected $expected, got $actual",
        )
    }

    private suspend fun withDem(block: suspend (BasemapRasterizer) -> Unit) {
        val dem = halfAndHalfDem()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    assertEquals(ResourceClass.DEM_TILE, request.resourceClass)
                    TransportResponse(200, dem)
                },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        try {
            block(rasterizer)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    /** Terrain-RGB 64 px: (1, 134, 160) decodes to 0 m on the left, (1, 173, 176) to 1000 m on the right. */
    private fun halfAndHalfDem(): ByteArray {
        val surface = Surface.makeRasterN32Premul(64, 64)
        try {
            val low = Paint().apply { color = Color.makeARGB(255, 1, 134, 160) }
            val high = Paint().apply { color = Color.makeARGB(255, 1, 173, 176) }
            try {
                surface.canvas.drawRect(Rect.makeLTRB(0f, 0f, 32f, 64f), low)
                surface.canvas.drawRect(Rect.makeLTRB(32f, 0f, 64f, 64f), high)
            } finally {
                low.close()
                high.close()
            }
            val image = surface.makeImageSnapshot()
            try {
                val encoded = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia could not encode the DEM")
                try {
                    return encoded.bytes
                } finally {
                    encoded.close()
                }
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }

    private companion object {
        const val NEAREST = """"resampling":"nearest","""
        const val LINEAR = """"resampling":"linear","""
        const val RAMP_RED_TO_BLUE = """["interpolate",["linear"],["elevation"],0,"#ff0000",1000,"#0000ff"]"""

        fun style(extraPaint: String, color: String?, opacity: String = "1"): String {
            val colorEntry = color?.let { """"color-relief-color":$it,""" }.orEmpty()
            return """{"version":8,"sources":{"dem":{"type":"raster-dem","tiles":["https://dem.test/{z}/{x}/{y}.png"],"tileSize":64,"maxzoom":0}},""" +
                """"layers":[{"id":"relief","type":"color-relief","source":"dem","paint":{$extraPaint$colorEntry"color-relief-opacity":$opacity}}]}"""
        }
    }
}
