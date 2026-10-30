# Draw color-relief layers from DEM elevations

Rentile draws the MapLibre style specification's `color-relief` layer into the Output Tile. Before
0.20.0 a visible `color-relief` layer failed preparation as an unimplemented layer type, so no style
that prepared before renders differently, and no output key or renderer marker moves.

## Supported construct

- A `raster-dem` source, decoded and sampled through the same path as hillshade: the centre DEM tile
  and its eight neighbours, the same Mapbox/Terrarium decoding, the same shared per-batch decodes.
  The decode is now one `DemNeighbourhood` both layers read; hillshade output is unchanged (an A/B
  run over Mapbox and Terrarium DEMs, z0 and overzoomed neighbourhood tiles and 256/512/2048 px
  gave identical raw pixels and content keys before and after the refactor).
- `color-relief-opacity`: a number in 0..1, zoom-dependent expressions allowed; it scales alpha.
- `resampling`: `linear` (the default) bilinearly interpolates elevation across DEM texels, reaching
  into neighbour tiles at edges; `nearest` takes the nearest texel.
- `color-relief-color`: an `interpolate`, `interpolate-hcl` or `interpolate-lab` expression whose
  input is `["elevation"]`, with any `linear`, `exponential` or `cubic-bezier` interpolation and
  strictly ascending finite numeric stops. Each stop output is a colour (constant or expression).
- `visibility`, `minzoom`, `maxzoom` and `metadata`; a hidden or out-of-range layer acquires no DEM.

## Semantics, following MapLibre GL JS

MapLibre GL JS 5.x builds its colour ramp from exactly this form: it evaluates the expression at
each stop's elevation and uploads those colours as a 1-pixel texture, then the fragment shader finds
the two stops around a pixel's elevation by binary search and samples the texture linearly between
them, clamping beyond the ends. Rentile reproduces that: stop colours only, linear in straight RGBA
between stops whatever the authored interpolation curve or colour space, the first or last colour
beyond the ends, and a single stop as one constant colour. Alpha is then multiplied by
`color-relief-opacity` and the result composited source-over.

GL JS emits the shader's straight colour scaled by opacity into a premultiplied blend, which
brightens translucent stops. Rentile composites conventionally instead; the two agree for opaque
stops at full opacity.

Hillshade shades the 512 style-pixel grid and lets the canvas magnify it. Color relief computes one
sample per *output* pixel and draws it 1:1, so 1024 and 2048 px tiles get a smooth ramp rather than
magnified blocks, consistent with `outputSizePx` being a pixel ratio (ADR 0030).

## Deliberate narrowing

GL JS silently draws nothing for any other `color-relief-color` form, an absent one included. Rentile
fails those as `UNSUPPORTED_RETAINED_CONSTRUCT` instead, as ADR 0026 does for author-intended layers:
an author who wrote a `step` or a constant colour expects a relief, not an empty layer. Unknown
paint or layout properties and a non-`raster-dem` source fail the same way.

A DEM tile outside its source's bounds or zoom range is never fetched and the relief draws nothing
there. A DEM tile that fails to load, `204` no-content included, fails the render with a typed
`ResourceAcquisitionException`, exactly as hillshade always has. The relief's straight RGBA buffer
and the copy Skia makes of it (eight bytes per output pixel) count against the output-surface safety
ceiling when a tile has an active color-relief layer.

`RenderOptions.includeHillshade = false` omits hillshade layers only. A color-relief layer is
retained and still requires its DEM; `includeHillshade` keeps its literal meaning.
