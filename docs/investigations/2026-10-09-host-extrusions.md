# Host extrusions: source evidence and consumer implementation sequence

## Rentile delivery

The 0.13.0 API separates three responsibilities:

| Responsibility | Implementation |
|---|---|
| Style admission, default raster compatibility | `StyleCompiler.compileHostExtrusionLayer`; two opt-in `CompatibilityPolicy` values |
| Fractional paint, integer filters, metre heights, layer opacity/gradient | `internal/style/ExtrusionProgram.kt` |
| Canonical source sampling and overzoom deduplication | Existing `CompiledVectorSource.sampleFor`, keyed by source identity and source z/x/y |
| Raw cache, cache-only/reload, single flight and decode permits | `VectorResourceAcquirer.acquireSelective` |
| Selective bounded protobuf decode and ring grouping | `internal/mvt/CompactExtrusionDecoder.kt` |
| Host boundary, no projection or GPU dependency | `Extrusions.kt` and the three `BasemapRasterizer` methods |

The previous path calls Wire's `Tile.ADAPTER.decode` before checking feature/command limits, then
constructs all source layers' feature/property/coordinate graphs. The new path avoids that graph
entirely. It scans layer headers and selected dictionaries, validates/counts polygon commands, then
allocates exact `IntArray`s for coordinates, ring offsets and polygon offsets. Scalar dictionaries
are bounded before decoding strings, with room charged for temporary decode buffers. Geometry is
shared across output children and style layers; constant paint closures release feature properties.
No global decoded-building cache is introduced.

Default raster styles still call the existing decoder and flatten extrusions. The new profiles
instead omit extrusion footprints, preserving other fill layers. Existing style/raster identities
stay unchanged; new profiles have distinct identities. Bounds throw typed errors before allocation,
not partial data. Network and raw-store buffers remain governed by the existing resource limits,
and the host must still budget all simultaneously retained CPU/GPU assets.

## Real tile measurement

Twelve locally supplied z15 MVTs from Paris, Manhattan and Tokyo were compared through the public
API with Rentile's existing decoder, coordinate by coordinate, including every hole and component.
The corpus had **1,372 feature records, 17,544 polygons, 18,083 rings and 143,621 vertices**. Some
records contain many polygon components: feature count is not building-footprint count.

On macOS arm64/JVM, after three warm-up acquisitions and seven timed acquisitions per tile, median
time ranged from **0.51 to 2.30 ms** per tile. Conservative retained estimates ranged from **0.08 to
0.43 MiB**. Timing includes local transport, hashing, selective decode and style binding, with a
no-op raw store; it excludes network, triangulation, meshes, GPU upload/draw and mobile execution.
This establishes the geometry path and desktop cost, not Android/iOS performance or OOM/ANR safety.

`HostExtrusionCorpusSmokeTest` reproduces the comparison/measurement with locally supplied files.
The live catalogue test prepares every catalogue style under the combined host profile and acquires
Manhattan building polygons for styles that author extrusions. It requires positive-height output,
without inventing extrusion layers for other styles. The catalogue is rolling; do not pin a fixed
style count as a permanent assertion.

## Projection spike

An isolated WebGL2 spike compiled the unmodified shared camera/projection source as a reference and
compared direct switching, calibrated switching and a geometric morph. It passed eight checks over
180 synthetic plans at 24/30/60 fps, including reverse overview transitions, short close-ups,
overlapping windows, skipped frames and seeks. Source comparison covered 20,640 projected points;
the maximum agreement error was below 1e-8 device pixels.

Calibration removes the existing explicit Mercator mode's 30% framing boost from the adaptive
handoff and corrects the latitude scale. It does not change the product's explicit Mercator mode.
A calibrated hard switch still displaced Manhattan anchors by about **2.94 px at 60-degree pitch**
in the tested handoff. The geometric morph had continuous endpoints at a frozen camera, with
forward/skipped/seek rendering agreeing at the same destination frame. A zoom-only hard switch at
z6 is not supported by these results: the pitch, footprint and camera scale still matter.

