package com.rohittp.rentile.internal.mvt

import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.style.CompiledVectorSource
import com.rohittp.rentile.internal.style.TileScheme
import com.rohittp.rentile.internal.style.intersects

internal data class VectorTileSample(
    val source: CompiledVectorSource,
    val outputTile: TileId,
    val sourceZ: Int,
    val sourceX: Int,
    val sourceY: Int,
    val childScale: Int,
    val childX: Int,
    val childY: Int,
) {
    val identity: String
        get() = "${source.idDigest}:$sourceZ/$sourceX/$sourceY"

    fun sourceCoordinateToOutputPixels(
        coordinate: VectorCoordinate,
        extent: Int,
        outputSizePx: Int,
    ): OutputPixelCoordinate {
        require(extent > 0)
        require(outputSizePx > 0)
        val localX = coordinate.x.toDouble() * childScale - childX.toDouble() * extent
        val localY = coordinate.y.toDouble() * childScale - childY.toDouble() * extent
        return OutputPixelCoordinate(
            x = localX * outputSizePx / extent,
            y = localY * outputSizePx / extent,
        )
    }
}

internal fun VectorTileSample.immediateChildren(): List<VectorTileSample> {
    if (sourceZ >= source.maxZoom) return emptyList()
    return (0..1).flatMap { deltaY ->
        (0..1).map { deltaX ->
            copy(
                sourceZ = sourceZ + 1,
                sourceX = sourceX * 2 + deltaX,
                sourceY = sourceY * 2 + deltaY,
                childScale = 1,
                childX = 0,
                childY = 0,
            )
        }
    }
}

internal fun VectorTileSample.ancestor(distance: Int): VectorTileSample? {
    require(distance > 0)
    val ancestorZ = sourceZ - distance
    if (ancestorZ < source.minZoom) return null
    val scale = 1 shl distance
    return copy(
        sourceZ = ancestorZ,
        sourceX = sourceX / scale,
        sourceY = sourceY / scale,
        childScale = childScale * scale,
        childX = (sourceX % scale) * childScale + childX,
        childY = (sourceY % scale) * childScale + childY,
    )
}

internal data class OutputPixelCoordinate(
    val x: Double,
    val y: Double,
)

internal fun CompiledVectorSource.sampleFor(tile: TileId): VectorTileSample? {
    if (tile.z < minZoom) return null
    if (bounds?.intersects(tile) == false) return null
    val outputDimension = 1L shl tile.z
    val canonicalOutputX = tile.x.toLong().floorMod(outputDimension)
    val sourceZ = minOf(tile.z, maxZoom)
    val zoomDelta = tile.z - sourceZ
    val childScale = 1 shl zoomDelta
    val sourceX = (canonicalOutputX / childScale).toInt()
    val sourceY = tile.y / childScale
    return VectorTileSample(
        source = this,
        outputTile = tile,
        sourceZ = sourceZ,
        sourceX = sourceX,
        sourceY = sourceY,
        childScale = childScale,
        childX = (canonicalOutputX % childScale).toInt(),
        childY = tile.y % childScale,
    )
}

internal fun VectorTileSample.tileUrl(): String {
    val dimension = 1L shl sourceZ
    val templateIndex = ((sourceZ.toLong() * 31L + sourceX * 17L + sourceY).floorMod(source.tileTemplates.size.toLong())).toInt()
    val template = source.tileTemplates[templateIndex].resolve()
    val requestY = when (source.scheme) {
        TileScheme.XYZ -> sourceY
        TileScheme.TMS -> (dimension - 1L - sourceY).toInt()
    }
    return template
        .replace("{z}", sourceZ.toString())
        .replace("{x}", sourceX.toString())
        .replace("{y}", requestY.toString())
        .replace("{-y}", (dimension - 1L - sourceY).toString())
}

private fun Long.floorMod(divisor: Long): Long {
    val remainder = this % divisor
    return if (remainder < 0) remainder + divisor else remainder
}
