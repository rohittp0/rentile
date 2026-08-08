package com.rohittp.rentile.internal.raster

import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.style.CompiledRasterSource
import com.rohittp.rentile.internal.style.intersects

internal data class RasterSample(
    val source: CompiledRasterSource,
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
}

internal data class RasterResource(
    val sample: RasterSample,
    val bytes: ByteArray,
    val contentDigest: String,
    val width: Int,
    val height: Int,
    val diagnostics: List<RenderDiagnostic>,
    val exactSample: RasterSample = sample,
    val substitution: ResourceSubstitution? = null,
)

internal fun CompiledRasterSource.sampleFor(tile: TileId): RasterSample? {
    if (tile.z < minZoom) return null
    if (bounds?.intersects(tile) == false) return null
    val outputDimension = 1L shl tile.z
    val canonicalOutputX = tile.x.toLong().floorMod(outputDimension)
    val sourceZ = minOf(tile.z, maxZoom)
    val zoomDelta = tile.z - sourceZ
    val childScale = 1 shl zoomDelta
    val sourceX = (canonicalOutputX / childScale).toInt()
    val sourceY = tile.y / childScale
    return RasterSample(
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

internal fun RasterSample.tileUrl(): String {
    val dimension = 1L shl sourceZ
    val templateIndex = ((sourceZ.toLong() * 31L + sourceX * 17L + sourceY).floorMod(source.tileTemplates.size.toLong())).toInt()
    val template = source.tileTemplates[templateIndex].resolve()
    val requestY = when (source.scheme) {
        com.rohittp.rentile.internal.style.TileScheme.XYZ -> sourceY
        com.rohittp.rentile.internal.style.TileScheme.TMS -> (dimension - 1L - sourceY).toInt()
    }
    return template
        .replace("{z}", sourceZ.toString())
        .replace("{x}", sourceX.toString())
        .replace("{y}", requestY.toString())
        .replace("{-y}", (dimension - 1L - sourceY).toString())
}

internal fun RasterSample.neighbor(deltaX: Int, deltaY: Int): RasterSample? {
    val dimension = 1L shl sourceZ
    val neighborY = sourceY.toLong() + deltaY
    if (neighborY !in 0 until dimension) return null
    return copy(
        sourceX = (sourceX.toLong() + deltaX).floorMod(dimension).toInt(),
        sourceY = neighborY.toInt(),
    )
}

internal fun RasterSample.immediateChildren(): List<RasterSample> {
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

internal fun RasterSample.ancestor(distance: Int): RasterSample? {
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

private fun Long.floorMod(divisor: Long): Long {
    val remainder = this % divisor
    return if (remainder < 0) remainder + divisor else remainder
}
