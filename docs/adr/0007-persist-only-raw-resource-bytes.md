# Persist only raw resource bytes

Rentile will persist raw resource bytes through an injected platform-neutral store, while prepared-style and decoded-source caches remain byte-bounded memory owned by a long-lived Rentile instance. Compiled and decoded representations are rebuilt after process loss because they are semantic-version-sensitive, and output PNGs remain entirely caller-owned under ADR 0006.

The raw store must provide atomic, corruption-safe cross-process reads and writes, but v1 makes no cross-process single-flight guarantee. Identical cold misses racing in different processes may perform duplicate exchanges and converge on the same validated cache entry; Rentile records that duplication through metrics rather than introducing distributed leases and crash recovery.

Raw entries retain allowlisted HTTP freshness and validator metadata. Normal acquisition uses a fresh entry directly, conditionally revalidates a stale entry with `ETag` or `Last-Modified`, and reuses its validated bytes on `304`; failed revalidation raises a typed resource error and never silently substitutes stale content.

## Amendment: how the style closure applies this

The style, its sprite JSON, its sprite image and every source's TileJSON acquire through one shared
revalidating path. Three refinements of the rule above are settled there rather than left to each
acquirer:

**A stored entry is served immediately and revalidated behind the caller**, whatever its freshness,
and the style is included. Revalidating in front of the caller is what the rule above describes and
it was correct, but production sends no `Cache-Control` on the style, the sprite documents or the
TileJSONs, so every preparation made about five conditional requests that could never be skipped and
that all answered `304` before anything was usable — one extra round trip on the path a style switch
is supposed to be instant on, plus exposure to the multi-second tail a single slow request adds.

A stale entry is therefore returned at once and one background revalidation is scheduled for it:
conditional when the entry carries validators, unconditional when it does not. `304` rewrites the
entry, `200` replaces it, and any failure keeps what is stored and raises nothing — a background
refresh may never fail a preparation. Refreshes are bounded to one per key per process, run in the
rasterizer's own scope so they outlive the caller but not the rasterizer, and take their exchange
permit at WARM priority so they never compete with tile acquisition (ADR 0031).

**The consequence is a one-preparation lag**, and it is accepted deliberately: a changed upstream
document is picked up at the *next* preparation, not the one that read it. For the style that is the
same lag the consumer already has, since a compiled style is memoised per process and can only
change between preparations. A fresh entry (an explicit `max-age` or `Expires` that has not passed)
still needs no request at all, exactly as stated above.

An entry with no validators at all is still stored: under stale-while-revalidate it is served
immediately and refreshed unconditionally behind the next caller, which is worth more than the
payload read and SHA-256 it costs to probe.

**Reusing stored bytes rewrites the entry.** A consumer's raw cache is trimmed by file age, so an
entry read on every start and never written becomes its oldest file and is evicted first — the
documents this exists to keep would be the first to go. Both a fresh hit and a `304` rewrite the
entry, carrying whatever validators and freshness the `304` restated.

Source tiles are unchanged by this amendment: they are immutable per URL in every corpus provider,
their cached bytes win outright, and prefetching them has its own permit and flight rules (ADR 0031).
