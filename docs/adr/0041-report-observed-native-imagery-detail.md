# Report observed native imagery detail without choosing output policy

`BasemapRasterizer.rasterImageryDetailCeiling(style, options)` reports whether retained raster
imagery has any further native source detail at a finer Output Tile zoom. It performs no I/O,
acquisition, rendering or cache-key mutation. A style with no retained imagery returns null. A
style with imagery returns `RasterImageryDetailCeiling`; its `maximumOutputZoom` stays null until
every retained imagery source has a validated payload dimension. Ordinary `prepareBatch`
acquisition establishes that fact, including validating raw-store hits. Raw warming deliberately
does not decode, so it cannot establish it. A caller may prepare an actual visible output tile
before planning finer output coverage; Rentile does not introduce a probe or a new fetch policy.

For source maximum zoom M, output size S, declared tile size T, and largest validated payload
dimension D, the last necessary integer output zoom is:

```
declaredDelta = log2(S / T)
effectiveDelta = OUTPUT_ZOOM ? 0 : min(declaredDelta, optionalNativeSourceZoomCap)
sourceCeiling = max(M - effectiveDelta, M + ceil(log2(D / S)))
```

An absent native cap leaves declaredDelta unchanged, including negative deltas. The first term
ensures the source's actual maximum level is reachable under the acquisition options. The second
preserves all validated source texels at the chosen output size. The result is the highest source
ceiling, bounded by the prepared compatibility profile's supported output zoom range. Dimensions
need not be powers of two; round up, and use the larger side of a rectangular payload. Larger
observed dimensions may raise the report; smaller later payloads cannot lower it. This is a
conservative report about observed payloads, not a guarantee about an unseen provider response.
Only one integer is retained per compiled raster source, never bytes or decoded pixels.

The distinction matters for the Terrain catalogue style: it declares 64px and serves 256px. At
source maxzoom 19 and 512px output, native cap 1 reports output ceiling 18; cap 0 reports 19,
because output 18 under that acquisition policy cannot yet fetch source 19. A Satellite source
with declared and served size 512 and maxzoom 18 reports 18. Subdividing its Output Tiles farther
would crop and encode the same maximum-level imagery without obtaining more source detail.
No source declaration is corrected and all 0.18.0 acquisition semantics stay unchanged.

`hasRasterizedVectorLayers` reports retained vector geometry and symbol draw layers, including
layers outside their current zoom range. Host-owned Label layers do not count. It is immediately
available even while payload dimensions are unknown. Consumers can use this fact to preserve
existing Output Tile zoom where vector paint still needs it. This report does not authorize
changing vector, label, line, hillshade or background evaluation, nor does Rentile automatically
clamp anything. A consumer that explicitly chooses a coarser imagery-only output still evaluates
raster paint at that chosen output's zoom, as before; the reporting API adds no style-zoom option.

The additive interface member has a conservative default of null for older implementations.
The public data class and new member require the minor version 0.19.0 and an updated ABI dump.
Tests cover unknown/validated state, raw cache hits, raw warming, invalid responses, all-source
conservatism, dimension growth, declared/payload disagreement, native caps and legacy acquisition,
non-power-of-two payloads, profile limits, mixed vector layers, host symbols, DEM exclusion, and
unchanged request/content keys and pixels. Existing rendering and platform gates remain required.
