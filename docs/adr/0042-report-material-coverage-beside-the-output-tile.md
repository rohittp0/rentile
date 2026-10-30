# Report Material Coverage beside the Output Tile

A consumer can ask, per render, for the Material Coverage of the layers a style tags through its
layer `metadata`. `RenderOptions.materialCoverage: MaterialCoverageRequest? = null` names one
`metadataKey` and one `metadataValue`; a layer is selected when its `metadata` object maps that key
to that JSON string. Rentile gives neither string a meaning. Travel Animator asks for
`ta:material` = `water` and writes the result into the alpha of its own tile texture, but nothing in
Rentile knows about water.

Every `RenderedTile` and `RawRenderedTile` gains an appended `materialCoverage: MaterialCoverage?`.
It is null when nothing was requested. With a request it is `MaterialCoverage.None` when no pixel is
covered, including when no layer matched, and otherwise `MaterialCoverage.Mask`: one byte per output
pixel, rows top-down, `outputSizePx` square, 0 uncovered and 255 fully covered.

## What coverage is: the material as it is visible

Coverage describes the selected material as it shows in the finished tile, not every feature that
carries the tag. Drawing starts at the first selected layer and walks the tile's active layers in
style order onto an 8-bit alpha surface, through the same `drawFill`/`drawLine` code as the colour
pass and with the same `outputSizePx / 512` canvas transform:

- A selected `fill` layer (flattened `fill-extrusion` footprints included, since they are fills in
  the Output Tile) or `line` layer adds its geometry with opaque ink. Colour, opacity and pattern
  imagery do not weaken it: a translucent water fill still covers its pixels fully.
- A later unselected `fill` or `line` layer - bridges, roads, piers, land patches over water - draws
  its own paint (colour, opacity, pattern, outline) with `DST_OUT`, so it removes coverage in
  proportion to the alpha it draws into the colour tile: an opaque one hides the material beneath it
  entirely, a 25%-opaque one by a quarter. A later selected layer adds coverage back. The
  round-join replacement pass, which builds a line in an offscreen layer, composites that layer with
  the line's blend mode so an erasing line erases there too.
- Layers before the first selected layer cannot hide anything drawn after them. Background, raster,
  hillshade, color-relief and icon layers never add or erase. Background layers never contribute,
  so a style must describe its material with explicit fills: a water-coloured background is not
  water.

Filters, zoom ranges, `visibility`, feature geometry, overzoom, `fill-antialias`, `fill-translate`,
the `fill-outline-color` stroke, `line-width`, `line-gap-width`, `line-offset`, `line-translate`,
dashes, caps, joins and `line-blur` therefore apply to both adding and erasing exactly as they do to
the colour tile, and the mask aligns with its pixels. A feature whose pattern sprite is missing is
skipped in every pass, and a non-string metadata value never matches.

The mask's values come from Skia's 8-bit alpha blitter. Drawing the same layer opaque white into an
RGBA tile gives an alpha channel within one rounding step per draw of the mask (two for an outlined
fill, which draws twice); the geometry is identical, for erasing as for adding.
`MaterialCoverageTest` pins this at every supported size and for an overzoomed tile;
`MaterialCoverageOcclusionTest` pins erasing by opaque and translucent fills and lines, re-adding,
and the layers that never erase.

## Absent data

A vector tile served empty (`204`) or outside its source's bounds or zoom range has no features, so
it adds and erases nothing. A tile that cannot be acquired fails the render with the same typed
`ResourceAcquisitionException` as without a request (ADR 0011); Rentile never reports "no material"
for data it could not read, because that answer would be cached under a valid key.

## Cost and identity

A request is more output, so it joins both Output Tile keys: `outputRequestKey` and the prepared
batch content keys append `material-coverage:` and the pair as a canonical JSON array. Two requests
for different pairs have different keys, and no choice of strings can collide with another pair.
Without a request nothing is appended, no second surface exists, and the PNG, raw pixels, keys and
work are byte-for-byte those of 0.19.0. The coverage surface adds one byte per pixel, which the
existing output-surface safety ceiling now counts when a request is present. The extra draw work is
one more pass over the fill and line layers from the first selected one onward; a tile with no
active selected layer does none. Coverage never changes which resources are acquired: only layers
already drawn into the tile take part.

Raster pass-through still applies: a tile whose only active layer is an unmodified raster has no
fill or line to cover and reports `None` alongside its passed-through PNG.

## Rejected

- **A separate `renderMaterialCoverage` call.** The mask must match the colour tile exactly, and a
  second call would redraw, re-decode and re-key the same tile, and leave the consumer to keep two
  caches in step. One render producing both, under one key, is cheaper and cannot drift.
- **Writing coverage into the Output Tile's alpha.** That breaks ADR 0010's straight-alpha canonical
  output for every consumer and is not generic. The consumer composes the two itself.
- **Encoding the mask as PNG.** Both render paths return the same raw bytes; a consumer that caches
  PNGs encodes its own composite. Encoding here would cost every caller an encode it may not want.
- **A list of requests per render.** One pair is what the only consumer needs. A list can be added
  later as a new option without changing what a single request means.
- **Coverage weighted by the selected layer's own paint opacity.** Material is what a surface is,
  not how translucently a style paints it; a style can tint water lightly and still describe it.
  Later unselected layers are different: they really hide the surface, so their drawn alpha erases.
- **Tag-only coverage that ignores later layers.** A consumer shading water would then put glints
  on bridges and roads drawn over it. Erasing through the colour pass's own draws is the only rule
  that keeps the mask consistent with the pixels it sits beside.
