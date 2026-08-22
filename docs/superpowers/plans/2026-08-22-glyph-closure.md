# Glyph Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a caller learn every glyph URL an `acquireLabelCandidates` call will request, before it requests any, without relaxing an exact-URL firewall.

**Architecture:** Split `acquireLabelCandidates` into two phases sharing one frozen list. `planLabelCandidates` acquires the batch's Label Tiles, decodes and evaluates them, and returns a public `LabelCandidatePlan` exposing the Glyph Closure; `acquireLabelCandidates(plan)` fetches exactly that closure and assembles the batch. Because both read the same list, the closure cannot under-approximate the acquisition that follows it. URLs are composed by Rentile from a template the caller supplies and Rentile verifies, so no credential is emitted and no consumer re-derives the percent-encoding.

**Tech Stack:** Kotlin Multiplatform, `kotlinx.coroutines`, `kotlin.concurrent.atomics`, `kotlin.test` with `runTest`. Gradle.

**Spec:** `docs/superpowers/specs/2026-08-22-glyph-closure-design.md`

## Global Constraints

- **Explicit API mode is on.** Every declaration in `kmp/src/commonMain` needs an explicit `public`/`internal` modifier and an explicit return type.
- **No new dependencies.** Rentile has a deliberate minimal dependency policy (ADR 0003).
- **Public API must never emit a credential or a raw font stack.** Font stacks reach the public surface only as `sha256Hex()` digests; templates and composed URLs are never returned. `text-font` may be data-driven, so a raw stack can carry decoded feature-property bytes.
- **Diagnostics must never carry credentials or raw provider strings** (`docs/error-model.md`, "Diagnostic safety").
- **`ResourceLimits` fields are append-only** — see the comment at `Api.kt:415`. This plan adds none, but do not reorder.
- **Existing behaviour of `acquireLabelCandidates(style, tiles, resourceAccess)` must not change** — same candidates, same `contentKey`, same diagnostics, same order.
- **Build/test command:** `./gradlew :kmp:jvmTest` for the fast loop. Use `--tests` filters as shown per task.

---

### Task 1: Make label assembly pure and rename it

`LabelCandidateAssembler.kt:178-179` mutates tallies the plan owns, so a second assembly over one plan double-counts `skippedNoGlyphs`. That breaks ADR 0026's invariant that the three loss counts are subsets of `candidateFeatures`. Fix it first, standalone, so the fix is attributable and the public handle can safely be reusable. The rename frees the name `LabelCandidatePlan` for the public type in Task 3.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/LabelCandidateAssembler.kt:135-260`
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt:562-576`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal class LabelAssembly` (renamed from `internal class LabelCandidatePlan`), same constructor parameters, same `val requiredRanges: List<GlyphRangeRequest>`, same `suspend fun assemble(ranges: List<AcquiredGlyphRange>, record: (RenderDiagnostic) -> Unit): LabelCandidateBatch`. `LabelCandidateAssembler.plan(...)` now returns `LabelAssembly`.

- [ ] **Step 1: Write the regression test** (it passes today; Step 2 proves it has teeth)

Add to `RentileRuntimeTest.kt`, beside the other label tests (near line 2960):

```kotlin
@Test
fun assemblingTwiceFromOneAssemblyDoesNotInflateLossCounts() = runTest {
    // A wholly-astral name: glyph endpoints stop at the BMP, so it requests no range,
    // lays out to nothing, and is counted as skippedNoGlyphs during assembly - which is
    // the only path that mutates the tallies. Verified not complex-script: U+1F600 is in
    // none of ScriptSupport.UNSUPPORTED_RANGES, so it is not excluded before layout.
    val vectorTile = placeNameVectorTile("😀")
    val glyphs = testGlyphRange("Open Sans Regular", 0)
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
            else TransportResponse(200, vectorTile)
        },
    )
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))
        val tiles = listOf(TileId(2, 1, 1))

        val first = rasterizer.acquireLabelCandidates(style, tiles)
        val second = rasterizer.acquireLabelCandidates(style, tiles)

        val firstSkips = first.diagnostics.single { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED }
        val secondSkips = second.diagnostics.single { it.code == DiagnosticCode.LABEL_FEATURE_SKIPPED }
        assertEquals("1", firstSkips.details["skippedNoGlyphs"])
        assertEquals(firstSkips.details, secondSkips.details)
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}
```

No helper change is needed: `placeNameVectorTile` at `:4290` already takes the name, and this
test needs no second feature.

**Note:** this test passes today, because each `acquireLabelCandidates` call builds a fresh
assembly. It is the guard that Task 3's *reusable* plan cannot regress. Write it now so the
pure-assembly refactor below is covered before it lands.

- [ ] **Step 2: Run the test to see it pass, then prove it can fail**

Run: `./gradlew :kmp:jvmTest --tests '*RentileRuntimeTest.assemblingTwiceFromOneAssemblyDoesNotInflateLossCounts'`
Expected: PASS.

Now prove the test has teeth. Temporarily change `assemble` to call itself twice — add `if (ranges.isNotEmpty()) assemble(ranges) {}` as the first statement of the `assemble` body — and re-run.
Expected: FAIL with `skippedNoGlyphs` reported as `2`. Revert the temporary change before Step 3.

- [ ] **Step 3: Make `assemble` pure**

In `LabelCandidateAssembler.kt`, replace the mutation at `:176-183`. The current code is:

```kotlin
            if (laidOut == null) {
                featureSkips[label.program.layerOrder]?.let {
                    it.noGlyphs += 1
                    it.tiles += label.requestedTile
                }
                continue
            }
```

Add a local accumulator at the top of `assemble`, beside `layerStyles`:

```kotlin
        // Layout losses are accumulated locally rather than into featureSkips, which this
        // assembly does not own alone: a reusable LabelCandidatePlan can assemble more than
        // once, and mutating the shared tallies would double-count skippedNoGlyphs on the
        // second pass, breaking ADR 0026's rule that the three loss counts are subsets of
        // candidateFeatures.
        val layoutLosses = mutableMapOf<Int, LayoutLoss>()
```

