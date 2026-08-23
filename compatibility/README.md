# Rolling public map corpus and coverage

Rentile validates `rentile-v1` against the latest map styles listed by the public, paginated catalog at:

```text
https://dashboard.lascade.com/travel_animator/v0/maps/
```

The harness follows the catalog's `next` links, requires all pages to remain on the exact catalog origin and path, checks the declared count, rejects duplicate IDs, and requires the resulting ID set to match the committed Coverage Manifest. Style JSON and provider resources are fetched at test time and are never committed.

## Credential-free Coverage Manifest

`rentile-v1-coverage.json` commits only stable map IDs, the supported z0–z22 range, XYZ cases, seam mosaics, overzoom gates, capability names and their expected dispositions, and the diagnostic policy that turns known omissions into gate failures. It contains no style JSON, style URL, provider URL, credential, or frozen layer count.

Each required capability has one of five dispositions: `evaluated`, `rasterized`, `resource-acquired`, `sprite-decoded`, or `label-candidate`. Static style inspection records only that a construct is declared. A `label-candidate` capability is counted as exercised only when a sampled public `LabelCandidateBatch` carries matching semantic evidence: for example, text-fit requires a non-null icon whose `textFit` is not `NONE`, and line placement requires `LINE` or `LINE_CENTER` plus non-empty line geometry. Functional placement and padding require a non-default or differing resolved value; functional transform requires an uppercase/lowercase result or differing resolved case; functional anchor uses non-empty finite candidate-local geometry because the public candidate already folds the anchor into its geometry rather than exposing an anchor enum. A descriptor alone never satisfies them. A full-corpus run fails when required runtime evidence is absent. A single-style run selected with `RENTILE_CORPUS_STYLE_ID` still enforces that style's fidelity rules, but deliberately skips the aggregate “every required capability was exercised” assertion.

The fidelity policy additionally requires every visible text-bearing vector symbol layer to have a public `LabelLayerDescriptor`. It rejects `UNSUPPORTED_TEXT_CONSTRUCT`, `LINE_PLACEMENT_LABEL_EXCLUDED`, and `TEXT_COUPLED_ICON_LAYER_EXCLUDED` instead of accepting the corresponding layer-level loss. `TEXT_ONLY_LAYER_EXCLUDED` remains valid for the PNG path only: the same layer must still be present through the label interface. Layer IDs used while checking this are never written to the report; failures carry only their digest.

Candidate contribution is tracked separately from descriptor coverage. The gate reports descriptors that produced no candidate in its sampled geographies and zooms, using only layer-ID digests. `requireEveryDescriptorToContribute` is currently `false`, because absence from a finite sample can mean the tile has no matching feature rather than an implementation omission. It is an explicit transitional switch, not an ignored result: the observation is visible in the HTML report and can become fatal once the coverage cases prove every descriptor has reachable source data.

Required capabilities describe the rolling catalog, not Rentile's entire supported grammar. A live declaration audit on 2026-08-23 found no expression-form `<` and no TMS source: the catalog's remaining `<` occurrences are legacy filters, which are tracked by `legacy-filter`. The stale aggregate `expression-less-than` and `tms-source` requirements were therefore removed from the manifest instead of manufacturing corpus evidence. Support remains pinned by `StyleExpressionTest` and `VectorSourceTest`; removing a rolling-corpus requirement does not remove or weaken the implementation contract.

Two cases, `tokyo-cjk-dense` and `cairo-rtl`, exist specifically to keep non-Latin text rendering honest. Tokyo is dense and CJK, so it exercises glyph-range fan-out: CJK labels pull in far more codepoints per tile than Latin scripts do, and that fan-out is exactly what a later bound on glyph-range size has to account for. Cairo is right-to-left, so it exercises the complex-script path, where Rentile must either fall back to a style-authored Latin label or drop the label outright, and must never emit garbled text.

## Label candidates in the gate

After a style's coverage tiles render, the gate additionally calls `acquireLabelCandidates` against three of the manifest's cases per style — `new-york-zoom-ladder`, `tokyo-cjk-dense` and `cairo-rtl` — taking the lowest- and highest-zoom tile of each and New York z14. Low zoom reaches settlement labels and glyph-range fan-out; high zoom reaches road, POI, water and other labels that were previously outside the place-only profile. New York z14 intersects the rolling catalog's z9/z12 functional `text-transform` layers while retaining place features, so the capability is proved by resolved candidate text rather than its static declaration. These geographies exercise a Latin baseline, functional casing, CJK glyph-range fan-out, and complex-script handling without multiplying the already long gate by every tile and zoom. Per style, the report records the candidate count, the glyph atlas dimensions, the distinct glyph-range count, and the redacted diagnostic codes, and checks that the glyph-range count never exceeds `maxGlyphRangesPerBatch`. The gate also plans each of those cases before acquiring it and asserts that the glyph-range URLs the acquisition then requests are a subset of the plan's predicted `glyphUrls` set, rather than asserting the two sets are equal, because the gate's raw resource store is warm and shared across every case and style in the run: a range one style's plan predicts can already have been fetched by an earlier style that resolved the same glyph provider, font stack, and codepoint block. That one-directional check is still the property a consumer preregistering exact URLs depends on, because a warm cache can only shrink what a case actually requests, never grow it beyond what its own plan predicted.