The spike uses synthetic plans, flat ground and diagnostic route/vehicle geometry on desktop WebGL.
It does not validate production terrain, atmosphere, map labels, glTF placement or mobile GPU cost.
Its two-second transition window and merge thresholds are experimental parameters, not shipping
values. It supports implementing Mercator-only buildings first, with production transition gates.

## Consumer work, separately authorized

No Travel Animator source changes belong to this release. The next change is in its shared renderer:

1. **Plan projection once from `FramePlan`.** Add a shared `ProjectionSchedule` beside
   `render/session/ResidentTileAssets.kt`, where the existing globe tile schedule is built. It
   records pure per-frame projection/morph state from effective zoom, pitch, latitude and footprint.
   Build entry/exit windows with overlap merging and hysteresis; finish entry before the earliest
   authored extrusion minimum zoom, including styles whose minimum is 14. Explicit Mercator plans
   bypass adaptive switching. Seeking samples destination state directly; playback/skipping and
   export sample the same immutable schedule.
2. **Use one calibrated transform everywhere.** Extend `render/math/Camera.kt` and the shared
   `world/surface/GlobeSurface.kt`/`PlaneSurface.kt` drawing path with the schedule's geometric blend.
   Apply it to ground anchors, route ribbons, vehicles, glTF, annotations and map-symbol projection.
   Keep a single depth buffer and surface pass; two overlapping opaque surface renderers create
   depth/texture artifacts. Verify continuity of position and motion at window boundaries.
3. **Adopt Rentile's profile and acquire bounded source windows.** Change the consumer's
   `render/rentile/constants.kt` policy to `RentileV1HostSymbolsAndExtrusions`. In
   `SessionSourceAssets.kt`, expose descriptors and candidate acquisition alongside labels/terrain.
   Add building requirements/residency in `ResidentTileAssets.kt`, keyed by canonical source tile
   and content revision. Acquire only upcoming visible windows, off the presentation/UI thread.
   Avoid an output-child mesh cache and whole-route decode. A style without descriptors requires no
   building data or pass.
4. **Triangulate once and draw a real GLES pass.** Add `world/buildings/` with compact roof/wall
   meshes, hole-aware triangulation, tile-boundary clipping/wall handling, metre-to-local-world
   conversion and camera-relative placement. Split batches for supported index widths. Use depth
   testing/writes and style color, base, height, opacity and vertical-gradient. Handle translucent
   layers with a depth prepass/composite policy so self-overlap does not darken incorrectly. Reuse
   meshes while source/style/height programs are unchanged; update zoom paint without MVT re-decode.
   Start with diffuse/ambient lighting matching release; shadows are an optional later improvement.
5. **Budget the combined renderer.** Extend `RenderProfile.kt`/`ResidencySizing.kt` with shared
   building CPU, triangulation scratch, GPU and in-flight limits, supplied by platform memory
   profiles. Account for old/new window overlap and upload staging. Live views can reduce distant
   building detail and shadows; exports retain complete geometry via smaller prepared windows and
   fail explicitly if one frame cannot fit. Cancel stale seek work, release GPU buffers on session
   close/context loss, and include diagnostics and memory-trim behavior.
6. **Gate renderer adoption.** Compare the six currently authored extrusion styles against release
   in Paris/Manhattan/Tokyo, at pitched/rotated cameras, source-max overzoom, tile boundaries,
   missing IDs, holes, terrain and translucent layers. Exercise Preview, export, Cover/map-only and
   other shared-renderer surfaces. Test forward/reverse playback, rapid seeks and skipped frames
   over every scheduled transition. Measure p50/p95 frame time, peak RSS/native/GPU residency,
   allocations and cancellation on representative Android/iOS devices. Run dense-city exports and
   repeated session/seek cycles under memory pressure. Do not admit a default rollout from desktop
   spike timings alone.

Only if the production projection/compositor checks remain unsatisfactory should the first delivery
expand to globe building meshes. Rentile's source-local geometry API will already support that path.
