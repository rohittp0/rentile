# Bound native raster source zoom explicitly

Native imagery density remains source/output-size aware by default. A caller may append
`RenderOptions.maximumRasterSourceZoomDelta: Int? = null` to bound that density-derived source
zoom. Null preserves 0.17.0 acquisition and cache identities. Non-null values are in 0..30 and are
applied only to `RasterAcquisitionDensity.NATIVE`:

```
sourceZ = min(max(0, outputZ + log2(outputSize / declaredTileSize)),
              source.maxzoom, outputZ + maximumRasterSourceZoomDelta)
```

Check source minzoom after this clamp. If the capped source zoom is below minzoom, imagery is
unavailable; neither fetch an invalid lower source level nor raise the level beyond the cap.
Source bounds, authored URLs, style/layer zoom evaluation, source declarations, output size,
vector, hillshade and DEM acquisition remain unchanged. This is not a new automatic device policy.
`OUTPUT_ZOOM` ignores the option, including output request and content keys, preserving its exact
legacy contract. Existing substitution and exact-recovery policies are unchanged; this cap bounds
density-derived acquisition, rather than redefining a missing source tile's fallback policy.

Travel Animator's Terrain source declares 64px while serving 256px JPEGs. A 512px output formerly
requested 64 children three levels finer. With delta 1 it requests four children one level finer,
whose actual 512px aggregate width matches that output. A 512px Satellite source at 512px output
still requests its native output level; the option does not force a zoom increase. At source
maxzoom, existing ancestor cropping remains in effect. Choosing a lower density is explicit
caller policy, not an implicit correction of a source declaration.

`prepareBatch` and options-aware raw warming share the same planner. A batch freezes these
options through rendering and exact recovery. Raw cache keys remain source-URL identities.
Explicit native caps add `raster-source-zoom-delta:<value>` to both output request and content
identities, including when the selected source coordinates happen to match uncapped acquisition.
This prevents a caller-owned output cache from hiding the new policy. Null native options and
all `OUTPUT_ZOOM` keys remain byte-identical to 0.17.0.

The appended public data-class field changes constructor/copy ABI and requires a minor release
and consumer recompilation. Tests cover declared/payload mismatch placement, minzoom before/after
clamping, maxzoom cropping, warming/cache-only closure, native cache separation, unchanged legacy
keys/pixels and validation. Existing unmodified default-density corpus and platform gates remain
required; the only catalogue exclusion is the separately documented style 71 CDN HTML failure.

Rejected: rewriting the declared tile size, lowering output dimensions or shifting output XYZ.
Those alter source/style semantics or the output product rather than expressing this acquisition
policy directly. No resource or memory budget is raised by this option.
