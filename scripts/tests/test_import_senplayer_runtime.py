import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("senplayer_import", Path(__file__).parents[1] / "import-senplayer-runtime.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SenPlayerImportTest(unittest.TestCase):
    def test_wrong_binary_is_rejected_without_overwriting_existing_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, destination = root / "source", root / "dest"
            source.mkdir()
            destination.mkdir()
            for name in module.RUNTIMES:
                (source / name).write_bytes(b"unverified")
                (destination / name).write_bytes(b"existing")
            with self.assertRaises(ValueError):
                module.import_runtime(source, destination)
            for name in module.RUNTIMES:
                self.assertEqual(b"existing", (destination / name).read_bytes())

    def test_archive_cannot_write_unrelated_or_traversal_entries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "sample.zip"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("../unexpected", b"bad")
                archive.writestr("assets/license.json", b"not imported")
            with self.assertRaises(ValueError):
                module.import_runtime(source, root / "dest")
            self.assertFalse((root / "unexpected").exists())
            self.assertFalse((root / "dest").exists())