The default `maxGlyphRangesPerBatch` is 256. This is based on a 2026-08-23 live measurement after the expanded label profile: Outdoor at Tokyo z14 planned and acquired 159 distinct ranges, producing an 8192x4357 atlas (142,770,176 decoded bytes, 136.16 MiB). The old default of 64, and a trial value of 128, both rejected that valid closure before acquisition; 256 is the next power of two above the observation. It remains one independent safety ceiling, not a promise that every adversarial 256-range glyph set fits: `maxRasterDimensionPx` and `maxDecodedRasterBytes` continue to reject oversized provider metrics independently. A 512 default was rejected because a dense atlas at that range count cannot fit those existing 8192-pixel and 256 MiB defaults.

Cairo's outcome is checked strictly because corpus layers branch on `is-supported-script`, and this proves that branch against live styles rather than fixtures. Exactly four outcomes are acceptable: candidates whose resolved text is a supported script (typically a style-authored `name:latin` fallback); no candidates alongside a reported `COMPLEX_SCRIPT_LABEL_EXCLUDED` diagnostic; a style with no label layers at all (`labelLayerDescriptors` is empty), which cannot say anything about complex-script handling either way; or no candidates and no *label-relevant* diagnostic (`COMPLEX_SCRIPT_LABEL_EXCLUDED`, `GLYPH_RANGE_UNAVAILABLE`, `LABEL_FEATURE_SKIPPED`, `UNSUPPORTED_TEXT_CONSTRUCT`, `LINE_PLACEMENT_LABEL_EXCLUDED`), meaning the style has label layers but this tile genuinely carries no matching features. That last outcome is deliberately narrow: unrelated style-preparation diagnostics such as `ROOT_BEHAVIOR_EXCLUDED` or `TEXT_ONLY_LAYER_EXCLUDED` never count toward it, and any *label-relevant* diagnostic other than the complex-script exclusion still fails the check, so it cannot mask a broken pipeline behind an empty result. Any other outcome — in particular, candidates whose text still requires a script this renderer cannot lay out — fails the gate, because that text would render as garbled output.

Validate it without network access:

```shell
python3 tools/check_coverage_manifest.py compatibility/rentile-v1-coverage.json
python3 tools/check_corpus_fidelity_policy.py compatibility/rentile-v1-coverage.json
```

Run the complete catalog gate locally:

```shell
RENTILE_COVERAGE_MANIFEST="$PWD/compatibility/rentile-v1-coverage.json" \
RENTILE_CORPUS_REPORT_DIR="$PWD/build/reports/rentile-corpus" \
./gradlew :kmp:testAndroidHostTest \
  --tests com.rohittp.rentile.MapCatalogCorpusSmokeTest
```

The report contains PNG tiles, available 3×3 mosaics, `results.tsv`, a capability ledger, and a contact sheet. The ledger separates `DECLARED_ONLY` from `EMITTED`: label capabilities reach `EMITTED` only through sampled candidate semantics, and its final column records credential-free evidence such as `text-fit:BOTH`, `placement:LINE,line-geometry`, `non-default:7.0`, or `differing-values`. Other dispositions retain `EXERCISED`/`MISSING`. `results.tsv` also carries one label-acquisition row per style per label case, tagged `label:<case-id>`, with the candidate count, atlas dimensions, glyph-range count and redacted diagnostic codes described above. It never contains catalog responses, style JSON, source caches, resource URLs, query strings, provider responses, or raw layer IDs — the label rows carry only counts and dimensions, never a glyphs URL or a font stack that could identify a provider account.

## GitHub Actions

The standalone map-catalog workflow runs only by explicit workflow dispatch. Every
non-documentation push to `main` runs the same complete corpus gate inside the serialised R2 release
workflow before upload, so the slow live gate is not duplicated. CI, the standalone workflow, and
the release workflow all validate both the Coverage Manifest structure and its fidelity policy
before running or publishing. No map-catalog secret or base64 JSON is required.