and change the null branch to:

```kotlin
            if (laidOut == null) {
                if (featureSkips.containsKey(label.program.layerOrder)) {
                    layoutLosses.getOrPut(label.program.layerOrder) { LayoutLoss() }
                        .record(label.requestedTile)
                }
                continue
            }
```

Declare `LayoutLoss` as a file-private class beside `LabelFeatureSkips`:

```kotlin
/** One layer's layout-time losses for a single assembly, kept out of the shared tallies. */
private class LayoutLoss {
    var noGlyphs: Int = 0
    val tiles: MutableSet<TileId> = mutableSetOf()

    fun record(tile: TileId) {
        noGlyphs += 1
        tiles += tile
    }
}
```

Thread the losses into diagnostics. Change `labelDiagnostics()` to take them, and `assemble`'s `diagnostics = style.diagnostics + labelDiagnostics().onEach(record)` to `diagnostics = style.diagnostics + labelDiagnostics(layoutLosses).onEach(record)`:

```kotlin
    /** One entry per layer per code, in layer order, so the list is identical between runs. */
    private fun labelDiagnostics(layoutLosses: Map<Int, LayoutLoss>): List<RenderDiagnostic> =
        (complexScript.keys + featureSkips.keys).sorted().flatMap { layerOrder ->
            val merged = featureSkips[layerOrder]?.mergedWith(layoutLosses[layerOrder])
            listOfNotNull(
                complexScript[layerOrder]?.toDiagnostic(layerOrder),
                merged?.takeIf { it.reportable }?.toDiagnostic(layerOrder),
            )
        }
```

Give `LabelFeatureSkips` a non-mutating merge, and drop its now-unused `noGlyphs` mutability by leaving the field but never assigning it outside construction:

```kotlin
    /** A copy carrying [loss] folded in, so the receiver is never mutated by an assembly. */
    fun mergedWith(loss: LayoutLoss?): LabelFeatureSkips {
        if (loss == null) return this
        val merged = LabelFeatureSkips(layerId)
        merged.candidates = candidates
        merged.skipped = skipped
        merged.nonPointGeometry = nonPointGeometry
        merged.noGlyphs = noGlyphs + loss.noGlyphs
        merged.tiles += tiles
        merged.tiles += loss.tiles
        return merged
    }
```

- [ ] **Step 4: Rename the internal class**

Rename `internal class LabelCandidatePlan` to `internal class LabelAssembly` at `:135`, update its KDoc first line to "Everything one Label acquisition decided before its glyphs exist: the labels that survived, the Glyph Ranges they need, and the diagnostics that decision produced.", and update the two references:

- `LabelCandidateAssembler.plan(...)`'s return type and its `return LabelCandidatePlan(` call (`:497`).
- The KDoc on `internal object LabelCandidateAssembler` (`:271`) which names `LabelCandidatePlan.assemble`.

`DefaultBasemapRasterizer.kt:562` binds it as `val plan = LabelCandidateAssembler.plan(...)`; the local name may stay `plan` for now — Task 3 renames it.

- [ ] **Step 5: Run the label tests**

Run: `./gradlew :kmp:jvmTest --tests '*RentileRuntimeTest*'`
Expected: PASS, including the new test and every pre-existing label test.

- [ ] **Step 6: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/LabelCandidateAssembler.kt \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt
git commit -m "refactor(label): assemble without mutating the tallies it was given

A second assembly over one set of tallies incremented skippedNoGlyphs twice,
which would have made a reusable plan report loss counts that are not subsets
of candidateFeatures. Layout losses now accumulate locally and merge when
diagnostics are built. Renamed to LabelAssembly to free the name for the
public handle."
```

---

### Task 2: Add the three exception types

Standalone and dependency-free, so the types exist before the code that throws them.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/Exceptions.kt:4-21` (enum), `:158-165` (after `ForeignPreparedBatchException`)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public class ForeignLabelCandidatePlanException(message: String = "Label candidate plan belongs to another rasterizer")`
  - `public class LabelCandidatePlanClosedException(message: String = "Label candidate plan is closed")`
  - `public class GlyphTemplateMismatchException(message: String = "Glyph template does not match the one this style resolves")`
  - Error codes `FOREIGN_LABEL_CANDIDATE_PLAN`, `LABEL_CANDIDATE_PLAN_CLOSED`, `GLYPH_TEMPLATE_MISMATCH`.

- [ ] **Step 1: Write the failing test**

Add to `ApiContractTest.kt`:

```kotlin
@Test
fun labelCandidatePlanExceptionsCarryLifecycleCodes() {
    assertEquals(RentileErrorCode.FOREIGN_LABEL_CANDIDATE_PLAN, ForeignLabelCandidatePlanException().code)
    assertEquals(PipelineStage.LIFECYCLE, ForeignLabelCandidatePlanException().stage)
    assertEquals(RentileErrorCode.LABEL_CANDIDATE_PLAN_CLOSED, LabelCandidatePlanClosedException().code)
    assertEquals(PipelineStage.LIFECYCLE, LabelCandidatePlanClosedException().stage)
    assertEquals(RentileErrorCode.GLYPH_TEMPLATE_MISMATCH, GlyphTemplateMismatchException().code)
    assertEquals(PipelineStage.RESOURCE_ACQUISITION, GlyphTemplateMismatchException().stage)
}

