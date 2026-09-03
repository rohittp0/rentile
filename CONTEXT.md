# Rentile

Rentile provides the vocabulary for a headless basemap-tile rendering dependency used by Travel Animator and other future consumers.

## Language

**Rentile**:
The `com.rohittp.rentile` Kotlin Multiplatform dependency that rasterizes the approved style profile into encoded PNG XYZ basemap tiles without a UI or platform render loop.
_Avoid_: Basemap Rasterizer, KML renderer

**Output Tile**:
An encoded PNG tile produced by Rentile for a requested XYZ coordinate. Its `outputSizePx` is a
device pixel ratio expressed as a size, not a zoom shift: the style is evaluated at the tile's own
zoom whatever the size, so 256, 512, 1024 and 2048 differ in pixels and never in cartography.
_Avoid_: Rendered provider tile, output tile size as a zoom

**Style Pixel**:
The unit every pixel-valued style property is authored in, defined against a 512-pixel-wide tile.
One Style Pixel is `outputSizePx / 512` Output Tile pixels, so a `line-width` of 4 is 4 px at 512
and 16 px at 2048. The Output Tile draw path works entirely in Style Pixels; a Label Candidate's
pixel-valued scalars are Style Pixels too, which is why the consumer applies the same ratio to
label geometry itself.
_Avoid_: Screen pixel, device pixel, logical pixel, output pixel

**Source Tile**:
A vector, raster, or elevation tile consumed as input while producing one or more output tiles.
_Avoid_: Output tile, Label Tile

**Raw Resource**:
The exact encoded style, source metadata, source tile, sprite, glyph range, or GeoJSON bytes acquired before compilation or decoding.
_Avoid_: Decoded source data, output tile

**Prepared Style**:
An immutable, validated rendering program and its resolved resource identities under a specific compatibility profile.
_Avoid_: Raw style, mutable renderer session

**Prepared Batch**:
An immutable rendering input that freezes the resource closure and output content keys for a caller-defined set of output tiles before any drawing occurs.
_Avoid_: Render result, mutable request queue

**Resource Closure**:
The complete set of immutable raw-resource identities required to render a specific batch of output tiles.
_Avoid_: Request queue, mutable cache contents

**Raster Pass-Through**:
A pixel-equivalent output path in which an already valid PNG source tile is returned without decoding, drawing, or re-encoding because preparation and runtime validation prove that no style operation changes it.
_Avoid_: Raster compatibility mode, unchecked byte forwarding

**Vector Overzoom**:
Rendering an output tile above a vector source's maximum data zoom by reusing the covering maximum-zoom source tile and transforming its geometry into the requested output-tile coordinate space.
_Avoid_: Missing high-zoom data, vector upscaling

**Label**:
Text produced by a visible text-bearing vector symbol layer and prepared for a host-owned renderer rather than drawn into an Output Tile. It includes place, road, point-of-interest, water, terrain, protected-area, and other style-authored symbol text.
_Avoid_: Place name, annotation, caption, raster text

**Label Candidate**:
A Label decoded, style-evaluated and laid out into glyph geometry, with its point or source-line geometry and any successfully resolved paired icon, but not positioned on screen and not resolved against any other Label. The consumer places and collides the text and icon as one symbol; a failed paired icon leaves the text plus an `ICON_FEATURE_SKIPPED` diagnostic.
_Avoid_: Label primitive, prepared label, placed label, label descriptor

**Paired Icon**:
An icon authored on the same symbol layer as a Label. Rentile carries its resolved style intent and final-box anchor; the viewport-owning consumer places and collides it with the Label as one symbol, and sprite imagery remains consumer-owned.
_Avoid_: Label icon layer, public sprite atlas, pre-fit anchor shift

**Label Tile**:
A vector tile consumed as input while producing Label Candidates rather than an Output Tile.
_Avoid_: Source Tile, label candidate

