# Label Candidates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a consumer draw style-correct place-name labels from Rentile without decoding MVT, evaluating style expressions, or fetching glyphs itself.

**Architecture:** A new `acquireLabelCandidates` acquisition, shaped like the existing `acquireLabelTiles` and `acquireTerrainTiles`, returns decoded and laid-out label geometry plus the glyph atlas it references. Rentile decides *what*; the consumer decides *where*. Glyph ranges become a resource class acquired through the injected transport and store. Layout comes from signed-distance-field glyph metrics, never from a platform font stack, so geometry is identical on every published target.

**Tech Stack:** Kotlin Multiplatform, Wire (protobuf), Skiko/Skia, Okio, kotlinx-serialization, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-08-18-label-candidates-design.md`

## Global Constraints

- **Additive only.** No change to `prepare`, `prepareBatch`, `render`, `RenderedTile`, or existing tile output. A consumer that never calls the new API must see no behaviour change, no new required configuration, and no new network traffic.
- **Release 1 must be published first.** Satisfied: it shipped as `0.2.0` on 2026-08-19. Read [ADR 0026](../../adr/0026-repaired-layers-degrade-and-author-intended-layers-fail.md) before starting — it governs how a layer retained by repair fails, and label work touches the same symbol-layer classification.
- **Scope is place names only.** `PLACE_NAME_SOURCE_LAYERS` in `CompiledStyle.kt:134` remains the gate. Road, POI, water, terrain and protected-area naming stays out.
- **No platform text shaping.** Do not import `org.jetbrains.skia.Font`, `Typeface`, `TextLine`, `TextBlob`, `shaper.*` or `paragraph.*`. ADR 0025 explains why they are deliberately unused.
- **Every byte through the injected adapters.** `ResourceTransport` and `RawResourceStore` only. No new dependency, no direct HTTP.
- **Redaction.** Glyph URLs use `withRedactedAuthenticationQuery().sha256Hex()` for all identity, exactly as `SpriteResourceAcquirer.acquireRaw` does. No URL, query string, or credential in any digest, diagnostic, metric, exception, or Corpus Report.
- **Cancellation.** `CancellationException` is caught and rethrown unwrapped before any generic `Throwable` handler, as `SpriteResourceAcquirer.kt:110-112` does.
- **Determinism.** Two runs over the same style and tiles must produce identical candidates in identical order. Content keys derive from the glyph set and content digests, never from encoded PNG bytes — ADR 0010 measures determinism on decoded pixels, not compressed bytes.
- **ABI.** `./gradlew :kmp:checkKotlinAbi` gates every release. Regenerate the dump with `./gradlew :kmp:updateKotlinAbi` only in the tasks that add public API, and review the diff.
- **Release is a minor.** Set `VERSION_NAME` in the root `gradle.properties` above every published version. With `0.2.0` published that is `0.3.0`; the default patch advance will not cut a minor (ADR 0023).

---

### Task 1: Public enums and limits

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/Resources.kt:4-13`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/Diagnostics.kt:22-37`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt:191-222`
- Modify: `kmp/api/jvm/kmp.api`, `kmp/api/kmp.klib.api` (regenerated)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt`

**Interfaces:**
- Produces: `ResourceClass.GLYPH_RANGE`; `DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED`, `DiagnosticCode.UNSUPPORTED_TEXT_CONSTRUCT`, `DiagnosticCode.GLYPH_RANGE_UNAVAILABLE`, `DiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED`; `ResourceLimits.maxGlyphRangeBytes: Long`, `ResourceLimits.maxGlyphRangesPerBatch: Int`.

Enum entries are appended, never inserted, because ordinal position is observable through the JVM ABI dump.

- [ ] **Step 1: Write the failing test**

Add to `ApiContractTest.kt`:

```kotlin
    @Test
    fun glyphLimitsAreValidatedAndDefaulted() {
        val limits = ResourceLimits()

        assertEquals(1L * 1024L * 1024L, limits.maxGlyphRangeBytes)
        assertEquals(64, limits.maxGlyphRangesPerBatch)
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maxGlyphRangeBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ResourceLimits(maxGlyphRangesPerBatch = 0) }
    }

    @Test
    fun glyphRangeIsAResourceClass() {
        assertTrue(ResourceClass.entries.contains(ResourceClass.GLYPH_RANGE))
    }
```

The `maxGlyphRangesPerBatch` default of 64 is provisional. Task 13 measures a real Tokyo viewport and may change it; if it does, update this test with the measured value.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.ApiContractTest'`
Expected: FAIL to compile — `GLYPH_RANGE`, `maxGlyphRangeBytes` and `maxGlyphRangesPerBatch` do not exist.

- [ ] **Step 3: Append the resource class**

In `Resources.kt`, append to `ResourceClass` after `GEO_JSON`:

```kotlin
    GEO_JSON,
    GLYPH_RANGE,
}
```

- [ ] **Step 4: Append the diagnostic codes**

In `Diagnostics.kt`, append to `DiagnosticCode` after `UNSUPPORTED_RETAINED_CONSTRUCT`:

```kotlin
    UNSUPPORTED_RETAINED_CONSTRUCT,
    COMPLEX_SCRIPT_LABEL_EXCLUDED,
    UNSUPPORTED_TEXT_CONSTRUCT,
    GLYPH_RANGE_UNAVAILABLE,
    LINE_PLACEMENT_LABEL_EXCLUDED,
}
```

- [ ] **Step 5: Add the limits**

In `Api.kt`, add two properties to `ResourceLimits` after `maxGeoJsonBytes`, and two `require` calls in its `init` block beside the others:

```kotlin
    public val maxGlyphRangeBytes: Long = 1L * 1024L * 1024L,
    public val maxGlyphRangesPerBatch: Int = 64,
```

```kotlin
        require(maxGlyphRangeBytes > 0)
        require(maxGlyphRangesPerBatch > 0)
```

Place both properties before `maxRasterDimensionPx` so the byte limits stay grouped, and keep the `require` order matching the property order — the existing block follows that convention exactly.

- [ ] **Step 6: Run the test and confirm it passes**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.ApiContractTest'`
Expected: PASS.

- [ ] **Step 7: Regenerate and review the ABI dump**

```bash
./gradlew :kmp:updateKotlinAbi
git diff kmp/api/
```

Expected diff: two new `getMaxGlyph*` accessors plus a widened `ResourceLimits` constructor and `copy` signature, one new `ResourceClass` field, four new `DiagnosticCode` fields. Nothing else. If an unrelated symbol moved, stop and find out why before committing.

- [ ] **Step 8: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/Resources.kt \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/Diagnostics.kt \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt kmp/api/
git commit -m "feat(api): add the glyph-range resource class, limits and label diagnostics"
```

---

### Task 2: Glyph range protobuf and decoder

**Files:**
- Create: `kmp/src/commonMain/proto/glyphs.proto`
- Create: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/GlyphRangeDecoder.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/GlyphRangeDecoderTest.kt`

**Interfaces:**
- Consumes: `ResourceLimits` from Task 1. The Wire plugin is already configured for `srcDir("src/commonMain/proto")` in `kmp/build.gradle.kts:182-187`; adding a second `.proto` there needs no build change.
- Produces:
  ```kotlin
  internal data class DecodedGlyph(
      val codepoint: Int, val width: Int, val height: Int,
      val left: Int, val top: Int, val advance: Int,
      val bitmap: ByteArray,
  )
  internal object GlyphRangeDecoder {
      fun decode(bytes: ByteArray, expectedFontStack: String): List<DecodedGlyph>
  }
  ```

Domain background. A glyph range is a protobuf holding one `fontstack` whose `glyphs` are signed-distance-field bitmaps. Each glyph is rendered at a 24-pixel em with a 3-pixel buffer on every side, so a glyph declaring `width`/`height` carries a bitmap of `(width + 6) * (height + 6)` single-channel bytes. `advance` is the pen movement in the same 24-pixel em units. `left` and `top` are the bearing from the pen position to the bitmap's top-left. A glyph with no `bitmap` is whitespace: it advances the pen and draws nothing.

- [ ] **Step 1: Write the proto**

```proto
syntax = "proto2";

package com.rohittp.rentile.internal.glyph;

message glyphs {
  message fontstack {
    required string name = 1;
    required string range = 2;
    repeated glyph glyphs = 3;
  }
  repeated fontstack stacks = 1;
}

message glyph {
  required uint32 id = 1;
  optional bytes bitmap = 2;
  required uint32 width = 3;
  required uint32 height = 4;
  required sint32 left = 5;
  required sint32 top = 6;
  required uint32 advance = 7;
}
```

`proto2` with `required` matches the upstream schema this wire format comes from; `vector_tile.proto` in the same directory is also proto2. Wire generates `Glyphs`, `Glyphs.Fontstack` and `Glyph` in the declared package.

- [ ] **Step 2: Write the failing test**

```kotlin
package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlyphRangeDecoderTest {
    @Test
    fun decodesGlyphMetricsAndBitmapExtent() {
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack(
                    name = "Open Sans Regular",
                    range = "0-255",
                    glyphs = listOf(
                        Glyph(id = 65, width = 10, height = 14, left = 1, top = -12, advance = 12,
                              bitmap = okio.ByteString.of(*ByteArray((10 + 6) * (14 + 6)))),
                        Glyph(id = 32, width = 0, height = 0, left = 0, top = 0, advance = 6),
                    ),
                ),
            ),
        ).encode()

        val decoded = GlyphRangeDecoder.decode(bytes, "Open Sans Regular")

        assertEquals(2, decoded.size)
        val a = decoded.single { it.codepoint == 65 }
        assertEquals(12, a.advance)
        assertEquals(1, a.left)
        assertEquals(-12, a.top)
        assertEquals((10 + 6) * (14 + 6), a.bitmap.size)
        val space = decoded.single { it.codepoint == 32 }
        assertEquals(6, space.advance)
        assertTrue(space.bitmap.isEmpty())
    }

    @Test
    fun rejectsABitmapThatDoesNotMatchItsDeclaredExtent() {
        val bytes = Glyphs(
            stacks = listOf(
                Glyphs.Fontstack("Open Sans Regular", "0-255", listOf(
                    Glyph(id = 66, width = 10, height = 14, left = 0, top = 0, advance = 12,
                          bitmap = okio.ByteString.of(*ByteArray(4))),
                )),
            ),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "Open Sans Regular")
        }
    }

    @Test
    fun rejectsAStackThatIsNotTheOneRequested() {
        val bytes = Glyphs(
            stacks = listOf(Glyphs.Fontstack("Some Other Font", "0-255", emptyList())),
        ).encode()

        assertFailsWith<IllegalArgumentException> {
            GlyphRangeDecoder.decode(bytes, "Open Sans Regular")
        }
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.GlyphRangeDecoderTest'`
Expected: FAIL to compile — `GlyphRangeDecoder` does not exist. If `Glyphs` also fails to resolve, run `./gradlew :kmp:generateCommonMainProtos` and confirm the generated sources appear under `kmp/build/generated/source/wire/`.

