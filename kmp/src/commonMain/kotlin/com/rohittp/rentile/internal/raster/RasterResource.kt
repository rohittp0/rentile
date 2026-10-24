package com.rohittp.rentile.internal.raster

import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.SafetyLimitException
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
    /** The destination subrectangle within the output tile; independent of source overzoom. */
    val outputScale: Int = 1,
    val outputX: Int = 0,
    val outputY: Int = 0,
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
    /**
     * Canonical RGBA8, unpremultiplied, top-down and tightly packed at `width * 4` bytes per row -
     * the pixels the validating decode already produced, kept only for callers that asked for
     * them. Null everywhere else, so an ordinary raster acquisition holds no decoded copy.
     */
    val rgba: ByteArray? = null,
)

internal fun CompiledRasterSource.sampleFor(tile: TileId): RasterSample? {
    if (tile.z < minZoom) return null
    if (bounds?.intersects(tile) == false) return null
    return sampleAtZoom(tile, minOf(tile.z, maxZoom))
}

private fun CompiledRasterSource.sampleAtZoom(tile: TileId, sourceZ: Int): RasterSample {
    val outputDimension = 1L shl tile.z
    val canonicalOutputX = tile.x.toLong().floorMod(outputDimension)
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

/** Maximum supported output/source size ratio is2048/64, or32 tiles on either axis. */
internal const val MAX_RASTER_SAMPLES_PER_OUTPUT_TILE = 1024

/**
 * Imagery source density follows declared tileSize, not the returned image dimensions. DEM and
 * hillshade continue using sampleFor: adding imagery detail must not move their elevation grid.
 */
internal fun CompiledRasterSource.imagerySamplesFor(
    tile: TileId,
    outputSizePx: Int,
    maximumSamples: Int = MAX_RASTER_SAMPLES_PER_OUTPUT_TILE,
): List<RasterSample> {
    if (bounds?.intersects(tile) == false) return emptyList()
    // All admitted source/output sizes are powers of two; integer arithmetic avoids rounding a
    // zoom boundary. Source z may exceed the public output profile's z22, but never XYZ z30.
    val zoomOffset = outputSizePx.countTrailingZeroBits() - tileSize.countTrailingZeroBits()
    val targetZoom = maxOf(0, tile.z + zoomOffset)
    if (targetZoom < minZoom) return emptyList()
    val sourceZ = minOf(targetZoom, maxZoom)
    if (sourceZ <= tile.z) return listOf(sampleAtZoom(tile, sourceZ))
    val scale = 1L shl (sourceZ - tile.z)
    val sampleCount = scale * scale
    val limit = minOf(maximumSamples, MAX_RASTER_SAMPLES_PER_OUTPUT_TILE)
    if (sampleCount > limit) throw SafetyLimitException(
        message = "Raster imagery source closure exceeds the sample limit",
        limitName = "maxRasterSourceSamples",
        limit = limit.toLong(),
        observed = sampleCount,
        stage = PipelineStage.RESOURCE_ACQUISITION,
        affectedTiles = listOf(tile),
    )
    val canonicalX = tile.x.toLong().floorMod(1L shl tile.z)
    return (0 until scale.toInt()).flatMap { y ->
        (0 until scale.toInt()).mapNotNull { x ->
            val sourceX = (canonicalX * scale + x).toInt()
            val sourceY = (tile.y.toLong() * scale + y).toInt()
            if (bounds?.intersects(TileId(sourceZ, sourceX, sourceY)) == false) return@mapNotNull null
            RasterSample(
                source = this, outputTile = tile, sourceZ = sourceZ,
                sourceX = sourceX, sourceY = sourceY,
                childScale = 1, childX = 0, childY = 0,
                outputScale = scale.toInt(), outputX = x, outputY = y,
            )
        }
    }
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
