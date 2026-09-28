"""An owner confirmation must not carry over to another version, artifact, or publication."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/mdk_distribution_approval.py"
DIGEST = "a" * 64


class MdkDistributionApprovalTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / ".github").mkdir()
        (self.root / "scripts").mkdir()
        (self.root / "version.properties").write_text("VERSION_CODE=251\nVERSION_NAME=1.0.89\n")
        (self.root / "release-notes.txt").write_text("1.0.89\nPlayback fixes.\n")
        (self.root / "scripts/engine-checksums.sha256").write_text(f"{DIGEST}  mdk-sdk-android.7z\n")
        self.approval = {
            "versionName": "1.0.89", "versionCode": 251, "scope": "package-only",
            "mdkArtifactSha256": DIGEST, "confirmed": True,
        }
        self.path = self.root / ".github/mdk-distribution-approval.json"
        self.save()

    def save(self):
        self.path.write_text(json.dumps(self.approval))

    def resolve(self, package_only=True):
        command = [sys.executable, str(SCRIPT), "--root", str(self.root)]
        if package_only:
            command.append("--package-only")
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.strip()

    def test_matching_package_is_confirmed_but_publication_is_not(self):
        self.assertEqual("true", self.resolve())
        self.assertEqual("false", self.resolve(package_only=False))

    def test_confirmation_is_bound_to_version_artifact_scope_and_boolean(self):
        for field, value in (
            ("versionName", "1.0.90"), ("versionCode", 252), ("versionCode", "251"),
            ("mdkArtifactSha256", "b" * 64), ("scope", "publish"),
            ("confirmed", False), ("confirmed", "true"),
        ):
            with self.subTest(field=field, value=value):
                original = self.approval[field]
                self.approval[field] = value
                self.save()
                self.assertEqual("false", self.resolve())
                self.approval[field] = original

    def test_missing_malformed_or_non_object_record_is_not_confirmed(self):
        self.path.unlink()
        self.assertEqual("false", self.resolve())
        for contents in ("{", "[]", "null"):
            with self.subTest(contents=contents):
                self.path.write_text(contents)
                self.assertEqual("false", self.resolve())

    def test_changed_or_ambiguous_engine_pin_is_not_confirmed(self):
        pins = self.root / "scripts/engine-checksums.sha256"
        for contents in ("", f"{'b' * 64}  mdk-sdk-android.7z\n",
                         f"{DIGEST}  mdk-sdk-android.7z\n" * 2):
            with self.subTest(contents=contents):
                pins.write_text(contents)
                self.assertEqual("false", self.resolve())


if __name__ == "__main__":
    unittest.main()