- [ ] **Step 4: Write the decoder**

```kotlin
package com.rohittp.rentile.internal.glyph

/** One signed-distance-field glyph at the 24-pixel em with a 3-pixel buffer. */
internal data class DecodedGlyph(
    val codepoint: Int,
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val advance: Int,
    val bitmap: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is DecodedGlyph && codepoint == other.codepoint &&
            width == other.width && height == other.height && left == other.left &&
            top == other.top && advance == other.advance && bitmap.contentEquals(other.bitmap))

    override fun hashCode(): Int = codepoint
}

internal object GlyphRangeDecoder {
    /** Signed-distance-field glyphs carry a three-pixel buffer on every side. */
    const val BUFFER_PX: Int = 3

    /** Signed-distance-field glyphs are rendered at this em size; text-size scales from it. */
    const val EM_PX: Double = 24.0

    fun decode(bytes: ByteArray, expectedFontStack: String): List<DecodedGlyph> {
        val message = Glyphs.ADAPTER.decode(bytes)
        val stack = message.stacks.singleOrNull()
        require(stack != null) { "A glyph range must contain exactly one font stack" }
        require(stack.name == expectedFontStack) { "A glyph range declared an unexpected font stack" }
        return stack.glyphs.map { glyph ->
            val width = glyph.width.toInt()
            val height = glyph.height.toInt()
            val bitmap = glyph.bitmap?.toByteArray() ?: ByteArray(0)
            if (bitmap.isNotEmpty()) {
                val expected = (width + BUFFER_PX * 2) * (height + BUFFER_PX * 2)
                require(bitmap.size == expected) { "A glyph bitmap does not match its declared extent" }
            }
            DecodedGlyph(
                codepoint = glyph.id.toInt(),
                width = width,
                height = height,
                left = glyph.left,
                top = glyph.top,
                advance = glyph.advance.toInt(),
                bitmap = bitmap,
            )
        }.sortedBy(DecodedGlyph::codepoint)
    }
}
```

The `sortedBy` is load-bearing: atlas packing order must not depend on the provider's serialization order, or `contentKey` would vary between providers serving identical glyphs. The hand-written `equals`/`hashCode` exist because `data class` with a `ByteArray` compares by reference; `ValidatedMvtTile` has the same problem and the same fix.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.GlyphRangeDecoderTest'`
Expected: PASS, all three.

- [ ] **Step 6: Confirm the proto did not leak into public ABI**

Run: `./gradlew :kmp:checkKotlinAbi`

Expected: this **will** fail, because Wire generates public classes and `internal/mvt/Tile` is already precedent for that leak (`kmp/api/jvm/kmp.api:847`). Regenerate with `./gradlew :kmp:updateKotlinAbi` and review: the diff must contain only `internal/glyph/Glyphs*` and `internal/glyph/Glyph*` entries. Do not attempt to hide them in this task — matching the existing `internal/mvt` precedent is correct, and narrowing both is a separate cleanup.

- [ ] **Step 7: Commit**

```bash
git add kmp/src/commonMain/proto/glyphs.proto \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/GlyphRangeDecoder.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/GlyphRangeDecoderTest.kt kmp/api/
git commit -m "feat(glyph): decode signed-distance-field glyph ranges"
```

---

### Task 3: Glyph range acquirer

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/GlyphResourceAcquirer.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/GlyphResourceAcquirerTest.kt`

**Interfaces:**
- Consumes: `DecodedGlyph`, `GlyphRangeDecoder` (Task 2); `ResourceClass.GLYPH_RANGE`, `ResourceLimits.maxGlyphRangeBytes` (Task 1); the existing `RentileConfiguration`, `ResourceWorkCoordinator`, `SingleFlight`, `withRedactedAuthenticationQuery`, `sha256Hex`.
- Produces:
  ```kotlin
  internal data class AcquiredGlyphRange(
      val fontStack: String, val rangeStart: Int,
      val glyphs: List<DecodedGlyph>, val contentDigest: String,
  )
  internal class GlyphResourceAcquirer(
      configuration: RentileConfiguration, scope: CoroutineScope,
      workCoordinator: ResourceWorkCoordinator,
  ) {
      suspend fun acquire(template: String, fontStack: String, rangeStart: Int): AcquiredGlyphRange
      companion object {
          fun rangeStartFor(codepoint: Int): Int
          fun resolveUrl(template: String, fontStack: String, rangeStart: Int): String
      }
  }
  ```

Read `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/sprite/SpriteResourceAcquirer.kt` in full before starting. This class is its sibling and must match its structure: `SingleFlight` on a redacted-URL digest, cache read with digest verification, `RAW_CACHE_HIT`/`RAW_CACHE_MISS` metrics, `workCoordinator.exchange`, cancellation rethrown first, non-2xx as `ResourceAcquisitionException`, over-limit as `SafetyLimitException`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertEquals

class GlyphResourceAcquirerTest {
    @Test
    fun mapsCodepointsToTheirRangeStart() {
        assertEquals(0, GlyphResourceAcquirer.rangeStartFor('A'.code))
        assertEquals(0, GlyphResourceAcquirer.rangeStartFor(255))
        assertEquals(256, GlyphResourceAcquirer.rangeStartFor(256))
        assertEquals(19968, GlyphResourceAcquirer.rangeStartFor(0x4E2D))
    }

    @Test
    fun substitutesTheFontStackAndRangeIntoTheTemplate() {
        val url = GlyphResourceAcquirer.resolveUrl(
            template = "https://glyphs.example.test/fonts/{fontstack}/{range}.pbf?key=secret",
            fontStack = "Open Sans Regular,Noto Sans Regular",
            rangeStart = 256,
        )

        assertEquals(
            "https://glyphs.example.test/fonts/Open%20Sans%20Regular,Noto%20Sans%20Regular/256-511.pbf?key=secret",
            url,
        )
    }
}
```

`rangeStartFor(0x4E2D)` is 19968 because 0x4E2D is 20013 and 20013 / 256 * 256 = 19968. Spaces are percent-encoded because font names contain them; commas separating stack members are left literal, which is what glyph endpoints expect.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.GlyphResourceAcquirerTest'`
Expected: FAIL to compile — `GlyphResourceAcquirer` does not exist.

- [ ] **Step 3: Write the acquirer**

```kotlin
package com.rohittp.rentile.internal.glyph

internal data class AcquiredGlyphRange(
    val fontStack: String,
    val rangeStart: Int,
    val glyphs: List<DecodedGlyph>,
    val contentDigest: String,
)

internal class GlyphResourceAcquirer(
    private val configuration: RentileConfiguration,
    scope: CoroutineScope,
    private val workCoordinator: ResourceWorkCoordinator,
) {
    private val singleFlight = SingleFlight<String, AcquiredGlyphRange>(scope)

    suspend fun acquire(template: String, fontStack: String, rangeStart: Int): AcquiredGlyphRange {
        val url = resolveUrl(template, fontStack, rangeStart)
        val sanitizedId = url.withRedactedAuthenticationQuery().sha256Hex()
        return singleFlight.run(sanitizedId) {
            val bytes = acquireRaw(url, sanitizedId)
            AcquiredGlyphRange(
                fontStack = fontStack,
                rangeStart = rangeStart,
                glyphs = GlyphRangeDecoder.decode(bytes, fontStack),
                contentDigest = bytes.sha256Hex(),
            )
        }
    }

    private suspend fun acquireRaw(url: String, sanitizedId: String): ByteArray {
        val limit = configuration.resourceLimits.maxGlyphRangeBytes
        val resourceClass = ResourceClass.GLYPH_RANGE
        val key = RawResourceKey(sanitizedId, resourceClass)
        val cached = readStore(key)
        if (cached != null) {
            if (cached.bytes.size.toLong() <= limit && cached.bytes.sha256Hex() == cached.contentDigest) {
                configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_HIT, resourceClass = resourceClass))
                return cached.bytes
            }
            removeStore(key)
        }
        configuration.metricsSink.recordSafely(RentileMetric(MetricName.RAW_CACHE_MISS, resourceClass = resourceClass))
        val response = workCoordinator.exchange(url) {
            configuration.metricsSink.recordSafely(RentileMetric(MetricName.RESOURCE_REQUEST, resourceClass = resourceClass))
            try {
                configuration.transport.execute(
                    TransportRequest(
                        url = url,
                        resourceClass = resourceClass,
                        maxResponseBytes = limit,
                        metadata = TransportRequestMetadata(accept = "application/x-protobuf"),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                throw ResourceAcquisitionException(
                    message = "Glyph transport failed",
                    resourceClass = resourceClass,
                    sanitizedResourceId = sanitizedId,
                )
            }
        }
        if (response.statusCode !in 200..299) {
            throw ResourceAcquisitionException(
                message = "Glyph transport returned a non-success status",
                resourceClass = resourceClass,
                sanitizedResourceId = sanitizedId,
                statusCode = response.statusCode,
                retryAfterMillis = response.metadata.retryAfterMillis,
            )
        }
        val bytes = response.body
        if (bytes.size.toLong() > limit) {
            throw SafetyLimitException(
                message = "Glyph range exceeds its configured byte limit",
                limitName = "maxGlyphRangeBytes",
                limit = limit,
                observed = bytes.size.toLong(),
                stage = PipelineStage.RESOURCE_ACQUISITION,
            )
        }
        val digest = bytes.sha256Hex()
        writeStore(
            key,
            StoredRawResource(
                bytes = bytes,
                contentDigest = digest,
                metadata = RawResourceMetadata(
                    contentType = response.metadata.contentType,
                    etag = response.metadata.etag,
                    lastModified = response.metadata.lastModified,
                    freshUntilEpochMillis = response.metadata.expiresAtEpochMillis,
                    storedAtEpochMillis = configuration.clock.nowEpochMillis(),
                ),
            ),
        )
        configuration.metricsSink.recordSafely(
            RentileMetric(
                MetricName.RESOURCE_WIRE_BYTES,
                value = response.metadata.wireByteCount ?: bytes.size.toLong(),
                resourceClass = resourceClass,
            ),
        )
        return bytes
    }

    companion object {
        private const val RANGE_SIZE = 256

        fun rangeStartFor(codepoint: Int): Int = codepoint / RANGE_SIZE * RANGE_SIZE

        fun resolveUrl(template: String, fontStack: String, rangeStart: Int): String = template
            .replace("{fontstack}", fontStack.replace(" ", "%20"))
            .replace("{range}", "$rangeStart-${rangeStart + RANGE_SIZE - 1}")
    }
}
```

