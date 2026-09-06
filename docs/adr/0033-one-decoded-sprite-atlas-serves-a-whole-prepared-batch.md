# One decoded sprite atlas serves a whole prepared batch

Every composited tile built a `SpriteRenderContext`, and that constructor called
`Image.makeFromEncoded(atlas.pngBytes)`. A session rendering fifty-two tiles from one style
therefore handed Skia the same sprite sheet fifty-two times.

The reason this was invisible is that `Image.makeFromEncoded` is not the decode. It returns a
*lazy* image: the call parses a header and costs a few microseconds, and the PNG is decoded on the
first draw that samples it, then memoised against that image object. A fresh image per tile is a
fresh memo per tile, so the decode repeated once per tile while the line that looked expensive
stayed cheap. Measured on this repository's own probe (macOS arm64, JVM, Skiko 0.148.2, four sprite
extractions per tile):

| Sprite sheet | 52 tiles, an image each | 52 tiles, one shared image | `makeFromEncoded` alone, ×52 |
|---|---:|---:|---:|
| 1024 px | 47.6 ms | 1.4 ms | 0.40 ms |
| 2048 px | 264.7 ms | 9.8 ms | 0.40 ms |

A 2048 px sheet is the ordinary case for a `@2x` provider sprite, so this was a quarter of a second
of pure repetition per fifty-two-tile session, on the metatile workers, for a result already in
memory.

## Decision

**One decoded sprite-atlas image is held by the prepared batch and borrowed by every tile that
batch draws.** `SpriteRenderContext` no longer decodes and no longer owns the atlas image; it takes
one and reads it.

**The wrapper stays per tile.** Only the decoded image is shared. `SpriteRenderContext` memoises
each extracted sprite in a plain `MutableMap`, and a batch's tiles are drawn concurrently on
`maxConcurrentMetatileWorkers` real threads (every public operation runs on `Dispatchers.Default`),
so sharing the context itself is a data race. Guarding it would need a non-suspending lock, and this
module has no common-source primitive for one — `kotlinx.coroutines.sync.Mutex` is suspending and
the draw path is not; an `expect`/`actual` lock across five target families to save a few
microseconds of sprite extraction per tile is the wrong trade. Sharing only the image needs no lock:
Skia states an `SkImage` cannot be modified after creation and is thread-safe, guards the lazy
decode behind it, and every extraction only samples it. The per-sprite images each context builds
are its own, are never published to another thread, and close with the tile.

**The batch is the scope because it is the widest one that closes.** `PreparedBatch` is
`AutoCloseable` and the caller closes it, so the decoded pixels are released on a boundary the
caller already manages. A `PreparedStyle` has no close of its own — ADR 0016 makes the rasterizer
the closeable owner — so a style-scoped atlas would hold decoded pixels for the life of the
rasterizer, and a style-scoped *sprite* memo would grow without a bound anyone had chosen. The style
overload of `render` prepares and closes a batch around one call, so it decodes once too.

Per *render call* was rejected for the opposite reason: it is too narrow. A consumer that renders a
prepared batch tile by tile so it can mark some of those tiles `URGENT` (ADR 0032) makes exactly the
call shape a per-call hoist would leave decoding once per tile — and that consumer is the one this
change exists for.

The image is decoded on first use rather than at `prepareBatch`, so a sheet that passes acquisition's
PNG-signature check and then fails Skia still raises `ResourceDecodeException` from a render, where
it always did, and a batch a caller prepares but never renders costs nothing. Two workers can reach
that first use at once; the winner is chosen by compare-and-set and the loser closes the image it
built, which is free in the order that matters — a lazy image nothing sampled never decoded
anything.

## Consequences

- **No output changes.** The bytes drawn are the same bytes: the same encoded sheet, the same Skia
  decode, the same `drawImageRect` extraction into the same per-tile surface. No request key, no
  content key and no renderer-semantics marker moves, so consumer caches carry over untouched. This
  was checked by rendering eight tiles at three output sizes through both `render` and `renderRaw`,
  before and after, and comparing SHA-256 digests: all fifty-six identical.
- **A wrong hoist is invisible in the output**, exactly as a wrong `RenderPriority` is. One decode
  and fifty-two decodes produce identical tiles, only slower, so the rasterizer carries a test-only
  decode recorder and `SpriteAtlasDecodeTest` asserts the count for a multi-tile batch, for repeated
  renders of one batch, and for a style with no sprite at all.
- **Concurrency is proven by pixels, not by assertion.** The same tiles are rendered one per batch
  on one worker — the previous arrangement, a decode each — and again as one batch on four workers
  sharing a decode, and compared byte for byte.
- **A batch now holds decoded sprite pixels until it is closed.** For a 2048 px sheet that is about
  16 MiB, released on `close()`. A caller that prepares many batches at once and closes none holds
  one per batch; a caller following the documented shape holds one at a time. Nothing else about the
  batch's memory changes, and a style with no sprite holds nothing.
- **Two batches of one style decode twice.** That is the deliberate cost of not retaining decoded
  pixels for the life of the rasterizer, and it is the same cost the renderer paid per *tile* before.
