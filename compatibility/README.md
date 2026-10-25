# Rolling public map corpus and coverage

Rentile validates `rentile-v1` against the latest map styles listed by the public, paginated catalog at:

```text
https://dashboard.lascade.com/travel_animator/v0/maps/
```

The harness follows the catalog's `next` links, requires all pages to remain on the exact catalog origin and path, checks the declared count, rejects duplicate IDs, and requires the resulting ID set to match the committed Coverage Manifest. Style JSON and provider resources are fetched at test time and are never committed.

## Explicit unavailable-style exclusion

On 2026-10-25 the coordinator authorized exclusion of **only style71**: its style endpoint serves
CDN-cached DRF HTML while ignoring `Vary: Accept`, including requests accepting JSON. The returned
HTML is not a style document. Both live catalog loops (host-extrusion acquisition and complete
corpus rendering) first validate the complete catalog against the unchanged Coverage Manifest,
then exclude ID71 from rendering/assertions. Missing, duplicate, invalid or unexpected catalog
entries still fail validation. No environment variable can add exclusions, and explicitly selecting
style71 fails with the documented exclusion rather than silently producing an empty run.

Every report records `exclusions.json`: original manifest style count, included style count, and
ID71 with reason code `CDN_RETURNED_HTML_FOR_STYLE_DOCUMENT` and the fixed explanation. The HTML
report links this receipt. The remaining33 styles run under both compatibility profiles, with all
cases, source acquisitions, label checks and aggregate required-capability assertions unchanged.
This exclusion is an upstream availability exception, not evidence that style71 renders correctly.
Remove it only after the endpoint reliably returns a valid style document and rerun the full gate.

## Credential-free Coverage Manifest

`rentile-v1-coverage.json` commits only stable map IDs, the supported z0–z22 range, XYZ cases, seam mosaics, overzoom gates, capability names and their expected dispositions, and the diagnostic policy that turns known omissions into gate failures. It contains no style JSON, style URL, provider URL, credential, or frozen layer count.

Each required capability has one of five dispositions: `evaluated`, `rasterized`, `resource-acquired`, `sprite-decoded`, or `label-candidate`. Static style inspection records only that a construct is declared. A `label-candidate` capability is counted as exercised only when a sampled public `LabelCandidateBatch` carries matching semantic evidence: for example, text-fit requires a non-null icon whose `textFit` is not `NONE`, and line placement requires `LINE` or `LINE_CENTER` plus non-empty line geometry. Functional placement and padding require a non-default or differing resolved value; functional transform requires an uppercase/lowercase result or differing resolved case; functional anchor uses non-empty finite candidate-local geometry because the public candidate already folds the anchor into its geometry rather than exposing an anchor enum. A descriptor alone never satisfies them. A full-corpus run fails when required runtime evidence is absent. A single-style run selected with `RENTILE_CORPUS_STYLE_ID` still enforces that style's fidelity rules, but deliberately skips the aggregate “every required capability was exercised” assertion.

The fidelity policy additionally requires every visible text-bearing vector symbol layer to have a public `LabelLayerDescriptor`. It rejects `UNSUPPORTED_TEXT_CONSTRUCT`, `LINE_PLACEMENT_LABEL_EXCLUDED`, and `TEXT_COUPLED_ICON_LAYER_EXCLUDED` instead of accepting the corresponding layer-level loss. `TEXT_ONLY_LAYER_EXCLUDED` remains valid for the PNG path only: the same layer must still be present through the label interface. Layer IDs used while checking this are never written to the report; failures carry only their digest.

Candidate contribution is tracked separately from descriptor coverage. The gate reports descriptors that produced no candidate in its sampled geographies and zooms, using only layer-ID digests. `requireEveryDescriptorToContribute` is currently `false`, because absence from a finite sample can mean the tile has no matching feature rather than an implementation omission. It is an explicit transitional switch, not an ignored result: the observation is visible in the HTML report and can become fatal once the coverage cases prove every descriptor has reachable source data.

Required capabilities describe the rolling catalog, not Rentile's entire supported grammar. A live declaration audit on 2026-08-23 found no expression-form `<` and no TMS source: the catalog's remaining `<` occurrences are legacy filters, which are tracked by `legacy-filter`. The stale aggregate `expression-less-than` and `tms-source` requirements were therefore removed from the manifest instead of manufacturing corpus evidence. Support remains pinned by `StyleExpressionTest` and `VectorSourceTest`; removing a rolling-corpus requirement does not remove or weaken the implementation contract.

