# Rentile

Rentile provides the vocabulary for a headless basemap-tile rendering dependency used by Travel Animator and other future consumers.

## Language

**Rentile**:
The `com.rohittp.rentile` Kotlin Multiplatform dependency that rasterizes the approved style profile into encoded PNG XYZ basemap tiles without a UI or platform render loop.
_Avoid_: Basemap Rasterizer, KML renderer

**Output Tile**:
An encoded PNG tile produced by Rentile for a requested XYZ coordinate.
_Avoid_: Rendered provider tile

**Source Tile**:
A vector, raster, or elevation tile consumed as input while producing one or more output tiles.
_Avoid_: Output tile

**Raw Resource**:
The exact encoded style, source metadata, source tile, sprite, or GeoJSON bytes acquired before compilation or decoding.
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

**Profile-Complete Rendering**:
Successful rendering of every current rolling-corpus style at every supported output zoom after applying the compatibility profile's deliberate transformations and exclusions.
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

The public consumer coordinate is `com.rohittp.rentile:kmp`, with `VERSION_NAME` in the root `gradle.properties` as its sole version source. Development began at `0.1.0-SNAPSHOT`; `0.1.0` through `0.1.3` are released on Maven Central. Maven Central publishing runs when that value changes to a non-snapshot version on `main` or through an explicit workflow dispatch of a non-snapshot version — so **the version bump must be the last commit of a release**, or the publish fires against an incomplete tree. Snapshot versions remain local-repository-only. Both Central paths require the exact version to pass local-repository resolution and runtime checks in clean Android, iOS, and Linux consumers first; a GitHub Release is not required, and an ordinary push without a version change does not publish.

Rentile is licensed under Apache-2.0. Published artifacts also carry a maintained third-party notices inventory for dependencies and copied or adapted upstream code.

Public documentation and the Maven POM project URL use `https://rohittp.com/rentile/`. The repository commits a dependency-free static site under `docs/` using the same GitHub Pages publishing arrangement as the author's Dependables repository; Rentile does not add a deployment workflow or repository-level custom-domain file.
