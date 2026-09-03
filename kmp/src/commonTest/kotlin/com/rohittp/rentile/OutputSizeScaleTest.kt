package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `outputSizePx` is a device pixel ratio, not a zoom shift.
 *
 * ADR 0013 fixes the contract: "Style-space scaling derives from `outputSizePx / 512`". So one
 * output zoom must produce *the same cartography* at every supported size - the same features, the
 * same relative ink - only with more pixels. A tile asked for at 2048 must not look like the
 * `z + 2` tile it has the pixel budget of: that is the difference between sharper and denser, and
 * it is the whole reason a consumer can ask for 2048 to cut its request fan-out sixteen-fold.
 *
 * Every test here fixes one half of that: the style is evaluated at the tile's `z` regardless of
 * size, and everything measured in pixels afterwards is multiplied by `outputSizePx / 512`.
 */
class OutputSizeScaleTest {
    @Test
    fun strokeWidthScalesWithOutputSizeSoOneZoomGetsSharperNotDenser() = runTest {
        // `line-width: 16` at the reference size is 16 px of ink. At 2x the pixels it must be 32 px
        // of ink covering the same ground, not 16 px of ink and twice the roads.
        val measured = SUPPORTED.associateWith { size -> paintedRows(size, MAJOR_ROW_FRACTION, ::isRed) }

        assertEquals(4, measured.size)
        for ((size, rows) in measured) {
            val expected = 16.0 * size / RenderOptions.DEFAULT_OUTPUT_SIZE_PX
            assertTrue(
                abs(rows - expected) <= 1 + expected * 0.05,
                "line-width 16 at outputSizePx $size should paint about $expected rows, painted $rows",
            )
        }
    }

    @Test
    fun relativeStrokeWidthsAreIdenticalAtEveryOutputSize() = runTest {
        // A four-fold width ratio in the style has to survive as a four-fold ratio in pixels at
        // every size. A scale applied to one property and not another would show up here even if
        // each individual width still looked plausible.
        val ratios = SUPPORTED.associateWith { size ->
            val thick = paintedRows(size, MAJOR_ROW_FRACTION, ::isRed).toDouble()
            val thin = paintedRows(size, MINOR_ROW_FRACTION, ::isGreen).toDouble()
            thick / thin
        }

        for ((size, ratio) in ratios) {
            assertTrue(
                abs(ratio - 4.0) <= 0.5,
                "line-width 16 over line-width 4 should stay 4x at outputSizePx $size, measured $ratio",
            )
        }
    }

    @Test
    fun zoomDependentWidthEvaluatesAtTheTilesZoomAndIsThenScaled() = runTest {
        // The trap this exists to catch: implementing 2048 as "fetch two zooms deeper" or
        // evaluating the style at `z + log2(scale)`. The style ramps line-width from 4 at z0 to 24
        // at z10, so z5 evaluates to 14. Re-detailing at 2048 would evaluate z7 (18) and paint
        // 18 px, not 14 px scaled to 56.
        for (size in SUPPORTED) {
            val rows = paintedRows(
                size = size,
                rowFraction = MAJOR_ROW_FRACTION,
                predicate = ::isRed,
                style = ZOOM_RAMP_STYLE,
                tile = TileId(5, 0, 0),
            )
            val expected = 14.0 * size / RenderOptions.DEFAULT_OUTPUT_SIZE_PX
            assertTrue(
                abs(rows - expected) <= 1 + expected * 0.05,
                "z5 evaluates line-width 14; at outputSizePx $size that is $expected rows, painted $rows",
            )
        }
    }

    @Test
    fun featureVisibilityDoesNotChangeWithOutputSize() = runTest {
        // A layer gated at minzoom 4 is the cheapest detector of a zoom shift: it is absent at z3
        // and present at z5, at every size, or the size is moving the evaluation zoom.
        for (size in SUPPORTED) {
            assertEquals(
                0,
                paintedRows(size, MINOR_ROW_FRACTION, ::isGreen, GATED_STYLE, TileId(3, 0, 0)),
                "the minzoom-4 layer must stay hidden at z3 for outputSizePx $size",
            )
            assertTrue(
                paintedRows(size, MINOR_ROW_FRACTION, ::isGreen, GATED_STYLE, TileId(5, 0, 0)) > 0,
                "the minzoom-4 layer must be drawn at z5 for outputSizePx $size",
            )
        }
    }

    @Test
    fun theLineStaysAtTheSameFractionOfTheTileAtEveryOutputSize() = runTest {
        // Ink weight scaling would be worthless if the geometry moved. Same ground, same place.
        val centres = SUPPORTED.associateWith { size ->
            val range = paintedRowRange(size, MAJOR_ROW_FRACTION, ::isRed)
            (range.first + range.last) / 2.0 / size
        }

        val reference = centres.getValue(RenderOptions.DEFAULT_OUTPUT_SIZE_PX)
        for ((size, centre) in centres) {
            assertTrue(
                abs(centre - reference) < 0.005,
                "the stroke centre moved from $reference to $centre at outputSizePx $size",
            )
        }
    }

