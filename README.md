# Rentile

[Public Maven repository](https://maven.rohittp.com)

Rentile is a headless Kotlin Multiplatform basemap tile rasterizer. It accepts a supported map style and north-up XYZ tile identities, performs bounded local CPU rendering, and returns encoded PNG bytes without a UI view or platform render loop.

Rentile is published to the public repository at `https://maven.rohittp.com`. Every push to `main` that changes anything outside documentation publishes a new release, taking the highest version already public and advancing its patch component. Set `VERSION_NAME` in the root `gradle.properties` above every published version to cut a deliberate minor or major release instead. Releases cannot overwrite an existing coordinate.

`0.11.3` is published, whole on all eight coordinates, and is the newest version on the public line.
It decodes a raster or DEM source tile once for the draws of a prepared batch that read it instead of
once per draw, and changes no public signature, no key and no pixel, so it is a drop-in for `0.11.2`.
See the [0.11.3 migration guide](docs/migrations/0.11.3.md). Consumers upgrading from `0.11.0` or
`0.11.1` should also read the [0.11.2 guide](docs/migrations/0.11.2.md), which carries the one change
an OkHttp-backed transport has to make for Rentile's per-origin budget to be spendable.

`0.11.0` and `0.11.1` are both published *without* a `kmp-macosarm64` coordinate, so a consumer with
that target resolves a 404 from either. A released coordinate is immutable; move to `0.11.2` or
later. See the [0.11.1 migration ledger](docs/migrations/0.11.1.md).

## Targets

- Android: `arm64-v8a` and `x86_64`
- JVM: `jvm`
- Apple: `iosArm64`, `iosSimulatorArm64`, and `macosArm64`
- Linux: `linuxX64` and `linuxArm64`

Apple support is Apple Silicon only; Rentile does not publish `iosX64` or `macosX64`. See [ADR 0022](docs/adr/0022-support-apple-silicon-macs-only.md).

## Dependency

Rentile publishes one consumer coordinate:

```kotlin
commonMain.dependencies {
    implementation("com.rohittp.rentile:kmp:<version>")
}
```

Add the shared public repository before the standard repositories. No repository credentials are required. Rentile is an ordinary KMP dependency, so it resolves through `dependencyResolutionManagement`; this project does not publish a Gradle plugin. Skiko's platform artifacts still come from JetBrains' public Compose repository.

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://maven.rohittp.com")
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
            content { includeGroup("org.jetbrains.skiko") }
        }
    }
}
```

## Build and verify

```text
python3 tools/check_coverage_manifest.py compatibility/rentile-v1-coverage.json
python3 tools/check_corpus_fidelity_policy.py compatibility/rentile-v1-coverage.json
./gradlew :kmp:checkKotlinAbi
./gradlew :kmp:testAndroidHostTest :kmp:jvmTest :kmp:macosArm64Test
./gradlew :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
./gradlew :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64
./gradlew :kmp:publishAllPublicationsToLocalTestRepository
./gradlew -p consumer-smoke compileAndroidMain compileKotlinJvm compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
```

Merging a non-documentation change to `main` starts one publication automatically. Do not also
dispatch the workflow manually for the same commit: a second run can legitimately resolve the next
patch version. Use manual dispatch only when no push-triggered run exists. To dispatch deliberately,
or to watch the run a push started:

```text
gh workflow run publish.yml --repo rohittp0/rentile --ref main
gh run list --repo rohittp0/rentile --workflow publish.yml --limit 1
gh run watch RUN_ID --repo rohittp0/rentile --exit-status
```

Architecture decisions and the evolving contract are in [`docs/`](docs/). Public documentation is prepared for `https://rohittp.com/rentile/`.

The rolling corpus is discovered from the public paginated map catalog and checked against a credential-free Coverage Manifest. See [`compatibility/README.md`](compatibility/README.md) for local and workflow usage.

## Label candidates

Rentile does not bake text into Output Tiles. It admits every visible text-bearing vector symbol
layer to descriptor and candidate compilation, including point, line, and line-center labels, and
carries each successfully resolved paired icon so the viewport-owning consumer can place and
collide the complete symbol. Unsupported scripts, constructs, sources, and feature values are
excluded with stable diagnostics instead of being represented as plausible but incorrect labels.
The public API exposes
glyph geometry, source-line geometry, feature-resolved paint, explicit overlap, alignment, and
z-order enums, and icon text-fit inputs. `LabelIconRef` now carries a `LabelIconAnchor` for the final
fitted box instead of the removed pre-fit `anchorOffsetX/Y` shifts. The consumer resolves sprite
imagery by `LabelIconRef.imageName`, as in 0.5.x, and still owns projection, shaping limitations,
cross-tile collision, depth, and final drawing. Rentile does not publish a second sprite atlas
through the label batch.
See [ADR 0024](docs/adr/0024-label-placement-belongs-to-the-consumer.md) and the
[0.6.0 migration guide](docs/migrations/0.6.0.md).

## Terrain tiles

`acquireTerrainTiles` returns the exact encoded DEM bytes **and** the pixels the decode that
validated them produced. `ValidatedDemTile.texels` is canonical 8-bit RGBA - fixed red, green, blue,
alpha byte order, rows top-down and tightly packed at `width * 4` bytes, never premultiplied and
never colour-converted - so a consumer reads elevation without owning a decoder for whichever
container the provider serves, which in a real style corpus is usually WebP rather than PNG.

Those are packed channel values, not metres: apply the tile's own `encoding` to them. `bytes`
remains the exact resource and remains the right value to hash for cache identity. Nothing but
terrain acquisition retains decoded pixels, and nothing is decoded twice.
See [ADR 0029](docs/adr/0029-expose-decoded-dem-texels.md) and the
[0.7.0 migration guide](docs/migrations/0.7.0.md).

### Static documentation version convention

Release versions must not be hardcoded in HTML. Every displayed Rentile release uses
`data-maven-version="kmp"`, and every applicable page loads the shared `docs/versions.js` script.
The browser reads `<versioning><release>` directly from
`https://maven.rohittp.com/com/rohittp/rentile/kmp/maven-metadata.xml`; it keeps the readable
`latest` fallback if metadata cannot be loaded. The R2 CORS origin is `https://rohittp.com`.
Publishing a new release requires no documentation commit or version-sync automation.

## Failure contract

Rentile raises typed `RentileException` subclasses with stable error codes, pipeline stages, redacted diagnostics, and affected tile identities. It does not retry, fall back, or return a partial output batch. `CancellationException` is propagated unchanged so callers retain control of cancellation. Callers also mark each render `URGENT` or `NORMAL` through `RenderPriority`, which decides only which waiting request the next freed metatile worker serves. Raw-resource cache entries completed for other tiles are not rolled back when a later tile fails.

Messages and causes from injected transport/store adapters are not forwarded because they may contain signed URLs or secret-bearing paths. Record adapter-specific failures in redacted form inside the adapter, and use Rentile's typed status, retry delay, resource class, stage, and affected tiles for recovery decisions.

## License

Rentile is licensed under Apache-2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for dependency and adapted-code notices.
