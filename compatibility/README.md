# Rolling public map corpus and coverage

Rentile validates `rentile-v1` against the latest map styles listed by the public, paginated catalog at:

```text
https://dashboard.lascade.com/travel_animator/v0/maps/
```

The harness follows the catalog's `next` links, requires all pages to remain on the exact catalog origin and path, checks the declared count, rejects duplicate IDs, and requires the resulting ID set to match the committed Coverage Manifest. Style JSON and provider resources are fetched at test time and are never committed.

## Credential-free Coverage Manifest

`rentile-v1-coverage.json` commits only stable map IDs, the supported z0–z22 range, XYZ cases, seam mosaics, overzoom gates, and required capability names. It contains no style JSON, style URL, provider URL, credential, or frozen layer count.

Two cases, `tokyo-cjk-dense` and `cairo-rtl`, exist specifically to keep non-Latin text rendering honest. Tokyo is dense and CJK, so it exercises glyph-range fan-out: CJK labels pull in far more codepoints per tile than Latin scripts do, and that fan-out is exactly what a later bound on glyph-range size has to account for. Cairo is right-to-left, so it exercises the complex-script path, where Rentile must either fall back to a style-authored Latin label or drop the label outright, and must never emit garbled text.

## Label candidates in the gate

After a style's coverage tiles render, the gate additionally calls `acquireLabelCandidates` against three of the manifest's cases per style — `new-york-zoom-ladder`, `tokyo-cjk-dense` and `cairo-rtl` — taking only the lowest-zoom tile of each, not every case at every zoom. Place-name labels (continent, country, state, city, town) live at low zoom and thin out or vanish entirely by deep overzoom, so the lowest zoom is the one that actually exercises label volume and glyph-range fan-out; a highest-zoom tile would return an empty label set and prove nothing. Label correctness itself turns on geography and script, not on zoom level, so acquiring the same three geographies again at every zoom would multiply the gate's runtime without adding signal; the corpus gate already takes tens of minutes before every release. Per style, the report records the candidate count, the glyph atlas dimensions, the distinct glyph-range count, and the redacted diagnostic codes, and checks that the glyph-range count never exceeds `maxGlyphRangesPerBatch`.

Cairo's outcome is checked strictly, because eleven corpus layers branch on `is-supported-script` and this is what proves that branch works against live styles rather than fixtures. Exactly four outcomes are acceptable: candidates whose resolved text is a supported script (typically a style-authored `name:latin` fallback); no candidates alongside a reported `COMPLEX_SCRIPT_LABEL_EXCLUDED` diagnostic; a style with no place-name label layers at all (`labelLayerDescriptors` is empty), which cannot say anything about complex-script handling either way; or no candidates and no *label-relevant* diagnostic (`COMPLEX_SCRIPT_LABEL_EXCLUDED`, `GLYPH_RANGE_UNAVAILABLE`, `LABEL_FEATURE_SKIPPED`, `UNSUPPORTED_TEXT_CONSTRUCT`, `LINE_PLACEMENT_LABEL_EXCLUDED`), meaning the style has label layers but this tile genuinely carries no place-name features. That last outcome is deliberately narrow: unrelated style-preparation diagnostics such as `ROOT_BEHAVIOR_EXCLUDED` or `TEXT_ONLY_LAYER_EXCLUDED` never count toward it, and any *label-relevant* diagnostic other than the complex-script exclusion still fails the check, so it cannot mask a broken pipeline behind an empty result. Any other outcome — in particular, candidates whose text still requires a script this renderer cannot lay out — fails the gate, because that text would render as garbled output.

Validate it without network access:

```shell
python3 tools/check_coverage_manifest.py compatibility/rentile-v1-coverage.json
```

Run the complete catalog gate locally:

```shell
RENTILE_COVERAGE_MANIFEST="$PWD/compatibility/rentile-v1-coverage.json" \
RENTILE_CORPUS_REPORT_DIR="$PWD/build/reports/rentile-corpus" \
./gradlew :kmp:testAndroidHostTest \
  --tests com.rohittp.rentile.MapCatalogCorpusSmokeTest
```

The report contains PNG tiles, available 3×3 mosaics, `results.tsv`, capability names, and a contact sheet; `results.tsv` also carries one label-acquisition row per style per label case, tagged `label:<case-id>`, with the candidate count, atlas dimensions, glyph-range count and redacted diagnostic codes described above. It never contains catalog responses, style JSON, source caches, resource URLs, query strings, or provider responses — the label rows carry only counts and dimensions, never a glyphs URL or a font stack that could identify a provider account.

## GitHub Actions

The map-catalog workflow runs on pushes to `main` and explicit workflow dispatches. R2 Maven publication runs the same corpus gate before upload. No map-catalog secret or base64 JSON is required.
