package com.rohittp.rentile.smoke

import com.rohittp.rentile.IconTextFit
import com.rohittp.rentile.LabelCandidate
import com.rohittp.rentile.LabelIconAnchor
import com.rohittp.rentile.LabelLayerStyle
import com.rohittp.rentile.LabelPlacement
import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.SymbolAlignment
import com.rohittp.rentile.SymbolOverlap
import com.rohittp.rentile.SymbolZOrder
import com.rohittp.rentile.TerrainDemEncoding
import com.rohittp.rentile.TileId
import com.rohittp.rentile.ValidatedDemTile

fun proveAggregateDependency(): Pair<TileId, RenderOptions> =
    TileId(z = 0, x = 0, y = 0) to RenderOptions()

/** Compile-time proof that the published aggregate exposes the complete 0.6 label contract. */
fun proveExpandedLabelApi(candidate: LabelCandidate, style: LabelLayerStyle): Boolean {
    val placementIsKnown = when (candidate.placement) {
        LabelPlacement.POINT -> candidate.line.isEmpty()
        LabelPlacement.LINE, LabelPlacement.LINE_CENTER -> candidate.line.isNotEmpty()
    }
    val overlapIsKnown = when (candidate.overlap) {
        SymbolOverlap.NEVER, SymbolOverlap.ALWAYS, SymbolOverlap.COOPERATIVE -> true
    }
    val orderIsKnown = when (candidate.zOrder) {
        SymbolZOrder.AUTO, SymbolZOrder.SOURCE, SymbolZOrder.VIEWPORT_Y -> true
    }
    val iconIsKnown = candidate.icon?.let { icon ->
        icon.translateAlignment in SymbolAlignment.entries &&
            icon.anchor in LabelIconAnchor.entries &&
            icon.rotationAlignment in SymbolAlignment.entries &&
            icon.pitchAlignment in SymbolAlignment.entries &&
            icon.textFit in IconTextFit.entries &&
            icon.textFitPadding.size == 4
    } ?: true
    return placementIsKnown && overlapIsKnown && orderIsKnown && iconIsKnown &&
        candidate.rotationAlignment in SymbolAlignment.entries &&
        candidate.pitchAlignment in SymbolAlignment.entries &&
        candidate.maxAngleDegrees >= 0.0 && candidate.color != candidate.haloColor && style.priority >= 0
}

/**
 * Compile-time proof that the published aggregate lets a consumer read elevation out of a DEM tile
 * with no image decoder of its own, which is the whole of the 0.7 terrain contract.
 *
 * This is also the worked example: index the texel, then apply the tile's own encoding. The
 * channels are packed values, never metres, and the encoded [ValidatedDemTile.bytes] remains what a
 * caller hashes for cache identity.
 */
fun proveDecodedDemTexelApi(tile: ValidatedDemTile, x: Int, y: Int): Double {
    val texels = tile.texels
    require(x in 0 until texels.width && y in 0 until texels.height)
    require(texels.rgba.size == texels.width * texels.height * 4)
    require(tile.bytes.isNotEmpty())

    // Rows run top-down and are tightly packed at width * 4 bytes, with no padding.
    val offset = (y * texels.width + x) * 4
    val red = texels.rgba[offset].toInt() and 0xff
    val green = texels.rgba[offset + 1].toInt() and 0xff
    val blue = texels.rgba[offset + 2].toInt() and 0xff

    // Unpremultiplied, so these three channels are usable as-is however opaque the alpha is.
    return when (tile.encoding) {
        TerrainDemEncoding.MAPBOX -> -10_000.0 + (red * 65_536 + green * 256 + blue) * 0.1
        TerrainDemEncoding.TERRARIUM -> red * 256.0 + green + blue / 256.0 - 32_768.0
    }
}
