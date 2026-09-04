# Integrating Rentile into Travel Animator

This guide integrates the published Rentile artifact into:

```text
/Users/rohittp/Data/Lascade/travel-animator-android
```

**The `0.1.3` integration is done.** Travel Animator currently consumes that historical release from
Maven Central. Rentile's newer releases come from `https://maven.rohittp.com`, so add the shared
repository before raising `rentileVersion` beyond `0.1.3`. The guide is kept as the record of *why*
the wiring looks the way it does — the native-runtime owner, the host adapters, the caller-owned PNG
caching and the verification list below are all still current. Do not re-run unrelated setup steps
against a working tree.

`0.6.0` and `0.7.0` are each a deliberate breaking minor on the still-prerelease API. Before raising
a consumer from `0.5.x`, follow the complete [0.6.0 migration ledger](docs/migrations/0.6.0.md): rebuild
against the changed label data classes and discard candidate and rendered-output cache entries made
with the old semantic keys. Before raising one from `0.6.x`, follow the
[0.7.0 migration ledger](docs/migrations/0.7.0.md); it appends decoded texels to `ValidatedDemTile`,
so consumers recompile but discard no cache entries. The source declaration is not proof of
publication; resolve the exact coordinate from the public repository before changing Travel Animator.

## The published artifact

One consumer coordinate, resolved from Rentile's public Maven repository for new releases:

```text
com.rohittp.rentile:kmp
```

Do not depend on the target-specific coordinates (`kmp-android`, `kmp-iosarm64`,
`kmp-iossimulatorarm64`, `kmp-linuxx64`, `kmp-linuxarm64`). Gradle selects them from the single
`com.rohittp.rentile:kmp` declaration through the root KMP metadata.

Rentile's own source is at `/Users/rohittp/Data/Other/rentile`, needed only when changing Rentile
itself.

## 1. Repositories

Travel Animator centralizes repositories in `settings.gradle.kts` and rejects project-level
repositories (`FAIL_ON_PROJECT_REPOS`). Three are required for a current Rentile release:

- `https://maven.rohittp.com` — Rentile itself; no consumer credentials are required.
- `mavenCentral()` — Kotlin and other ordinary dependencies.
- `https://maven.pkg.jetbrains.space/public/p/compose/dev` — `org.jetbrains.skiko:skiko`'s
  `androidJvm` variant redirects to the `skiko-android` artifact, which is published only there.

Add `maven("https://maven.rohittp.com")` before `mavenCentral()` inside
`dependencyResolutionManagement.repositories`. Rentile is an ordinary KMP coordinate, not a Gradle
plugin, so this integration does not require a matching `pluginManagement` entry.

**There is no `mavenLocal()`, and adding one is a regression.** An earlier revision of this guide
told you to add a Rentile-scoped `mavenLocal()` because the only artifact was a local
`0.1.0-SNAPSHOT`. Released coordinates now come from the shared public repository, so that step adds
a repository that can shadow a real dependency.

### Developing Rentile against Travel Animator

Only when you are changing Rentile itself and want to test it here before publishing:

```bash
cd /Users/rohittp/Data/Other/rentile
./gradlew publishToMavenLocal          # publishes VERSION_NAME as-is
```

Then add a Rentile-scoped `mavenLocal()` ahead of the remote repositories, point `rentileVersion` at
the local version, and build with `--refresh-dependencies`. **Revert both before committing.** A
snapshot version never reaches the public R2 repository (ADR 0018), so a committed snapshot dependency breaks
every other checkout and CI.

## 2. Add the one common KMP dependency

In `shared/build.gradle.kts`, add a version next to the other self-contained shared-module versions:

```kotlin
private val rentileVersion = "0.1.3"
```

Then add Rentile to `commonMain.dependencies`:

```kotlin
implementation("com.rohittp.rentile:kmp:$rentileVersion")
```

Do not add `kmp-android`, `kmp-iosarm64`, or any other target coordinate directly.

## 3. Preserve one Skiko native-runtime owner

The published Rentile Android AAR contains:

```text
jni/arm64-v8a/libskiko-android-arm64.so
jni/x86_64/libskiko-android-x64.so
```

