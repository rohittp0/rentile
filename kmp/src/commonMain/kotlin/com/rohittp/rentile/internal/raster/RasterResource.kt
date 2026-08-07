package com.rohittp.rentile.internal.raster

import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.TileId
import com.rohittp.rentile.internal.style.CompiledRasterSource

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
)

internal fun CompiledRasterSource.sampleFor(tile: TileId): RasterSample? {
    if (tile.z < minZoom) return null
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
        com.rohittp.rentile.internal.style.RasterScheme.XYZ -> sourceY
        com.rohittp.rentile.internal.style.RasterScheme.TMS -> (dimension - 1L - sourceY).toInt()
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
