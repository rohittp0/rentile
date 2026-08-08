package com.rohittp.rentile.internal.mvt

import com.rohittp.rentile.DiagnosticCode
import com.rohittp.rentile.DiagnosticSeverity
import com.rohittp.rentile.PipelineStage
import com.rohittp.rentile.RenderDiagnostic
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.ResourceSubstitution
import com.rohittp.rentile.TileId
import com.rohittp.rentile.TileSubstitutionStrategy
import com.rohittp.rentile.internal.sha256Hex
import kotlin.math.roundToInt

internal fun composeVectorChildren(
    requested: VectorTileSample,
    children: List<VectorResource>,
): VectorResource {
    require(children.size == 4)
    val layerNames = children.flatMap { it.tile.layers }.map { it.name }.distinct()
    val mergedLayers = layerNames.map { layerName ->
        val sourceLayers = children.mapIndexedNotNull { childIndex, child ->
            child.tile.layers.singleOrNull { it.name == layerName }?.let { childIndex to it }
        }
        val targetExtent = sourceLayers.first().second.extent
        DecodedVectorLayer(
            name = layerName,
            extent = targetExtent,
            features = sourceLayers.flatMap { (childIndex, layer) ->
                layer.features.map { feature ->
                    feature.copy(
                        geometry = feature.geometry.intoParent(
                            childX = childIndex % 2,
                            childY = childIndex / 2,
                            sourceExtent = layer.extent,
                            targetExtent = targetExtent,
                        ),
                    )
                }
            },
        )
    }
    val provenance = ResourceSubstitution(
        resourceClass = ResourceClass.VECTOR_TILE,
        sanitizedSourceId = requested.source.idDigest,
        strategy = TileSubstitutionStrategy.IMMEDIATE_CHILDREN,
        sourceTiles = children.map { it.sample.sourceTileId() },
    )
    val diagnostic = substitutionDiagnostic(requested.outputTile, provenance)
    return VectorResource(
        sample = requested,
        tile = DecodedVectorTile(mergedLayers),
        contentDigest = buildString {
            append("vector-children\n")
            append(requested.identity)
            children.forEach { append('\n').append(it.contentDigest) }
        }.sha256Hex(),
        diagnostics = children.flatMap { it.diagnostics } + diagnostic,
        exactSample = requested,
        substitution = provenance,
    )
}

internal fun vectorAncestorSubstitute(
    requested: VectorTileSample,
    ancestor: VectorResource,
    distance: Int,
): VectorResource {
    val provenance = ResourceSubstitution(
        resourceClass = ResourceClass.VECTOR_TILE,
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

private fun DecodedVectorGeometry.intoParent(
    childX: Int,
    childY: Int,
    sourceExtent: Int,
    targetExtent: Int,
): DecodedVectorGeometry = when (this) {
    is DecodedVectorGeometry.Points -> copy(
        points = points.map { it.intoParent(childX, childY, sourceExtent, targetExtent) },
    )
    is DecodedVectorGeometry.Lines -> copy(
        lines = lines.map { line -> line.map { it.intoParent(childX, childY, sourceExtent, targetExtent) } },
    )
    is DecodedVectorGeometry.Polygons -> copy(
        rings = rings.map { ring ->
            val points = ring.points.map { it.intoParent(childX, childY, sourceExtent, targetExtent) }
            VectorRing(points, signedAreaTwice(points))
        },
    )
}

private fun VectorCoordinate.intoParent(
    childX: Int,
    childY: Int,
    sourceExtent: Int,
    targetExtent: Int,
): VectorCoordinate = VectorCoordinate(
    x = ((childX * targetExtent + x.toDouble() * targetExtent / sourceExtent) / 2.0).roundToInt(),
    y = ((childY * targetExtent + y.toDouble() * targetExtent / sourceExtent) / 2.0).roundToInt(),
)

private fun signedAreaTwice(points: List<VectorCoordinate>): Double = points.indices.sumOf { index ->
    val current = points[index]
    val next = points[(index + 1) % points.size]
    current.x.toDouble() * next.y - next.x.toDouble() * current.y
}

private fun VectorTileSample.sourceTileId(): TileId = TileId(sourceZ, sourceX, sourceY)

private fun substitutionDiagnostic(
    tile: TileId,
    provenance: ResourceSubstitution,
): RenderDiagnostic = RenderDiagnostic(
    code = DiagnosticCode.TILE_RESOURCE_SUBSTITUTED,
    severity = DiagnosticSeverity.WARNING,
    stage = PipelineStage.RESOURCE_ACQUISITION,
    message = "An unavailable vector tile was substituted",
    details = mapOf(
        "strategy" to provenance.strategy.name,
        "ancestorZoomDistance" to (provenance.ancestorZoomDistance?.toString() ?: "0"),
    ),
    affectedTiles = listOf(tile),
)
