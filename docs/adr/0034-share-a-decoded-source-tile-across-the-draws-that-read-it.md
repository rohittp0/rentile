# Share a decoded source tile across the draws that read it

[ADR 0033](0033-one-decoded-sprite-atlas-serves-a-whole-prepared-batch.md) fixed one instance of a
defect that had two siblings. `drawRaster` and `drawHillshade` each called `Image.makeFromEncoded`
on every draw, and hillshade calls it nine times per output tile — the DEM neighbourhood a slope
needs — where the neighbourhoods of adjacent output tiles overlap almost entirely.

The measurement is on this repository's own probe (macOS arm64, JVM, Skiko 0.148.2, 512 px source
tiles encoding to 381 KB, which is what a provider's Terrain-RGB actually weighs, four metatile
workers, 512 px output). The two sites do not deserve the same answer, and one of them barely
deserves one at all.

## Hillshade repeats, and a lazy image cannot fix it

| Session | DEM tiles read | distinct | decode + pixel-read, before | after |
|---|---:|---:|---:|---:|
| 2×2 metatile at the source's zoom | 36 | 16 | 111.3 ms | 24.5 ms |
| 4×4 metatile at the source's zoom | 144 | 36 | 435.3 ms | 43.1 ms |
| 4×4 metatile overzoomed ×4 | 144 | 9 | 434.7 ms | 14.4 ms |

Wall clock for the whole 4×4 overzoomed session goes 507.5 ms → 402.9 ms; at the source's own zoom
1149.3 ms → 1064.0 ms, because hillshade's own arithmetic — a million `heightAt` samples per output
tile — is the larger half and this change does not touch it.

ADR 0033's fix does not transfer. It shares a *lazy* image, which works because Skia decodes on the
first draw that samples the image and memoises the result against that image. `drawHillshade` does
not draw the DEM; it reads its pixels with `readPixels`, and Skia re-decodes for every one of those.
Measured over 52 reads of one 512 px DEM: **141.3 ms** for a freshly decoded image each time,
**143.1 ms** sharing one lazy image — a saving of nothing — and **1.3 ms** sharing an image whose
decode has already been forced.

## Raster repeats only when it overzooms

| Session | draws | distinct source tiles | `drawRaster`, before | after |
|---|---:|---:|---:|---:|
| 4×4 metatile at the source's zoom | 16 | 16 | 34.5 ms | 34.0 ms |
| 4×4 metatile overzoomed ×4 | 16 | 1 | 47.0 ms | 34.0 ms |
| 52 tiles overzoomed ×2 | 52 | 16 | 153.6 ms | 88.0 ms |

At a raster source's own zoom every output tile has a source tile of its own, so **there is nothing
to share and the report of a per-draw decode, while true, costs nothing.** The repeat appears only
when a batch overzooms the source — the same covering tile serving `childScale²` output tiles — and
then it is most of the work `drawRaster` does.

## Decision

**One decoded source-tile image is held by the prepared batch and borrowed by every draw of that
batch that reads the same bytes.** A batch-scoped `SharedSourceTileImages` keyed by
`RasterResource.contentDigest` holds them; `drawRaster` and `drawHillshade` borrow rather than
decode.

**A decode is shared only where a decode is reused.** The batch computes, once, which content
digests more than one of its raster entries carries, and shares only those. Without that rule this
would be a memory regression dressed as a saving: a 52-tile raster batch at the source's own zoom
would hold 52 decoded tiles — about 52 MiB — to save nothing, because none of them is read twice.
With it, nothing is held that was not going to be decoded more than once anyway, and a raster style
at its own zoom is left exactly as it was, decode per draw and no pixels retained. The visible
consequence is that re-rendering one tile of a batch decodes again whatever that tile alone reads:
one DEM at the corner of a metatile, where before it was that tile's whole neighbourhood.