@Test
fun glyphTemplateMismatchNeverEchoesATemplate() {
    // The message is a fact, not a diff: echoing either template could print a credential.
    val message = GlyphTemplateMismatchException().message.orEmpty()
    assertFalse(message.contains("http"))
    assertFalse(message.contains("{fontstack}"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kmp:jvmTest --tests '*ApiContractTest*'`
Expected: FAIL — compilation error, unresolved reference `ForeignLabelCandidatePlanException`.

- [ ] **Step 3: Add the codes and types**

Append to `RentileErrorCode` (append only, after `BATCH_RENDER_FAILED`):

```kotlin
    FOREIGN_LABEL_CANDIDATE_PLAN,
    LABEL_CANDIDATE_PLAN_CLOSED,
    GLYPH_TEMPLATE_MISMATCH,
```

Add after `ForeignPreparedBatchException` (`:165`):

```kotlin
public class ForeignLabelCandidatePlanException(
    message: String = "Label candidate plan belongs to another rasterizer",
) : RentileException(
    code = RentileErrorCode.FOREIGN_LABEL_CANDIDATE_PLAN,
    stage = PipelineStage.LIFECYCLE,
    message = message,
)

public class LabelCandidatePlanClosedException(
    message: String = "Label candidate plan is closed",
) : RentileException(
    code = RentileErrorCode.LABEL_CANDIDATE_PLAN_CLOSED,
    stage = PipelineStage.LIFECYCLE,
    message = message,
)

/**
 * The template passed to [LabelCandidatePlan.glyphUrls] is not the one this style resolves.
 *
 * Neither template appears in the message. Both can carry the provider credential, and a caller
 * that reached this exception already holds its own copy, so echoing them would leak a secret to
 * buy nothing. The usual cause is passing the style document's `glyphs` value unresolved when it
 * is a relative reference.
 */
public class GlyphTemplateMismatchException(
    message: String = "Glyph template does not match the one this style resolves",
) : RentileException(
    code = RentileErrorCode.GLYPH_TEMPLATE_MISMATCH,
    stage = PipelineStage.RESOURCE_ACQUISITION,
    message = message,
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :kmp:jvmTest --tests '*ApiContractTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/Exceptions.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/ApiContractTest.kt
git commit -m "feat(api): add label candidate plan and glyph template exceptions"
```

---

### Task 3: The public types and the two-phase split

The core of the change.

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt` — add types after `LabelCandidateBatch` (`:382-389`); add interface methods after `labelCandidateRequestKey` (`:597`)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt:532-578` (split), `:744-755` (ownership), end of file (new private class)
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/glyph/GlyphResourceAcquirer.kt:230-236` (no signature change; confirm `resolveUrl` stays the single composer)
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`

**Interfaces:**
- Consumes: `LabelAssembly` and `LabelCandidateAssembler.plan(...)` from Task 1; the three exceptions from Task 2.
- Produces:
  - `public data class GlyphRangeRef(public val fontStackDigest: String, public val rangeStart: Int)`
  - `public interface LabelCandidatePlan : AutoCloseable` with `val tiles: List<TileId>`, `val glyphClosure: List<GlyphRangeRef>`, `fun glyphUrls(template: String): List<String>`, `val diagnostics: List<RenderDiagnostic>`, `override fun close()`
  - `BasemapRasterizer.planLabelCandidates(style: PreparedStyle, tiles: List<TileId>, resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL): LabelCandidatePlan`
  - `BasemapRasterizer.acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch`

- [ ] **Step 1: Write the failing tests**

Add to `RentileRuntimeTest.kt`:

```kotlin
@Test
fun theGlyphClosureIsExactlyWhatTheAcquisitionRequests() = runTest {
    val vectorTile = placeNameVectorTile("Tokyo")
    val glyphs = testGlyphRange("Open Sans Regular", 0)
    val requested = mutableListOf<String>()
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            if (request.resourceClass == ResourceClass.GLYPH_RANGE) {
                requested += request.url
                TransportResponse(200, glyphs)
            } else {
                TransportResponse(200, vectorTile)
            }
        },
    )
    try {
        val template = "https://glyphs.example.test/{fontstack}/{range}.pbf"
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))

        val plan = rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1)))
        val predicted = plan.glyphUrls(template)

        // Nothing was fetched to learn the closure.
        assertTrue(requested.isEmpty())
        assertTrue(predicted.isNotEmpty())

        rasterizer.acquireLabelCandidates(plan)
        plan.close()

        // Equality, not containment: a superset would hide a route that is never fetched,
        // and an under-approximation is what fails closed behind a consumer's firewall.
        assertEquals(predicted.toSet(), requested.toSet())
        assertEquals(predicted.size, predicted.toSet().size)
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}

@Test
fun theOneShotPathMatchesTheTwoPhasePath() = runTest {
    val vectorTile = placeNameVectorTile("Tokyo")
    val glyphs = testGlyphRange("Open Sans Regular", 0)
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
            else TransportResponse(200, vectorTile)
        },
    )
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))
        val tiles = listOf(TileId(2, 1, 1))

        val oneShot = rasterizer.acquireLabelCandidates(style, tiles)
        val twoPhase = rasterizer.planLabelCandidates(style, tiles).use { plan ->
            rasterizer.acquireLabelCandidates(plan)
        }

        assertEquals(oneShot.contentKey, twoPhase.contentKey)
        assertEquals(oneShot.candidates, twoPhase.candidates)
        assertEquals(oneShot.layerStyles, twoPhase.layerStyles)
        assertEquals(oneShot.atlas.contentKey, twoPhase.atlas.contentKey)
        assertEquals(oneShot.diagnostics, twoPhase.diagnostics)
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}

@Test
fun aPlanIsReusableAndYieldsEqualBatches() = runTest {
    // Astral name: exercises the assembly path that used to mutate the shared tallies.
    val vectorTile = placeNameVectorTile("😀")
    val glyphs = testGlyphRange("Open Sans Regular", 0)
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
            else TransportResponse(200, vectorTile)
        },
    )
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))

        rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1))).use { plan ->
            val first = rasterizer.acquireLabelCandidates(plan)
            val second = rasterizer.acquireLabelCandidates(plan)
            assertEquals(first.contentKey, second.contentKey)
            assertEquals(first.candidates, second.candidates)
            assertEquals(first.diagnostics, second.diagnostics)
        }
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}

