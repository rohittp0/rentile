#!/usr/bin/env python3
"""Verify frozen-input legacy compatibility reports, including actual encoded PNG bytes."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

APPROVED_EXCLUSION = {
    "id": "71",
    "reasonCode": "CDN_RETURNED_HTML_FOR_STYLE_DOCUMENT",
    "reason": "Coordinator-approved exclusion: the style endpoint returns CDN-cached DRF HTML while ignoring Vary: Accept; no valid style document is available.",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_exclusions(root, manifest):
    path = root / "exclusions.json"
    if not path.exists():
        return None
    receipt = json.loads(path.read_text())
    styles = {str(style) for style in manifest["styleRefs"]}
    assert "71" in styles, "Excluded style absent from unchanged Coverage Manifest"
    assert receipt == {"schema": 1, "manifestStyleCount": len(styles),
                       "includedStyleCount": len(styles) - 1,
                       "excludedStyles": [APPROVED_EXCLUSION]}, "Unapproved style exclusion or reason"
    return receipt


def read_report(root, manifest, mode, acquisition):
    complete = json.loads((root / "comparison-complete.json").read_text())
    epoch = complete.pop("clockEpochMillis", None)
    assert type(epoch) is int and 0 < epoch <= 9223372036854775807, "Invalid frozen clock epoch"
    styles = {str(style) for style in manifest["styleRefs"]}
    exclusions = read_exclusions(root, manifest)
    if exclusions:
        styles.remove("71")
    expected_styles = styles | {f"{style}-host-symbols" for style in styles}
    actual_mode = complete.pop("mode", None)
    allowed_modes = (mode,) if isinstance(mode, str) else mode
    assert actual_mode in allowed_modes, "Incomplete or wrong-mode corpus run"
    assert complete == {"schema": 1, "acquisition": acquisition,
                        "profileStyles": len(expected_styles)}, "Incomplete or wrong-mode corpus run"
    coverage_tiles = {(tile["z"], tile["x"], tile["y"])
                      for case in manifest["cases"] for tile in case["tiles"]}
    expected_full = {f"tiles/{style}/{z}-{x}-{y}.png" for style in expected_styles
                     for z, x, y in coverage_tiles}
    outputs = {}
    for line in (root / "output-bytes.tsv").read_text().splitlines():
        name, count, sha = line.split("\t")
        relative = Path(name)
        assert not relative.is_absolute() and ".." not in relative.parts, "Unsafe output path"
        assert name not in outputs, "Duplicate output row"
        assert re.fullmatch(r"[0-9a-f]{64}", sha), "Invalid output digest"
        path = root / relative
        assert path.is_file() and not path.is_symlink(), "Missing or symlinked output"
        assert path.stat().st_size == int(count) and digest(path) == sha, "Output bytes differ from manifest"
        outputs[name] = (int(count), sha)
    full = {name for name in outputs if name.startswith("tiles/")}
    assert full == expected_full, "Full manifest coverage or compatibility profile missing"
    samples = {name for name in outputs if name.startswith("legacy-samples/")}
    assert samples, "Raster output-size/hillshade samples absent"
    sampled_styles = {Path(name).parts[3] for name in samples}
    for style in sampled_styles:
        assert style in expected_styles, "Unknown sampled style"
        for size in (256, 512):
            for hillshade in ("false", "true"):
                for z in (0, 1, 12, 18, 19, 22):
                    assert any(name.startswith(f"legacy-samples/{size}-{hillshade}/tiles/{style}/{z}-")
                               for name in samples), "Raster sample matrix incomplete"
    requests = Counter((root / "acquisition.tsv").read_text().splitlines())
    assert requests, "Acquisition ledger absent"
    for line in requests:
        kind, identity, bound, status, count, sha = line.split("\t")
        assert kind and re.fullmatch(r"[0-9a-f]{64}", identity)
        assert re.fullmatch(r"[0-9a-f]{64}", sha)
        assert 0 <= int(count) <= int(bound) and 100 <= int(status) <= 599
    return outputs, requests, epoch, actual_mode


def compare(before, after, manifest):
    exclusions = read_exclusions(before, manifest)
    assert exclusions == read_exclusions(after, manifest), "Exclusion receipt changed between runs"
    old_outputs, old_requests, old_epoch, old_mode = read_report(
        before, manifest, "REPLAY" if exclusions else ("CAPTURE", "REPLAY"), "BASELINE")
    new_outputs, new_requests, new_epoch, new_mode = read_report(after, manifest, "REPLAY", "OUTPUT_ZOOM")
    assert old_epoch == new_epoch, "Frozen clock epoch changed"
    assert old_requests == new_requests, "Acquisition request/response multiset changed"
    assert old_outputs == new_outputs, "Encoded output bytes changed"
    return {"schema": 1, "status": "byte_identical", "clockEpochMillis": old_epoch,
            "baselineMode": old_mode, "candidateMode": new_mode,
            "manifestStyleCount": len(set(manifest["styleRefs"])),
            "includedStyleCount": len(set(manifest["styleRefs"])) - (1 if exclusions else 0),
            "excludedStyles": exclusions["excludedStyles"] if exclusions else [], "outputs": len(old_outputs),
            "fullOutputs": sum(name.startswith("tiles/") for name in old_outputs),
            "supplementalOutputs": sum(name.startswith("legacy-samples/") for name in old_outputs),
            "acquisitions": sum(old_requests.values()), "uniqueAcquisitions": len(old_requests),
            "beforeManifestSha256": digest(before / "output-bytes.tsv"),
            "afterManifestSha256": digest(after / "output-bytes.tsv"),
            "beforeAcquisitionSha256": digest(before / "acquisition.tsv"),
            "afterAcquisitionSha256": digest(after / "acquisition.tsv")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--manifest", type=Path,
                        default=Path(__file__).resolve().parents[1] / "compatibility/rentile-v1-coverage.json")
    parser.add_argument("--result", type=Path, required=True)
    args = parser.parse_args()
    result = compare(args.before, args.after, json.loads(args.manifest.read_text()))
    args.result.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