**Decoded DEM Texels**:
The pixels of one elevation Source Tile as canonical 8-bit RGBA - a fixed red, green, blue, alpha
byte order, rows top-down and tightly packed, never premultiplied and never colour-converted -
produced by the decode that already validates the tile and carried on its acquisition result so a
consumer needs no image decoder of its own. They are packed channel values, not elevations: the
consumer still applies the tile's `TerrainDemEncoding` to obtain metres.
_Avoid_: Elevation samples, height array, DEM bitmap, decoded raster

**Glyph Range**:
One block of 256 consecutive Unicode codepoints of a font stack, acquired as signed-distance-field glyph bitmaps and their metrics.
_Avoid_: Font file, glyph page, character set

**Label Candidate Batch**:
The immutable result of one Label acquisition: its Label Candidates, the glyph atlas they reference, and the content key identifying the acquired glyph and vector bytes.
_Avoid_: Prepared Batch, Label Candidate Plan, render result, Label Tile

**Glyph Closure**:
The complete set of Glyph Range identities one Label Candidate Plan will acquire.
_Avoid_: Resource Closure, glyph atlas, request queue

**Label Candidate Plan**:
The frozen Glyph Closure and evaluated label content for one tile set, held between Label Tile acquisition and Glyph Range acquisition.
_Avoid_: Prepared Batch, Label Candidate Batch, resource closure

**Repaired Layer**:
A symbol layer retained in the Output Tile path only because the compatibility profile removed its text and its icon's geometry does not depend on that text, as opposed to one the style author declared as an icon layer. The Label Candidate path does not repair away that text: it can emit the Label and a successfully resolved paired icon, including icon text-fit inputs; a failed icon leaves the text and a diagnostic.
_Avoid_: Retained layer, text-coupled layer, degraded layer

**Profile-Complete Rendering**:
Successful rendering of every current rolling-corpus style at every supported output zoom, plus successful Label preparation for every visible text-bearing vector symbol layer in the corpus geographies chosen to exercise script and geometry coverage, after applying the compatibility profile's deliberate transformations and exclusions.
_Avoid_: Unmodified style parity, zoom-zero smoke success

**Coverage Manifest**:
The immutable set of style, zoom, coordinate, source, and capability cases that proves profile-complete rendering without pretending to enumerate every possible XYZ tile.
_Avoid_: Sample gallery, exhaustive tile set

**Public Map Catalog**:
The public paginated endpoint that supplies the current complete set of map IDs, names, and style-document URLs to corpus gates.
_Avoid_: Private Style Index, committed style fixture

**Rolling Style Corpus**:
The latest style documents resolved from the Public Map Catalog when a corpus gate runs; intentional upstream edits become the current corpus without a digest-approval step.
_Avoid_: Pinned style snapshot, immutable style corpus

**Corpus Report**:
A credential-free artifact from one public-catalog corpus run containing rendered PNGs, mosaics, capability coverage, and redacted failures for automated and human inspection.
_Avoid_: Public Map Catalog response, raw-resource archive

## Deferred work

Performance profiling and numeric acceptance budgets are intentionally deferred while the standalone API and renderer are being established. Before a prerelease is approved for Travel Animator integration, profile representative cold, resource-warm, and caller-output-warm workloads; record latency, throughput, peak memory, request fan-out, and release artifact size; then ratify platform-specific acceptance budgets. See [Deferred work](docs/deferred-work.md).

## Distribution

The public consumer coordinate is `com.rohittp.rentile:kmp`. Releases `0.1.0` through `0.1.4` remain on Maven Central; the shared repository at `https://maven.rohittp.com` is canonical after the migration and is also the version line: each release takes the highest version already published there and advances its patch component. `VERSION_NAME` in the root `gradle.properties` governs only when it names a version strictly above everything public, which is how a deliberate minor or major release is requested. `0.6.0` is published, and is the highest version on that line. The source tree declares `0.7.0` for the breaking terrain-texel addition; that declaration is a release request, not proof that the coordinate has been published. Snapshot versions never govern and remain local-repository-only. Every push to `main` outside documentation publishes; documentation-only commits do not consume a version, and releases are serialised so concurrent pushes cannot race for one coordinate. The release workflow rejects an existing primary POM before upload, requires the exact version to pass signed local publication plus Android, JVM, iOS, macOS, Linux, and rolling-corpus gates, verifies every public artifact, then resolves it from a fresh credential-free consumer. A GitHub Release is not required. Version numbers are cheap and non-contiguous; a gap does not imply a withdrawn version.