@Test
fun theClosureDedupesAcrossTilesAndIsStable() = runTest {
    val vectorTile = placeNameVectorTile("Tokyo")
    val glyphs = testGlyphRange("Open Sans Regular", 0)
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
            else TransportResponse(200, vectorTile)
        },
    )
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))
        val tiles = listOf(TileId(2, 1, 1), TileId(2, 2, 1))

        val first = rasterizer.planLabelCandidates(style, tiles).use { it.glyphClosure }
        val second = rasterizer.planLabelCandidates(style, tiles).use { it.glyphClosure }

        assertEquals(first, second)
        assertEquals(first.size, first.toSet().size)
        assertEquals(first.sortedWith(compareBy({ it.fontStackDigest }, { it.rangeStart })).size, first.size)
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}

@Test
fun aStyleWithoutGlyphsPlansAnEmptyClosure() = runTest {
    val vectorTile = placeNameVectorTile("Tokyo")
    val rasterizer = testRasterizer(transport = ResourceTransport { TransportResponse(200, vectorTile) })
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson(glyphs = null)))

        rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1))).use { plan ->
            assertTrue(plan.glyphClosure.isEmpty())
            // Not verified against any template: this plan will fetch nothing.
            assertTrue(plan.glyphUrls("https://anything.example.test/{fontstack}/{range}.pbf").isEmpty())
            assertTrue(plan.diagnostics.any { it.code == DiagnosticCode.GLYPH_RANGE_UNAVAILABLE })

            val batch = rasterizer.acquireLabelCandidates(plan)
            assertTrue(batch.candidates.isEmpty())
            assertTrue(batch.diagnostics.any { it.code == DiagnosticCode.GLYPH_RANGE_UNAVAILABLE })
        }
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}

@Test
fun aClosedOrForeignPlanIsRejected() = runTest {
    val vectorTile = placeNameVectorTile("Tokyo")
    val glyphs = testGlyphRange("Open Sans Regular", 0)
    val transport = ResourceTransport { request ->
        if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
        else TransportResponse(200, vectorTile)
    }
    val rasterizer = testRasterizer(transport = transport)
    val other = testRasterizer(transport = transport)
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))
        val plan = rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1)))

        assertFailsWith<ForeignLabelCandidatePlanException> { other.acquireLabelCandidates(plan) }

        plan.close()
        plan.close() // idempotent
        assertFailsWith<LabelCandidatePlanClosedException> { rasterizer.acquireLabelCandidates(plan) }
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
        other.close()
        other.awaitClosed()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kmp:jvmTest --tests '*RentileRuntimeTest*'`
Expected: FAIL — compilation error, unresolved reference `planLabelCandidates`.

- [ ] **Step 3: Add the public types to `Api.kt`**

Insert after `LabelCandidateBatch` (`:389`):

```kotlin
/**
 * One Glyph Range a [LabelCandidatePlan] will acquire. Identity only: no URL, no credential.
 *
 * The raw font stack is deliberately absent. `text-font` may be a data-driven expression, so a
 * resolved stack can carry bytes from a decoded feature property; [fontStackDigest] identifies it
 * without republishing it, and matches [LabelGlyphAtlasEntry.fontStackDigest] so a closure entry
 * correlates with the atlas entries it eventually produces.
 */
public data class GlyphRangeRef(
    public val fontStackDigest: String,
    /** The first codepoint of the 256-wide block, so `0`, `256`, `512` and so on. */
    public val rangeStart: Int,
)

/**
 * A frozen Glyph Closure for one tile set, held between Label Tile acquisition and Glyph Range
 * acquisition. Not a Prepared Batch; see CONTEXT.md.
 *
 * A caller that must know a glyph URL before it is fetched reads [glyphUrls] and then passes this
 * same plan to [BasemapRasterizer.acquireLabelCandidates]. Both read one frozen list, so the
 * closure cannot under-approximate the acquisition that follows it - which a second, independent
 * query could, because tile bytes can legitimately change between two acquisitions.
 *
 * Reusable: acquiring from one plan repeatedly yields equal batches.
 */
public interface LabelCandidatePlan : AutoCloseable {
    /** The de-duplicated tile set this plan was computed over, in (z, x, y) order. */
    public val tiles: List<TileId>

    /**
     * Exactly the Glyph Ranges [BasemapRasterizer.acquireLabelCandidates] will request from this
     * plan - not a superset and not an estimate. Sorted by resolved font stack then
     * [GlyphRangeRef.rangeStart], de-duplicated across every layer and tile in the batch, and
     * stable across runs. The sort key is not exposed, so the order is stable but not
     * re-derivable; [glyphUrls] returns its list in this same order.
     */
    public val glyphClosure: List<GlyphRangeRef>

    /**
     * The URL of every entry in [glyphClosure], in the same order, composed by Rentile's own
     * substitution so a caller never re-derives it.
     *
     * [template] is the caller's copy of the style's `glyphs` value, resolved against the style's
     * base URI. Rentile holds its own copy but will not emit it, because that copy can carry the
     * provider credential. Instead it checks the two agree and throws
     * [GlyphTemplateMismatchException] when they do not, so a relative reference passed
     * unresolved, a stale template, or another style's template fails here rather than as labels
     * that silently stop drawing.
     *
     * Returns an empty list, without checking [template], when the style resolves no `glyphs`
     * template: such a plan will fetch nothing.
     */
    public fun glyphUrls(template: String): List<String>

    public val diagnostics: List<RenderDiagnostic>