That body is `SpriteResourceAcquirer.acquireRaw` (`SpriteResourceAcquirer.kt:84-165`) with the resource class, limit, accept header and messages substituted. Two details in it are load-bearing and must not be simplified: `CancellationException` is caught and rethrown *before* the generic `Throwable` handler, so cancellation is never converted into an acquisition failure; and a cached entry whose stored bytes do not rehash to its recorded digest is removed and treated as a miss rather than trusted.

`readStore`, `removeStore` and `writeStore` are private helpers on the sprite acquirer. Either lift them into a small shared internal helper both acquirers use, or copy them — prefer lifting, since a third acquirer will want them too, but do not let that refactor grow beyond moving the three methods.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.GlyphResourceAcquirerTest'`
Expected: PASS, both.

- [ ] **Step 5: Add an acquisition test through a fake transport**

Add to the same test class. Use `InMemoryRawResourceStore` from the existing common test sources — do not write a new store.

```kotlin
    @Test
    fun acquiresOnceForConcurrentRequestsAndCachesTheBytes() = runTest {
        var requests = 0
        val payload = Glyphs(
            stacks = listOf(Glyphs.Fontstack("Open Sans Regular", "0-255", listOf(
                Glyph(id = 65, width = 0, height = 0, left = 0, top = 0, advance = 12),
            ))),
        ).encode()
        val rasterizer = Rentile.create(
            RentileConfiguration(
                transport = ResourceTransport { requests += 1; TransportResponse(200, payload) },
                rawResourceStore = InMemoryRawResourceStore(),
            ),
        )
        // Acquire the same range twice; assert requests == 1 and that both results
        // carry the same contentDigest. Drive it through the rasterizer's own
        // acquirer wiring once Task 10 exposes acquireLabelCandidates; until then,
        // construct GlyphResourceAcquirer directly with the same configuration.
        rasterizer.close()
        rasterizer.awaitClosed()
    }
```

Complete that test body against whichever construction path exists when you reach this task. If Task 10 is not yet done, construct `GlyphResourceAcquirer` directly.

- [ ] **Step 6: Run the full suite and commit**

```bash
./gradlew :kmp:jvmTest
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/GlyphResourceAcquirer.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/GlyphResourceAcquirerTest.kt
git commit -m "feat(glyph): acquire glyph ranges through the injected transport and store"
```

---

### Task 4: The `concat` expression operator

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleExpression.kt:82-106`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/style/StyleExpressionTest.kt`

**Interfaces:**
- Consumes: existing `compileNode`, `requireAtLeast`, `StyleValue.StringValue`, `StyleValue.NumberValue`, `StyleValue.BooleanValue`, `StyleValue.Null`.
- Produces: `"concat"` handled in `compileNode`'s `when`.

Measured against the rolling corpus on 2026-08-18: `concat` appears 23 times in place-name `text-field` values. It is one of only two operators those values use that Rentile does not already implement.

- [ ] **Step 1: Write the failing test**

Follow the file's existing test style for building and evaluating an expression; mirror whatever helper the `coalesce` tests use.

```kotlin
    @Test
    fun concatJoinsStringsAndStringifiesScalars() {
        assertEquals(StyleValue.StringValue("A1true"), evaluate("""["concat","A",1,true]"""))
    }

    @Test
    fun concatTreatsNullAsEmpty() {
        assertEquals(StyleValue.StringValue("AB"), evaluate("""["concat","A",["get","missing"],"B"]"""))
    }

    @Test
    fun concatDropsTheDecimalOfAWholeNumber() {
        assertEquals(StyleValue.StringValue("7"), evaluate("""["concat",7]"""))
    }
```

The third case matters because MVT numeric properties decode to `Double`, and a naive `toString()` renders a population of 7 as `7.0` inside a label.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.style.StyleExpressionTest'`
Expected: FAIL — the compiler rejects `concat` as an unsupported operator.

- [ ] **Step 3: Implement it**

Add to the `when (operator)` block in `compileNode`, beside `"coalesce"`:

```kotlin
            "concat" -> compileConcat(arguments)
```

and add the method beside `compileCoalesce`:

```kotlin
    private fun compileConcat(arguments: List<JsonElement>): StyleExpression {
        val expressions = requireAtLeast("concat", arguments, 1).map(::compileNode)
        return StyleExpression { context ->
            StyleValue.StringValue(
                expressions.joinToString("") { expression ->
                    when (val value = expression.evaluate(context)) {
                        is StyleValue.StringValue -> value.value
                        is StyleValue.NumberValue -> value.value.stringifyForText()
                        is StyleValue.BooleanValue -> value.value.toString()
                        else -> ""
                    }
                },
            )
        }
    }
```

Add the shared helper as a private extension in the same file, because Task 6 needs the identical rule when a bare `["get", ...]` produces a number:

```kotlin
    private fun Double.stringifyForText(): String =
        if (this == toLong().toDouble()) toLong().toString() else toString()
```

`StyleExpression` is a functional interface in this file; match the exact construction style the neighbouring `compile*` methods use rather than copying the lambda shape above verbatim.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.style.StyleExpressionTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleExpression.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/style/StyleExpressionTest.kt
git commit -m "feat(style): support the concat expression operator"
```

---

### Task 5: Script support and the `is-supported-script` operator

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/ScriptSupport.kt`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleExpression.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/ScriptSupportTest.kt`

**Interfaces:**
- Consumes: `compileNode`, `requireAtLeast`, `StyleValue.BooleanValue`, `StyleValue.StringValue`.
- Produces:
  ```kotlin
  internal object ScriptSupport {
      fun requiresComplexShaping(text: String): Boolean
      fun isSupported(text: String): Boolean   // == !requiresComplexShaping(text)
  }
  ```
  plus `"is-supported-script"` handled in `compileNode`.

This is the highest-leverage task in the plan and the reason is not obvious. Eleven corpus layers already wrap their `text-field` in `is-supported-script`, choosing a Latin fallback when the renderer says no. Implementing the operator truthfully turns a hard exclusion into style-authored text: the style itself picks `name:latin` and the label survives. Return `true` blindly and those styles hand back text Rentile cannot lay out.

Supported means: layout by advance accumulation alone is faithful. That holds for Latin, Greek, Cyrillic, Han, Hiragana, Katakana, Hangul and the punctuation around them. It fails for scripts needing bidirectional reordering or contextual joining.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptSupportTest {
    @Test
    fun latinGreekCyrillicAndCjkAreSupported() {
        assertTrue(ScriptSupport.isSupported("Paris"))
        assertTrue(ScriptSupport.isSupported("Αθήνα"))
        assertTrue(ScriptSupport.isSupported("Москва"))
        assertTrue(ScriptSupport.isSupported("東京"))
        assertTrue(ScriptSupport.isSupported("서울"))
        assertTrue(ScriptSupport.isSupported("Saint-Jean-de-Luz (1)"))
    }

    @Test
    fun reorderingAndJoiningScriptsAreNotSupported() {
        assertFalse(ScriptSupport.isSupported("القاهرة"))
        assertFalse(ScriptSupport.isSupported("תל אביב"))
        assertFalse(ScriptSupport.isSupported("नई दिल्ली"))
        assertFalse(ScriptSupport.isSupported("กรุงเทพ"))
        assertFalse(ScriptSupport.isSupported("ភ្នំពេញ"))
        assertFalse(ScriptSupport.isSupported("ဝန်းသိုမြို့"))
    }

    @Test
    fun mixedTextIsUnsupportedIfAnyPartIsUnsupported() {
        assertFalse(ScriptSupport.isSupported("Cairo القاهرة"))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.ScriptSupportTest'`
Expected: FAIL to compile — `ScriptSupport` does not exist.

- [ ] **Step 3: Implement the script classifier**

```kotlin
package com.rohittp.rentile.internal.glyph

/**
 * Whether text can be laid out by accumulating glyph advances alone.
 *
 * Rentile lays labels out from glyph metrics and never shapes through a platform
 * font stack (ADR 0025), so scripts requiring bidirectional reordering or
 * contextual joining cannot be rendered faithfully. Ranges are listed by the
 * property that disqualifies them, not by language.
 */
internal object ScriptSupport {
    private val UNSUPPORTED_RANGES: List<IntRange> = listOf(
        0x0590..0x05FF, // Hebrew: right-to-left
        0x0600..0x06FF, // Arabic: right-to-left, contextual joining
        0x0700..0x074F, // Syriac
        0x0750..0x077F, // Arabic Supplement
        0x0780..0x07BF, // Thaana
        0x07C0..0x08FF, // NKo, Samaritan, Arabic Extended-A
        0x0900..0x0DFF, // Devanagari through Sinhala: Brahmic reordering
        0x0E00..0x0E7F, // Thai: combining vowel placement
        0x0E80..0x0EFF, // Lao
        0x0F00..0x0FFF, // Tibetan
        0x1000..0x109F, // Myanmar
        0x1780..0x17FF, // Khmer
        0x1800..0x18AF, // Mongolian
        0xFB1D..0xFDFF, // Hebrew and Arabic presentation forms
        0xFE70..0xFEFF, // Arabic presentation forms-B
    )

    fun requiresComplexShaping(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codepoint = text.codePointAtCompat(index)
            if (UNSUPPORTED_RANGES.any { codepoint in it }) return true
            index += if (codepoint > 0xFFFF) 2 else 1
        }
        return false
    }

    fun isSupported(text: String): Boolean = !requiresComplexShaping(text)
}
```

`codePointAtCompat` must be a small `commonMain` helper, because `String.codePointAt` is not available across all Kotlin Multiplatform targets. If the codebase already has surrogate-pair handling — check `internal/` for existing codepoint iteration before writing a new one — reuse it. Otherwise write it beside `ScriptSupport`: read the char, and if it is a high surrogate followed by a low surrogate, combine them.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.ScriptSupportTest'`
Expected: PASS, all three.

- [ ] **Step 5: Write the failing test for the operator**

Add to `StyleExpressionTest.kt`:

```kotlin
    @Test
    fun isSupportedScriptReflectsWhatLayoutCanRender() {
        assertEquals(StyleValue.BooleanValue(true), evaluate("""["is-supported-script","Tokyo"]"""))
        assertEquals(StyleValue.BooleanValue(false), evaluate("""["is-supported-script","القاهرة"]"""))
    }
```

- [ ] **Step 6: Implement the operator**

Add to `compileNode`'s `when`:

```kotlin
            "is-supported-script" -> compileIsSupportedScript(arguments)
```

