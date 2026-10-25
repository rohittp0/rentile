import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("comparison", Path(__file__).resolve().parents[1] / "compare_legacy_corpus.py")
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


class LegacyCorpusComparisonTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.before = Path(self.temporary.name) / "before"
        self.after = Path(self.temporary.name) / "after"
        self.manifest = {"styleRefs": ["59"], "cases": [{"tiles": [{"z": 0, "x": 0, "y": 0}]}]}
        for directory, mode, acquisition in [(self.before, "CAPTURE", "BASELINE"), (self.after, "REPLAY", "OUTPUT_ZOOM")]:
            directory.mkdir()
            (directory / "comparison-complete.json").write_text(json.dumps(
                {"schema": 1, "mode": mode, "acquisition": acquisition, "profileStyles": 2, "clockEpochMillis": 100}))
            (directory / "acquisition.tsv").write_text(f"RASTER_TILE\t{'a'*64}\t16\t200\t3\t{'b'*64}\n")
            names = [f"tiles/{style}/0-0-0.png" for style in ("59", "59-host-symbols")]
            names += [f"legacy-samples/{size}-{hillshade}/tiles/{style}/{z}-0-0.png"
                      for size in (256, 512) for hillshade in ("false", "true")
                      for style in ("59", "59-host-symbols") for z in (0, 1, 12, 18, 19, 22)]
            rows = []
            for name in names:
                path = directory / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"PNG")
                rows.append(f"{name}\t3\t{hashlib.sha256(b'PNG').hexdigest()}")
            (directory / "output-bytes.tsv").write_text("\n".join(sorted(rows)) + "\n")

    def run_comparison(self):
        return comparison.compare(self.before, self.after, self.manifest)

    def enable_approved_exclusion(self):
        self.manifest["styleRefs"].append("71")
        for directory in (self.before, self.after):
            (directory / "exclusions.json").write_text(json.dumps({
                "schema": 1, "manifestStyleCount": 2, "includedStyleCount": 1,
                "excludedStyles": [comparison.APPROVED_EXCLUSION],
            }))
        path = self.before / "comparison-complete.json"
        complete = json.loads(path.read_text())
        complete["mode"] = "REPLAY"
        path.write_text(json.dumps(complete))

    def test_exact_outputs_and_requests_pass(self):
        self.assertEqual("byte_identical", self.run_comparison()["status"])

    def test_modified_png_fails_even_with_unchanged_ledger(self):
        (self.after / "tiles/59/0-0-0.png").write_bytes(b"BAD")
        with self.assertRaisesRegex(AssertionError, "Output bytes differ"):
            self.run_comparison()

    def test_missing_profile_fails(self):
        path = self.after / "output-bytes.tsv"
        path.write_text("\n".join(row for row in path.read_text().splitlines() if not row.startswith("tiles/59-host-symbols/")))
        with self.assertRaisesRegex(AssertionError, "Full manifest coverage"):
            self.run_comparison()

    def test_request_count_difference_fails(self):
        path = self.after / "acquisition.tsv"
        path.write_text(path.read_text() * 2)
        with self.assertRaisesRegex(AssertionError, "multiset changed"):
            self.run_comparison()

    def test_wrong_candidate_mode_fails(self):
        path = self.after / "comparison-complete.json"
        path.write_text(path.read_text().replace("OUTPUT_ZOOM", "NATIVE"))
        with self.assertRaisesRegex(AssertionError, "wrong-mode"):
            self.run_comparison()

    def test_incomplete_supplemental_matrix_fails(self):
        path = self.after / "output-bytes.tsv"
        path.write_text("\n".join(row for row in path.read_text().splitlines() if not row.startswith("legacy-samples/256-false/tiles/59/18-")))
        with self.assertRaisesRegex(AssertionError, "matrix incomplete"):
            self.run_comparison()

    def test_path_escape_fails(self):
        path = self.after / "output-bytes.tsv"
        path.write_text(path.read_text() + "../outside.png\t3\t" + "a"*64 + "\n")
        with self.assertRaisesRegex(AssertionError, "Unsafe output path"):
            self.run_comparison()

    def test_changed_frozen_clock_fails(self):
        path = self.after / "comparison-complete.json"
        contents = json.loads(path.read_text())
        contents["clockEpochMillis"] = 200
        path.write_text(json.dumps(contents))
        with self.assertRaisesRegex(AssertionError, "Frozen clock epoch changed"):
            self.run_comparison()

    def test_invalid_or_missing_frozen_clock_fails(self):
        path = self.after / "comparison-complete.json"
        contents = json.loads(path.read_text())
        for epoch in (None, 0, -1, True, "100", 9223372036854775808):
            contents["clockEpochMillis"] = epoch
            path.write_text(json.dumps(contents))
            with self.subTest(epoch=epoch), self.assertRaisesRegex(AssertionError, "Invalid frozen clock"):
                self.run_comparison()

    def test_baseline_can_replay_frozen_responses_without_exclusion(self):
        path = self.before / "comparison-complete.json"
        path.write_text(path.read_text().replace("CAPTURE", "REPLAY"))
        self.assertEqual("REPLAY", self.run_comparison()["baselineMode"])

    def test_only_approved71_exclusion_preserves_all_remaining_coverage(self):
        self.enable_approved_exclusion()
        result = self.run_comparison()
        self.assertEqual([comparison.APPROVED_EXCLUSION], result["excludedStyles"])
        self.assertEqual(2, result["manifestStyleCount"])
        self.assertEqual(1, result["includedStyleCount"])
        self.assertEqual(2, result["fullOutputs"])
        self.assertEqual("REPLAY", result["baselineMode"])
        self.assertEqual("REPLAY", result["candidateMode"])

    def test_exclusion_requires_both_runs_to_replay(self):
        self.enable_approved_exclusion()
        for directory in (self.before, self.after):
            path = directory / "comparison-complete.json"
            original = path.read_text()
            path.write_text(original.replace("REPLAY", "CAPTURE"))
            with self.subTest(directory=directory), self.assertRaisesRegex(AssertionError, "wrong-mode"):
                self.run_comparison()
            path.write_text(original)

    def test_exclusion_receipt_missing_on_one_side_fails(self):
        self.enable_approved_exclusion()
        (self.after / "exclusions.json").unlink()
        with self.assertRaisesRegex(AssertionError, "receipt changed"):
            self.run_comparison()

    def test_other_style_additional_style_or_changed_reason_fails(self):
        self.enable_approved_exclusion()
        path = self.after / "exclusions.json"
        original = json.loads(path.read_text())
        for edit in ("other_id", "additional_id", "no_reason", "other_reason", "other_code", "wrong_count"):
            receipt = json.loads(json.dumps(original))
            if edit == "other_id":
                receipt["excludedStyles"][0]["id"] = "59"
            elif edit == "additional_id":
                receipt["excludedStyles"].append({**comparison.APPROVED_EXCLUSION, "id": "59"})
            elif edit == "no_reason":
                receipt["excludedStyles"][0].pop("reason")
            elif edit == "other_reason":
                receipt["excludedStyles"][0]["reason"] = "Skipped for speed"
            elif edit == "other_code":
                receipt["excludedStyles"][0]["reasonCode"] = "OTHER"
            else:
                receipt["includedStyleCount"] = 0
            path.write_text(json.dumps(receipt))
            with self.subTest(edit=edit), self.assertRaisesRegex(AssertionError, "Unapproved style exclusion"):
                self.run_comparison()

    def test_exclusion_does_not_allow_missing_other_case_or_profile(self):
        self.enable_approved_exclusion()
        path = self.after / "output-bytes.tsv"
        path.write_text("\n".join(row for row in path.read_text().splitlines()
                                 if not row.startswith("tiles/59-host-symbols/")))
        with self.assertRaisesRegex(AssertionError, "Full manifest coverage"):
            self.run_comparison()

    def test_exclusion_does_not_allow_acquisition_or_encoded_byte_changes(self):
        self.enable_approved_exclusion()
        path = self.after / "acquisition.tsv"
        original = path.read_text()
        path.write_text(original * 2)
        with self.assertRaisesRegex(AssertionError, "multiset changed"):
            self.run_comparison()
        path.write_text(original)
        (self.after / "tiles/59/0-0-0.png").write_bytes(b"BAD")
        with self.assertRaisesRegex(AssertionError, "Output bytes differ"):
            self.run_comparison()

    def test_exclusion_cannot_rewrite_manifest_to_drop71(self):
        self.enable_approved_exclusion()
        self.manifest["styleRefs"].remove("71")
        with self.assertRaisesRegex(AssertionError, "Excluded style absent"):
            self.run_comparison()


if __name__ == "__main__":
    unittest.main()