**The key is the content digest, not the sample identity.** Identical bytes decode identically
whoever asked for them, and it is the only key that stays right when a substituted resource carries
an ancestor's bytes under the requested tile's sample. All three sites that build a `RasterResource`
produce a digest that determines the bytes: two hash the bytes, and `composeRasterChildren` hashes
the provenance that fully determines them.

**The raster image stays lazy; the DEM image does not.** For a draw, laziness is free and keeps the
decode where it has always been — inside a render, on the first sample. For `readPixels` laziness
buys nothing (143.1 ms against 141.3 ms above), so a shared DEM image is built by decoding once into
a bitmap, marking it immutable and wrapping it with `Image.makeFromBitmap`, and every later reader
copies from that. It is still decoded on first use inside a render, never at `prepareBatch`.

**Only the image is shared.** Skia's header says an `SkImage` cannot be modified after it is created;
it also says an `SkBitmap` *is not thread safe* and that "each thread must have its own copy of
SkBitmap fields, although threads may share the underlying pixel array." A batch's tiles draw
concurrently on `maxConcurrentMetatileWorkers` real threads, so the `Bitmap` that `drawHillshade`
reads heights from stays private to the draw that filled it, and only the image it is filled from is
shared. That also keeps the arithmetic identical: the heights come from the same `getColor` on the
same kind of bitmap as before.

**The DEM decodes of a render are done once, before its tiles fan out.** Publication is a
compare-and-set — this module has no common-source non-suspending lock, for ADR 0033's reason — and
a loser discards what it built. For a lazy raster image that is free, as it is for the sprite atlas.
For a DEM it is a wasted decode, and the batch shape that gains most is the one that races hardest:
sixteen output tiles over one nine-tile neighbourhood start together on every worker and all want
the same nine. Leaving them to race cost 118 ms of decoding where doing it once costs 31 ms, so each
`render` and `renderRaw` first decodes the shared DEM tiles its requested tiles need, once each, on
the same render permits the tiles themselves use. That step is best-effort: a DEM Skia refuses is
left uncached and the draw that needs it decodes it and throws, so the failure still names the output
tile it always named.

## Consequences

- **No output changes.** Checked rather than asserted: 108 tiles across six styles — raster at the
  source's zoom, raster overzoomed ×4, a raster source with non-opaque alpha overzoomed ×2, hillshade
  at the source's zoom, hillshade overzoomed ×4, and a Terrarium-encoded DEM — each rendered at 256,
  512 and 1024 px through both `render` and `renderRaw`, on one worker and on four. All 324 lines of
  PNG digest, raw RGBA digest and content key are identical before and after. No request key, content
  key or renderer-semantics marker moves, so consumer caches carry over untouched.
- **A wrong hoist is invisible in the output.** As with the sprite atlas, the rasterizer carries a
  test-only decode recorder and `SourceTileDecodeTest` pins the counts: 25 decodes for a 3×3
  hillshade metatile that reads 81 DEM tiles, no further decode across repeated renders of that
  batch, one decode for an overzoomed raster batch against nine for the same batch at the source's
  own zoom, and one decode per batch rather than per rasterizer.
- **Concurrency is proven by pixels.** The same tiles are rendered one batch per tile on one worker —
  the previous arrangement, a decode per draw, nothing shared — and again as one batch on four
  workers, and compared byte for byte.
- **A batch holds decoded pixels until it is closed**, but only for source tiles more than one of its
  draws reads: about 1 MiB per shared 512 px tile, so a 4×4 hillshade metatile holds around 32 MiB
  where the *transient* peak of four workers each holding a nine-tile neighbourhood was already
  36 MiB. A raster style at its source's own zoom holds nothing, and neither does a style with no
  raster or DEM source.
- **Two batches of one style decode twice**, for ADR 0033's reason: a `PreparedBatch` is the widest
  thing that closes, so it is the widest thing that can release decoded pixels on a boundary the
  caller already manages.
- **The first tile of a hillshade render waits for that render's shared DEM decodes.** They are the
  decodes it was going to do anyway, and it no longer waits for the ones its neighbours duplicate.
