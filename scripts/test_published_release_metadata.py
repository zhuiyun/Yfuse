import base64
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from published_release_metadata import prepare_release
from release_metadata import read_release


class PublishedReleaseMetadataTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.artifacts = self.root / "artifacts"
        self.package = self.artifacts / "Yfuse-1.0.45"
        self.package.mkdir(parents=True)
        self.apk_bytes = b"The exact APK bytes verified by the publishing workflow."
        self.apk = self.package / "Yfuse-207-1.0.45.apk"
        self.apk.write_bytes(self.apk_bytes)
        self.manifest = self.package / "update-v2.json"
        self.metadata = {
            "versionCode": 207,
            "versionName": "1.0.45",
            "apkUrl": "https://updates.example.com/android/Yfuse-207-1.0.45.apk",
            "sha256": hashlib.sha256(self.apk_bytes).hexdigest(),
            "size": len(self.apk_bytes),
            "notes": "手动发布说明\n\n- 起播优化\n- 保留多行与 $() 字面文本\n",
            # Signature structure is checked here; the trusted publishing run is the
            # provenance boundary, rather than a local test signing key.
            "signature": base64.b64encode(b"s" * 64).decode("ascii"),
        }
        self.write_manifest()
        self.apk_output = self.root / "release" / "Yfuse-published.apk"
        self.notes_output = self.root / "release" / "published-release-notes.txt"

    def write_manifest(self, **changes):
        self.manifest.write_text(json.dumps(self.metadata | changes, ensure_ascii=False), encoding="utf-8")

    def prepare(self):
        return prepare_release(self.artifacts, self.apk_output, self.notes_output)

    def assert_rejected_without_outputs(self):
        with self.assertRaises((ValueError, UnicodeError)):
            self.prepare()
        self.assertFalse(self.apk_output.exists())
        self.assertFalse(self.notes_output.exists())
        if self.apk_output.parent.exists():
            self.assertEqual(list(self.apk_output.parent.iterdir()), [])

    def test_manual_override_and_notes_win_over_repository_version_end_to_end(self):
        (self.root / "version.properties").write_text("VERSION_CODE=206\nVERSION_NAME=1.0.44\n", encoding="utf-8")
        (self.root / "release-notes.txt").write_text("1.0.44\nOld repository notes\n", encoding="utf-8")
        self.assertEqual(read_release(self.root)["versionCode"], 206)
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("published_release_metadata.py")),
             "--artifacts", str(self.artifacts), "--apk-output", str(self.apk_output),
             "--notes-output", str(self.notes_output)],
            cwd=self.root, capture_output=True, text=True, check=True,
        )
        self.assertEqual(result.stdout, "version_code=207\nversion_name=1.0.45\n")
        self.assertEqual(self.apk_output.read_bytes(), self.apk_bytes)
        self.assertEqual(self.notes_output.read_text(encoding="utf-8"), self.metadata["notes"])

    def test_tampered_apk_is_rejected_for_both_same_and_changed_size(self):
        for contents in (b"!" + self.apk_bytes[1:], self.apk_bytes + b"appended"):
            with self.subTest(contents=contents):
                self.apk.write_bytes(contents)
                self.assert_rejected_without_outputs()

    def test_invalid_versions_notes_and_integrity_fields_fail_closed(self):
        cases = (
            ("versionCode", True), ("versionCode", 207.0), ("versionCode", "207"),
            ("versionCode", 0), ("versionCode", 2_100_000_001),
            ("versionName", "1.0.45\nversion_code=999"), ("versionName", "1.0"),
            ("versionName", "١.٠.٤٥"), ("versionName", None),
            ("notes", None), ("notes", ["notes"]), ("notes", " \t\r\n"),
            ("notes", "notes\x00more"), ("notes", "notes\x1b[31m"),
            ("size", True), ("size", float(len(self.apk_bytes))), ("size", 0),
            ("size", len(self.apk_bytes) + 1), ("sha256", "0" * 64), ("sha256", "g" * 64),
            ("signature", "not base64"), ("signature", base64.b64encode(b"short").decode()),
        )
        for key, value in cases:
            with self.subTest(key=key, value=value):
                self.write_manifest(**{key: value})
                self.assert_rejected_without_outputs()

    def test_duplicate_json_keys_non_object_and_nonfinite_values_are_rejected(self):
        for value in (
            self.manifest.read_text(encoding="utf-8")[:-1] + ', "versionCode": 207}',
            "[]", "null", '{"versionCode": NaN}', '{"versionCode": Infinity}',
            json.dumps(self.metadata | {"notes": "\ud800"}),
        ):
            with self.subTest(value=value):
                self.manifest.write_text(value, encoding="utf-8")
                self.assert_rejected_without_outputs()

    def test_artifact_url_must_name_exact_version_without_unsafe_paths(self):
        for url in (
            "https://updates.example.com/Yfuse-206-1.0.44.apk",
            "https://updates.example.com/../Yfuse-207-1.0.45.apk",
            "https://updates.example.com/%2e%2e%2fYfuse-207-1.0.45.apk",
            "https://updates.example.com/Yfuse-207-1.0.45.apk?ignored=1",
            "https://updates.example.com/Yfuse-207-1.0.45.apk#fragment",
            "http://updates.example.com/Yfuse-207-1.0.45.apk",
            "https://user:password@updates.example.com/Yfuse-207-1.0.45.apk",
            "https://updates.example.com\\escape/Yfuse-207-1.0.45.apk",
            "https://updates.example.com:bad-port/Yfuse-207-1.0.45.apk",
        ):
            with self.subTest(url=url):
                self.write_manifest(apkUrl=url)
                self.assert_rejected_without_outputs()

    def test_missing_or_ambiguous_manifest_never_falls_back_to_an_apk_or_legacy_manifest(self):
        legacy = self.package / "update.json"
        self.manifest.rename(legacy)
        self.assert_rejected_without_outputs()
        legacy.rename(self.manifest)
        other = self.artifacts / "another-artifact"
        other.mkdir()
        (other / "update-v2.json").write_bytes(self.manifest.read_bytes())
        self.assert_rejected_without_outputs()

    def test_an_unrelated_apk_cannot_substitute_for_the_versioned_sibling(self):
        self.apk.rename(self.package / "some-other.apk")
        self.assert_rejected_without_outputs()

    def test_legacy_manifest_cannot_override_current_published_metadata(self):
        (self.package / "update.json").write_text('{"versionCode":206,"versionName":"1.0.44"}', encoding="utf-8")
        self.assertEqual(self.prepare(), {"versionCode": 207, "versionName": "1.0.45"})

    def test_outputs_cannot_overwrite_input_artifacts(self):
        with self.assertRaises(ValueError):
            prepare_release(self.artifacts, self.apk, self.notes_output)
        self.assertEqual(self.apk.read_bytes(), self.apk_bytes)


if __name__ == "__main__":
    unittest.main()
