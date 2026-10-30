package com.rohittp.rentile.internal

import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.raster.RasterSample
import com.rohittp.rentile.internal.style.CompiledRasterSource
import com.rohittp.rentile.internal.style.DemEncoding
import com.rohittp.rentile.internal.style.TileScheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DemNeighbourhood.heightAt] resolves the nine adjacent tiles once instead of keying a map per
 * sample. It must read exactly what the former per-sample map lookup read, including wrapping
 * across the antimeridian, a missing neighbour falling back to the centre, and offsets beyond one
 * tile, which still take the map path.
 */
class DemNeighbourhoodTest {
    @Test
    fun theAdjacentTableReadsWhatThePerSampleLookupRead() {
        for ((sourceZ, centerX, centerY, missing) in listOf(
            Case(2, 1, 1, emptySet()),
            Case(2, 0, 0, setOf(3 to 1)), // west wraps to x=3; one neighbour absent
            Case(2, 3, 3, setOf(2 to 2)), // north-west absent; no row south of the last
            Case(0, 0, 0, emptySet()), // every horizontal neighbour is the centre itself
        )) {
            val dimension = 1 shl sourceZ
            val decoded = mutableMapOf<Pair<Int, Int>, HillshadeDemPixels>()
            for (y in centerY - 1..centerY + 1) for (x in centerX - 1..centerX + 1) {
                if (y !in 0 until dimension) continue
                val wrapped = ((x % dimension) + dimension) % dimension
                if ((wrapped to y) in missing || (wrapped to y) in decoded) continue
                decoded[wrapped to y] = pixels(seed = wrapped * 31 + y * 7 + 1)
            }
            val center = decoded.getValue(centerX to centerY)
            val sample = RasterSample(SOURCE, TileId(sourceZ, centerX, centerY), sourceZ, centerX, centerY, 1, 0, 0)
            val neighbourhood = DemNeighbourhood(sample, center, decoded, DemEncoding.MAPBOX)
            for (y in -2 * SIZE..3 * SIZE - 1) for (x in -2 * SIZE..3 * SIZE - 1) {
                assertEquals(
                    reference(sample, center, decoded, x, y),
                    neighbourhood.heightAt(x, y),
                    "z$sourceZ $centerX/$centerY at ($x, $y)",
                )
            }
        }
    }

    /** The pre-0.20.0 hillshade `sourceHeightAt`, verbatim apart from its parameters. */
    private fun reference(
        center: RasterSample,
        centerBitmap: HillshadeDemPixels,
        decoded: Map<Pair<Int, Int>, HillshadeDemPixels>,
        sourcePixelX: Int,
        sourcePixelY: Int,
    ): Double {
        val sourceWidth = centerBitmap.width
        val sourceHeight = centerBitmap.height
        val sourceDimension = 1L shl center.sourceZ
        val deltaX = floor(sourcePixelX.toDouble() / sourceWidth).toInt()
        val deltaY = floor(sourcePixelY.toDouble() / sourceHeight).toInt()
        val sourceX = floorMod(center.sourceX.toLong() + deltaX, sourceDimension).toInt()
        val sourceY = center.sourceY + deltaY
        val bitmap = decoded[sourceX to sourceY] ?: centerBitmap
        val localX = floorMod(sourcePixelX.toLong(), sourceWidth.toLong()).toInt()
        val localY = sourcePixelY.coerceIn(0, sourceHeight - 1)
            .let { value -> if (bitmap === centerBitmap && sourceY != center.sourceY) value else floorMod(sourcePixelY.toLong(), sourceHeight.toLong()).toInt() }
        return bitmap.heightAt(localX, localY, DemEncoding.MAPBOX)
    }

    private fun floorMod(value: Long, divisor: Long): Long {
        val remainder = value % divisor
        return if (remainder < 0) remainder + divisor else remainder
    }

    private fun pixels(seed: Int): HillshadeDemPixels {
        val rgba = ByteArray(SIZE * SIZE * 4)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val offset = (y * SIZE + x) * 4
            rgba[offset] = seed.toByte()
            rgba[offset + 1] = (x * 17 + seed).toByte()
            rgba[offset + 2] = (y * 29 + seed * 3).toByte()
            rgba[offset + 3] = 0xff.toByte()
        }
        val image = Image.makeRaster(ImageInfo(SIZE, SIZE, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), rgba, SIZE * 4)
        val bitmap = Bitmap()
        try {
            check(bitmap.allocN32Pixels(SIZE, SIZE, false))
            check(image.readPixels(bitmap))
            return checkNotNull(HillshadeDemPixels.read(bitmap))
        } finally {
            bitmap.close()
            image.close()
        }
    }

    private data class Case(val z: Int, val x: Int, val y: Int, val missing: Set<Pair<Int, Int>>)

    private companion object {
        const val SIZE = 8
        val SOURCE = CompiledRasterSource(
            idDigest = "dem",
            tileTemplates = emptyList(),
            tileSize = 256,
            scheme = TileScheme.XYZ,
            minZoom = 0,
            maxZoom = 22,
        )
    }
}
