# Make the rasterizer a closeable owner

`BasemapRasterizer` implements common `kotlin.AutoCloseable` and is the process-local owner of its scheduler, single-flight registry, in-memory prepared/decoded caches, native resources, and ephemeral credential contexts. `PreparedStyle` remains an immutable handle bound to the rasterizer that created it, so it contains no independently owned native resources or directly exposed secrets; using a handle with another or closed rasterizer raises a typed lifecycle error.

Non-suspending `close()` idempotently marks the rasterizer closed, rejects new work, cancels active operations, and returns promptly without blocking a platform thread. Idempotent `awaitClosed()` suspends until workers, native objects, retained leases, and ephemeral credential state are fully released, giving callers an explicit deterministic-shutdown path.