    /** Idempotent, non-blocking, and non-throwing. */
    override fun close()
}
```

Insert into `BasemapRasterizer` after `labelCandidateRequestKey` (`:597`):

```kotlin
    /**
     * Acquires this tile set's Label Tiles, decodes and evaluates them, and freezes the Glyph
     * Ranges the batch will need - without acquiring any of them.
     *
     * Tile substitution is deliberately not applied, exactly as in [acquireLabelCandidates].
     */
    public suspend fun planLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode = ResourceAccessMode.NORMAL,
    ): LabelCandidatePlan

    /**
     * Acquires [LabelCandidatePlan.glyphClosure] and assembles the batch, reusing the access mode
     * the plan was made with so one batch cannot disagree with itself about what caching meant.
     */
    public suspend fun acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch
```

- [ ] **Step 4: Expose the redacted template for the check**

`DefaultLabelCandidatePlan` needs the style's redacted glyph template. `ProtectedResourceUrl.canonicalUrl` (`internal/SecretContext.kt:9`) already is it, and `CompiledPreparedStyle.glyphsTemplate` is already `ProtectedResourceUrl?`. No change is needed here — confirm both by reading, and do not add an accessor.

- [ ] **Step 5: Split the rasterizer method**

In `DefaultBasemapRasterizer.kt`, replace `acquireLabelCandidates` (`:532-578`) with:

```kotlin
    override suspend fun planLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
    ): LabelCandidatePlan = operation {
        val compiledStyle = requireOwnedStyle(style)
        val stableTiles = tiles.toList()
        stableTiles.forEach { validateTile(it, compiledStyle.policy) }
        // A style with no glyphs template has no text to lay out. That is a legitimate style, and
        // this API is opt-in, so it reports and plans an empty closure rather than failing
        // (ADR 0026). The diagnostic is recorded here rather than at acquisition because it is a
        // planning fact: nothing about the tiles can change the answer.
        if (compiledStyle.glyphsTemplate == null) {
            recordDiagnosticSafely(LabelCandidateAssembler.glyphRangeUnavailable(stableTiles))
            return@operation DefaultLabelCandidatePlan(
                owner = owner,
                style = compiledStyle,
                tiles = stableTiles.distinct().sortedWith(LABEL_TILE_ORDER),
                resourceAccess = resourceAccess,
                assembly = null,
                limits = configuration.resourceLimits,
            )
        }

        val samples = compiledStyle.labelLayers
            .map { it.source }
            .distinctBy { it.idDigest }
            .flatMap { source -> stableTiles.mapNotNull { tile -> source.sampleFor(tile) } }
            .distinctBy { it.identity to it.outputTile }
        val tileOutcomes = supervisorScope {
            samples.map { sample ->
                async { sample to acquireOutcome { vectorAcquirer.acquire(sample, resourceAccess) } }
            }.awaitAll()
        }
        throwAcquisitionFailures(tileOutcomes.map { it.second })
        val resources = tileOutcomes.associate { (sample, outcome) ->
            (sample.source.idDigest to sample.outputTile) to (outcome as AcquisitionOutcome.Success).value
        }

        val assembly = LabelCandidateAssembler.plan(
            style = compiledStyle,
            tiles = stableTiles.distinct(),
            resources = resources,
            limits = configuration.resourceLimits,
            iconImageNameOf = ::evaluateIconImageName,
        )
        DefaultLabelCandidatePlan(
            owner = owner,
            style = compiledStyle,
            tiles = stableTiles.distinct().sortedWith(LABEL_TILE_ORDER),
            resourceAccess = resourceAccess,
            assembly = assembly,
            limits = configuration.resourceLimits,
        )
    }

    override suspend fun acquireLabelCandidates(plan: LabelCandidatePlan): LabelCandidateBatch = operation {
        val owned = requireOwnedLabelCandidatePlan(plan)
        // Read once, into a local, before any suspension: a concurrent close() must not be able
        // to pull state out from under an acquisition already in flight.
        val state = owned.stateForAcquisition()
        val assembly = state.assembly
            ?: return@operation LabelCandidateAssembler.emptyBatch(state.style, state.tiles, state.limits)

        val glyphsTemplate = state.style.glyphsTemplate
            ?: return@operation LabelCandidateAssembler.emptyBatch(state.style, state.tiles, state.limits)
        val rangeOutcomes = supervisorScope {
            assembly.requiredRanges.map { request ->
                async {
                    acquireOutcome {
                        glyphAcquirer.acquire(
                            glyphsTemplate.resolve(),
                            request.fontStack,
                            request.rangeStart,
                            state.resourceAccess,
                        )
                    }
                }
            }.awaitAll()
        }
        throwAcquisitionFailures(rangeOutcomes)
        val ranges = rangeOutcomes.map { (it as AcquisitionOutcome.Success<AcquiredGlyphRange>).value }

        assembly.assemble(ranges, ::recordDiagnosticSafely)
    }

    override suspend fun acquireLabelCandidates(
        style: PreparedStyle,
        tiles: List<TileId>,
        resourceAccess: ResourceAccessMode,
    ): LabelCandidateBatch {
        val plan = planLabelCandidates(style, tiles, resourceAccess)
        return try {
            acquireLabelCandidates(plan)
        } finally {
            plan.close()
        }
    }
```

Import `LABEL_TILE_ORDER` from `internal.glyph` if it is not already in scope; it is declared at `LabelCandidateAssembler.kt:91`.

Add beside `requireOwnedBatch` (`:750`):

```kotlin
    private fun requireOwnedLabelCandidatePlan(plan: LabelCandidatePlan): DefaultLabelCandidatePlan {
        val owned = plan as? DefaultLabelCandidatePlan ?: throw ForeignLabelCandidatePlanException()
        if (owned.owner !== owner) throw ForeignLabelCandidatePlanException()
        return owned
    }
```

- [ ] **Step 6: Add the plan implementation**

Append to `DefaultBasemapRasterizer.kt`, beside `DefaultPreparedBatch` (`:2943`):

```kotlin
/** Everything one acquisition reads off a plan, captured atomically so close() cannot race it. */
private class LabelCandidatePlanState(
    val style: CompiledPreparedStyle,
    val tiles: List<TileId>,
    val resourceAccess: ResourceAccessMode,
    val assembly: LabelAssembly?,
    val limits: ResourceLimits,
)

