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

## What coverage is

Coverage is geometric. The selected `fill` layers (flattened `fill-extrusion` footprints included,
since they are fills in the Output Tile) and `line` layers active at the tile's zoom are drawn a
second time, in style order, through the same `drawFill`/`drawLine` code as the colour pass, onto an
8-bit alpha surface with opaque ink and the same `outputSizePx / 512` canvas transform. Filters,
zoom ranges, `visibility`, feature geometry, overzoom, `fill-antialias`, `fill-translate`, the
`fill-outline-color` stroke, `line-width`, `line-gap-width`, `line-offset`, `line-translate`, dashes,
caps, joins, round-join replacement and `line-blur` therefore apply exactly as they do to the colour
tile, and the mask aligns with its pixels. Colour, opacity and pattern imagery do not weaken
coverage: a translucent water fill still covers its pixels fully. A feature whose pattern sprite is
missing is skipped in both passes. Background, raster, hillshade, color-relief and icon layers never
contribute, and a non-string metadata value never matches.

The mask's values come from Skia's 8-bit alpha blitter. Drawing the same layer opaque white into an
RGBA tile gives an alpha channel within one rounding step per draw of the mask (two for an outlined
fill, which draws twice); the geometry is identical. `MaterialCoverageTest` pins this at every
supported size for fills and for plain and fully styled lines.

## Cost and identity

A request is more output, so it joins both Output Tile keys: `outputRequestKey` and the prepared
batch content keys append `material-coverage:` and the pair as a canonical JSON array. Two requests
for different pairs have different keys, and no choice of strings can collide with another pair.
Without a request nothing is appended, no second surface exists, and the PNG, raw pixels, keys and
work are byte-for-byte those of 0.19.0. The coverage surface adds one byte per pixel, which the
existing output-surface safety ceiling now counts when a request is present. Coverage never changes
which resources are acquired: only layers already drawn into the tile are selected.

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
- **Coverage weighted by paint opacity.** Material is what a surface is, not how translucently a
  style paints it; a style can hide water by opacity and still describe it.