Two cases, `tokyo-cjk-dense` and `cairo-rtl`, exist specifically to keep non-Latin text rendering honest. Tokyo is dense and CJK, so it exercises glyph-range fan-out: CJK labels pull in far more codepoints per tile than Latin scripts do, and that fan-out is exactly what a later bound on glyph-range size has to account for. Cairo is right-to-left, so it exercises the complex-script path, where Rentile must either fall back to a style-authored Latin label or drop the label outright, and must never emit garbled text.

## The host-owned-symbols profile

The manifest is `rentile-v1`'s, and the gate also renders every style under
`rentile-v1-host-symbols` (ADR 0035), whose coverage it **derives** from this manifest instead of
reading a second committed file. Two files listing the same rolling catalog would be two places to
forget to update; one file and a fixed derivation cannot drift. The derived profile keeps every
style reference, case, zoom, threshold and required capability, and changes three things:

- `independent-point-icon` and `independent-line-icon` take the `label-candidate` disposition,
  because that profile emits those icons as candidates instead of rasterizing them. Each needs
  runtime evidence - a sampled candidate from a declaring layer that carries an icon, and for the
  line capability `LINE`/`LINE_CENTER` placement with line geometry - and a descriptor for every
  declaring layer.
- Every visible vector symbol layer with a meaningful `text-field` **or** `icon-image` must have a
  `LabelLayerDescriptor`, not only the text-bearing ones.
- `TEXT_COMPONENT_REMOVED_ICON_RETAINED` and `TEXT_COUPLED_ICON_LAYER_EXCLUDED` join the forbidden
  preparation diagnostics, because that profile repairs no icon into a tile.

A symbol layer the profile cannot represent at all - one on a GeoJSON source, which has no Label
Tiles - is counted as a `HOST_SYMBOL_LAYER_UNREPRESENTED` fidelity observation rather than failed,
since the profile accepts that loss by design. The derived profile's report rows carry the style id
with a `-host-symbols` suffix, its tiles and mosaics are written under that id, and its capability
ledger is `capabilities-rentile-v1-host-symbols.txt` beside `capabilities.txt`. Both
`tools/check_*` scripts validate the committed manifest only, which is all that is committed; the
derivation is pinned by `MapCatalogCorpusSmokeTest.theHostSymbolsProfileIsDerivedFromTheCommittedManifest`.
Rendering both profiles roughly doubles the gate's CPU time but not its downloads, because both read
one shared raw store.

## Label candidates in the gate

After a style's coverage tiles render, the gate additionally calls `acquireLabelCandidates` against three of the manifest's cases per style — `new-york-zoom-ladder`, `tokyo-cjk-dense` and `cairo-rtl` — taking the lowest- and highest-zoom tile of each and New York z14. Low zoom reaches settlement labels and glyph-range fan-out; high zoom reaches road, POI, water and other labels that were previously outside the place-only profile. New York z14 intersects the rolling catalog's z9/z12 functional `text-transform` layers while retaining place features, so the capability is proved by resolved candidate text rather than its static declaration. These geographies exercise a Latin baseline, functional casing, CJK glyph-range fan-out, and complex-script handling without multiplying the already long gate by every tile and zoom. Per style, the report records the candidate count, the glyph atlas dimensions, the distinct glyph-range count, and the redacted diagnostic codes, and checks that the glyph-range count never exceeds `maxGlyphRangesPerBatch`. The gate also plans each of those cases before acquiring it and asserts that the glyph-range URLs the acquisition then requests are a subset of the plan's predicted `glyphUrls` set, rather than asserting the two sets are equal, because the gate's raw resource store is warm and shared across every case and style in the run: a range one style's plan predicts can already have been fetched by an earlier style that resolved the same glyph provider, font stack, and codepoint block. That one-directional check is still the property a consumer preregistering exact URLs depends on, because a warm cache can only shrink what a case actually requests, never grow it beyond what its own plan predicted.

The default `maxGlyphRangesPerBatch` is 256. This is based on a 2026-08-23 live measurement after the expanded label profile: Outdoor at Tokyo z14 planned and acquired 159 distinct ranges, producing an 8192x4357 atlas (142,770,176 decoded bytes, 136.16 MiB). The old default of 64, and a trial value of 128, both rejected that valid closure before acquisition; 256 is the next power of two above the observation. It remains one independent safety ceiling, not a promise that every adversarial 256-range glyph set fits: `maxRasterDimensionPx` and `maxDecodedRasterBytes` continue to reject oversized provider metrics independently. A 512 default was rejected because a dense atlas at that range count cannot fit those existing 8192-pixel and 256 MiB defaults.

