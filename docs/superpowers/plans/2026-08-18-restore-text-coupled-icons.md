# Restore Text-Coupled Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw the icon of a symbol layer that pairs an icon with required text, instead of excluding the whole layer, while still excluding icons whose size derives from text extents.

**Architecture:** One condition in `StyleCompiler.classifySymbol` currently excludes a symbol layer whenever its text is not explicitly `text-optional`. That conflates two unrelated situations. Split it so the deciding factor is `icon-text-fit` alone: an icon whose geometry is independent of the text is retained and the text is dropped; an icon sized from text extents stays excluded because Rentile has no text metrics to size it with.

**Tech Stack:** Kotlin Multiplatform, Gradle, kotlin.test, Skiko/Skia.

**Spec:** `docs/superpowers/specs/2026-08-18-label-candidates-design.md` (section "Release sequence", item 1)

> **Outcome — shipped as `0.2.0` on 2026-08-19.** The plan's two tasks became seven implementer rounds and five reviews. The premise that a retained icon is "fully computable without the text" held geometrically but not in practice: these layers had never been compiled, and making them reachable exposed unsupported constructs, an unfetched sprite, an unfetched source, and an unfetched render-time tileset, each of which had to degrade rather than fail. Measured benefit across the rolling corpus is one style gaining its markers, with zero regressions; the larger value is a duplicated classification rule deleted and a cache-invalidation defect caught before publication. See [ADR 0026](../../adr/0026-repaired-layers-degrade-and-author-intended-layers-fail.md).

## Global Constraints

- This is release 1 of 2 and ships **alone**. Do not begin any Label Candidate work in this plan.
- `DiagnosticCode` is public ABI. Do not remove or reorder any enum entry. `TEXT_COUPLED_ICON_LAYER_EXCLUDED` keeps its entry and keeps firing, only for a narrower case.
- No new public API symbol. `./gradlew :kmp:checkKotlinAbi` must pass with the committed dump unchanged.
- This changes rendered output for 21 of 34 rolling-corpus styles (419 affected layers, measured 2026-08-18). That is the point of the release, and it is why nothing else may ride along.
- ~~Release is a patch. Do not touch `VERSION_NAME`.~~ **Superseded during execution:** the work required two new public `DiagnosticCode` entries, and appending to a public Kotlin enum is source-incompatible for a consumer with an exhaustive `when`, so this shipped as the minor `0.2.0` with `VERSION_NAME` set deliberately (ADR 0023).

---

### Task 1: Split the text-coupled classification on `icon-text-fit`

**Files:**
- Modify: `kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleCompiler.kt:1396-1412`
- Test: `kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt`

**Interfaces:**
- Consumes: `SymbolClassification(retained: Boolean, diagnostic: RenderDiagnostic?)`, the existing private return type of `classifySymbol`. `diagnostic(code, severity, message, details)` is an existing private helper in the same class.
- Produces: no new symbols. Behaviour change only.

Background an implementer needs. `classifySymbol` runs per symbol layer during `prepare`. Before the block you are changing it has already established that the layer has a meaningful `icon-image`, is not hidden, and has meaningful `text-field`. Two of the three existing outcomes are already pinned by tests: `TEXT_ONLY_LAYER_EXCLUDED` at `RentileRuntimeTest.kt:263` and `:280`, and `TEXT_COMPONENT_REMOVED_ICON_RETAINED` at `:512`. The third, `TEXT_COUPLED_ICON_LAYER_EXCLUDED`, has no test at all — which is why its behaviour was never examined.

`icon-text-fit` is a Mapbox layout property with values `none`, `width`, `height`, `both`. When it is anything but `none`, the icon is stretched to fit the rendered text, so its width or height is a function of text extents. Rentile draws no text and has no glyph metrics, so it genuinely cannot compute that geometry — those layers must stay excluded. When it is absent or `none`, the icon's size comes from the sprite and `icon-size` only, so the icon is fully computable without the text.

- [ ] **Step 1: Write the failing test for the case that should now be retained**

Add to `RentileRuntimeTest.kt`, beside the other symbol classification tests:

```kotlin
    @Test
    fun requiredTextIsRemovedAndAnIndependentIconIsRetained() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"poi","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED })
            assertTrue(style.diagnostics.none { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
```

Note there is no `text-optional` in that layout. That is the whole point: today this layer is excluded outright.

