# Hand compact extrusion polygons to the host

Rentile flattens `fill-extrusion` into a raster footprint under its existing profiles. The current
consumer uses that texture on a shared GLES globe or Mercator surface, so a flat footprint cannot
reproduce the release map's buildings. A second raw-MVT decoder in the consumer would duplicate
source overzoom, style compilation, credentials, cache access and resource scheduling. Returning
Rentile's existing coordinate-object graph would keep every unrelated source layer alive and move
an avoidable memory peak into the host.

## Decision

Two opt-in profiles, `RentileV1HostExtrusions` and `RentileV1HostSymbolsAndExtrusions`, omit visible
extrusion layers from Output Tiles and compile them as host-owned programs. The first keeps ordinary
symbol behavior; the second combines the ownership of ADR 0035 with extrusion ownership. Existing
profiles, defaults, style digests, raster keys and output semantics are unchanged.

`acquireExtrusionCandidates` returns canonical source-tile polygons in compact primitive arrays,
with implicit ring closure and explicit polygon/hole grouping. Source geometry is decoded once per
resource in a batch, shared between overzoomed output children and style layers, and kept in source
extent coordinates. Buffered coordinates survive. A feature without an ID keeps a null ID, with its
source-layer index and resource revision supplying the local identity. The host must use the source
tile/extent, never interpret these as requested output-tile pixels.

Feature paint uses the prepared style's existing expression/filter compiler: fractional zoom for
paint, integer zoom for filters. Heights and bases are metres, missing height defaults to zero,
negative values clamp to zero, and base clamps to height. Missing or unusable property values use
MapLibre property defaults, including a zero base when a feature has height but no `height_min`;
legacy explicit defaults take precedence. Feature color alpha is ignored; opacity
and vertical-gradient belong to the layer. Constant feature paint is evaluated once. Zoom-dependent
paint retains its feature properties and compiled program, with no source URL or encoded tile in
the candidate's closure. Unsupported authored constructs fail preparation, rather than secretly
falling back to a footprint.

The new decoder reads protobuf directly in two passes and selectively decodes only requested
source layers. It counts and validates before allocating exact coordinate/offset arrays. It shares
raw storage, acquisition flights and decode permits with the existing raster path, and checks
cancellation during long loops. Malformed cached data is removed and retried; tighter per-operation
limits do not evict valid cached data. Cache-only never fetches and there is no tile substitution.

## Ownership and limits

Rentile owns source acquisition, bounded selective decoding, ring grouping, style evaluation and
credential-free request/content identities. The caller owns projection, triangulation, clipping,
tile-edge wall policy, lighting, depth/transparency, terrain placement and GPU residency. This API
contains no globe/Mercator decision; the same polygons can serve either later.

An operation caps requested tiles, encoded input before selective decoding, temporary dictionary
storage and estimated retained data across the batch. Raw transport buffering is bounded separately
by `ResourceLimits.maxTileBytes`, because all consumers of a raw-resource flight must agree on its
transport limit. Retained estimates conservatively charge primitive arrays, maps, strings,
candidates and bookkeeping before allocation; they are not an exact heap or RSS measurement.
Concurrent operations, old batches, triangulation scratch and GPU copies need the host's own budget.
Decode is sequential per batch. Exceeding a budget fails explicitly and never omits a building.

The host requests bounded viewport or prepared-export windows and releases old batches. Live
quality adaptation and export admission stay in the host. No buildings are invented for a style
without an extrusion layer. The three interface methods have default unsupported bodies so existing
consumer fakes and decorators compile unchanged.

## Validation

Common tests cover default raster compatibility, both opt-in profiles, metre/color/opacity semantics,
fractional paint and integer filters, holes, multipolygons, buffered geometry, missing/unsigned IDs,
source overzoom sharing, identities, allocation limits, malformed protobuf, field order, repeated
packed/unpacked fields, cache modes, redaction and cancellation. The optional Android-host corpus
probe compares every coordinate with the existing decoder on locally supplied real MVT tiles and
reports acquisition time and retained estimates. Desktop/native tests do not qualify mobile OOM,
ANR, GPU performance or visual parity; those require the consumer renderer and device measurements.

Primary references: [MapLibre extrusion properties](https://maplibre.org/maplibre-style-spec/layers/#fill-extrusion),
[MapLibre legacy function defaults](https://github.com/maplibre/maplibre-style-spec/blob/main/src/function/index.ts),
[MapLibre's vertex shader](https://github.com/maplibre/maplibre-gl-js/blob/main/src/shaders/glsl/fill_extrusion.vertex.glsl),
and [MVT 2.1](https://github.com/mapbox/vector-tile-spec/tree/master/2.1).