That atlas size belongs to the default packing, which holds every glyph of every acquired range. The gate runs the default, so its label rows keep reporting it. A host that opts into `LabelGlyphPacking.REFERENCED_GLYPHS` packs only the glyphs its candidates draw. On 2026-10-07 the same Tokyo z14 closure, still 159 ranges, packed 523 glyphs into an 8179x60 atlas (1,962,960 decoded bytes) instead of 40,254 into 8192x4357. Under a 4096 `maxDimensionPx` it packed into 4082x120. `GlyphAtlasFootprintProbe` takes these measurements, and the [0.12.0 migration guide](../docs/migrations/0.12.0.md) tabulates them for both geographies.

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

Unexpected failures outside an individual tile/label result are retained as sanitized failed
style/profile rows. The same Gradle invocation still visits every later included style/profile,
writes the complete report and fails its aggregate assertion; continuation never accepts an error
or adds an exclusion. Coroutine cancellation still propagates from the outer continuation wrapper.

## Comparing original raster acquisition

The opt-in legacy comparison runs the same host harness against 0.15.1 and the current
`RasterAcquisitionDensity.OUTPUT_ZOOM` implementation. The harness uses reflection only at this
test boundary so its source can remain identical while the production API changes. Baseline mode
rejects a library exposing the new acquisition setting; candidate mode requires it.

Run `MapCatalogCorpusSmokeTest.rendersPublicCatalogCoverageThroughPublicInterface` first with
the 0.15.1 production source and these additional environment variables:

```shell
RENTILE_CORPUS_SNAPSHOT_MODE=CAPTURE
RENTILE_CORPUS_ACQUISITION=BASELINE
RENTILE_CORPUS_PRIVATE_SNAPSHOT_DIR="$PWD/build/private-legacy-snapshot"
RENTILE_CORPUS_REPORT_DIR="$PWD/build/reports/legacy-before"
```

Then run the identical harness against current source, changing the first two values to `REPLAY`
and `OUTPUT_ZOOM`, and the report directory to `build/reports/legacy-after`. Keep the snapshot
directory unchanged. Replay never calls the underlying transport: an uncaptured request or a
modified response fails. Capture itself reuses its first response for repeated identical requests.
Capture requires an empty snapshot directory and creates a clock-epoch file exclusively. Both
runs inject that same constant `RentileClock`, so elapsed wall time cannot alter freshness or
validator requests. Replay rejects a missing or malformed epoch; capture cannot reuse a snapshot.
The ordinary native-density gate continues using the system clock and live transport.

While the explicit style71 exclusion is active, the comparator requires the baseline to run in
`REPLAY` / `BASELINE` mode too. Use the existing verified private capture, or first initialize a
fresh capture as above, then rerender 0.15.1 into a **new** baseline report with `REPLAY` before
running the candidate. A prior capture whose only failure was excluded style71 may supply the
raw inputs; none of its PNGs or success assertions are reused. Every included style/profile is
rendered again. A missing input or changed body fails without network fallback. Both exclusion
receipts must name only71 with the exact documented reason and original/included catalog counts.

Both runs render the full Coverage Manifest case matrix for every included style under both compatibility profiles at 512 pixels.
Raster styles additionally sample 256/512 pixels, with and without hillshade, at z0/1/12/18/19/22;
the ordinary native-density corpus gate remains unchanged.

**The snapshot contains raw provider responses, including credential-bearing style/catalog
documents and response headers. Keep it private, outside the report directory, never commit or
publish it, and exclude it from public evidence archives.** Its filenames are hashes; report
ledgers contain only request/response digests, classes, limits, status and lengths.

Compare the completed reports with:

```shell
python3 tools/compare_legacy_corpus.py build/reports/legacy-before build/reports/legacy-after \
  --result build/reports/legacy-comparison.json
python3 -m unittest discover -s tools/tests -p 'test_compare_legacy_corpus.py'
```

The comparator requires successful full-profile coverage, checks every encoded output file against
its manifest, requires the supplemental matrix, and compares acquisition request/response
multisets and encoded output bytes exactly. It does not assert cache keys are identical: the
explicit compatibility setting deliberately has a separate output-cache identity. Record source
revisions and an identical harness digest alongside the result when temporarily switching source.

## GitHub Actions

The standalone map-catalog workflow runs only by explicit workflow dispatch. Every
non-documentation push to `main` runs the same complete corpus gate inside the serialised R2 release
workflow before upload, so the slow live gate is not duplicated. CI, the standalone workflow, and
the release workflow all validate both the Coverage Manifest structure and its fidelity policy
before running or publishing. No map-catalog secret or base64 JSON is required.
