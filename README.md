# Rentile

Rentile is a headless Kotlin Multiplatform basemap tile rasterizer. It accepts a supported map style and north-up XYZ tile identities, performs bounded local CPU rendering, and returns encoded PNG bytes without a UI view or platform render loop.

The implementation is under active development. The first Maven Central release will be `0.1.0`; `0.1.0-SNAPSHOT` is currently available only through local publication.

## Targets

- Android: `arm64-v8a` and `x86_64`
- Apple: `iosArm64` and `iosSimulatorArm64`
- Linux: `linuxX64` and `linuxArm64`

## Dependency

Rentile publishes one consumer coordinate:

```kotlin
commonMain.dependencies {
    implementation("com.rohittp.rentile:kmp:0.1.0")
}
```

Skiko's platform artifacts are resolved from JetBrains' public Compose repository, so consumers also need this narrow repository declaration:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
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

Architecture decisions and the evolving contract are in [`docs/`](docs/). Public documentation is prepared for `https://rohittp.com/rentile/`.

The rolling corpus is discovered from the public paginated map catalog and checked against a credential-free Coverage Manifest. See [`compatibility/README.md`](compatibility/README.md) for local and workflow usage.

## Failure contract

Rentile raises typed `RentileException` subclasses with stable error codes, pipeline stages, redacted diagnostics, and affected tile identities. It does not retry, fall back, or return a partial output batch. `CancellationException` is propagated unchanged so callers retain control of cancellation and priority. Raw-resource cache entries completed for other tiles are not rolled back when a later tile fails.

Messages and causes from injected transport/store adapters are not forwarded because they may contain signed URLs or secret-bearing paths. Record adapter-specific failures in redacted form inside the adapter, and use Rentile's typed status, retry delay, resource class, stage, and affected tiles for recovery decisions.

## License

Rentile is licensed under Apache-2.0. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for dependency and adapted-code notices.
