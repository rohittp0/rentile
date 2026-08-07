package com.rohittp.rentile.smoke

import com.rohittp.rentile.RenderOptions
import com.rohittp.rentile.TileId

fun proveAggregateDependency(): Pair<TileId, RenderOptions> =
    TileId(z = 0, x = 0, y = 0) to RenderOptions()
