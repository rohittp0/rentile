package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineRoundLimitRuntimeTest {
    @Test
    fun defaultRoundLimitConvertsAShallowRoundJoinToMiter() = runTest {
        val rasterizer = rasterizerFor(turn = Turn.SHALLOW)
        try {
            val default = render(rasterizer, roundLimit = null)
            val explicitConvertingLimit = render(rasterizer, roundLimit = 2.0)
            val disabledConversion = render(rasterizer, roundLimit = 1.0)

            assertTrue(default.contentEquals(explicitConvertingLimit))
            assertFalse(default.contentEquals(disabledConversion))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun nonDefaultRoundLimitConvertsAJoinThatTheDefaultKeepsRound() = runTest {
        val rasterizer = rasterizerFor(turn = Turn.STEEP)
        try {
            val default = render(rasterizer, roundLimit = null)
            val raisedLimit = render(rasterizer, roundLimit = 2.0)

            assertFalse(default.contentEquals(raisedLimit))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun convertedJoinDoesNotApplyTranslucentLineOpacityTwice() = runTest {
        val rasterizer = rasterizerFor(turn = Turn.SHALLOW)
        try {
            val png = render(rasterizer, roundLimit = 2.0, opacity = 0.5)

            val color = png.centerPixelColor()
            val alpha = color ushr 24 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            assertEquals(255, alpha, "the temporary line layer must preserve the opaque background")
            assertTrue(green in 126..129 && blue in 126..129, "expected one 50% red stroke pass, got 0x${color.toUInt().toString(16)}")
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun convertedRoundJoinDoesNotInheritAnAuthoredLowMiterLimit() = runTest {
        val rasterizer = rasterizerFor(turn = Turn.SHALLOW)
        try {
            val lowAuthoredMiter = render(rasterizer, roundLimit = 2.0, miterLimit = 0.25)
            val permissiveAuthoredMiter = render(rasterizer, roundLimit = 2.0, miterLimit = 10.0)

            // line-miter-limit applies to an authored miter join, not to a round join selected for
            // conversion by line-round-limit. Before the replacement pass raised and restored its
            // own miter ceiling, the low value silently turned this selected miter into a bevel.
            assertTrue(lowAuthoredMiter.contentEquals(permissiveAuthoredMiter))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun selectedShallowJoinDoesNotImportAnAdjacentSharpMiter() = runTest {
        val actualRasterizer = rasterizerFor(turn = Turn.CLOSE_SHALLOW_THEN_SHARP)
        val referenceRasterizer = rasterizerForTile(closeJoinReferenceVectorTile())
        try {
            val actual = render(actualRasterizer, roundLimit = 2.0)
            val isolatedReference = renderCloseJoinReference(referenceRasterizer)

            // The reference decomposes the four-point path into two overlapping three-point
            // layers: only the first can convert its shallow join, while the second keeps the
            // adjacent sharp join round. It is the same intended geometry without giving a full
            // MITER redraw any neighboring join to leak through the shallow join's clip.
            val excessCoverage = actual.maximumRedCoverageBeyond(isolatedReference)
            assertTrue(
                excessCoverage <= 8,
                "the adjacent sharp miter leaked into the selected shallow join by $excessCoverage alpha levels",
            )
        } finally {
            actualRasterizer.close()
            actualRasterizer.awaitClosed()
            referenceRasterizer.close()
            referenceRasterizer.awaitClosed()
        }
    }

    private suspend fun render(
        rasterizer: BasemapRasterizer,
        roundLimit: Double?,
        miterLimit: Double? = null,
        opacity: Double = 1.0,
    ): ByteArray {
        val roundLimitJson = roundLimit?.let { ",\"line-round-limit\":$it" }.orEmpty()
        val miterLimitJson = miterLimit?.let { ",\"line-miter-limit\":$it" }.orEmpty()
        val style = rasterizer.prepare(
            StyleInput.InlineJson(
                """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"road","type":"line","source":"v","source-layer":"roads","layout":{"line-cap":"butt","line-join":"round"$roundLimitJson$miterLimitJson},"paint":{"line-color":"#ff0000","line-opacity":$opacity,"line-width":64}}]}""",
            ),
        )
        return rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))
            .tiles
            .single()
            .pngBytes
    }

    private fun rasterizerFor(turn: Turn): BasemapRasterizer {
        return rasterizerForTile(lineVectorTile(turn))
    }

    private fun rasterizerForTile(tile: ByteArray): BasemapRasterizer {
        return Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    TransportResponse(
                        statusCode = 200,
                        body = tile,
                        metadata = TransportResponseMetadata(contentType = "application/vnd.mapbox-vector-tile"),
                    )
                },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
    }

    private suspend fun renderCloseJoinReference(rasterizer: BasemapRasterizer): ByteArray {
        val style = rasterizer.prepare(
            StyleInput.InlineJson(
                """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"base","type":"background","paint":{"background-color":"#ffffff"}},{"id":"sharp","type":"line","source":"v","source-layer":"sharp","layout":{"line-cap":"butt","line-join":"round","line-round-limit":1.0},"paint":{"line-color":"#ff0000","line-width":64}},{"id":"shallow","type":"line","source":"v","source-layer":"shallow","layout":{"line-cap":"butt","line-join":"round","line-round-limit":2.0},"paint":{"line-color":"#ff0000","line-width":64}}]}""",
            ),
        )
        return rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(256))
            .tiles.single().pngBytes
    }

    private fun lineVectorTile(turn: Turn): ByteArray {
        val start = 30 to 128
        val vertex = 128 to 128
        val end = when (turn) {
            Turn.SHALLOW -> 214 to 178
            Turn.STEEP -> 178 to 214
            Turn.CLOSE_SHALLOW_THEN_SHARP -> 140 to 140
        }
        val tail = if (turn == Turn.CLOSE_SHALLOW_THEN_SHARP) listOf(118 to 130) else emptyList()
        return vectorTile(mapOf("roads" to listOf(listOf(start, vertex, end) + tail)))
    }

    private fun closeJoinReferenceVectorTile(): ByteArray = vectorTile(
        mapOf(
            "shallow" to listOf(listOf(30 to 128, 128 to 128, 140 to 140)),
            "sharp" to listOf(listOf(128 to 128, 140 to 140, 118 to 130)),
        ),
    )

    private fun vectorTile(layers: Map<String, List<List<Pair<Int, Int>>>>): ByteArray {
        fun feature(points: List<Pair<Int, Int>>): Tile.Feature {
            var cursor = 0 to 0
            val geometry = buildList {
                val first = points.first()
                add(command(1, 1))
                add(zigZag(first.first))
                add(zigZag(first.second))
                cursor = first
                add(command(2, points.size - 1))
                for (point in points.drop(1)) {
                    add(zigZag(point.first - cursor.first))
                    add(zigZag(point.second - cursor.second))
                    cursor = point
                }
            }
            return Tile.Feature(type = Tile.GeomType.LINESTRING, geometry = geometry)
        }
        return Tile.ADAPTER.encode(
            Tile(
                layers = layers.map { (name, features) ->
                    Tile.Layer(
                        version = 2,
                        name = name,
                        features = features.map(::feature),
                        extent = 256,
                    )
                },
            ),
        )
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun ByteArray.centerPixelColor(): Int {
        val image = Image.makeFromEncoded(this)
        try {
            val bitmap = Bitmap()
            try {
                check(bitmap.allocN32Pixels(image.width, image.height, false))
                check(image.readPixels(bitmap))
                return bitmap.getColor(bitmap.width / 2, bitmap.height / 2)
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    private fun ByteArray.maximumRedCoverageBeyond(reference: ByteArray): Int {
        val actualImage = Image.makeFromEncoded(this)
        val referenceImage = Image.makeFromEncoded(reference)
        try {
            val actualBitmap = Bitmap()
            val referenceBitmap = Bitmap()
            try {
                check(actualBitmap.allocN32Pixels(actualImage.width, actualImage.height, false))
                check(referenceBitmap.allocN32Pixels(referenceImage.width, referenceImage.height, false))
                check(actualImage.readPixels(actualBitmap))
                check(referenceImage.readPixels(referenceBitmap))
                var maximum = 0
                for (y in 0 until actualBitmap.height) {
                    for (x in 0 until actualBitmap.width) {
                        val actualGreen = actualBitmap.getColor(x, y) ushr 8 and 0xff
                        val referenceGreen = referenceBitmap.getColor(x, y) ushr 8 and 0xff
                        maximum = maxOf(maximum, referenceGreen - actualGreen)
                    }
                }
                return maximum
            } finally {
                actualBitmap.close()
                referenceBitmap.close()
            }
        } finally {
            actualImage.close()
            referenceImage.close()
        }
    }

    private enum class Turn {
        SHALLOW,
        STEEP,
        CLOSE_SHALLOW_THEN_SHARP,
    }
}
