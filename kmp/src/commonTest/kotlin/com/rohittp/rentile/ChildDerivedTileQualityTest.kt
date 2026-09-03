package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Is a tile derived from its four children the same quality as the exact tile?
 *
 * On raw resolution it ought to be better: four 512 px children downsampled into one 512 px output
 * is 1024 px of source, i.e. 2x supersampling. This measures whether that holds once a real style is
 * involved.
 *
 * The geometry is identical in world terms in both arms -- one horizontal line across the upper
 * quarter of the parent tile -- so any difference in the result comes from the derivation, not from
 * the map data.
 *
 * It does not hold, and the reason is more basic than cartographic generalisation: `line-width` is
 * in **style pixels**, which one output tile renders at `outputSizePx / 512` whatever its own zoom.
 * Each child draws its full-width stroke and composition then scales it by a half, so every stroked
 * feature in a derived tile comes out at half weight. Measured here at 16 rows exact against 8 rows
 * derived, with the line centred identically in both -- same content, same position, half the
 * weight.
 *
 * Rendered at the reference size, so `line-width: 16` is 16 output pixels and the arithmetic below
 * is the style's own. The defect is the composition step, not the size: it is a half either way.
 *
 * This style is deliberately zoom-independent (constant width, no minzoom, no interpolation), so
 * the effect is not the wrong zoom's styling. A zoom-dependent style stacks that error on top.
 * Ancestor substitution has the mirror defect: upscaling doubles stroke weight and adds blur.
 *
 * This is why child derivation is a degradation to be budgeted rather than an optimisation to be
 * maximised, despite being supersampled -- see ADR 0010.
 */
class ChildDerivedTileQualityTest {
    @Test
    fun childDerivationHalvesStrokeWidthBecauseLineWidthIsInScreenPixels() = runTest {
        val exact = renderExactParent()
        val derived = renderDerivedFromChildren()

        val exactRows = exact.paintedRowsAtCentre()
        val derivedRows = derived.paintedRowsAtCentre()
        // The line is authored `line-width: 16`, so the exact tile paints ~16 rows. Each child
        // paints ~16 of its own rows, and composing halves them: the derived tile paints ~8.
        assertTrue(
            exactRows in 14..18,
            "exact tile should paint ~16 rows for line-width 16, painted $exactRows",
        )
        assertTrue(
            derivedRows in 6..10,
            "child-derived tile should paint ~8 rows (half), painted $derivedRows",
        )
        assertTrue(
            derivedRows * 2 in (exactRows - 3)..(exactRows + 3),
            "derived stroke should be about half the exact stroke: $derivedRows vs $exactRows",
        )
    }

    @Test
    fun bothArmsPlaceTheLineAtTheSamePositionSoOnlyWeightDiffers() = runTest {
        val exact = renderExactParent().paintedRowRangeAtCentre()
        val derived = renderDerivedFromChildren().paintedRowRangeAtCentre()

        val exactCentre = (exact.first + exact.last) / 2
        val derivedCentre = (derived.first + derived.last) / 2
        // Position is preserved -- the artifact is weight, not placement. If this ever fails the
        // derivation has a geometry bug, which is a different and worse problem.
        assertTrue(
            derivedCentre in (exactCentre - 2)..(exactCentre + 2),
            "line centre moved: exact $exactCentre, derived $derivedCentre",
        )
    }

    // ---- arms -------------------------------------------------------------------------------

