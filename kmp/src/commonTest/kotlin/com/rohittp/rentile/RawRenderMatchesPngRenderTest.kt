package com.rohittp.rentile

import com.rohittp.rentile.internal.mvt.Tile
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [BasemapRasterizer.renderRaw] exists so a caller uploading tiles to a texture can skip the PNG
 * encode and the decode that immediately undoes it. That is only sound if the bytes it returns are
 * the bytes decoding the PNG would have produced.
 *
 * The trap this pins: `Surface` renders premultiplied N32, and N32 is BGRA on some platforms. Handing
 * those bytes to a consumer expecting straight-alpha RGBA8888 -- which is what decoding the PNG
 * yields -- gives swapped channels and wrong alpha, on some platforms only. So `renderRaw` converts
 * explicitly rather than handing back the surface's native layout.
 */
class RawRenderMatchesPngRenderTest {
    @Test
    fun rawPixelsAreIdenticalToDecodingThePng() = runTest {
        val rasterizer = rasterizerServingLine()
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LINE_STYLE))
            val tile = TileId(0, 0, 0)

            val batch = rasterizer.prepareBatch(style, listOf(tile), RenderOptions(SIZE))
            try {
                val png = rasterizer.render(batch, listOf(tile)).tiles.single()
                val raw = rasterizer.renderRaw(batch, listOf(tile)).tiles.single()

                assertEquals(SIZE, raw.widthPx)
                assertEquals(SIZE, raw.heightPx)
                assertEquals(SIZE * SIZE * 4, raw.rgbaBytes.size)
                // Same content, so the same cache entry serves both.
                assertEquals(png.contentKey, raw.contentKey)

                val fromPng = png.pngBytes.decodeToStraightRgba()
                assertEquals(fromPng.size, raw.rgbaBytes.size)

                val mismatches = fromPng.indices.count { fromPng[it] != raw.rgbaBytes[it] }
                assertEquals(
                    0,
                    mismatches,
                    "raw and PNG-decoded pixels differ in $mismatches of ${fromPng.size} bytes",
                )
            } finally {
                batch.close()
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun rawOutputIsNotPremultipliedAndNotChannelSwapped() = runTest {
        // A pure red line on white. Straight-alpha RGBA8888 puts red in byte 0. A BGRA layout would
        // put it in byte 2, and premultiplication would darken it -- either failure shows up here.
        val rasterizer = rasterizerServingLine()
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(LINE_STYLE))
            val tile = TileId(0, 0, 0)
            val raw = rasterizer.renderRaw(
                rasterizer.prepareBatch(style, listOf(tile), RenderOptions(SIZE)).also { it.tiles },
                listOf(tile),
            ).tiles.single()

            val centre = ((SIZE / 2) * SIZE + (SIZE / 2)) * 4
            val r = raw.rgbaBytes[centre].toInt() and 0xFF
            val g = raw.rgbaBytes[centre + 1].toInt() and 0xFF
            val b = raw.rgbaBytes[centre + 2].toInt() and 0xFF
            val a = raw.rgbaBytes[centre + 3].toInt() and 0xFF

            assertTrue(r > 0xE0, "expected red in byte 0, got r=$r g=$g b=$b a=$a")
            assertTrue(g < 0x20, "expected no green at the line centre, got r=$r g=$g b=$b a=$a")
            assertTrue(b < 0x20, "expected no blue at the line centre, got r=$r g=$g b=$b a=$a")
            assertEquals(0xFF, a, "expected opaque")
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    private fun ByteArray.decodeToStraightRgba(): ByteArray {
        val image = Image.makeFromEncoded(this)
        try {
            val info = ImageInfo(
                ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, null),
                image.width,
                image.height,
            )
            val bitmap = Bitmap()
            try {
                bitmap.allocPixels(info)
                image.readPixels(bitmap)
                return bitmap.readPixels()!!
            } finally {
                bitmap.close()
            }
        } finally {
            image.close()
        }
    }

    private fun rasterizerServingLine(): BasemapRasterizer = Rentile.create(
        RentileConfiguration(
            transport = ResourceTransport {
                TransportResponse(
                    statusCode = 200,
                    body = lineTile(),
                    metadata = TransportResponseMetadata(
                        contentType = "application/vnd.mapbox-vector-tile",
                    ),
                )
            },
            rawResourceStore = InMemoryRawResourceStore(),
        ),
    )

    private fun lineTile(): ByteArray {
        val points = listOf(0 to EXTENT / 2, EXTENT to EXTENT / 2)
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
        return Tile.ADAPTER.encode(
            Tile(
                layers = listOf(
                    Tile.Layer(
                        version = 2,
                        name = "roads",
                        features = listOf(
                            Tile.Feature(type = Tile.GeomType.LINESTRING, geometry = geometry),
                        ),
                        extent = EXTENT,
                    ),
                ),
            ),
        )
    }

    private fun command(id: Int, count: Int): Int = (count shl 3) or id

    private fun zigZag(value: Int): Int = (value shl 1) xor (value shr 31)

    private companion object {
        const val SIZE = 256
        const val EXTENT = 256
        const val LINE_STYLE =
            """{"version":8,"sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},""" +
                """"layers":[{"id":"bg","type":"background","paint":{"background-color":"#ffffff"}},""" +
                """{"id":"road","type":"line","source":"v","source-layer":"roads",""" +
                """"layout":{"line-cap":"butt"},"paint":{"line-color":"#ff0000","line-width":32}}]}"""
    }
}
