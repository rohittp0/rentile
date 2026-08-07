# Expose content keys before drawing

Rentile will expose `prepareBatch()` to freeze a caller-defined tile set's resource closure and compute per-tile content keys before drawing, followed by `render(PreparedBatch, subset)` so the caller can render only output-cache misses. A convenience `render(PreparedStyle, tiles, ...)` composes both stages, while the explicit path makes caller-owned output caching and deterministic network-free drawing practical without giving Rentile an output store.

`PreparedBatch` implements common `kotlin.AutoCloseable` and strongly retains its immutable encoded raw-resource bytes until closed, so drawing cannot be invalidated by raw-store eviction and performs neither network nor store reads. Retained bytes count against `ExecutionPolicy`; callers use Kotlin `use` or explicitly close the batch for deterministic release on Native targets.

Closing a prepared batch is idempotent, non-blocking, non-throwing, and prevents new renders without cancelling renders that already hold a lease. Retained resources are released after the final active render exits; attempting a new render with a closed batch raises `PreparedBatchClosedException`.
