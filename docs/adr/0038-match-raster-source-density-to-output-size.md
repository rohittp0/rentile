# Match raster source density to the output size

Raster imagery acquisition uses `sourceZoom = outputTile.z + log2(outputSizePx / source.tileSize)`,
bounded by the source's maximum zoom and hidden below its minimum source zoom. Source TileJSON limits and explicit style
limits both apply. The declared tile size governs this calculation even when a provider returns
different pixel dimensions; changing acquisition after reading a payload would make warming,
cache-only operation and the frozen resource closure disagree. A declared 64 source returning 256
pixels therefore requests the same finer source zoom a Mapbox raster source declares, rather than
silently treating its tile size as 256.

Above the output tile's zoom, acquisition enumerates its exact child grid and draws each source
directly into its own output subrectangle. Children outside source bounds are omitted and leave
that destination cell transparent; the remaining cells keep their original indices. It allocates no full native mosaic. At/below the output
zoom, the existing ancestor window mapping crops the covering source tile. At source maxzoom the
renderer can only magnify those available pixels; it never fabricates more native imagery.
Style/layer evaluation remains at the output tile's zoom. Vector cartography, hillshade and DEM
sampling retain their existing semantics; imagery density must not move the elevation grid.

This supersedes ADR 0030's raster magnification-only clause. That optimization selected one source
tile at the output tile's own XYZ irrespective of source/output pixel size. For a 256px source
rendered into 2048 pixels it spread one source texel across eight output pixels even when finer
native imagery existed. Vectors could gain resolution from a larger output surface; raster
imagery could not.

`prepareBatch` already receives output options. An additive `warmRawResources` overload accepts
the same options, and the original signature uses default options. Default interface behavior
preserves custom implementations. `hasRasterImagery` reports retained visible raster imagery
layers, excluding unused sources, hidden layers, hillshade and DEM, so hosts can choose imagery
LOD without guessing from map names. Zoom-gated raster layers count because they can be active
elsewhere in a session. No automatic retina URL rewriting is introduced: authored templates stay
the resource identity.

Planning rejects more than 1024 samples per source/output tile or 4096 imagery samples per batch
before enumerating the excess closure. It charges unique declared source planes plus the output surface
against the existing minimum of decoded-resource and resident-decoded budgets. Raw-only warming
skips decoded/output-plane accounting because it decodes nothing, while keeping the sample cap. Acquisition also
bounds retained encoded imagery with that budget and cancels outstanding work on overflow.
Before publishing batch state, actual payload dimensions, possible retained shared imagery,
concurrent worker output surfaces and native planes are checked against the same working budget.
For example,64 declared pixels with 256px payloads need 64 inputs at 512 output: 16 MiB native planes
plus the output. At 2048 output those payloads need 256 MiB native planes and are rejected by the
default 128 MiB resident budget. The response is a typed safety failure, never silent coarsening or
an unmeasured budget increase.

Exact raw keys remain source-URL keys. Substitution preserves both the requested source window
and the destination mosaic cell; retryExact replaces only substituted cells. Output request and
content semantics advance so a caller-owned cache cannot reuse the former magnified pixels.

The official Mapbox GL JS source uses raster source tile size in covering zoom selection and
selects high-DPI raster URLs independently: [transform](https://github.com/mapbox/mapbox-gl-js/blob/main/src/geo/transform.ts),
[raster source](https://github.com/mapbox/mapbox-gl-js/blob/main/src/source/raster_tile_source.ts),
[URL normalization](https://github.com/mapbox/mapbox-gl-js/blob/main/src/util/mapbox.ts).
