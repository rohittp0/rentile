package com.rohittp.rentile.internal

import com.rohittp.rentile.ResourceAcquisitionException
import com.rohittp.rentile.ResourceClass
import com.rohittp.rentile.TileId

/** A missing DEM is resolved absence, never an empty encoded image or a disk-cache entry. */
internal fun requireDemContent(
    statusCode: Int,
    byteCount: Int,
    resourceClass: ResourceClass,
    sanitizedResourceId: String,
    outputTile: TileId,
) {
    if (statusCode == 204 && byteCount == 0 && resourceClass == ResourceClass.DEM_TILE) {
        throw ResourceAcquisitionException(
            message = "DEM source returned no content",
            resourceClass = resourceClass,
            sanitizedResourceId = sanitizedResourceId,
            statusCode = statusCode,
            affectedTiles = listOf(outputTile),
        )
    }
}