    /** The parent rendered normally: one network tile, full style, at the requested zoom. */
    private suspend fun renderExactParent(): ByteArray {
        val rasterizer = rasterizerServing { z, _, y ->
            if (z == 0) lineTile(LINE_Y_IN_PARENT) else emptyTile()
        }
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LINE_STYLE))
            return rasterizer.render(style, listOf(TileId(0, 0, 0)), RenderOptions(SIZE))
                .tiles.single().pngBytes
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    /**
     * The same world geometry, rendered as four z+1 children and composed down.
     *
     * The line sits in the parent's upper quarter, so in the top two children it lands at their
     * vertical centre and the bottom two are empty -- exactly how the world geometry subdivides.
     */
    private suspend fun renderDerivedFromChildren(): ByteArray {
        val rasterizer = rasterizerServing { z, _, y ->
            when {
                z == 1 && y == 0 -> lineTile(LINE_Y_IN_TOP_CHILD)
                else -> emptyTile()
            }
        }
        val children: List<ByteArray>
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LINE_STYLE))
            children = listOf(
                TileId(1, 0, 0), TileId(1, 1, 0),
                TileId(1, 0, 1), TileId(1, 1, 1),
            ).map { child ->
                rasterizer.render(style, listOf(child), RenderOptions(SIZE)).tiles.single().pngBytes
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
        return composeQuadrants(children)
    }

    /** Mirrors `composeRasterChildren`: four children into quadrants, LINEAR sampling. */
    private fun composeQuadrants(children: List<ByteArray>): ByteArray {
        val surface = Surface.makeRasterN32Premul(SIZE, SIZE)
        try {
            children.forEachIndexed { index, bytes ->
                val image = Image.makeFromEncoded(bytes)
                try {
                    val left = (index % 2) * SIZE / 2f
                    val top = (index / 2) * SIZE / 2f
                    surface.canvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeXYWH(left, top, SIZE / 2f, SIZE / 2f),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                } finally {
                    image.close()
                }
            }
            val image = surface.makeImageSnapshot()
            try {
                return image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
            } finally {
                image.close()
            }
        } finally {
            surface.close()
        }
    }

    // ---- measurement ------------------------------------------------------------------------

    private fun ByteArray.paintedRowsAtCentre(): Int = paintedRowRangeAtCentre().count()

    /** Rows in the centre column whose red channel dominates, i.e. the stroke. */
    private fun ByteArray.paintedRowRangeAtCentre(): IntRange {
        val image = Image.makeFromEncoded(this)
        try {
            val bitmap = Bitmap()
            try {
                bitmap.allocN32Pixels(image.width, image.height, false)
                image.readPixels(bitmap)
                val painted = (0 until image.height).filter { y ->
                    val argb = bitmap.getColor(image.width / 2, y)
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    r > 0x80 && g < 0x80
                }
                return if (painted.isEmpty()) IntRange.EMPTY else painted.first()..painted.last()
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private fun rasterizerServing(tileFor: (z: Int, x: Int, y: Int) -> ByteArray): BasemapRasterizer =
        Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { request ->
                    val (z, x, y) = request.url.tileCoordinates()
                    TransportResponse(
                        statusCode = 200,
                        body = tileFor(z, x, y),
                        metadata = TransportResponseMetadata(
                            contentType = "application/vnd.mapbox-vector-tile",
                        ),
                    )
                },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )

    private fun String.tileCoordinates(): Triple<Int, Int, Int> {
        val parts = substringBefore('?').removeSuffix(".pbf").split('/')
        val y = parts[parts.lastIndex].toInt()
        val x = parts[parts.lastIndex - 1].toInt()
        val z = parts[parts.lastIndex - 2].toInt()
        return Triple(z, x, y)
    }

    private fun lineTile(y: Int): ByteArray = vectorTile(listOf(listOf(0 to y, EXTENT to y)))

    private fun emptyTile(): ByteArray = vectorTile(emptyList())

    private fun vectorTile(lines: List<List<Pair<Int, Int>>>): ByteArray {
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
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = "roads",
                        features = lines.map(::feature),
                        extent = EXTENT,
                    ),
                ),
            ),
        )
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private companion object {
        const val SIZE = 512
        const val EXTENT = 256

        /** Upper quarter of the parent, so it falls at the centre of the top children. */
        const val LINE_Y_IN_PARENT = EXTENT / 4
        const val LINE_Y_IN_TOP_CHILD = EXTENT / 2

        /** Deliberately zoom-independent: `line-width` is constant, so any difference is derivation. */
        const val LINE_STYLE =
            """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},""" +
                """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
                """{"id":"road","type":"line","source":"v","source-layer":"roads",""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#ff0000","line-width":16}}]}"""
    }
}
