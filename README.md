# Rentile

[Public Maven repository](https://maven.rohittp.com)

Rentile is a headless Kotlin Multiplatform basemap tile rasterizer. It accepts a supported map style and north-up XYZ tile identities, performs bounded local CPU rendering, and returns encoded PNG bytes without a UI view or platform render loop.

Rentile is published to the public repository at `https://maven.rohittp.com`. `VERSION_NAME` in the root `gradle.properties` is the sole release-version source. Releases are published by manually dispatching the `Build and Publish` workflow and cannot overwrite an existing coordinate.

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
./gradlew :kmp:checkKotlinAbi
./gradlew :kmp:testAndroidHostTest
./gradlew :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
./gradlew :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64
./gradlew :kmp:publishAllPublicationsToLocalTestRepository
./gradlew -p consumer-smoke compileAndroidMain compileKotlinIosArm64 compileKotlinIosSimulatorArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
```

Publish a release after its local and consumer gates pass:

```text
gh workflow run publish.yml --repo rohittp0/rentile --ref main -f modules=kmp
gh run list --repo rohittp0/rentile --workflow publish.yml --limit 1
gh run watch RUN_ID --repo rohittp0/rentile --exit-status
```

Architecture decisions and the evolving contract are in [`docs/`](docs/). Public documentation is prepared for `https://rohittp.com/rentile/`.

The rolling corpus is discovered from the public paginated map catalog and checked against a credential-free Coverage Manifest. See [`compatibility/README.md`](compatibility/README.md) for local and workflow usage.

### Static documentation version convention

Release versions must not be hardcoded in HTML. Every displayed Rentile release uses
`data-maven-version="kmp"`, and every applicable page loads the shared `docs/versions.js` script.
The browser reads `<versioning><release>` directly from
`https://maven.rohittp.com/com/rohittp/rentile/kmp/maven-metadata.xml`; it keeps the readable
`latest` fallback if metadata cannot be loaded. The R2 CORS origin is `https://rohittp.com`.
Publishing a new release requires no documentation commit or version-sync automation.

## Failure contract

Rentile raises typed `RentileException` subclasses with stable error codes, pipeline stages, redacted diagnostics, and affected tile identities. It does not retry, fall back, or return a partial output batch. `CancellationException` is propagated unchanged so callers retain control of cancellation and priority. Raw-resource cache entries completed for other tiles are not rolled back when a later tile fails.

Messages and causes from injected transport/store adapters are not forwarded because they may contain signed URLs or secret-bearing paths. Record adapter-specific failures in redacted form inside the adapter, and use Rentile's typed status, retry delay, resource class, stage, and affected tiles for recovery decisions.

## License

Rentile is licensed under Apache-2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for dependency and adapted-code notices.