This test needs a sprite to resolve. Copy the transport stub the existing sprite test at `RentileRuntimeTest.kt:506` uses — find how it supplies `sprite.json` and `sprite.png` and pass the same `transport = ...` argument to `testRasterizer(...)`. Do not invent a new stub.

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest.requiredTextIsRemovedAndAnIndependentIconIsRetained'`

Expected: FAIL. The assertion on `TEXT_COMPONENT_REMOVED_ICON_RETAINED` fails because the layer emits `TEXT_COUPLED_ICON_LAYER_EXCLUDED` instead.

- [ ] **Step 3: Write the failing test for the case that must stay excluded**

Add immediately after the previous test:

```kotlin
    @Test
    fun anIconSizedFromTextExtentsStaysExcluded() = runTest {
        val rasterizer = testRasterizer()
        try {
            val style = rasterizer.prepare(
                StyleInput.InlineJson(
                    """{"version":8,"sprite":"https://sprite.example.test/icons","sources":{"v":{"type":"vector","tiles":["https://tiles.example.test/{z}/{x}/{y}.pbf"]}},"layers":[{"id":"shield","type":"symbol","source":"v","source-layer":"poi","layout":{"icon-image":"marker","icon-text-fit":"width","text-field":["get","name"]}}]}""",
                ),
            )

            assertTrue(style.diagnostics.any { it.code == DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED })
        } finally {
            rasterizer.close()
            rasterizer.awaitClosed()
        }
    }
```

Use the same sprite transport stub as the previous test.

- [ ] **Step 4: Run it and confirm it passes already**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest.anIconSizedFromTextExtentsStaysExcluded'`

Expected: PASS. This test pins behaviour that is already correct and currently untested. Its job is to fail loudly if step 5 over-reaches and retains `icon-text-fit` layers too.

- [ ] **Step 5: Make the condition depend on `icon-text-fit` alone**

In `StyleCompiler.classifySymbol`, replace this:

```kotlin
        val textOptional = layout["text-optional"]?.asPrimitive()?.booleanOrNull == true
        val iconTextFit = layout["icon-text-fit"]?.asPrimitive()?.content
        if (textOptional && (iconTextFit == null || iconTextFit == "none")) {
            val diagnostic = diagnostic(
                code = DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED,
                severity = DiagnosticSeverity.WARNING,
                message = "Optional text is removed and the icon is retained independently",
                details = identity,
            )
            return SymbolClassification(true, diagnostic)
        }
        return SymbolClassification(false, diagnostic(
            code = DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED,
            severity = DiagnosticSeverity.INFO,
            message = "A text-coupled icon layer is excluded by the compatibility profile",
            details = identity,
        ))
```

with this:

```kotlin
        val iconTextFit = layout["icon-text-fit"]?.asPrimitive()?.content
        if (iconTextFit == null || iconTextFit == "none") {
            val diagnostic = diagnostic(
                code = DiagnosticCode.TEXT_COMPONENT_REMOVED_ICON_RETAINED,
                severity = DiagnosticSeverity.WARNING,
                message = "Text is removed and the icon is retained independently",
                details = identity,
            )
            return SymbolClassification(true, diagnostic)
        }
        return SymbolClassification(false, diagnostic(
            code = DiagnosticCode.TEXT_COUPLED_ICON_LAYER_EXCLUDED,
            severity = DiagnosticSeverity.INFO,
            message = "An icon sized from text extents is excluded by the compatibility profile",
            details = identity,
        ))
```

Three things changed and each matters. `text-optional` no longer participates, so `textOptional` is deleted rather than left unused. The retained message drops the word "Optional", because it now describes required text too. The excluded message names the real reason, so the diagnostic explains itself in a Corpus Report.

- [ ] **Step 6: Run both new tests and the whole common suite**

Run: `./gradlew :kmp:jvmTest --tests 'com.rohittp.rentile.RentileRuntimeTest'`

Expected: PASS, including the pre-existing `retainsIndependentIconsAndReportsSpriteDiagnostics`-style test at `RentileRuntimeTest.kt:506` that asserts `TEXT_COMPONENT_REMOVED_ICON_RETAINED` for a `text-optional` layer. That layer has no `icon-text-fit`, so the new condition still retains it.

If any test fails asserting the old message string, update the assertion to the new message — messages are not ABI. If any test fails asserting `TEXT_COUPLED_ICON_LAYER_EXCLUDED` for a layer with no `icon-text-fit`, that test encoded the bug; change it to expect `TEXT_COMPONENT_REMOVED_ICON_RETAINED` and say so in the commit message.

