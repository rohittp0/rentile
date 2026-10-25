# Retain output-zoom raster acquisition explicitly

Full-density raster acquisition from ADR 0038 can multiply the source tiles, decode work and
memory an interactive consumer needs. Travel Animator measured an unacceptable Preview cost
when increasing raster detail, while its offline export should retain native source detail.
Acquisition quality must be an explicit caller choice rather than inferred from a style name,
output dimension or runtime platform.

`RenderOptions.rasterAcquisitionDensity` is `NATIVE` by default: 0.16.0 behavior remains unchanged.
`OUTPUT_ZOOM` instead uses the exact pre-0.16.0 sampling function: skip an output below source
minzoom or outside source bounds, choose `min(outputTile.z, source.maxZoom)`, canonicalize X and
crop the covering ancestor when overzoomed. The source's declared tile size and requested output
pixel size do not shift source zoom in this mode. Layer activation remains evaluated at output
zoom. In `OUTPUT_ZOOM`, a raster source below its minzoom is excluded from the active draw layers
before paint evaluation and PNG pass-through counting, as in 0.15.1. `NATIVE` retains 0.16.0
activation because its adjusted source zoom can reach minzoom below the numeric output zoom. Vector, hillshade, DEM, style preparation and authored URL templates are unchanged.

Both options-aware raw warming and acquisition use this choice. A prepared batch freezes the
whole `RenderOptions`, so exact-substitution recovery retains its original acquisition policy.
Raw source bytes keep URL identities and can be shared between modes. Output request and content
keys add `raster-acquisition:output-zoom` only for `OUTPUT_ZOOM`; default `NATIVE` keys stay byte-for-byte
identical to 0.16.0. Even when both modes acquire identical source coordinates their output keys
remain separate, so a consumer cannot accidentally mix the policies in its rendered tile cache.

The compatibility mode uses the existing compositor and strict PNG pass-through validation:
one identity raster layer, one uncropped source sample and encoded PNG dimensions matching the
requested output size. The old 0.15.1 implementation already checked those dimensions. A mismatched
payload is rendered/resampled into the requested output dimensions; it is never forwarded as a
wrong-sized output. Ancestor crops are rendered as before. Raw RGBA output remains a composited
output plane rather than an encoded-source pass-through.

The public option is an API addition requiring a minor release and consumer recompilation;
`RenderOptions` constructor/copy ABI changes. Existing safety budgets still apply and neither
policy raises them. Unit tests cover minzoom, bounds/wrapping, maxzoom crops, source/output size
mismatches, identical PNG forwarding, warming/cache-only closure, output keys and exact recovery.
The rolling-corpus compatibility comparison must compare the selected source coordinates and
PNG bytes against 0.15.1 using the same raw resource store, not compare image similarity alone.

Rejected: changing `outputSizePx`, rewriting a source's declared tile size, or passing shifted
output XYZ coordinates to emulate legacy behavior. Those alter output dimensions, cartography,
source availability or byte forwarding and fail to preserve the old consumer contract.
