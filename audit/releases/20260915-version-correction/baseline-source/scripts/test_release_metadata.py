import tempfile
import unittest
from pathlib import Path

from release_metadata import read_release


class ReleaseMetadataTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / ".github").mkdir()
        self.write("version.properties", "VERSION_CODE=206\nVERSION_NAME=1.0.44\n")
        self.write("release-notes.txt", "1.0.44\n\nCurrent fixes\n\n1.0.43\nOld fixes\n")
        self.write(".github/sign-android-request", "# Trigger only\n")

    def write(self, name, value):
        (self.root / name).write_text(value, encoding="utf-8")

    def test_signing_and_publish_read_current_notes_only(self):
        self.assertEqual(read_release(self.root), {
            "versionCode": 206, "versionName": "1.0.44", "releaseNotes": "Current fixes",
        })

    def test_rejects_duplicate_or_invalid_versions(self):
        for value in (
            "VERSION_CODE=206\nVERSION_NAME=1.0.44\nVERSION_CODE=207\n",
            "VERSION_CODE=0\nVERSION_NAME=1.0.44\n",
            "VERSION_CODE=2100000001\nVERSION_NAME=1.0.44\n",
            "VERSION_CODE=206\nVERSION_NAME=1.0\n",
            "VERSION_NAME=1.0.44\n",
        ):
            with self.subTest(value=value):
                self.write("version.properties", value)
                with self.assertRaises(ValueError):
                    read_release(self.root)

    def test_rejects_drifted_or_empty_current_notes(self):
        for value in ("1.0.43\nOld notes", "1.0.44\n\n1.0.43\nOld notes"):
            with self.subTest(value=value):
                self.write("release-notes.txt", value)
                with self.assertRaises(ValueError):
                    read_release(self.root)

    def test_rejects_a_second_version_source_even_if_equal(self):
        self.write(".github/sign-android-request", "version_code=206\nversion_name=1.0.44\n")
        with self.assertRaises(ValueError):
            read_release(self.root)


if __name__ == "__main__":
    unittest.main()