@OptIn(ExperimentalAtomicApi::class)
private class DefaultLabelCandidatePlan(
    val owner: Any,
    style: CompiledPreparedStyle,
    override val tiles: List<TileId>,
    resourceAccess: ResourceAccessMode,
    assembly: LabelAssembly?,
    limits: ResourceLimits,
) : LabelCandidatePlan {
    private val closed = AtomicBoolean(false)
    private val state = LabelCandidatePlanState(style, tiles, resourceAccess, assembly, limits)

    override val glyphClosure: List<GlyphRangeRef> =
        state.assembly?.requiredRanges.orEmpty().map { request ->
            GlyphRangeRef(
                fontStackDigest = request.fontStack.sha256Hex(),
                rangeStart = request.rangeStart,
            )
        }

    override val diagnostics: List<RenderDiagnostic> =
        if (state.assembly == null) {
            style.diagnostics + LabelCandidateAssembler.glyphRangeUnavailable(tiles)
        } else {
            style.diagnostics
        }

    override fun glyphUrls(template: String): List<String> {
        val resolved = state.style.glyphsTemplate ?: return emptyList()
        if (state.assembly == null) return emptyList()
        // Compared in redacted form, so two copies of one template that differ only by credential
        // agree, and neither side has to reveal its own. A relative reference passed unresolved
        // does not agree, which is the case worth catching: it would compose URLs that look right
        // and are never the ones fetched.
        if (template.withRedactedAuthenticationQuery() != resolved.canonicalUrl) {
            throw GlyphTemplateMismatchException()
        }
        return state.assembly.requiredRanges.map { request ->
            GlyphResourceAcquirer.resolveUrl(template, request.fontStack, request.rangeStart)
        }
    }

    override fun close() {
        closed.store(true)
    }

    fun stateForAcquisition(): LabelCandidatePlanState {
        if (closed.load()) throw LabelCandidatePlanClosedException()
        return state
    }
}
```

`sha256Hex`, `withRedactedAuthenticationQuery`, `GlyphResourceAcquirer`, `LabelAssembly` and `ResourceLimits` all need imports; check the file's existing import block first, several are already there.

- [ ] **Step 7: Run the tests**

Run: `./gradlew :kmp:jvmTest --tests '*RentileRuntimeTest*'`
Expected: PASS, including all six new tests and every pre-existing label test.

- [ ] **Step 8: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/Api.kt \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt
git commit -m "feat(label): freeze the glyph closure in a label candidate plan

planLabelCandidates acquires the batch's label tiles and freezes the glyph
ranges it will need; acquireLabelCandidates(plan) fetches exactly that list.
Both read one frozen closure, so a caller preregistering exact URLs cannot be
handed a closure that under-approximates the acquisition that follows it."
```

---

### Task 4: Template verification and encoding fidelity

Task 3 wires the check; this task proves it holds for the inputs that motivated it.

**Files:**
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`
- Modify (only if a test fails): `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt` (the `glyphUrls` body)

**Interfaces:**
- Consumes: `LabelCandidatePlan.glyphUrls` and `GlyphTemplateMismatchException` from Task 3.
- Produces: nothing new.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun glyphUrlsRejectAMismatchedTemplateAndAcceptACredentialDifference() = runTest {
    val vectorTile = placeNameVectorTile("Tokyo")
    val glyphs = testGlyphRange("Open Sans Regular", 0)
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            if (request.resourceClass == ResourceClass.GLYPH_RANGE) TransportResponse(200, glyphs)
            else TransportResponse(200, vectorTile)
        },
    )
    try {
        val template = "https://glyphs.example.test/{fontstack}/{range}.pbf?key=SECRET"
        val style = rasterizer.prepare(
            StyleInput.InlineJson(placeNameStyleJson(glyphs = template)),
        )
        rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1))).use { plan ->
            // Same template: fine.
            assertTrue(plan.glyphUrls(template).isNotEmpty())
            // Differs only by credential value: redaction makes them agree.
            assertTrue(
                plan.glyphUrls("https://glyphs.example.test/{fontstack}/{range}.pbf?key=OTHER")
                    .isNotEmpty(),
            )
            // A relative reference passed unresolved: the case this check exists for.
            assertFailsWith<GlyphTemplateMismatchException> { plan.glyphUrls("/{fontstack}/{range}.pbf") }
            // Another host entirely.
            assertFailsWith<GlyphTemplateMismatchException> {
                plan.glyphUrls("https://elsewhere.example.test/{fontstack}/{range}.pbf?key=SECRET")
            }
        }
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}

@Test
fun glyphUrlsEncodeAFontStackExactlyAsTheFetchDoes() = runTest {
    // A data-driven text-font puts decoded feature-property bytes into the stack, so the
    // encoding has to survive a space, a fragment marker, a query marker, path traversal
    // and non-Latin bytes. Any divergence between this and resolveUrl fails closed.
    val hostileStack = "Noto Sans #1?a/../b 日本"
    val vectorTile = placeNameVectorTile("Tokyo")
    val glyphs = testGlyphRange(hostileStack, 0)
    val requested = mutableListOf<String>()
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            if (request.resourceClass == ResourceClass.GLYPH_RANGE) {
                requested += request.url
                TransportResponse(200, glyphs)
            } else {
                TransportResponse(200, vectorTile)
            }
        },
    )
    try {
        val template = "https://glyphs.example.test/{fontstack}/{range}.pbf"
        val style = rasterizer.prepare(
            StyleInput.InlineJson(placeNameStyleJson(textFont = """["$hostileStack"]""")),
        )
        rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1))).use { plan ->
            val predicted = plan.glyphUrls(template)
            rasterizer.acquireLabelCandidates(plan)
            assertEquals(predicted.toSet(), requested.toSet())
            // single(): "Tokyo" lives entirely in block 0, so the closure holds one entry.
            // The hostile bytes are escaped rather than passed through.
            assertTrue(predicted.single().contains("%20"))
            assertTrue(predicted.single().contains("%23"))
            assertTrue(predicted.single().contains("%3F"))
            assertFalse(predicted.single().contains("../"))
        }
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}
```

