# Rentile Error Model

Rentile owns failure detection and diagnosis. The caller owns recovery.

## Contract

- `prepare()` and `render()` throw subclasses of `RentileException` for Rentile failures.
- Every Rentile exception has a stable error code, pipeline stage, accumulated diagnostics, affected output-tile identities when known, and a causal exception when safe and available.
- Resource failures also identify the resource class, a sanitized stable resource identity, and protocol facts such as an HTTP status or retry delay when available.
- Rentile does not automatically retry, refetch, substitute content, treat an HTTP failure as empty data, or switch renderer/provider behavior.
- A caller may catch the exception and repeat or reshape the operation according to its own policy. Successfully validated raw resources acquired for other tiles remain atomically cached and are never rolled back because one resource failed; the failing resource produces no partial or negative cache entry.
- `render()` is all-or-error for its caller-defined tile list. One tile failure throws a typed batch exception and returns no partial PNG collection; callers choose smaller batches when they require isolated outcomes.
- Rentile fails fast on the first terminal failure. It cancels work owned only by that call, reports the primary failure plus concurrent failures already observed, and does not continue solely to collect more errors; shared work awaited by other callers continues.
- `CancellationException` passes through unchanged. Cancellation is control flow, not a Rentile error code.

## Exception families

`RentileException` is the sealed public root for:

- `StylePreparationException` — invalid style/profile input or unsupported reachable behavior.
- `ResourceAcquisitionException` — transport, redirect, HTTP status, response-size, or resource-closure acquisition failure.
- `ResourceDecodeException` — malformed or unsupported MVT, raster, DEM, sprite, TileJSON, or GeoJSON bytes.
- `RasterizationException` — deterministic geometry, paint, placement, or native drawing failure.
- `PngEncodingException` — failure to produce the canonical PNG output.
- `ResourceStoreException` — persistent raw-store read, validation, atomic-write, or corruption failure.
- `SafetyLimitException` — a declared byte, count, depth, coordinate, or operation limit was exceeded.
- `PreparedBatchClosedException` — a caller attempted to start rendering from a prepared batch after closing it.
- `RasterizerClosedException` — a caller attempted to start work after closing the owning rasterizer.
- `ForeignPreparedStyleException` — a prepared-style handle was passed to a rasterizer instance that did not create it.
- `ForeignPreparedBatchException` — a prepared-batch handle was passed to a rasterizer instance that did not create it.
- `ForeignLabelCandidatePlanException` — a label-candidate-plan handle was passed to a rasterizer instance that did not create it.
- `LabelCandidatePlanClosedException` — a caller attempted acquisition, or called `LabelCandidatePlan.glyphUrls`, on a label candidate plan after closing it.
- `GlyphTemplateMismatchException` — the glyphs template passed to `LabelCandidatePlan.glyphUrls` is not the one the prepared style resolves.
- `InvalidTileIdException` — a tile identity fell outside the supported XYZ range or the profile's output zooms.
- `TileNotInPreparedBatchException` — a render was requested for a tile the prepared batch does not carry.
- `TileSubstitutionLimitException` — more output tiles needed substitutes than the substitution policy allows.
- `TileSubstitutionException` — a substitute could not be resolved for an output tile that required one.
- `BatchRenderException` — one or more output tiles in a caller-defined render failed.

Specific error codes are stable API. Exception messages are for humans and are not machine contracts.

## Diagnostic safety

Diagnostics may contain sanitized origins, resource classes, content digests, XYZ identities, pipeline timings, status codes, and bounded failure details. They must never contain API keys, session identifiers, authorization headers, cookies, full signed URLs, unrestricted query strings, raw credential-bearing style documents, or unbounded provider response bodies.

## Caller examples

A caller may respond to the same `ResourceAcquisitionException` by retrying later, bypassing its transport cache, splitting a batch, switching an application-level fallback, or cancelling sibling jobs. Those policies stay outside Rentile so preview, export, background, and future consumers can make different choices without changing renderer semantics.