Travel Animator currently extracts and packages the same Skiko 0.148.2 native JARs from `shared/build.gradle.kts`. Leaving both mechanisms active risks duplicate native entries during APK packaging.

When adding Rentile, remove Travel Animator's native-packaging mechanism from `shared/build.gradle.kts`:

1. Remove `UnpackSkikoAndroidNatives` and its task-only imports.
2. Remove the `skikoAndroidRuntimeArm64` and `skikoAndroidRuntimeX64` configurations.
3. Remove the two native-runtime dependencies and `unpackSkikoAndroidNatives` task.
4. Remove the `KotlinMultiplatformAndroidComponentsExtension` block that registers the generated JNI directory.

Keep Travel Animator's direct:

```kotlin
implementation("org.jetbrains.skiko:skiko:0.148.2")
```

Its own source still compiles against Skiko; Rentile becomes the sole Android native-binary packager. Keep the existing host-test AWT runtime dependency because JVM tests still need a host-native Skiko runtime.

After assembling an APK, verify that each supported ABI contains exactly one matching Skiko library:

```bash
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'lib/.*/libskiko.*\.so'
```

## 4. Dependency-version compatibility

The current projects already use matching risky dependencies:

| Dependency | Rentile | Travel Animator |
|---|---:|---:|
| Kotlin | 2.3.21 | 2.3.21 |
| kotlinx-coroutines | 1.11.0 | 1.11.0 |
| kotlinx-serialization-json | 1.11.0 | 1.11.0 |
| Okio | 3.18.1 | 3.18.1 |
| Wire runtime | 6.4.5 | 6.4.5 |
| Skiko | 0.148.2 | 0.148.2 |

Rentile publishes strict constraints for Wire and Skiko because mixing those versions can create generated-code incompatibility or native-runtime conflicts. Other transitive modules use normal Gradle conflict resolution.

If either strict version later differs, align the projects deliberately. Do not solve a Wire or Skiko conflict by excluding Rentile's dependency, forcing two Skiko runtimes into the app, or adding `pickFirst` packaging rules. Prefer upgrading Rentile and the consumer together and rerunning all platform consumers.

Useful checks after adding the dependency are:

```bash
./gradlew :shared:resolvableConfigurations
./gradlew :shared:dependencyInsight --dependency skiko --configuration <android-runtime-configuration>
./gradlew :shared:dependencyInsight --dependency wire-runtime --configuration <android-runtime-configuration>
./gradlew :shared:dependencyInsight --dependency kmp --configuration <common-or-android-configuration>
```

Use a configuration name printed by `resolvableConfigurations`; KMP/AGP configuration names can change between plugin versions.

## 5. Add host adapters in the shared module

Rentile intentionally does not publish Ktor, OkHttp, Darwin, or a filesystem implementation through its public API. Travel Animator must supply:

- A `ResourceTransport` backed by its existing HTTP stack.
- A persistent `RawResourceStore` in a dedicated `rentile/raw` namespace.
- Optional metrics and diagnostics sinks.
- A `MapSessionProvider` only for configured provider origins.
- A `CredentialProvider` only when a required resource has no credential in the style or its resolved URLs.

Do not adapt the current output `TileClient` directly into `RawResourceStore`. Rentile's raw cache stores styles, TileJSON, MVT, raster, DEM, sprite, and GeoJSON bytes with validators and content digests. Keep it separate from caller-owned rendered PNG caching.

### Resource transport requirements

The adapter must perform one HTTP exchange per `TransportRequest`:

- Disable automatic redirects. Rentile validates and follows redirects itself.
- Use `GET` and copy only `ifNoneMatch`, `ifModifiedSince`, and `accept` request metadata.
- Return the decompressed body.
- Enforce `request.maxResponseBytes` against decompressed bytes, preferably while streaming.
- Populate only the allowlisted `TransportResponseMetadata` fields.
- Never log `request.url`; its query may carry a key or session ID.
- Propagate `CancellationException` unchanged.
- Give adapter errors redacted messages without URLs or response bodies.

Android can use the existing Ktor OkHttp engine. Runtime iOS remote-style support also requires replacing the current throwing `SessionHttpClient.ios.kt` implementation with a real Darwin-backed client. A build-only dependency smoke test does not require that runtime change.

### Raw resource store requirements

Implement `RawResourceStore` over the existing Okio filesystem primitives with these rules:

