# Deferred work

## Performance characterization and budgets

**Status:** Deferred until the standalone renderer and representative vertical slices exist.

Do not block the current API, compiler, resource-planning, or rendering work on an unmeasured comparison with Travel Animator's existing rendered-tile path. Do not invent numeric performance budgets during the initial design phase.

Before publishing a prerelease for Travel Animator integration:

1. Benchmark representative raster-only, base vector, pattern, hillshade, and independent-icon styles on the release target matrix.
2. Measure cold, raw-resource-warm, decoded-resource-warm, and caller-output-cache-warm states separately.
3. Record preparation and per-tile latency, sustained throughput at supported concurrency limits, peak resident memory, request and byte fan-out, cache footprint, and release artifact size by platform and ABI.
4. Compare the same requested tiles, output size, pixel ratio, style snapshot, resource bytes, and device conditions with the existing rendered-tile path where a direct comparison is meaningful.
5. Ratify numeric acceptance budgets before reviewing optimization results, then gate prerelease acceptance on those budgets.

This deferral does not relax correctness, determinism, cancellation, bounded-resource, credential-handling, or cache-integrity requirements. Safety limits needed to protect against malformed or oversized inputs must still be defined before accepting untrusted resources.
