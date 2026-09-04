# Persist only raw resource bytes

Rentile will persist raw resource bytes through an injected platform-neutral store, while prepared-style and decoded-source caches remain byte-bounded memory owned by a long-lived Rentile instance. Compiled and decoded representations are rebuilt after process loss because they are semantic-version-sensitive, and output PNGs remain entirely caller-owned under ADR 0006.

The raw store must provide atomic, corruption-safe cross-process reads and writes, but v1 makes no cross-process single-flight guarantee. Identical cold misses racing in different processes may perform duplicate exchanges and converge on the same validated cache entry; Rentile records that duplication through metrics rather than introducing distributed leases and crash recovery.

Raw entries retain allowlisted HTTP freshness and validator metadata. Normal acquisition uses a fresh entry directly, conditionally revalidates a stale entry with `ETag` or `Last-Modified`, and reuses its validated bytes on `304`; failed revalidation raises a typed resource error and never silently substitutes stale content.

## Amendment: how the style closure applies this

The style, its sprite JSON, its sprite image and every source's TileJSON acquire through one shared
revalidating path. Three refinements of the rule above are settled there rather than left to each
acquirer:

**A style is never served on freshness alone.** Everything else in the closure may use a fresh entry
directly, exactly as stated above. The style may not: it is the root of the closure, so serving one
from a `max-age` without asking the origin would pin an entire cached resource tree to a document
nobody re-confirmed, and a style switch is a user action that must not wait out an expiry. A warm
start therefore costs one conditional request for the style even when its entry has not expired.

**Reusing stored bytes rewrites the entry.** A consumer's raw cache is trimmed by file age, so an
entry read on every start and never written becomes its oldest file and is evicted first — the
documents this exists to keep would be the first to go. Both a fresh hit and a `304` rewrite the
entry, carrying whatever validators and freshness the `304` restated.

**An entry that could never be reused is not stored.** No `ETag`, no `Last-Modified` and no expiry
means no later start can do anything with it but throw it away, so writing it buys nothing and costs
a payload read plus a SHA-256 every time it is probed.

Source tiles are unchanged by this amendment: they are immutable per URL in every corpus provider,
their cached bytes win outright, and prefetching them has its own permit and flight rules (ADR 0031).