- [ ] **Step 7: Confirm no public ABI drift**

Run: `./gradlew :kmp:checkKotlinAbi`

Expected: PASS with no changes to `kmp/api/jvm/kmp.api` or `kmp/api/kmp.klib.api`. This change adds no public symbol. If the task reports a diff, something beyond this plan's scope was modified — revert it.

- [ ] **Step 8: Run the host and Apple test suites**

Run: `./gradlew :kmp:testAndroidHostTest :kmp:jvmTest :kmp:macosArm64Test`

Expected: PASS. The corpus smoke test is skipped unless `RENTILE_COVERAGE_MANIFEST` is set, so this run does not hit the network.

- [ ] **Step 9: Commit**

```bash
git add kmp/src/commonMain/kotlin/com/rohittp/rentile/internal/style/StyleCompiler.kt \
        kmp/src/commonTest/kotlin/com/rohittp/rentile/RentileRuntimeTest.kt
git commit -m "fix(style): keep the icon when text is required but the icon is not sized from it

classifySymbol excluded a whole symbol layer whenever its text was not
text-optional, discarding an icon whose geometry never depended on the text.
The deciding factor is icon-text-fit: absent or none means the sprite and
icon-size alone determine the icon, so the icon is retained and the text
dropped. A real icon-text-fit coupling still excludes the layer, because
Rentile has no text metrics to size that icon with, and
TEXT_COUPLED_ICON_LAYER_EXCLUDED now fires only for that case.

Both branches are now pinned by tests; the excluded branch had none."
```

---

### Task 2: Re-gate the corpus and record the output change

**Files:**
- No source changes. This task produces evidence.

**Interfaces:**
- Consumes: the behaviour from Task 1.
- Produces: a corpus report confirming the change is the intended one and nothing regressed.

- [ ] **Step 1: Run the rolling-corpus gate**

```bash
RENTILE_COVERAGE_MANIFEST="$PWD/compatibility/rentile-v1-coverage.json" \
RENTILE_CORPUS_REPORT_DIR="$PWD/build/reports/rentile-corpus-iconfix" \
./gradlew :kmp:testAndroidHostTest --tests com.rohittp.rentile.MapCatalogCorpusSmokeTest
```

Expected: all 34 styles pass. Takes roughly 8 minutes and requires network.

This gate is known to be flaky against transient sprite-transport exceptions: a run on 2026-08-18 failed 6 styles on `RESOURCE_ACQUISITION_FAILED` / "Sprite transport failed" and an immediate re-run passed 34/34. If styles fail with exactly that error code and message, re-run once before investigating. A failure with any other error code is real.

- [ ] **Step 2: Confirm the diagnostic mix changed in the expected direction**

```bash
cut -f10 build/reports/rentile-corpus-iconfix/results.tsv \
  | grep -o 'TEXT_[A-Z_]*' | sort | uniq -c | sort -rn
```

Expected: `TEXT_COMPONENT_REMOVED_ICON_RETAINED` appears for many more styles than before, and `TEXT_COUPLED_ICON_LAYER_EXCLUDED` still appears — it should not vanish, because 12 corpus styles use `icon-text-fit`. If `TEXT_COUPLED_ICON_LAYER_EXCLUDED` disappears entirely, the condition was over-relaxed and `icon-text-fit` layers are being drawn with wrong geometry.

- [ ] **Step 3: Eyeball two affected styles**

Open `build/reports/rentile-corpus-iconfix/index.html` and compare the contact sheet for styles `17` (Default) and `83` (Outdoor) against the previous release's report if you have one. Expect additional markers to appear. Confirm they are placed at sensible points and are not stretched or clipped — stretching would mean an `icon-text-fit` layer slipped through.

- [ ] **Step 4: Push and let the release gates run**

```bash
git checkout main && git merge --ff-only fix/text-coupled-icons && git push origin main
```

Pushing the feature branch publishes nothing: `publish.yml` triggers only on `push: branches: [main]`, with `paths-ignore` for `docs/**`, `**/*.md` and `LICENSE`. Merging to `main` is what releases. Watch it:

```bash
gh run list --repo rohittp0/rentile --workflow publish.yml --limit 1
gh run watch RUN_ID --repo rohittp0/rentile --exit-status
```

Do not start the Label Candidate plan until this release is published, so that any output regression reported by a consumer is attributable to this diff alone.