Rentile is licensed under Apache-2.0. Published artifacts also carry a maintained third-party notices inventory for dependencies and copied or adapted upstream code.

Public documentation and the Maven POM project URL use `https://rohittp.com/rentile/`. The repository commits a dependency-free static site under `docs/` using the same GitHub Pages publishing arrangement as the author's Dependables repository; Rentile does not add a deployment workflow or repository-level custom-domain file.

## Flagged ambiguities

- "Label" was used to mean a place name specifically. Resolved for `0.6.0`: **Label** is every visible text-bearing vector symbol layer admitted by the compatibility profile. Place, road, point-of-interest, water, terrain, protected-area, and other symbol text all belong to the same public closure; raster-baked text and non-vector annotations do not.
- Point placement is not assumed. A **Label Candidate** says whether it is point, line, or line-center placed, carries the geographic source line for line modes, and carries the selected tangent and repeat spacing. The consumer projects that geometry and owns final screen-space placement.
- A **Paired Icon** is part of the same candidate rather than a second, disconnected icon layer. Its sprite geometry, paint, collision intent, alignment, text-fit inputs, and anchor on the final fitted box travel with the Label so one consumer decision can fold both together. The consumer resolves its sprite imagery by name; Rentile does not add a public sprite atlas.
- A **Label Candidate Batch** is not a **Prepared Batch**, and labels still cannot be folded into `prepareBatch`. The reason was once stated too strongly: which Glyph Ranges a tile set needs depends on decoded feature properties, so a **Glyph Closure** cannot be frozen before **Label Tile** acquisition — but it can be frozen after it, which is what a **Label Candidate Plan** holds. The closure is therefore frozen in two stages rather than not at all. A Glyph Closure is still not a **Resource Closure**: it is complete and immutable in the same way, but it is what one Label Candidate Batch needs, not what a batch of Output Tiles needs. See [ADR 0028](docs/adr/0028-freeze-the-glyph-closure-in-a-label-candidate-plan.md).
- Three label keys serve three distinct questions and none substitutes for another: a request key answers "must I fetch?" before any network, a **Label Candidate Batch** content key answers "are my cached candidates still valid?" after acquisition, and the glyph atlas content key answers "must I re-upload the texture?". Candidate layout or public-field changes bump both candidate request and content semantics; glyph pixels alone govern the atlas key.
- Output Tile request and content keys have their own renderer-semantics markers. Any change that can alter PNG pixels for identical style, tile, options, and resources must bump both markers so caller-owned caches cannot serve output from the previous renderer.
- A **Repaired Layer** and an author-declared icon layer look alike in a style document but do not fail alike: the first degrades with a diagnostic, the second fails loudly. `text-optional: true` marks the author's intent and therefore selects the strict path. See [ADR 0026](docs/adr/0026-repaired-layers-degrade-and-author-intended-layers-fail.md).
- "Output pixels" and "Style Pixels" were used interchangeably, and the code had it both ways: MVT
  geometry was mapped through `outputSizePx` while every evaluated width, offset, blur, pattern
  period and collision box was applied as a raw output pixel. The two agree only at 512. Resolved
  by [ADR 0030](docs/adr/0030-scale-the-output-tile-by-its-pixel-ratio.md): the draw path works in
  Style Pixels alone and `outputSizePx` reaches it only as a canvas transform, which also settled
  two unit mismatches that had followed from the ambiguity — `symbol-spacing` compared against
  output-pixel arc length, and a collision box whose extent and centre were in different units.
- Rentile prepares Labels but does not draw them. The host routes label acquisition through Rentile, then owns projection, collision, occlusion, and drawing above the Output Tile texture.
