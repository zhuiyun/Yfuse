import random
import runpy
import tempfile
import unittest
import warnings
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile


compact = runpy.run_path(str(Path(__file__).resolve().parents[1] / "recompress-apk.py"))["compact"]


class RecompressApkTest(unittest.TestCase):
    def test_only_large_deflated_runtime_entries_are_recompressed(self):
        chunk = random.Random(19).randbytes(4096)
        data = chunk * 128
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "assembled.apk"
            target = Path(temporary) / "prepared.apk"
            with ZipFile(source, "w") as archive:
                for name in ("classes.dex", "lib/arm64-v8a/libycore_demux.so", "assets/senplayer/native/libFFmpegKit.so",
                             "assets/senplayer/classes.dex", "assets/unrelated.dex"):
                    archive.writestr(name, data, compress_type=ZIP_DEFLATED, compresslevel=0)
                archive.writestr("classes2.dex", b"small", compress_type=ZIP_DEFLATED, compresslevel=0)
                archive.writestr("lib/arm64-v8a/libstored.so", data, compress_type=ZIP_STORED)
                archive.writestr("resources.arsc", data[:50], compress_type=ZIP_STORED)
            compact(source, target)
            with ZipFile(source) as old, ZipFile(target) as new:
                self.assertEqual(set(old.namelist()), set(new.namelist()))
                for item in old.infolist():
                    name = item.filename
                    self.assertEqual(old.read(name), new.read(name))
                    self.assertEqual(item.compress_type, new.getinfo(name).compress_type)
                    if name in ("classes.dex", "lib/arm64-v8a/libycore_demux.so", "assets/senplayer/classes.dex",
                                "assets/senplayer/native/libFFmpegKit.so"):
                        self.assertLess(new.getinfo(name).compress_size, item.compress_size)
                    else:
                        self.assertEqual(new.getinfo(name).compress_size, item.compress_size)

    def test_empty_selection_and_duplicate_entry_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "assembled.apk"
            target = Path(temporary) / "prepared.apk"
            with ZipFile(source, "w") as archive:
                archive.writestr("resources.arsc", b"table")
            compact(source, target)
            original = target.read_bytes()
            self.assertEqual(original, source.read_bytes())
            with self.assertRaisesRegex(ValueError, "separate output"):
                compact(source, source)
            with warnings.catch_warnings(), ZipFile(source, "a") as archive:
                warnings.simplefilter("ignore", UserWarning)
                archive.writestr("resources.arsc", b"duplicate")
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                compact(source, target)
            self.assertEqual(original, target.read_bytes())
