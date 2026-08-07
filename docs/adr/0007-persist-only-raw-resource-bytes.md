# Persist only raw resource bytes

Rentile will persist raw resource bytes through an injected platform-neutral store, while prepared-style and decoded-source caches remain byte-bounded memory owned by a long-lived Rentile instance. Compiled and decoded representations are rebuilt after process loss because they are semantic-version-sensitive, and output PNGs remain entirely caller-owned under ADR 0006.

The raw store must provide atomic, corruption-safe cross-process reads and writes, but v1 makes no cross-process single-flight guarantee. Identical cold misses racing in different processes may perform duplicate exchanges and converge on the same validated cache entry; Rentile records that duplication through metrics rather than introducing distributed leases and crash recovery.

Raw entries retain allowlisted HTTP freshness and validator metadata. Normal acquisition uses a fresh entry directly, conditionally revalidates a stale entry with `ETag` or `Last-Modified`, and reuses its validated bytes on `304`; failed revalidation raises a typed resource error and never silently substitutes stale content.
