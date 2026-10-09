"""Execute the same guard CLI that the release resolver invokes before exposing its version."""
from pathlib import Path
import subprocess
import sys
import unittest


class DispatchVersionGuardTest(unittest.TestCase):
    guard = Path(__file__).resolve().parents[1] / "check_dispatch_version.py"

    def run_guard(self, event="workflow_dispatch", ref="refs/heads/fix/dem-204-no-content-20261009", declared="0.12.2", resolved="0.12.2"):
        return subprocess.run([sys.executable, str(self.guard), "--event", event, "--ref", ref,
                               "--declared", declared, "--resolved", resolved], capture_output=True, text=True)

    def test_task_manual_exact_version_is_allowed(self):
        self.assertEqual(0, self.run_guard().returncode)

    def test_task_manual_new_minor_publication_cannot_promote_an_old_api(self):
        result = self.run_guard(resolved="0.13.1")
        self.assertEqual(1, result.returncode)
        self.assertIn("refusing publication", result.stdout)

    def test_task_manual_coordinate_collision_cannot_advance_its_patch(self):
        self.assertEqual(1, self.run_guard(resolved="0.12.3").returncode)

    def test_main_push_and_main_manual_keep_automatic_patch_resolution(self):
        for event in ["push", "workflow_dispatch"]:
            with self.subTest(event=event):
                self.assertEqual(0, self.run_guard(event=event, ref="refs/heads/main", resolved="0.13.1").returncode)

    def test_main_snapshot_declaration_keeps_existing_automatic_release_policy(self):
        self.assertEqual(0, self.run_guard(event="push", ref="refs/heads/main", declared="0.12.2-SNAPSHOT", resolved="0.13.1").returncode)

    def test_non_main_push_is_outside_the_manual_guard(self):
        self.assertEqual(0, self.run_guard(event="push", resolved="0.13.1").returncode)

    def test_manual_tag_is_also_bound_to_its_declared_version(self):
        self.assertEqual(1, self.run_guard(ref="refs/tags/backport", resolved="0.13.1").returncode)
        self.assertEqual(0, self.run_guard(ref="refs/tags/backport").returncode)

    def test_missing_context_and_malformed_versions_fail_closed(self):
        for args in [{"event":""}, {"ref":""}, {"declared":""}, {"resolved":""}, {"resolved":"0.13.1-SNAPSHOT"}]:
            with self.subTest(args=args):
                self.assertEqual(1, self.run_guard(**args).returncode)


if __name__ == "__main__":
    unittest.main()