`placeNameStyleJson` at `:4377` hardcodes `"text-font":["Open Sans Regular"]`. Add a parameter:

```kotlin
    private fun placeNameStyleJson(
        // ... existing parameters unchanged, then:
        textFont: String = """["Open Sans Regular"]""",
    ): String
```

and change the layout line at `:4396` to use `"text-font":$textFont`.

`testGlyphRange(fontStack, rangeStart)` at `:4348` encodes the stack into the range payload; pass `hostileStack` so the decoded range's name matches what the URL asked for.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :kmp:jvmTest --tests '*RentileRuntimeTest.glyphUrls*'`
Expected: FAIL — compilation error on the new `textFont` parameter before it is added; after adding it, both tests must pass without touching `glyphUrls`. If either fails on an assertion, fix `glyphUrls` in `DefaultBasemapRasterizer.kt` rather than the test.

- [ ] **Step 3: Run the full label suite**

Run: `./gradlew :kmp:jvmTest --tests '*RentileRuntimeTest*'`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt \
        kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/DefaultBasemapRasterizer.kt
git commit -m "test(label): pin glyph url composition and the template check

A data-driven text-font puts decoded feature-property bytes into the stack,
so the predicted URL and the fetched URL are asserted equal over a stack
carrying a space, a fragment marker, a query marker and path traversal."
```

---

### Task 5: Safety limit and access mode at plan time

The `maxGlyphRangesPerBatch` ceiling and `CACHE_ONLY` failures now surface from `planLabelCandidates`, before a caller preregisters anything. Pin that.

**Files:**
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`

**Interfaces:**
- Consumes: `planLabelCandidates` from Task 3.
- Produces: nothing new.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun theGlyphRangeCeilingThrowsFromPlanningNotAcquisition() = runTest {
    // Text spanning more 256-codepoint blocks than the ceiling allows. Deliberately CJK:
    // a naive ramp like (it * 256) + 65 walks through the Arabic block at it = 6, and
    // ScriptSupport.requiresComplexShaping would exclude the whole label, leaving zero
    // ranges rather than 71. CJK needs many codepoints and no reordering (ADR 0025).
    val wide = buildString { (0..70).forEach { append((0x4E00 + it * 256).toChar()) } }
    val vectorTile = placeNameVectorTile(wide)
    val rasterizer = testRasterizer(
        transport = ResourceTransport { TransportResponse(200, vectorTile) },
        resourceLimits = ResourceLimits(maxGlyphRangesPerBatch = 8),
    )
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))
        val error = assertFailsWith<SafetyLimitException> {
            rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1)))
        }
        assertEquals("maxGlyphRangesPerBatch", error.limitName)
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}

@Test
fun cacheOnlyPlanningFailsBeforeAnyGlyphIsConsidered() = runTest {
    val requested = mutableListOf<ResourceClass>()
    val rasterizer = testRasterizer(
        transport = ResourceTransport { request ->
            requested += request.resourceClass
            TransportResponse(200, ByteArray(0))
        },
    )
    try {
        val style = rasterizer.prepare(StyleInput.InlineJson(placeNameStyleJson()))
        requested.clear()
        assertFailsWith<ResourceAcquisitionException> {
            rasterizer.planLabelCandidates(style, listOf(TileId(2, 1, 1)), ResourceAccessMode.CACHE_ONLY)
        }
        assertTrue(requested.isEmpty())
    } finally {
        rasterizer.close()
        rasterizer.awaitClosed()
    }
}
```

Check `testRasterizer` at `:4158` for whether it already accepts a `resourceLimits` parameter. If it does not, add one defaulting to `ResourceLimits()` and thread it into the `RentileConfiguration` it builds.

- [ ] **Step 2: Run tests**

Run: `./gradlew :kmp:jvmTest --tests '*RentileRuntimeTest.theGlyphRangeCeiling*' --tests '*RentileRuntimeTest.cacheOnlyPlanning*'`
Expected: PASS. Both behaviours fall out of Task 3's split; these tests pin them so a later refactor cannot move the throw back to acquisition time.

If `theGlyphRangeCeilingThrowsFromPlanningNotAcquisition` fails because the fixture text does not produce enough distinct ranges, widen `wide` — every codepoint must land in a distinct 256-block and be non-astral, so keep each below `0xFFFF`.

- [ ] **Step 3: Commit**

```bash
git add kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt
git commit -m "test(label): pin the range ceiling and cache-only failure to plan time

