package com.rohittp.rentile.internal.raster

import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileSubstitutionStrategy
import com.rohittp.rentile.internal.sha256Hex
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

internal fun composeRasterChildren(
    requested: RasterSample,
    children: List<RasterResource>,
): RasterResource {
    require(children.size == 4)
    val width = children.first().width
    val height = children.first().height
    require(children.all { it.width == width && it.height == height })
    val surface = Surface.makeRasterN32Premul(width, height)
    try {
        children.forEachIndexed { index, child ->
            val image = Image.makeFromEncoded(child.bytes)
            try {
                val left = (index % 2) * width / 2f
                val top = (index / 2) * height / 2f
                surface.canvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeXYWH(left, top, width / 2f, height / 2f),
                    rasterSubstitutionSampling(requested.source.resourceClass),
                    null,
                    true,
                )
            } finally {
                image.close()
            }
        }
        val image = surface.makeImageSnapshot()
        try {
            val data = requireNotNull(image.encodeToData(EncodedImageFormat.PNG))
            try {
                val bytes = data.bytes
                val provenance = childProvenance(requested, children)
                val diagnostic = substitutionDiagnostic(requested.outputTile, provenance)
                return RasterResource(
                    sample = requested,
                    bytes = bytes,
                    contentDigest = buildString {
                        append("raster-children\n")
                        append(requested.identity)
                        children.forEach { append('\n').append(it.contentDigest) }
                    }.sha256Hex(),
                    width = width,
                    height = height,
                    diagnostics = children.flatMap { it.diagnostics } + diagnostic,
                    exactSample = requested,
                    substitution = provenance,
                )
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
    } finally {
        surface.close()
    }
}

internal fun rasterSubstitutionSampling(resourceClass: ResourceClass): SamplingMode =
    if (resourceClass == ResourceClass.DEM_TILE) SamplingMode.DEFAULT else SamplingMode.LINEAR

internal fun rasterAncestorSubstitute(
    requested: RasterSample,
    ancestor: RasterResource,
    distance: Int,
): RasterResource {
    val provenance = ResourceSubstitution(
        resourceClass = requested.source.resourceClass,
        sanitizedSourceId = requested.source.idDigest,
        strategy = TileSubstitutionStrategy.ANCESTOR,
        sourceTiles = listOf(ancestor.sample.sourceTileId()),
        ancestorZoomDistance = distance,
    )
    return ancestor.copy(
        exactSample = requested,
        substitution = provenance,
        diagnostics = ancestor.diagnostics + substitutionDiagnostic(requested.outputTile, provenance),
    )
}

private fun childProvenance(
    requested: RasterSample,
    children: List<RasterResource>,
): ResourceSubstitution = ResourceSubstitution(
    resourceClass = requested.source.resourceClass,
    sanitizedSourceId = requested.source.idDigest,
    strategy = TileSubstitutionStrategy.IMMEDIATE_CHILDREN,
    sourceTiles = children.map { it.sample.sourceTileId() },
)

private fun RasterSample.sourceTileId(): TileId = TileId(sourceZ, sourceX, sourceY)

private fun substitutionDiagnostic(
    tile: TileId,
    provenance: ResourceSubstitution,
): RenderDiagnostic = RenderDiagnostic(
    code = DiagnosticCode.TILE_RESOURCE_SUBSTITUTED,
    severity = DiagnosticSeverity.WARNING,
    stage = PipelineStage.RESOURCE_ACQUISITION,
    message = "An unavailable tile resource was substituted",
    details = mapOf(
        "resourceClass" to provenance.resourceClass.name,
        "strategy" to provenance.strategy.name,
        "ancestorZoomDistance" to (provenance.ancestorZoomDistance?.toString() ?: "0"),
    ),
    affectedTiles = listOf(tile),
)