- Override `metadata(key)` as a header-only lookup. It answers "is this already cached?", which
  warming asks for every resource of every warmed tile; the inherited default falls back to `read`
  and therefore pays for the whole payload plus its integrity hash on a warm session.
- Hash `RawResourceKey.stableId` before using it as a path component.
- Use a dedicated namespace such as `<cacheDir>/rentile/raw`.
- Persist bytes, `contentDigest`, and `RawResourceMetadata` together.
- Write to a unique sibling temporary file and atomically replace the complete entry.
- Treat missing or corrupt entries as misses and remove them.
- Keep credentials, signed URLs, API keys, and `mtsid` out of paths and metadata.
- Revalidate cross-process behavior because an in-memory mutex does not coordinate the main and export processes.

Rentile does not cache final PNG output. Store a returned `RenderedTile.pngBytes` through Travel Animator's caller-owned output cache after a successful call.

## 6. Create one long-lived rasterizer per render process

Create Rentile alongside the process/session rendering owner, not once per tile:

```kotlin
val rasterizer = Rentile.create(
    RentileConfiguration(
        transport = travelAnimatorTransport,
        rawResourceStore = travelAnimatorRawResourceStore,
        sessionProvider = mapSessionProvider,
        metricsSink = MetricsSink { metric ->
            // Record only typed names, counts, resource classes, and safe tags.
        },
        diagnosticSink = DiagnosticSink { diagnostic ->
            // Diagnostic fields are sanitized; still avoid appending request URLs.
        },
        executionPolicy = ExecutionPolicy(
            maxConcurrentExchanges = 8,
            maxConcurrentExchangesPerOrigin = 6,
            maxConcurrentDecodes = 2,
            maxConcurrentMetatileWorkers = 1,
        ),
    ),
)
```

The execution policy controls bounded internal fetch/decode throughput. Travel Animator remains responsible for request priority, which tiles are submitted, and cancellation of its coroutine jobs.

Prepare the style once, then render caller-selected XYZ tiles:

```kotlin
val preparedStyle = rasterizer.prepare(
    StyleInput.Remote(styleUrl),
    CompatibilityPolicy.RentileV1,
)

val result = rasterizer.render(
    style = preparedStyle,
    tiles = listOf(TileId(z, x, y)),
    options = RenderOptions(outputSizePx = 512),
)

for (tile in result.tiles) {
    require(tile.pngBytes.isNotEmpty())
    callerOutputStore.write(tile.id, tile.contentKey, tile.pngBytes)
}
```

`outputSizePx` accepts 256, 512, 1024 and 2048, and it is a device pixel ratio rather than a zoom
shift: the style is evaluated at the tile's own zoom whatever the size, and `outputSizePx / 512`
scales every pixel-valued style property. One coordinate therefore renders the same features, the
same labels and the same relative ink at every size, only sharper. A consumer selecting basemap
zoom by `log2(512 / tileSize)` on a high-density screen can raise `outputSizePx` to drop that zoom
and cover a view with a sixteenth of the tiles at unchanged sharpness. Label geometry is in style
pixels at ratio one and carries no output size, so apply the same ratio to it when compositing
labels over a tile rendered above 512. See
[ADR 0030](docs/adr/0030-scale-the-output-tile-by-its-pixel-ratio.md) for the cost curve and the
limits.

For deterministic export, acquire resources first and draw afterward:

```kotlin
val preparedBatch = rasterizer.prepareBatch(preparedStyle, requestedTiles)
try {
    // No network occurs during this call.
    val result = rasterizer.render(preparedBatch)
    result.tiles.forEach { tile ->
        callerOutputStore.write(tile.id, tile.contentKey, tile.pngBytes)
    }
} finally {
    preparedBatch.close()
}
```

On process/session shutdown:

```kotlin
rasterizer.close() // Prompt, non-blocking, cancels active work.
rasterizer.awaitClosed() // Suspend until native and secret state is released.
```

Call `awaitClosed()` from a suspendable cleanup path. Do not block the main thread waiting for it.

## 7. Credentials and provider sessions

Start with `StyleInput.Remote(styleUrl)` and no standalone key input. Rentile first uses credentials already present in the style URL or resolved style resources and removes them from cache identity and diagnostics.

