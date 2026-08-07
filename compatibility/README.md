# Rolling public map corpus and coverage

Rentile validates `rentile-v1` against the latest map styles listed by the public, paginated catalog at:

```text
https://dashboard.lascade.com/travel_animator/v0/maps/
```

The harness follows the catalog's `next` links, requires all pages to remain on the exact catalog origin and path, checks the declared count, rejects duplicate IDs, and requires the resulting ID set to match the committed Coverage Manifest. Style JSON and provider resources are fetched at test time and are never committed.

## Credential-free Coverage Manifest

`rentile-v1-coverage.json` commits only stable map IDs, the supported z0–z22 range, XYZ cases, seam mosaics, overzoom gates, and required capability names. It contains no style JSON, style URL, provider URL, credential, or frozen layer count.

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

The report contains PNG tiles, available 3×3 mosaics, `results.tsv`, capability names, and a contact sheet. It never contains catalog responses, style JSON, source caches, resource URLs, query strings, or provider responses.

## GitHub Actions

The map-catalog workflow runs on pushes to `main` and explicit workflow dispatches. Maven Central publication runs the same corpus gate before upload. No map-catalog secret or base64 JSON is required.
