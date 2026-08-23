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
import com.rohittp.rentile.TileId

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