Add a `CredentialProvider` only if preparation identifies a required exact HTTPS origin and query-parameter name whose credential is genuinely absent. Do not globally append the app's key to every request.

The current SDK interceptor does not automatically cover Rentile's host-provided transport. If the host supplies `mtsid`, expose the same long-lived session through `MapSessionProvider` only for explicitly allowed MapTiler HTTPS origins. Do not create a session per tile, operation, retry, or process request, and do not infer billing classification from a successful response.

## 8. Cancellation and errors

Each Rentile operation is cancellable. Catch cancellation before Rentile's typed failures:

```kotlin
try {
    val result = rasterizer.render(preparedStyle, requestedTiles)
    result.tiles.forEach { persistOutput(it) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: ResourceAcquisitionException) {
    // Caller decides whether and when to retry from statusCode/retryAfterMillis.
    handleAcquisitionFailure(failure)
} catch (failure: RentileException) {
    // Use code, stage, diagnostics, and affectedTiles for the caller's policy.
    handleRenderFailure(failure)
}
```

Rentile does not retry, prioritize, fall back, or return a partial output batch. A failed operation can still leave successfully completed raw-resource cache entries from other tiles intact. If the caller needs independent per-tile failure handling, schedule separate cancellable render operations and persist each successful PNG before starting dependent work.

Do not log transport/store exception causes, full URLs, query strings, or response bodies. Use the typed `RentileException` fields.

## 9. Wire Rentile before the existing texture boundary

The integration direction is:

```text
existing style selection
  -> Rentile style preparation
  -> caller-selected XYZ render jobs
  -> returned PNG bytes and content keys
  -> caller-owned output cache
  -> existing plane/globe texture consumer
```

Keep Rentile's raw cache and Travel Animator's output PNG cache in distinct namespaces. Keep the current remote-rendered path available as a separately keyed fallback during rollout.

Rentile does not draw labels and never will, but it prepares candidates for every visible
text-bearing vector symbol layer. A host that wants them calls `acquireLabelCandidates` and performs
projection, cross-tile collision, occlusion, and drawing on top. Point, line, and line-center
candidates carry the geometry needed for that placement. When a label layer also declares an icon,
the candidate carries that paired icon's sprite geometry, paint, collision, alignment, and text-fit
inputs so the host places text and icon as one symbol. A host that must know its Glyph Range URLs
before they are fetched calls `planLabelCandidates` first and reads them from the plan. Do not route
atmosphere, route overlays, vehicles, globe/plane mapping, or UI camera state through Rentile.

## 10. Local consumer verification

After every republish of the same snapshot, force Travel Animator to refresh changing-module metadata:

```bash
cd /Users/rohittp/Data/Lascade/travel-animator-android
./gradlew --refresh-dependencies :shared:compileAndroidMain
./gradlew :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64
./gradlew :app:assembleDebug
```

Then verify:

1. Gradle selected `com.rohittp.rentile:kmp` at `rentileVersion` from the intended public or local test repository, through root KMP metadata.
2. Wire resolves once at 6.4.5.
3. Skiko resolves once at 0.148.2.
4. The APK contains one Rentile-provided Skiko library for each supported Android ABI.
5. A renderer can be created and closed off the Android main thread.
6. A synthetic or known raster style returns a non-empty PNG.
7. Cancelling a fetch or render job promptly propagates `CancellationException`.
8. A second identical render uses the raw cache; output caching is tested separately in the host.
9. No credential or session value appears in logs, cache paths, exceptions, or content keys.
10. The existing remote path still works after a local-render failure.

The correction for the previously dark Terrain-style zoom-0 output shipped in `0.1.0`. After any later Rentile change, publish a new immutable version (or `publishToMavenLocal` while iterating) and rerun the consumer with `--refresh-dependencies` before comparing images. Never rebuild a released coordinate: R2 publication rejects an existing primary POM.

## 11. Commit boundary

The Travel Animator integration should be a separate authorized change. Do not copy Rentile source into the application, publish a target-specific coordinate, or modify Rentile's artifact contents from the consumer build. The application change should consist of the repository/dependency declaration, one native-runtime owner, thin host adapters, lifecycle wiring, caller-owned PNG caching, and guarded rollout selection.