Both now throw before a caller has preregistered anything, which is the
improvement the two-phase split buys."
```

---

### Task 6: Documentation

**Files:**
- Modify: `docs/error-model.md:20-31`
- Modify: `docs/adr/0024-label-placement-belongs-to-the-consumer.md` (final paragraph)
- Modify: `instructions.md:299`
- Modify: `compatibility/README.md:19`
- Modify: `gradle.properties` (`VERSION_NAME`)

**Interfaces:**
- Consumes: everything from Tasks 1-5.
- Produces: nothing.

- [ ] **Step 1: Backfill and extend the exception families**

`docs/error-model.md` lists 11 families; `Exceptions.kt` declares 20 after Task 2. Add all nine missing entries to the bulleted list, after `ForeignPreparedStyleException`:

```markdown
- `ForeignPreparedBatchException` — a prepared-batch handle was passed to a rasterizer instance that did not create it.
- `ForeignLabelCandidatePlanException` — a label-candidate-plan handle was passed to a rasterizer instance that did not create it.
- `LabelCandidatePlanClosedException` — a caller attempted acquisition from a label candidate plan after closing it.
- `GlyphTemplateMismatchException` — the glyphs template passed to `LabelCandidatePlan.glyphUrls` is not the one the prepared style resolves.
- `InvalidTileIdException` — a tile identity fell outside the supported XYZ range or the profile's output zooms.
- `TileNotInPreparedBatchException` — a render was requested for a tile the prepared batch does not carry.
- `TileSubstitutionLimitException` — more output tiles needed substitutes than the substitution policy allows.
- `TileSubstitutionException` — a substitute could not be resolved for an output tile that required one.
- `BatchRenderException` — one or more output tiles in a caller-defined render failed.
```

- [ ] **Step 2: Qualify ADR 0024**

Its final paragraph ends: "...a Label Candidate Batch cannot freeze its closure before acquisition and is therefore not a Prepared Batch; labels are not foldable into `prepareBatch`." Replace that clause with:

```markdown
Because the Glyph Ranges a tile set needs depend on decoded feature properties rather than on style and tile identities alone, a Label Candidate Batch cannot freeze its closure before its Label Tiles are acquired, and is therefore not a Prepared Batch; labels are not foldable into `prepareBatch`. It can freeze that closure *after* them, which is what a Label Candidate Plan holds — see [ADR 0028](0028-freeze-the-glyph-closure-in-a-label-candidate-plan.md).
```

- [ ] **Step 3: Update the two consumer-facing notes**

`instructions.md:299` — after "calls `acquireLabelCandidates` and does its own placement and rendering on top", add: "A host that must know its glyph URLs before they are fetched calls `planLabelCandidates` first and reads them from the plan."

`compatibility/README.md:19` — the gate already checks the glyph-range count against `maxGlyphRangesPerBatch`. Add one sentence: "The gate also plans each of those cases before acquiring it and asserts that the plan's `glyphUrls` set equals the set of glyph URLs the acquisition then requests, which is the property a consumer preregistering exact URLs depends on."

- [ ] **Step 4: Request the minor version**

`gradle.properties` currently has `VERSION_NAME=0.4.0`. Adding abstract members to the public `BasemapRasterizer` interface is source-breaking for implementers, which is how the label APIs themselves arrived (`f865320` cut `0.3.0`). Set:

```properties
VERSION_NAME=0.5.0
```

- [ ] **Step 5: Verify the whole build**

Run: `./gradlew :kmp:jvmTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add docs/error-model.md docs/adr/0024-label-placement-belongs-to-the-consumer.md \
        instructions.md compatibility/README.md gradle.properties
git commit -m "docs(label): document the plan, backfill nine exception families

error-model.md presented itself as enumerating the exception families while
missing six that had shipped, including BatchRenderException, which render()
throws. Adds those alongside this change's three."
```

---

### Task 7: Corpus gate assertion

The closure-exactness property proved against fixtures in Task 3 is worth proving against the 34 real styles the corpus gate already runs.

**Files:**
- Modify: `kmp/src/androidHostTest/kotlin/com/rohittp/rentile/MapCatalogCorpusSmokeTest.kt`

**Interfaces:**
- Consumes: `planLabelCandidates` and `LabelCandidatePlan.glyphUrls` from Task 3.
- Produces: nothing.

- [ ] **Step 1: Read the existing label section of the gate**

Run: `grep -n "acquireLabelCandidates" -B 20 -A 30 kmp/src/androidHostTest/kotlin/com/rohittp/rentile/MapCatalogCorpusSmokeTest.kt`

Find where it calls `acquireLabelCandidates` for the three geographies and how it records the distinct glyph-range count per style. The transport it uses must be wrapped to record glyph URLs; check whether one already records requests before adding a wrapper.

- [ ] **Step 2: Replace the call with the two-phase form and assert exactness**

Change the acquisition to plan first, predict, acquire, then compare — recording glyph URLs through the gate's transport:

```kotlin
val predicted = rasterizer.planLabelCandidates(style, listOf(tile)).use { plan ->
    val urls = plan.glyphUrls(glyphsTemplateFor(styleJson))
    val batch = rasterizer.acquireLabelCandidates(plan)
    recordLabelStats(batch)
    urls
}
assertEquals(
    predicted.toSet(),
    recordedGlyphUrls.toSet(),
    "glyph closure did not match the URLs acquired",
)
```

`glyphsTemplateFor(styleJson)` reads the style document's `glyphs` value and resolves it against the style URL the gate already fetched from, mirroring `StyleCompiler.kt:146-156`: absolute `http://` or `https://` references pass through, anything else resolves against the base URI. Reuse whatever helper the gate already has for the style URL; do not add a dependency to parse it.

Clear the recorded URL list before each style so one style's routes cannot mask another's.

- [ ] **Step 3: Run the gate**

Run: `./gradlew :kmp:androidHostTest --tests '*MapCatalogCorpusSmokeTest*'`
Expected: PASS across the corpus. This gate takes tens of minutes and needs catalog credentials; if they are unavailable in this environment, confirm the file compiles with `./gradlew :kmp:compileDebugAndroidTestKotlinAndroid` (or the equivalent task the module exposes) and note in the commit message that the gate was not executed locally.

- [ ] **Step 4: Commit**

```bash
git add kmp/src/androidHostTest/kotlin/com/rohittp/rentile/MapCatalogCorpusSmokeTest.kt
git commit -m "test(corpus): assert glyph closure exactness across the rolling corpus"
```

---

## Verification

After Task 7:

- [ ] `./gradlew :kmp:jvmTest` passes.
- [ ] `git log --oneline` shows seven commits, one per task.
- [ ] `grep -rn "LabelPlan\b" kmp/ docs/` returns nothing — the intermediate name from the spec's first draft must not survive anywhere.
- [ ] `grep -rn "urlFontStack" kmp/ docs/` returns nothing.
- [ ] `LabelCandidateBatch.diagnostics` is unchanged for the one-shot path, proved by `theOneShotPathMatchesTheTwoPhasePath`.