    @Test
    fun aLargeTileBoxDownscalesOntoItsReferenceSizedCounterpart() = runTest {
        // The strongest statement of the property: a 1024 tile averaged 2x2 back down is the 512
        // tile. Only a pure scale can satisfy this; any re-detailing, any unscaled ink, and any
        // geometry drift shows up as error here.
        val reference = renderBitmap(RenderOptions.DEFAULT_OUTPUT_SIZE_PX, MIXED_STYLE)
        val large = renderBitmap(2 * RenderOptions.DEFAULT_OUTPUT_SIZE_PX, MIXED_STYLE)
        try {
            var total = 0.0
            var samples = 0
            var coarse = 0
            for (y in 0 until reference.height) {
                for (x in 0 until reference.width) {
                    val expected = reference.getColor(x, y)
                    val actual = boxAverage(large, x * 2, y * 2, 2)
                    for (shift in intArrayOf(16, 8, 0)) {
                        val difference = abs(
                            ((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF),
                        )
                        total += difference
                        samples++
                        if (difference > 24) coarse++
                    }
                }
            }
            val mean = total / samples
            assertTrue(mean < 4.0, "mean channel error between the downscaled 1024 tile and the 512 tile was $mean")
            assertTrue(
                coarse.toDouble() / samples < 0.02,
                "${coarse.toDouble() / samples * 100} percent of channels differed by more than 24",
            )
        } finally {
            reference.close()
            large.close()
        }
    }

    @Test
    fun everySupportedSizeProducesATileOfExactlyThatSize() = runTest {
        for (size in SUPPORTED) {
            val png = render(size, TWO_WIDTH_STYLE, TileId(0, 0, 0))
            val image = Image.makeFromEncoded(png)
            try {
                assertEquals(size, image.width, "PNG width at outputSizePx $size")
                assertEquals(size, image.height, "PNG height at outputSizePx $size")
            } finally {
                image.close()
            }
        }
    }

    @Test
    fun theRawPathReturnsFourBytesPerPixelAtEverySupportedSize() = runTest {
        // The raw path hands the caller `outputSizePx^2 * 4` bytes: 16 MiB at 2048. Pinning the
        // length here is what makes the memory cost of the large sizes a stated fact rather than
        // something a consumer discovers on a device.
        for (size in SUPPORTED) {
            val rasterizer = rasterizer()
            try {
                val prepared = rasterizer.prepare(StyleInput.InlineJson(TWO_WIDTH_STYLE))
                val tile = TileId(0, 0, 0)
                val batch = rasterizer.prepareBatch(prepared, listOf(tile), RenderOptions(size))
                try {
                    val raw = rasterizer.renderRaw(batch, listOf(tile)).tiles.single()
                    assertEquals(size, raw.widthPx)
                    assertEquals(size, raw.heightPx)
                    assertEquals(size * size * 4, raw.rgbaBytes.size)
                } finally {
                    batch.close()
                }
            } finally {
                rasterizer.close()
                rasterizer.awaitClosed()
            }
        }
    }

    @Test
    fun anOversizedOutputSurfaceFailsTheRasterDimensionCeiling() = runTest {
        // The raster ceilings used to bound only *source* imagery and the glyph atlas; the tile
        // Rentile allocates for itself was unbounded, which stopped being harmless once one could
        // be 16 MiB. A host that has tightened the ceiling for a small device must get a typed
        // refusal, not a Skia allocation failure or an OOM.
        val error = assertFailsWith<SafetyLimitException> {
            renderWithLimits(ResourceLimits(maxRasterDimensionPx = 1024), 2048)
        }

        assertEquals("maxRasterDimensionPx", error.limitName)
        assertEquals(1024L, error.limit)
        assertEquals(2048L, error.observed)
        assertEquals(PipelineStage.RASTERIZATION, error.stage)
        assertEquals(listOf(TileId(0, 0, 0)), error.affectedTiles)
    }

    @Test
    fun anOversizedOutputSurfaceFailsTheDecodedByteCeiling() = runTest {
        // 2048 px is 2048 * 2048 * 4 = 16 MiB. Under a 4 MiB ceiling it must be refused, and the
        // observed value must be the surface's own byte count so the message is actionable.
        val error = assertFailsWith<SafetyLimitException> {
            renderWithLimits(ResourceLimits(maxDecodedRasterBytes = 4L * 1024L * 1024L), 2048)
        }

        assertEquals("maxResidentDecodedBytes", error.limitName)
        assertEquals(4L * 1024L * 1024L, error.limit)
        assertEquals(2048L * 2048L * 4L, error.observed)
        assertEquals(PipelineStage.RASTERIZATION, error.stage)
    }

    @Test
    fun theDefaultCeilingsClearEverySupportedSize() = runTest {
        // The mirror of the two tests above: nothing in the default configuration refuses a
        // supported size. 2048 px is 16 MiB against a 256 MiB decoded ceiling and 8192 px of
        // dimension, so no ceiling needed raising to support it - and this fails if one is ever
        // lowered past a size the public set still advertises.
        val limits = ResourceLimits()
        for (size in SUPPORTED) {
            assertTrue(size <= limits.maxRasterDimensionPx, "outputSizePx $size exceeds maxRasterDimensionPx")
            assertTrue(
                size.toLong() * size.toLong() * 4L <= limits.maxDecodedRasterBytes,
                "an outputSizePx $size surface exceeds maxDecodedRasterBytes",
            )
            assertTrue(
                size.toLong() * size.toLong() * 4L <= ExecutionPolicy().maxResidentDecodedBytes,
                "an outputSizePx $size surface exceeds maxResidentDecodedBytes",
            )
        }
    }

    // ---- measurement ------------------------------------------------------------------------

    private suspend fun renderWithLimits(limits: ResourceLimits, size: Int) {
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport {
                    TransportResponse(
                        statusCode = 200,
                        body = VECTOR_TILE,
                        metadata = TransportResponseMetadata(contentType = "application/vnd.mapbox-vector-tile"),
                    )
                },
                rawResourceStore = InMemoryRawResourceStore(),
                resourceLimits = limits,
            ),
        )
        try {
            val prepared = rasterizer.prepare(StyleInput.InlineJson(TWO_WIDTH_STYLE))
            rasterizer.render(prepared, listOf(TileId(0, 0, 0)), RenderOptions(size))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private suspend fun paintedRows(
        size: Int,
        rowFraction: Double,
        predicate: (Int) -> Boolean,
        style: String = TWO_WIDTH_STYLE,
        tile: TileId = TileId(0, 0, 0),
    ): Int = paintedRowRange(size, rowFraction, predicate, style, tile).let {
        if (it == IntRange.EMPTY) 0 else it.count()
    }

    /**
     * The run of rows the predicate accepts in the column through the middle of the tile, searched
     * only around [rowFraction] so a second layer's stroke cannot be mistaken for this one's.
     */
    private suspend fun paintedRowRange(
        size: Int,
        rowFraction: Double,
        predicate: (Int) -> Boolean,
        style: String = TWO_WIDTH_STYLE,
        tile: TileId = TileId(0, 0, 0),
    ): IntRange {
        val bitmap = renderBitmap(size, style, tile)
        try {
            val centre = (rowFraction * size).roundToInt()
            val window = size / 8
            val painted = ((centre - window).coerceAtLeast(0) until (centre + window).coerceAtMost(size))
                .filter { y -> predicate(bitmap.getColor(size / 2, y)) }
            return if (painted.isEmpty()) IntRange.EMPTY else painted.first()..painted.last()
        } finally {
            bitmap.close()
        }
    }

    private fun boxAverage(bitmap: Bitmap, left: Int, top: Int, span: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (y in top until top + span) {
            for (x in left until left + span) {
                val colour = bitmap.getColor(x, y)
                red += (colour shr 16) and 0xFF
                green += (colour shr 8) and 0xFF
                blue += colour and 0xFF
            }
        }
        val count = span * span
        return (red / count shl 16) or (green / count shl 8) or (blue / count)
    }

    private suspend fun renderBitmap(
        size: Int,
        style: String,
        tile: TileId = TileId(0, 0, 0),
    ): Bitmap {
        val png = render(size, style, tile)
        val image = Image.makeFromEncoded(png)
        try {
            val bitmap = Bitmap()
            check(bitmap.allocN32Pixels(image.width, image.height, false))
            check(image.readPixels(bitmap))
            check(bitmap.width == size) { "expected a $size px tile, got ${bitmap.width}" }
            return bitmap
        } finally {
            image.close()
        }
    }

    private suspend fun render(size: Int, style: String, tile: TileId): ByteArray {
        val rasterizer = rasterizer()
        try {
            val prepared = rasterizer.prepare(StyleInput.InlineJson(style))
            return rasterizer.render(prepared, listOf(tile), RenderOptions(size)).tiles.single().pngBytes
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private fun rasterizer(): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = ResourceTransport {
                TransportResponse(
                    statusCode = 200,
                    body = VECTOR_TILE,
                    metadata = TransportResponseMetadata(contentType = "application/vnd.mapbox-vector-tile"),
                )
            },
            rawResourceStore = InMemoryRawResourceStore(),
        ),
    )

    private companion object {
        val SUPPORTED = RenderOptions.SUPPORTED_OUTPUT_SIZES.sorted()

        const val EXTENT = 4096
        const val MAJOR_ROW = EXTENT / 4
        const val MINOR_ROW = EXTENT * 3 / 4
        const val MAJOR_ROW_FRACTION = 0.25
        const val MINOR_ROW_FRACTION = 0.75

        private fun isRed(colour: Int): Boolean =
            ((colour shr 16) and 0xFF) > 0x80 && ((colour shr 8) and 0xFF) < 0x80

        private fun isGreen(colour: Int): Boolean =
            ((colour shr 8) and 0xFF) > 0x80 && ((colour shr 16) and 0xFF) < 0x80

        private const val SOURCE =
            """"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}}"""

        private const val BACKGROUND =
            """{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}}"""

        /** Constant widths four apart, so the ratio is checkable without any zoom arithmetic. */
        const val TWO_WIDTH_STYLE =
            """{"version":8,$SOURCE,"layers":[$BACKGROUND,""" +
                """{"id":"major","type":"line","source":"v","source-layer":"major",""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#ff0000","line-width":16}},""" +
                """{"id":"minor","type":"line","source":"v","source-layer":"minor",""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#00ff00","line-width":4}}]}"""

        /** Ramps 4 -> 24 between z0 and z10, so z5 is 14 and z7 is 18. */
        const val ZOOM_RAMP_STYLE =
            """{"version":8,$SOURCE,"layers":[$BACKGROUND,""" +
                """{"id":"major","type":"line","source":"v","source-layer":"major",""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#ff0000",""" +
                """"line-width":["interpolate",["linear"],["zoom"],0,4,10,24]}}]}"""

        /** The green layer appears only from z4, at every output size or not at all. */
        const val GATED_STYLE =
            """{"version":8,$SOURCE,"layers":[$BACKGROUND,""" +
                """{"id":"major","type":"line","source":"v","source-layer":"major",""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#ff0000","line-width":16}},""" +
                """{"id":"minor","type":"line","source":"v","source-layer":"minor","minzoom":4,""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#00ff00","line-width":8}}]}"""

        /** A fill, a straight stroke and a diagonal stroke, for the downscale comparison. */
        const val MIXED_STYLE =
            """{"version":8,$SOURCE,"layers":[$BACKGROUND,""" +
                """{"id":"land","type":"fill","source":"v","source-layer":"land",""" +
                """"paint":{"fill-color":"#c8dcc8"}},""" +
                """{"id":"major","type":"line","source":"v","source-layer":"major",""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#ff0000","line-width":16}},""" +
                """{"id":"diagonal","type":"line","source":"v","source-layer":"diagonal",""" +
                """"layout":{"line-cap":"butt","line-join":"miter"},""" +
                """"paint":{"line-color":"#0000ff","line-width":6}}]}"""

        val VECTOR_TILE: ByteArray = vectorTile()

        private fun vectorTile(): ByteArray = Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    lineLayer("major", listOf(0 to MAJOR_ROW, EXTENT to MAJOR_ROW)),
                    lineLayer("minor", listOf(0 to MINOR_ROW, EXTENT to MINOR_ROW)),
                    lineLayer("diagonal", listOf(0 to 0, EXTENT to EXTENT)),
                    polygonLayer("land"),
                ),
            ),
        )

        private fun lineLayer(name: String, points: List<Pair<Int, Int>>): Tile.Layer = Tile.Layer(
            version = 2,
            name = name,
            features = listOf(
                Tile.Feature(type = Tile.GeomType.LINESTRING, geometry = geometry(points, closed = false)),
            ),
            extent = EXTENT,
        )

        private fun polygonLayer(name: String): Tile.Layer = Tile.Layer(
            version = 2,
            name = name,
            features = listOf(
                Tile.Feature(
                    type = Tile.GeomType.POLYGON,
                    geometry = geometry(
                        listOf(
                            EXTENT / 8 to EXTENT / 2,
                            EXTENT * 7 / 8 to EXTENT / 2,
                            EXTENT * 7 / 8 to EXTENT * 5 / 8,
                            EXTENT / 8 to EXTENT * 5 / 8,
                        ),
                        closed = true,
                    ),
                ),
            ),
            extent = EXTENT,
        )

        private fun geometry(points: List<Pair<Int, Int>>, closed: Boolean): List<Int> = buildList {
            var cursor = 0 to 0
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
            if (closed) add(command(7, 1))
        }

        private fun command(id: Int, count: Int): Int = (count shl 3) or id

        private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)
    }
}
