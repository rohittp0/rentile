# Rentile owns work scheduling

Rentile, rather than the injected transport or caller, will own single-flight deduplication and bounded scheduling across source-resource exchanges, decoding, metatile drawing, and PNG encoding. Each process uses one long-lived Rentile instance as the ownership boundary for its scheduler, memory budgets, decoded caches, and single-flight registry; only persistent store contracts coordinate separate processes. Immutable `ExecutionPolicy` supplied when constructing the rasterizer defines fixed v1 limits for global and per-origin exchanges, decodes, resident decoded bytes, and metatile workers; these operational limits are not `RenderOptions` and never enter pixel or cache identity. The transport remains a cancellable single-exchange primitive, with one mobile metatile worker by default until measurements justify more.

Rentile does not define work classes or prioritize callers. Operations are cancellable, and the caller controls priority by retaining or cancelling its own work; scheduling metadata therefore cannot leak into rendering semantics.

The caller also owns batch sizing and chunking. Rentile schedules the tile list it is given without introducing a separate batch-size policy or internally splitting caller-visible work.

Single-flight work uses last-waiter cancellation. Cancelling a caller detaches that caller without stopping shared work awaited elsewhere; the underlying exchange, decode, or render is cancelled only after its final waiter leaves, and cancellation propagates through the transport and worker checkpoints without committing partial cache entries.