and the method:

```kotlin
    private fun compileIsSupportedScript(arguments: List<JsonElement>): StyleExpression {
        val expressions = requireAtLeast("is-supported-script", arguments, 1).map(::compileNode)
        return StyleExpression { context ->
            StyleValue.BooleanValue(
                expressions.all { expression ->
                    when (val value = expression.evaluate(context)) {
                        is StyleValue.StringValue -> ScriptSupport.isSupported(value.value)
                        else -> true
                    }
                },
            )
        }
    }
```

- [ ] **Step 7: Run the tests and commit**

```bash
./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.style.StyleExpressionTest'
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/ScriptSupport.kt \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleExpression.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/ScriptSupportTest.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/style/StyleExpressionTest.kt
git commit -m "feat(style): answer is-supported-script from what metric layout can render

Eleven rolling-corpus place-name layers branch on this operator and select a
Latin fallback when the renderer says no. Answering truthfully turns a hard
exclusion into style-authored text."
```

---

### Task 6: Compile the text program onto the label layer

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/CompiledStyle.kt:288-291` (`CompiledLabelLayer`)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/CompiledStyle.kt:292-306` (`CompiledPreparedStyle`)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleCompiler.kt:88-108` (glyphs template resolution), `:140-166` (label layer compilation)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`

**Interfaces:**
- Consumes: `concat` and `is-supported-script` (Tasks 4, 5); existing `CompiledStyleProperty`, `CompiledStyleFilter`, `IconAnchor`, `SymbolPlacement`, `resolveHttpReference`, `secretContext.protectUrl`, `failRetained`, `diagnostic`.
- Produces:
  ```kotlin
  internal enum class TextTransform { NONE, UPPERCASE, LOWERCASE }
  internal enum class TextJustify { LEFT, CENTER, RIGHT }

  internal data class CompiledLabelLayer(
      val descriptor: LabelLayerDescriptor,
      val source: CompiledVectorSource,
      val layerOrder: Int,
      val filter: CompiledStyleFilter,
      val text: CompiledStyleProperty,
      val font: CompiledStyleProperty,
      val size: CompiledStyleProperty,
      val anchor: IconAnchor,
      val offset: CompiledStyleProperty,
      val justify: TextJustify,
      val maxWidth: CompiledStyleProperty,
      val letterSpacing: CompiledStyleProperty,
      val lineHeight: CompiledStyleProperty,
      val transform: TextTransform,
      val padding: Double,
      val allowOverlap: Boolean,
      val ignorePlacement: Boolean,
      val sortKey: CompiledStyleProperty?,
      val color: CompiledStyleProperty,
      val haloColor: CompiledStyleProperty,
      val haloWidth: CompiledStyleProperty,
      val haloBlur: CompiledStyleProperty,
      val opacity: CompiledStyleProperty,
      val minZoom: Double,
      val maxZoom: Double,
  )
  ```
  and `CompiledPreparedStyle.glyphsTemplate: String?`.

The existing `CompiledLabelLayer` holds only a descriptor and a source, because until now the consumer compiled the layer JSON itself. Everything above is new. `descriptor` and the raw `layerJson` stay, because `labelLayerDescriptors` and `acquireLabelTiles` keep working unchanged for consumers that want raw MVT.

Two defaults from the Mapbox style specification that the corpus relies on: `text-max-width` is 10 ems, `text-line-height` is 1.2 ems, `text-letter-spacing` is 0 ems, `text-padding` is 2 pixels, `text-size` is 16 pixels, `text-anchor` is `center`, `text-justify` is `center`.

- [ ] **Step 1: Write the failing test**

Add to `RentileRuntimeTest.kt`:

```kotlin
    @Test
    fun preparationResolvesTheGlyphsTemplateAndKeepsTheLabelDescriptor() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf?key=secret","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","layout":{"text-field":["get","name"],"text-font":["Open Sans Regular"],"text-size":14}}]}""",
                ),
            )

            val descriptors = rasterizer.labelLayerDescriptors(style)

            assertEquals(1, descriptors.size)
            assertEquals("place", descriptors.single().sourceLayer)
            // The glyphs template is credential-bearing and must not surface anywhere public.
            assertTrue(style.diagnostics.none { it.details.values.any { value -> value.contains("secret") } })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aLinePlacedPlaceLayerIsExcludedWithADiagnostic() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"glyphs":"https://glyphs.example.test/{fontstack}/{range}.pbf","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"],"maxzoom":14}},"layers":[{"id":"places","type":"symbol","source":"v","source-layer":"place","layout":{"text-field":["get","name"],"symbol-placement":"line"}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.LINE_PLACEMENT_LABEL_EXCLUDED })
            assertEquals(0, rasterizer.labelLayerDescriptors(style).size)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
```

- [ ] **Step 2: Run them and confirm the second fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest.aLinePlacedPlaceLayerIsExcludedWithADiagnostic'`
Expected: FAIL — no `LINE_PLACEMENT_LABEL_EXCLUDED` is emitted today, and the layer still yields a descriptor.

- [ ] **Step 3: Resolve the glyphs template during preparation**

In `StyleCompiler`, beside the existing `sprite` resolution at `:97-105`, add the same shape for `glyphs`. The template must be resolved against the style base URI when relative, and passed through `secretContext.protectUrl(...).resolve()` so an embedded credential is extracted into ephemeral secret state exactly as the sprite URL's is:

```kotlin
        val glyphsReference = root["glyphs"]?.asPrimitive()?.takeIf { it.isString }?.content
        val glyphsTemplate = glyphsReference?.let { reference ->
            val resolved = when {
                reference.startsWith("https://") || reference.startsWith("http://") -> reference
                baseUri != null -> resolveHttpReference(baseUri, reference)
                else -> null
            } ?: failUnsupported("The glyphs URL cannot be resolved against the style base URI")
            secretContext.protectUrl(resolved).resolve()
        }
```

Thread `glyphsTemplate` into `CompiledPreparedStyle`. A style with no `glyphs` key yields `null`, which Task 10 turns into an empty batch and a diagnostic rather than a failure.

- [ ] **Step 4: Compile the text properties**

Extend the `isAuxiliaryLabelLayer` branch at `StyleCompiler.kt:140-166`. Keep the existing descriptor construction untouched and add the compiled program alongside it. Before compiling, read `symbol-placement`: if it is `line`, emit `LINE_PLACEMENT_LABEL_EXCLUDED` at `INFO` with the existing `identity` details and skip the layer entirely — do not add it to `labelLayers`. Place-name source layers are point geometry, so line placement means the style is doing something this profile does not implement.

Compile each property with the same `compileProperty`-style helpers `compileIconLayer` uses for its own `size`, `opacity`, `color`, `haloColor`, `haloWidth`, `haloBlur`, `offset` and `sortKey` — open `compileIconLayer` and follow it property for property rather than inventing a second convention. Map the enums:

```kotlin
        val transform = when (layout["text-transform"]?.asPrimitive()?.takeIf { it.isString }?.content ?: "none") {
            "none" -> TextTransform.NONE
            "uppercase" -> TextTransform.UPPERCASE
            "lowercase" -> TextTransform.LOWERCASE
            else -> failRetained(index, layerId, "text-transform is unsupported")
        }
        val justify = when (layout["text-justify"]?.asPrimitive()?.takeIf { it.isString }?.content ?: "center") {
            "left" -> TextJustify.LEFT
            "center" -> TextJustify.CENTER
            "right" -> TextJustify.RIGHT
            else -> failRetained(index, layerId, "text-justify is unsupported")
        }
```

`text-anchor` reuses the existing `IconAnchor` enum and the existing string mapping — the nine values are identical, and duplicating the enum would be a second source of truth for the same concept.

Any property whose expression fails to compile raises the existing unsupported-construct path. Emit `UNSUPPORTED_TEXT_CONSTRUCT` at `INFO` and exclude that layer rather than failing preparation, matching how `TEXT_ONLY_LAYER_EXCLUDED` degrades instead of throwing.

- [ ] **Step 5: Run both tests and the full common suite**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest'`
Expected: PASS, including the pre-existing `preparedStyleExposesEveryPlaceNameSourceLayerAcrossBothTileSchemas` test at `:1085`. That test's style has no `glyphs` key and no `symbol-placement`, so it must be unaffected. If it now fails, the line-placement check or the glyphs resolution is rejecting styles it should accept.

- [ ] **Step 6: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/CompiledStyle.kt \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleCompiler.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt
git commit -m "feat(style): compile the place-name text program and resolve the glyphs template"
```

---

### Task 7: Deterministic glyph atlas packing

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/GlyphAtlasPacker.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/GlyphAtlasPackerTest.kt`

**Interfaces:**
- Consumes: `AcquiredGlyphRange`, `DecodedGlyph`, `GlyphRangeDecoder.BUFFER_PX` (Tasks 2, 3); `LabelGlyphEntry` (Task 9 — if Task 9 is not yet done, define `LabelGlyphEntry` there first, since this task cannot compile without it).
- Produces:
  ```kotlin
  internal data class PackedGlyphAtlas(
      val entries: List<LabelGlyphEntry>,
      val pngBytes: ByteArray,
      val width: Int,
      val height: Int,
      val contentKey: String,
      val indexOf: Map<Pair<String, Int>, Int>,   // (fontStackDigest, codepoint) -> entries index
  )
  internal object GlyphAtlasPacker {
      fun pack(ranges: List<AcquiredGlyphRange>): PackedGlyphAtlas
  }
  ```

Two things are non-negotiable. Packing order must be `(fontStackDigest, codepoint)` so the same glyph set always produces the same layout regardless of the order ranges arrived in — otherwise `contentKey` changes between runs and the consumer re-uploads a texture that did not change. And `contentKey` must be a digest of the **glyph set and their metrics**, never of `pngBytes`: ADR 0010 measures determinism on decoded pixels, and PNG encoders are not required to be byte-identical across platforms.

Glyphs with an empty bitmap (whitespace) contribute an advance but occupy no atlas space, so they get no entry and no `indexOf` mapping. Layout must handle a missing entry as "advance only".

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlyphAtlasPackerTest {
    private fun glyph(id: Int, w: Int, h: Int) = DecodedGlyph(
        codepoint = id, width = w, height = h, left = 0, top = -h, advance = w + 2,
        bitmap = ByteArray((w + 6) * (h + 6)) { 1 },
    )

    private fun range(stack: String, start: Int, vararg glyphs: DecodedGlyph) =
        AcquiredGlyphRange(stack, start, glyphs.toList(), "digest-$stack-$start")

    @Test
    fun packsEveryDrawableGlyphAndSkipsWhitespace() {
        val space = DecodedGlyph(32, 0, 0, 0, 0, 6, ByteArray(0))
        val atlas = GlyphAtlasPacker.pack(
            listOf(range("Open Sans Regular", 0, glyph(65, 10, 14), glyph(66, 9, 14), space)),
        )

        assertEquals(2, atlas.entries.size)
        assertTrue(atlas.entries.all { it.width > 0 && it.height > 0 })
        assertTrue(atlas.width > 0 && atlas.height > 0)
        assertTrue(atlas.pngBytes.isNotEmpty())
    }

    @Test
    fun isIndependentOfTheOrderRangesArriveIn() {
        val a = range("Open Sans Regular", 0, glyph(65, 10, 14))
        val b = range("Roboto Regular", 0, glyph(66, 9, 14))

        val forward = GlyphAtlasPacker.pack(listOf(a, b))
        val reversed = GlyphAtlasPacker.pack(listOf(b, a))

        assertEquals(forward.contentKey, reversed.contentKey)
        assertEquals(forward.entries, reversed.entries)
    }

    @Test
    fun contentKeyChangesWhenAGlyphMetricChanges() {
        val one = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, glyph(65, 10, 14))))
        val two = GlyphAtlasPacker.pack(listOf(range("Open Sans Regular", 0, glyph(65, 11, 14))))

        assertNotEquals(one.contentKey, two.contentKey)
    }

    @Test
    fun everyDrawableGlyphIsAddressableByFontStackAndCodepoint() {
        val atlas = GlyphAtlasPacker.pack(
            listOf(range("Open Sans Regular", 0, glyph(65, 10, 14))),
        )
        val stackDigest = atlas.entries.single().fontStackDigest
        assertEquals(0, atlas.indexOf.getValue(stackDigest to 65))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.GlyphAtlasPackerTest'`
Expected: FAIL to compile — `GlyphAtlasPacker` does not exist.

- [ ] **Step 3: Implement the packer**

```kotlin
package com.rohittp.rentile.internal.glyph

internal data class PackedGlyphAtlas(
    val entries: List<LabelGlyphEntry>,
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val contentKey: String,
    val indexOf: Map<Pair<String, Int>, Int>,
)

internal object GlyphAtlasPacker {
    private const val MAX_WIDTH = 1024

    fun pack(ranges: List<AcquiredGlyphRange>): PackedGlyphAtlas {
        // Flatten to (fontStackDigest, glyph), then order canonically so layout is
        // independent of arrival order. Whitespace glyphs carry no bitmap and are dropped.
        val drawable = ranges
            .flatMap { range -> range.glyphs.map { range.fontStack.sha256Hex() to it } }
            .filter { (_, glyph) -> glyph.bitmap.isNotEmpty() }
            .distinctBy { (digest, glyph) -> digest to glyph.codepoint }
            .sortedWith(compareBy({ it.first }, { it.second.codepoint }))

        // Shelf-pack left to right, wrapping at MAX_WIDTH. Each cell is the buffered
        // bitmap extent, so the consumer samples the same padding the provider encoded.
        val entries = ArrayList<LabelGlyphEntry>(drawable.size)
        val index = HashMap<Pair<String, Int>, Int>(drawable.size)
        var penX = 0
        var penY = 0
        var shelfHeight = 0
        var atlasWidth = 0
        for ((digest, glyph) in drawable) {
            val cellWidth = glyph.width + GlyphRangeDecoder.BUFFER_PX * 2
            val cellHeight = glyph.height + GlyphRangeDecoder.BUFFER_PX * 2
            if (penX + cellWidth > MAX_WIDTH && penX > 0) {
                penY += shelfHeight
                penX = 0
                shelfHeight = 0
            }
            index[digest to glyph.codepoint] = entries.size
            entries += LabelGlyphEntry(
                fontStackDigest = digest,
                codepoint = glyph.codepoint,
                x = penX, y = penY,
                width = cellWidth, height = cellHeight,
                left = glyph.left, top = glyph.top,
                advance = glyph.advance,
            )
            penX += cellWidth
            atlasWidth = maxOf(atlasWidth, penX)
            shelfHeight = maxOf(shelfHeight, cellHeight)
        }
        val atlasHeight = penY + shelfHeight

        // contentKey covers the glyph set and its metrics, never the encoded PNG:
        // ADR 0010 measures determinism on decoded pixels, not compressed bytes.
        val contentKey = entries.joinToString("|") { entry ->
            "${entry.fontStackDigest}:${entry.codepoint}:${entry.width}x${entry.height}:" +
                "${entry.left},${entry.top},${entry.advance}"
        }.sha256Hex()

        return PackedGlyphAtlas(
            entries = entries,
            pngBytes = encode(drawable, entries, atlasWidth, atlasHeight),
            width = atlasWidth,
            height = atlasHeight,
            contentKey = contentKey,
            indexOf = index,
        )
    }

    private fun encode(
        drawable: List<Pair<String, DecodedGlyph>>,
        entries: List<LabelGlyphEntry>,
        width: Int,
        height: Int,
    ): ByteArray {
        // RGB is opaque white and the signed distance goes in alpha, matching the
        // convention SDF sprites already use (see the tint path at
        // DefaultBasemapRasterizer.kt:1825) so a consumer has one sampling rule for
        // both atlases.
        val pixels = ByteArray(width * height * 4)
        drawable.forEachIndexed { index, (_, glyph) ->
            val entry = entries[index]
            for (row in 0 until entry.height) {
                val sourceOffset = row * entry.width
                val targetOffset = ((entry.y + row) * width + entry.x) * 4
                for (column in 0 until entry.width) {
                    val target = targetOffset + column * 4
                    pixels[target] = -1        // 0xFF red
                    pixels[target + 1] = -1    // 0xFF green
                    pixels[target + 2] = -1    // 0xFF blue
                    pixels[target + 3] = glyph.bitmap[sourceOffset + column]
                }
            }
        }
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val image = Image.makeRaster(info, pixels, width * 4)
        try {
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia could not encode the glyph atlas as PNG")
            try {
                return data.bytes
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
    }
}
```

Notes on `encode`. `ImageInfo`, `ColorType`, `ColorAlphaType`, `Image` and `EncodedImageFormat` are all already imported elsewhere in `commonMain`, so no new dependency is involved. The close-in-`finally` discipline matches `renderSyntheticPng` in `internal/SyntheticPngProbe.kt`, which is the smallest existing example of this encode path — read it first. Byte literals are written as `-1` because Kotlin's `Byte` is signed and `0xFF.toByte()` is `-1`.

`UNPREMUL` is deliberate: the alpha channel carries a distance, not a coverage value, so premultiplying would corrupt it.

Guard the degenerate case. When `drawable` is empty, `pack` must return `width = 1`, `height = 1` and a 1×1 fully transparent PNG rather than attempting a zero-dimension image, which Skia rejects. The empty-atlas test in Step 4 pins that.

- [ ] **Step 4: Add the empty-atlas test**

```kotlin
    @Test
    fun anEmptyGlyphSetStillProducesAValidAtlas() {
        val atlas = GlyphAtlasPacker.pack(emptyList())

        assertEquals(0, atlas.entries.size)
        assertEquals(1, atlas.width)
        assertEquals(1, atlas.height)
        assertTrue(atlas.pngBytes.isNotEmpty())
    }
```

- [ ] **Step 5: Run all packer tests and confirm they pass**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.GlyphAtlasPackerTest'`
Expected: PASS, all five.

- [ ] **Step 6: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/GlyphAtlasPacker.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/GlyphAtlasPackerTest.kt
git commit -m "feat(glyph): pack a deterministic glyph atlas keyed by its glyph set"
```

---

### Task 8: Metric layout

**Files:**
- Create: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/LabelLayout.kt`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/LabelLayoutTest.kt`

**Interfaces:**
- Consumes: `PackedGlyphAtlas`, `GlyphRangeDecoder.EM_PX`, `ScriptSupport` (Tasks 5, 7); `IconAnchor`, `TextJustify` (Task 6); `LabelGlyphQuad`, `LabelBox` (Task 9).
- Produces:
  ```kotlin
  internal data class LabelTextStyle(
      val fontStackDigest: String, val sizePx: Double, val anchor: IconAnchor,
      val offsetEm: Pair<Double, Double>, val justify: TextJustify,
      val maxWidthEm: Double, val letterSpacingEm: Double, val lineHeightEm: Double,
      val paddingPx: Double,
  )
  internal object LabelLayout {
      fun layOut(text: String, atlas: PackedGlyphAtlas, style: LabelTextStyle): LaidOutLabel?
  }
  internal data class LaidOutLabel(val quads: List<LabelGlyphQuad>, val box: LabelBox)
  ```

The algorithm, in the units the glyph data uses. All glyph metrics are in a 24-pixel em; `scale = sizePx / 24.0` converts to output pixels at the end.

1. Greedy word wrap. Accumulate each glyph's `advance + letterSpacingEm * 24` in em-units. Break at the last space before the line exceeds `maxWidthEm * 24`. A single word longer than the limit stays on its own line rather than being split.
2. Each line's width is the sum of its advances, minus the trailing letter spacing.
3. Lines stack downward by `lineHeightEm * 24`.
4. The block is aligned horizontally per `justify`, then shifted so `anchor` sits at the origin, then displaced by `offsetEm * 24`.
5. Each glyph's quad is at `((penX + entry.left) * scale, (baselineY + entry.top) * scale)` with the atlas cell's own extent, and `scale` on the quad.
6. The bounding box is the union of quads expanded by `paddingPx`.

`layOut` returns `null` when the text is empty after trimming, so the caller drops the candidate.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelLayoutTest {
    private fun atlasOf(vararg codepoints: Int): PackedGlyphAtlas =
        GlyphAtlasPacker.pack(
            listOf(
                AcquiredGlyphRange(
                    "Test Font", 0,
                    codepoints.map { cp ->
                        DecodedGlyph(cp, 10, 14, 1, -14, 12, ByteArray((10 + 6) * (14 + 6)) { 1 })
                    } + DecodedGlyph(32, 0, 0, 0, 0, 6, ByteArray(0)),
                    "digest",
                ),
            ),
        )

    private fun styleFor(atlas: PackedGlyphAtlas, maxWidthEm: Double = 10.0) = LabelTextStyle(
        fontStackDigest = atlas.entries.first().fontStackDigest,
        sizePx = 24.0, anchor = IconAnchor.CENTER, offsetEm = 0.0 to 0.0,
        justify = TextJustify.CENTER, maxWidthEm = maxWidthEm,
        letterSpacingEm = 0.0, lineHeightEm = 1.2, paddingPx = 2.0,
    )

    @Test
    fun oneQuadPerDrawableGlyph() {
        val atlas = atlasOf('A'.code, 'B'.code)
        val laid = LabelLayout.layOut("AB", atlas, styleFor(atlas))!!

        assertEquals(2, laid.quads.size)
        // Advance is 12 em-units and sizePx equals EM_PX, so scale is 1.0. Assert the
        // relative advance, which is independent of where the anchor puts the block.
        assertEquals(12.0, laid.quads[1].x - laid.quads[0].x)
        assertEquals(laid.quads[0].y, laid.quads[1].y)
        assertTrue(laid.quads.all { it.scale == 1.0 })
    }

    @Test
    fun whitespaceAdvancesWithoutAQuad() {
        val atlas = atlasOf('A'.code, 'B'.code)
        val laid = LabelLayout.layOut("A B", atlas, styleFor(atlas))!!

        assertEquals(2, laid.quads.size)
        // 12 for 'A' plus 6 for the space.
        assertEquals(18.0, laid.quads[1].x - laid.quads[0].x)
    }

    @Test
    fun wrapsAtTheWidthLimitOnWordBoundaries() {
        val atlas = atlasOf('A'.code, 'B'.code)
        // Two two-glyph words are 24 em-units each; a 2-em limit forces a break.
        val laid = LabelLayout.layOut("AB AB", atlas, styleFor(atlas, maxWidthEm = 2.0))!!

        val rows = laid.quads.map { it.y }.distinct()
        assertEquals(2, rows.size)
        assertEquals(1.2 * 24.0, rows[1] - rows[0])
    }

    @Test
    fun scalesGeometryByTextSize() {
        val atlas = atlasOf('A'.code, 'B'.code)
        val small = LabelLayout.layOut("AB", atlas, styleFor(atlas))!!
        val large = LabelLayout.layOut("AB", atlas, styleFor(atlas).copy(sizePx = 48.0))!!

        assertEquals(2.0, large.quads[1].x / small.quads[1].x)
        assertTrue(large.quads.all { it.scale == 2.0 })
    }

    @Test
    fun theBoundingBoxCoversEveryQuadPlusPadding() {
        val atlas = atlasOf('A'.code, 'B'.code)
        val laid = LabelLayout.layOut("AB", atlas, styleFor(atlas))!!

        assertTrue(laid.box.left <= laid.quads.minOf { it.x } - 2.0)
        assertTrue(laid.box.right >= laid.quads.maxOf { it.x } + 2.0)
    }

    @Test
    fun emptyTextYieldsNoLabel() {
        val atlas = atlasOf('A'.code)
        assertNull(LabelLayout.layOut("   ", atlas, styleFor(atlas)))
    }

    @Test
    fun isDeterministicAcrossRepeatedCalls() {
        val atlas = atlasOf('A'.code, 'B'.code)
        val style = styleFor(atlas)

        assertEquals(
            LabelLayout.layOut("AB AB", atlas, style),
            LabelLayout.layOut("AB AB", atlas, style),
        )
    }
}
```

These assertions are deliberately relative rather than absolute. The absolute x of the first quad depends on the anchor shift for a centred single line, which is a consequence of the implementation rather than a contract; the 12-unit advance between consecutive glyphs is the contract. Add one absolute assertion of your own once the anchor arithmetic is written, so a future change to it is caught.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.LabelLayoutTest'`
Expected: FAIL to compile — `LabelLayout` does not exist.

- [ ] **Step 3: Implement layout**

Write `LabelLayout.layOut` following the six numbered steps above. Structure it as three private helpers so each is testable in isolation and none grows past a screenful: `wrap(text, atlas, style): List<List<Int>>` returning codepoints per line, `place(lines, atlas, style): List<LabelGlyphQuad>`, and `bounds(quads, atlas, style): LabelBox`.

Look up each codepoint through `atlas.indexOf[style.fontStackDigest to codepoint]`. A codepoint absent from the atlas is a glyph the provider does not have: skip it entirely, contributing neither quad nor advance. A codepoint present in `indexOf` uses `atlas.entries[index].advance`; whitespace never appears in `indexOf`, so hold a small map of whitespace advances from the ranges, or treat an absent space as `0.25 * 24` em-units. Prefer the former — invented metrics are the kind of thing that looks fine until a provider disagrees.

Iterate codepoints with the surrogate-aware helper from Task 5, not by `Char`, so astral-plane CJK does not split.

- [ ] **Step 4: Run the layout tests and confirm they pass**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.internal.glyph.LabelLayoutTest'`
Expected: PASS, all seven.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/LabelLayout.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/internal/glyph/LabelLayoutTest.kt
git commit -m "feat(glyph): lay labels out from glyph advances and bearings"
```

---

### Task 9: Public API types

> **Execution order:** Tasks 7 and 8 reference `LabelGlyphEntry`, `LabelGlyphQuad` and `LabelBox`, which this task defines. If you are executing strictly in order, do this task's Step 3 before starting Task 7, then return here for the rest. Nothing else in Tasks 7 or 8 depends on it.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt` (types after `ValidatedMvtTile` at `:134-141`; interface methods after `acquireLabelTiles` at `:310-314`)
- Modify: `kmp/api/jvm/kmp.api`, `kmp/api/kmp.klib.api` (regenerated)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt`

**Interfaces:**
- Produces: the whole public surface. Copy these declarations verbatim from the spec's "Public API" section — `LabelGlyphEntry`, `LabelGlyphAtlas`, `LabelGlyphQuad`, `LabelBox`, `LabelLayerStyle`, `LabelIconRef`, `LabelCandidate`, `LabelCandidateBatch`, plus `BasemapRasterizer.labelCandidateRequestKey` and `BasemapRasterizer.acquireLabelCandidates`.

Two conventions the existing file already sets and this must follow. `LabelGlyphAtlas.pngBytes` is a `ByteArray` in a `data class`, so it needs hand-written `equals`/`hashCode` and a `toString` that does not dump bytes — copy the treatment `ValidatedMvtTile` uses. And `acquireLabelCandidates` takes `resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL` as its third parameter, matching `acquireLabelTiles` and `acquireTerrainTiles` exactly.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun labelCandidateGeometryCarriesNoScreenCoordinates() {
        // A compile-time contract check: a candidate exposes geography and label-local
        // geometry only. If someone later adds a screen-space field, this stops compiling
        // against the property list and the reviewer has to justify it.
        val candidate = LabelCandidate(
            layerStyleIndex = 0,
            requestedTile = TileId(14, 14547, 6451),
            sourceTile = TileId(14, 14547, 6451),
            longitude = 139.6503, latitude = 35.6762,
            glyphs = listOf(LabelGlyphQuad(entryIndex = 0, x = 0.0, y = 0.0, scale = 1.0)),
            boundingBox = LabelBox(left = -1.0, top = -1.0, right = 1.0, bottom = 1.0),
            icon = null,
            allowOverlap = false, ignorePlacement = false,
            padding = 2.0, sortKey = 0.0, opacity = 1.0,
            haloWidth = 0.0, haloBlur = 0.0,
        )

        assertEquals(0, candidate.layerStyleIndex)
        assertEquals(139.6503, candidate.longitude)
        assertEquals(14, candidate.sourceTile.z)
    }

    @Test
    fun theGlyphAtlasComparesAndPrintsByValueNotByReference() {
        val one = LabelGlyphAtlas(byteArrayOf(1, 2, 3), 4, 4, "key", emptyList())
        val two = LabelGlyphAtlas(byteArrayOf(1, 2, 3), 4, 4, "key", emptyList())

        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        assertFalse(one.toString().contains("1, 2, 3"))
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.ApiContractTest'`
Expected: FAIL to compile — none of the types exist.

- [ ] **Step 3: Add the types**

Add all eight data classes to `Api.kt` immediately after `ValidatedMvtTile`, exactly as the spec declares them, with the spec's KDoc comments carried across. Keep them together and in the spec's order so the file reads from smallest part to whole batch.

- [ ] **Step 4: Add the interface methods**

Add to `BasemapRasterizer`, immediately after `acquireLabelTiles`:

```kotlin
    /**
     * Identity for a Label acquisition, available before any network call.
     *
     * Covers style identity, tile identities and Rentile's label semantics. It
     * deliberately omits credentials, sessions, validators and the glyph closure,
     * because which glyph ranges a tile set needs is not knowable until its features
     * are decoded. A caller must store [LabelCandidateBatch.contentKey] beside any
     * candidates it indexes with this value.
     */
    public fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String

    /**
     * All-or-error validated Label acquisition. Tile substitution is deliberately not
     * applied. A style with no glyph endpoint yields an empty batch and a diagnostic.
     */
    public suspend fun acquireLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): LabelCandidateBatch
```

Adding methods to `BasemapRasterizer` makes the build fail until `DefaultBasemapRasterizer` implements them. Add throwing stubs (`TODO("Task 10")`) so this task compiles, and remove them in Task 10. Do not commit the stubs — this task's commit comes after Step 6, and Step 6 will fail while stubs throw, which is the signal to finish Task 10 first if you are batching.

- [ ] **Step 5: Fix the misleading KDoc while you are in this file**

`Api.kt:306` documents `labelLayerDescriptors` as "Resolved visible text-symbol layers in style order". It returns only place-name layers — `RentileRuntimeTest.kt:1113` pins `road_label`, `water_label` and `poi` as excluded. Replace it with:

```kotlin
    /** Resolved visible place-name symbol layers in style order. URL templates remain private. */
```

- [ ] **Step 6: Run the contract tests**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.ApiContractTest'`
Expected: PASS once Task 10 supplies real implementations.

- [ ] **Step 7: Regenerate and review the ABI dump**

```bash
./gradlew :kmp:updateKotlinAbi
git diff --stat kmp/api/
```

Review the diff by hand. Every added symbol must be one of the eight data classes, their accessors and `copy`/`component` functions, or the two interface methods and their `$default` bridges. An added symbol you did not intend means something leaked from `internal`.

- [ ] **Step 8: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt kmp/api/
git commit -m "feat(api): expose label candidates, their glyph atlas and per-layer paint

Also corrects the labelLayerDescriptors KDoc, which claimed every visible
text-symbol layer while the implementation has always returned place-name
layers only."
```

---

### Task 10: Acquire label candidates

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt` (new methods beside `acquireLabelTiles` at `:308-345`; acquirer wiring beside the existing `vectorAcquirer` and sprite acquirer construction)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–9. Specifically `GlyphResourceAcquirer.acquire`, `GlyphResourceAcquirer.rangeStartFor`, `GlyphAtlasPacker.pack`, `LabelLayout.layOut`, `LabelTextStyle`, `ScriptSupport.requiresComplexShaping`, `CompiledLabelLayer`, `CompiledPreparedStyle.glyphsTemplate`, and the existing `operation { }` wrapper, `requireOwnedStyle`, `validateTile`, `sampleFor`, `vectorAcquirer`, `acquireOutcome`, `throwAcquisitionFailures`, `featureContext`, `evaluatedNumber`, `evaluatedColor`, `evaluatedOpacity`.
- Produces: `DefaultBasemapRasterizer.acquireLabelCandidates` and `labelCandidateRequestKey` implementations. Task 11 replaces the request-key body.

Orchestration, in eleven steps. Read `acquireLabelTiles` at `:308-345` first: steps 1 and 2 are that method almost verbatim.

1. `requireOwnedStyle`, stabilise the tile list, `validateTile` each against the policy.
2. Resolve label sources and their samples via `sampleFor`, acquire concurrently in a `supervisorScope`, then `throwAcquisitionFailures`.
3. If `glyphsTemplate` is `null`, return an empty batch carrying a `GLYPH_RANGE_UNAVAILABLE` diagnostic at `INFO` and the empty atlas from `GlyphAtlasPacker.pack(emptyList())`. Do not throw — a style without glyphs is legitimate.
4. Decode each acquired tile, and for every layer in style order, for every feature passing `layer.filter`, evaluate the text program at that feature and the requested tile's zoom. Apply `text-transform`. Trim. Skip empty results.
5. Drop any feature whose anchor falls outside `[0, extent)` of its source tile — that is a tile-buffer duplicate, and the clip removes it exactly.
6. If `ScriptSupport.requiresComplexShaping(text)`, record `COMPLEX_SCRIPT_LABEL_EXCLUDED` once per layer (not per feature — thousands of identical diagnostics are noise) and skip the feature.
7. Collect the `(fontStack, rangeStart)` set across all surviving features. If its size exceeds `maxGlyphRangesPerBatch`, raise `SafetyLimitException(limitName = "maxGlyphRangesPerBatch")`. Otherwise acquire all ranges concurrently through `GlyphResourceAcquirer`.
8. `GlyphAtlasPacker.pack` the ranges, then lay out every surviving feature against that atlas.
9. Populate `icon` when, and only when, the same style layer also declares a meaningful `icon-image` that resolves in the sprite atlas `prepare` already built. Evaluate the image name with the existing `evaluateIconImageName` helper, take the sprite's logical width and height the way `placeIcons` computes them (`entry.width / entry.pixelRatio * size`), and carry `icon-offset` scaled by `icon-size`. Leave it `null` when the layer has no icon, when the name does not resolve, or when the style has no sprite — a label without a marker is normal, and inventing a placeholder would draw something the style never asked for.
10. Build `layerStyles` as the distinct `(layerId, requestedZoom)` pairs actually used, evaluating `text-color` and `text-halo-color` with no feature context, and record each candidate's index into that list.
11. Sort candidates by (layer order, requested tile, source tile, feature index) and compute `contentKey` from the sorted MVT content digests and glyph range digests.

Convert tile-space coordinates to geography with the projection helpers already used for anchors; do not write a second mercator inverse.

- [ ] **Step 1: Write the failing determinism and dedup test**

```kotlin
    @Test
    fun labelCandidatesAreDeterministicAndCarryNoScreenGeometry() = runTest {
        val vectorTile = placeNameVectorTile("Tokyo")
        val glyphs = testGlyphRange("Open Sans Regular", 0)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                when {
                    request.resourceClass == ResourceClass.GLYPH_RANGE -> TransportResponse(200, glyphs)
                    else -> TransportResponse(200, vectorTile)
                }
            },
        )
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))
            val tiles = listOf(TileId(2, 1, 1))

            val first = rasterizer.acquireLabelCandidates(style, tiles)
            val second = rasterizer.acquireLabelCandidates(style, tiles)

            assertTrue(first.candidates.isNotEmpty())
            assertEquals(first.candidates, second.candidates)
            assertEquals(first.contentKey, second.contentKey)
            assertEquals(first.atlas.contentKey, second.atlas.contentKey)
            first.candidates.forEach { candidate ->
                assertTrue(candidate.layerStyleIndex in first.layerStyles.indices)
                assertTrue(candidate.glyphs.all { it.entryIndex in first.atlas.entries.indices })
            }
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun aStyleWithoutGlyphsYieldsAnEmptyBatchNotAFailure() = runTest {
        val vectorTile = placeNameVectorTile("Tokyo")
        val rasterizer = testRasterizer(transport = ResourceTransport { TransportResponse(200, vectorTile) })
        try {
            // placeNameStyleJson(glyphs = null) omits the glyphs key entirely.
            val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson(glyphs = null)))

            val batch = rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1)))

            assertTrue(batch.candidates.isEmpty())
            assertTrue(batch.diagnostics.any { it.code == DiagnosticCode.GLYPH_RANGE_UNAVAILABLE })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun complexScriptTextIsExcludedWithADiagnostic() = runTest {
        val vectorTile = placeNameVectorTile("القاهرة")
        val glyphs = testGlyphRange("Open Sans Regular", 0)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
                else TransportResponse(200, vectorTile)
            },
        )
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))

            val batch = rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1)))

            assertTrue(batch.candidates.isEmpty())
            assertEquals(1, batch.diagnostics.count { it.code == DiagnosticCode.COMPLEX_SCRIPT_LABEL_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun tooManyGlyphRangesExceedsItsLimit() = runTest {
        // 70 codepoints, each from a different 256-block of CJK Unified Ideographs, so the
        // name needs 70 glyph ranges. CJK is chosen because it is supported by layout, so
        // the script gate cannot drop the label before ranges are ever collected.
        val name = buildString { repeat(70) { block -> append((0x4E00 + block * 256).toChar()) } }
        val vectorTile = placeNameVectorTile(name)
        val glyphs = testGlyphRange("Open Sans Regular", 0)
        val rasterizer = testRasterizer(
            transport = ResourceTransport { request ->
                if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
                else TransportResponse(200, vectorTile)
            },
        )
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))

            val failure = assertFailsWith<SafetyLimitException> {
                rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1)))
            }

            assertEquals("maxGlyphRangesPerBatch", failure.limitName)
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
```

Write three helpers beside the existing `overzoomVectorTile()` in the same test class, following its construction style: `placeNameVectorTile(name: String): ByteArray` producing a single POINT feature in a `place` source-layer with a `name` tag; `testGlyphRange(fontStack: String, rangeStart: Int): ByteArray` encoding a `Glyphs` message covering the codepoints those tests need; and `placeNameStyleJson(glyphs: String? = "https://glyphs.example.test/{fontstack}/{range}.pbf")` returning a style with one `place` symbol layer.

`placeNameStyleJson` needs two optional parameters by the end of this task: `glyphs: String?` and `iconImage: String?`, both defaulting to the common case. Add a `spriteAndGlyphTransport(vectorTile, glyphs)` helper that serves sprite JSON, sprite PNG, glyph ranges and vector tiles by `request.resourceClass`, reusing whatever sprite fixtures the existing test at `:506` already builds.

The last test assumes the provisional ceiling of 64 from Task 1. If Task 13's measurement raises it, raise the `repeat(70)` count to stay above the new ceiling — the test asserts the limit fires, not any particular number.

- [ ] **Step 2: Run them and confirm they fail**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest'`
Expected: FAIL — `acquireLabelCandidates` throws `TODO("Task 10")` from the Task 9 stub.

- [ ] **Step 3: Wire the glyph acquirer**

Construct `GlyphResourceAcquirer` alongside the existing vector and sprite acquirers in `DefaultBasemapRasterizer`, passing the same `configuration`, coroutine scope and `workCoordinator` they receive. Follow how the sprite acquirer is constructed and how it participates in `close()`/`awaitClosed()`; if the sprite acquirer needs no explicit teardown, neither does this one.

- [ ] **Step 4: Implement the two methods**

Implement `acquireLabelCandidates` following the ten numbered steps above, inside the existing `operation { }` wrapper so it inherits cancellation, metrics and lifecycle handling exactly as `acquireLabelTiles` does. Implement `labelCandidateRequestKey` as a temporary digest of the style digest and tile identities; Task 11 replaces it with the full definition.

Keep the method under roughly 80 lines by extracting the per-feature evaluation and the layer-style table into private helpers in the same file. If it grows past that, extract a `internal/glyph/LabelCandidateAssembler.kt` and keep `DefaultBasemapRasterizer` as the thin public entry point — that file is already large and should not absorb another subsystem.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest'`
Expected: PASS, including all pre-existing tests. `acquireLabelTiles` behaviour must be unchanged; the test at `:1146` still asserts its all-or-error contract.

- [ ] **Step 6: Confirm no stubs survive**

```bash
grep -rn 'TODO("Task 10")' kmp/src/commonMain/
```

Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/ \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt
git commit -m "feat(label): acquire deterministic label candidates and their glyph atlas"
```

---

### Task 11: The request key and batch content key

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt` (`labelCandidateRequestKey`)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`

**Interfaces:**
- Consumes: `outputRequestKey`'s existing implementation as the model — find it in `DefaultBasemapRasterizer` and mirror its inputs and its exclusions.
- Produces: the final `labelCandidateRequestKey` semantics.

The two keys answer different questions and their inputs must not be confused. The request key is available before any network and must be stable for the same style and tile set, and must change when the label semantics version changes. The batch content key is computed after acquisition from the resolved digests. Neither may contain a credential.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun aLabelPairedWithAnIconCarriesTheSpriteReference() = runTest {
        // Same layer, an icon-image beside the text-field. The label keeps its glyphs and
        // gains an icon reference; it does not become an icon-only draw layer.
        val vectorTile = placeNameVectorTile("Tokyo")
        val glyphs = testGlyphRange("Open Sans Regular", 0)
        val rasterizer = testRasterizer(
            transport = spriteAndGlyphTransport(vectorTile = vectorTile, glyphs = glyphs),
        )
        try {
            val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson(iconImage = "marker")))

            val batch = rasterizer.acquireLabelCandidates(style, listOf(TileId(2, 1, 1)))

            val candidate = batch.candidates.single()
            assertEquals("marker", candidate.icon?.imageName)
            assertTrue(candidate.glyphs.isNotEmpty())
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }

    @Test
    fun theLabelRequestKeyIsStableTileOrderIndependentAndCredentialFree() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(placeNameStyleJson(glyphs = "https://glyphs.example.test/{fontstack}/{range}.pbf?key=supersecret")),
            )
            val a = TileId(2, 1, 1)
            val b = TileId(2, 1, 2)

            val forward = rasterizer.labelCandidateRequestKey(style, listOf(a, b))
            val reversed = rasterizer.labelCandidateRequestKey(style, listOf(b, a))
            val single = rasterizer.labelCandidateRequestKey(style, listOf(a))

            assertEquals(forward, reversed)
            assertNotEquals(forward, single)
            assertFalse(forward.contains("supersecret"))
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
```

Tile-order independence is the point: a consumer that reorders its viewport tiles between frames must not see a different key and refetch.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest.theLabelRequestKeyIsStableTileOrderIndependentAndCredentialFree'`
Expected: FAIL on the order-independence assertion if Task 10's temporary implementation digests tiles in argument order.

- [ ] **Step 3: Implement it**

```kotlin
    override fun labelCandidateRequestKey(style: PreparedStyle, tiles: List<TileId>): String {
        val compiledStyle = requireOwnedStyle(style)
        val stableTiles = tiles
            .distinct()
            .sortedWith(compareBy(TileId::z, TileId::x, TileId::y))
            .joinToString(",") { "${it.z}/${it.x}/${it.y}" }
        return listOf(
            LABEL_SEMANTICS_VERSION,
            compiledStyle.digest,
            compiledStyle.policy.name,
            stableTiles,
        ).joinToString("|").sha256Hex()
    }
```

with a companion constant:

```kotlin
        /** Bump when label evaluation, layout or atlas packing changes observable output. */
        private const val LABEL_SEMANTICS_VERSION = "label-candidates-1"
```

`compiledStyle.digest` already excludes credentials, which is why the key can never leak one. Bumping `LABEL_SEMANTICS_VERSION` is how a future layout change invalidates consumer caches; document that in the KDoc, because a silent layout change with an unchanged key is the failure mode this constant exists to prevent.

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt
git commit -m "feat(label): key label acquisitions before the network and by content after"
```

---

### Task 12: Corpus coverage

**Files:**
- Modify: `compatibility/rentile-v1-coverage.json`
- Modify: `kmp/src/androidHostTest/kotlin/com/rohittp/rentile/MapCatalogCorpusSmokeTest.kt`
- Modify: `compatibility/README.md`

**Interfaces:**
- Consumes: `acquireLabelCandidates` (Task 10).
- Produces: two new coverage cases and a `label-candidate` capability, plus a narrowed label assertion in the corpus gate.

The manifest is validated by `tools/check_coverage_manifest.py`, which enforces rules an editor will otherwise trip over: `cases` entries must have exactly the keys `id`, `tags`, `tiles`; `tags` must be unique and sorted; `requiredCapabilities` must be unique and sorted; the file must be byte-identical to `json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"`; and every integer zoom 0 through 22 must remain covered. Edit it with a script, not by hand.

Both cases below were run against all 34 rolling-corpus styles on 2026-08-18 and rendered 136/136 tile rows, so they are known to pass before any label work.

- [ ] **Step 1: Add the capability and the two cases**

```bash
python3 - <<'PY'
import json
p='compatibility/rentile-v1-coverage.json'
m=json.load(open(p))
m['requiredCapabilities']=sorted(set(m['requiredCapabilities'])|{'label-candidate'})
existing={c['id'] for c in m['cases']}
for case in [
    {"id":"tokyo-cjk-dense","tags":["non-latin","vector-overzoom"],
     "tiles":[{"z":14,"x":14547,"y":6451},{"z":16,"x":58190,"y":25807}]},
    {"id":"cairo-rtl","tags":["non-latin","vector-overzoom"],
     "tiles":[{"z":14,"x":9613,"y":6757},{"z":16,"x":38454,"y":27029}]},
]:
    if case['id'] not in existing: m['cases'].append(case)
open(p,'wb').write(json.dumps(m,ensure_ascii=False,indent=2,sort_keys=True).encode()+b'\n')
PY
python3 tools/check_coverage_manifest.py compatibility/rentile-v1-coverage.json && echo VALID
```

Expected: `VALID`.

- [ ] **Step 2: Extend the corpus gate to acquire candidates**

In `MapCatalogCorpusSmokeTest.renderStyle`, after the tiles for a style have rendered, acquire label candidates for **three cases only** — `new-york-zoom-ladder`, `tokyo-cjk-dense` and `cairo-rtl` — taking the highest-zoom tile from each. Do not acquire for every case at every zoom: label correctness varies by geography and script, not by zoom, and the publish gate is already a 28-minute job.

Record per style, in `results.tsv` and the HTML report: candidate count, atlas dimensions, distinct glyph range count, and the redacted diagnostic codes. Assert that acquisition does not throw, and that the glyph range count stays at or below `maxGlyphRangesPerBatch`.

For Cairo, assert the outcome is one of exactly two acceptable results and nothing else: either candidates exist because the style used `is-supported-script` and selected a Latin fallback, or no candidates exist and `COMPLEX_SCRIPT_LABEL_EXCLUDED` was reported. Garbage — candidates whose text contains an unsupported script — must fail the gate.

Keep the existing credential discipline: the report must never contain a style URL, a glyphs URL, a query string, or a font stack that could identify a provider account. Font stack **digests** are safe; names are safe too, since they are style content, but do not add URLs.

- [ ] **Step 3: Run the gate**

```bash
RENTILE_COVERAGE_MANIFEST="$PWD/compatibility/rentile-v1-coverage.json" \
RENTILE_CORPUS_REPORT_DIR="$PWD/build/reports/rentile-corpus-labels" \
./gradlew :kmp:testAndroidHostTest --tests com.rohittp.rentile.MapCatalogCorpusSmokeTest
```

Expected: 34/34 styles pass. If styles fail with `RESOURCE_ACQUISITION_FAILED` and "Sprite transport failed", that is the known transient flake — re-run once. Any other failure is real.

Every corpus style has a glyphs endpoint with `{fontstack}` and `{range}` (verified 2026-08-18), so a `GLYPH_RANGE_UNAVAILABLE` diagnostic from a corpus style means the template resolution in Task 6 is wrong, not that the style lacks glyphs.

- [ ] **Step 4: Document the new cases**

Add a short paragraph to `compatibility/README.md` next to the existing coverage description: the two non-Latin cases exist to exercise CJK glyph-range fan-out and the complex-script path, the gate acquires candidates for three cases per style rather than all of them, and why.

- [ ] **Step 5: Commit**

```bash
git add compatibility/ kmp/src/androidHostTest/kotlin/com/rohittp/rentile/MapCatalogCorpusSmokeTest.kt
git commit -m "test(corpus): gate label candidates against CJK and complex-script geographies"
```

---

### Task 13: Measure the range ceiling, finish the docs, release

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt` (`maxGlyphRangesPerBatch` default, if measurement disagrees)
- Modify: `kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt` (the default assertion)
- Modify: `instructions.md`
- Modify: `gradle.properties` (`VERSION_NAME`)

**Interfaces:**
- Consumes: the corpus report from Task 12.

- [ ] **Step 1: Read the measured range counts**

```bash
cut -f1,2 --complement build/reports/rentile-corpus-labels/results.tsv | head -1
grep -o 'ranges=[0-9]*' build/reports/rentile-corpus-labels/results.tsv \
  | cut -d= -f2 | sort -n | uniq -c | tail -10
```

The Tokyo case is the interesting one: it is dense and CJK, so it should sit at the top of that distribution.

- [ ] **Step 2: Set the default from the measurement**

Set `maxGlyphRangesPerBatch` to roughly twice the highest observed count, rounded to a power of two, so a denser viewport than the corpus contains still succeeds while a runaway style still trips the ceiling. Update the assertion in `ApiContractTest.glyphLimitsAreValidatedAndDefaulted` to the chosen value, and complete Task 10's `tooManyGlyphRangesExceedsItsLimit` test against it. Record the observed maximum in a comment beside the property so the next person knows what the number came from:

```kotlin
    /** Highest observed in the rolling corpus was N ranges, at Tokyo z14 (measured YYYY-MM-DD). */
    public val maxGlyphRangesPerBatch: Int = CHOSEN,
```

- [ ] **Step 3: Reconcile the Travel Animator guidance**

`instructions.md` section 9 currently says "Do not route labels, atmosphere, route overlays, vehicles, globe/plane mapping, or UI camera state through Rentile." That predates the place-name seam Rentile ships. Rewrite that line so it says what is actually true: Rentile does not *draw* labels and never will, but it does prepare place-name label candidates, and a host that wants them calls `acquireLabelCandidates` and does its own placement. Keep the rest of the list unchanged — atmosphere, route overlays, vehicles, globe mapping and camera state genuinely stay out.

- [ ] **Step 4: Run every gate the release runs**

```bash
python3 tools/check_coverage_manifest.py compatibility/rentile-v1-coverage.json
./gradlew :kmp:checkKotlinAbi
./gradlew :kmp:testAndroidHostTest :kmp:jvmTest :kmp:macosArm64Test
./gradlew :kmp:compileKotlinIosArm64 :kmp:compileKotlinIosSimulatorArm64
./gradlew :kmp:compileKotlinLinuxX64 :kmp:compileKotlinLinuxArm64
./gradlew :kmp:publishAllPublicationsToLocalTestRepository
./gradlew -p consumer-smoke compileAndroidMain compileKotlinJvm compileKotlinIosArm64 \
  compileKotlinIosSimulatorArm64 compileKotlinMacosArm64 compileKotlinLinuxX64 compileKotlinLinuxArm64
```

Expected: all pass. The Linux and iOS compile legs matter more than usual here: they are what prove no platform text stack crept in, since `Typeface` and `FontMgr` availability differs there.

- [ ] **Step 5: Cut the minor version**

This is additive public API, so it is a minor release. Set `VERSION_NAME` in the root `gradle.properties` strictly above every published version — check the current highest first:

```bash
curl -s https://maven.rohittp.com/com/rohittp/rentile/kmp/maven-metadata.xml | grep -o '<release>[^<]*'
```

Set `VERSION_NAME` to the next minor above that. The default patch advance will not cut a minor (ADR 0023).

- [ ] **Step 6: Commit and release**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt \
        instructions.md gradle.properties
git commit -m "feat(label): release label candidates for host-owned label rendering

Sets the glyph range ceiling from the measured rolling-corpus maximum,
reconciles the Travel Animator guidance with the place-name seam that
actually ships, and cuts the minor this additive API needs."
git push
gh run list --repo rohittp0/rentile --workflow publish.yml --limit 1
```

- [ ] **Step 7: Tell RenG**

The handoff this work answers lives in RenG's repository. Report the published version, the API entry points (`labelCandidateRequestKey`, `acquireLabelCandidates`), the three keys and what each is for, that scope is place names only, and that complex-script text either arrives as a style-authored Latin fallback or does not arrive at all. Point at ADR 0024 and ADR 0025 rather than restating their reasoning.
