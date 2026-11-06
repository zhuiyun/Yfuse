import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

SCRIPT = Path(__file__).parents[1] / "import-senplayer-runtime.py"


def load_module():
    spec = importlib.util.spec_from_file_location("senplayer_import", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


module = load_module()


def fixture_entries():
    # Synthetic headers exercise ABI preflight; these are not playable/vendor binaries.
    native = bytearray(64)
    native[:6] = b"\x7fELF\x02\x01"
    native[18:20] = (183).to_bytes(2, "little")
    return {
        "classes.dex": b"synthetic-dex-fixture",
        "assets/license.json": b'{"fixture": true}',
        **{f"lib/arm64-v8a/{name}": bytes(native) for name in module.RUNTIMES},
    }


def write_apk(path, entries):
    with zipfile.ZipFile(path, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def existing_runtime(path):
    for target in [*module.FILES.values(), "runtime.json"]:
        file = path / target
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(b"existing-" + target.encode())


def snapshot(path):
    return {file.relative_to(path).as_posix(): file.read_bytes() for file in path.rglob("*") if file.is_file()}


class SenPlayerImportTest(unittest.TestCase):
    def test_import_has_no_asset_read_or_write_side_effect(self):
        with patch.object(Path, "read_bytes", side_effect=AssertionError("asset read during import")), \
             patch.object(Path, "mkdir", side_effect=AssertionError("mkdir during import")), \
             patch.object(Path, "write_bytes", side_effect=AssertionError("asset write during import")), \
             patch.object(Path, "write_text", side_effect=AssertionError("manifest write during import")):
            imported = load_module()
        self.assertEqual(5, len(imported.FILES))
        self.assertIn("libc++_shared.so", imported.RUNTIMES)
        self.assertEqual("45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c",
                         imported.EXPECTED_APK_SHA256)

    def test_wrong_apk_is_rejected_without_overwriting_existing_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, destination = root / "wrong.apk", root / "dest"
            write_apk(apk, fixture_entries())
            existing_runtime(destination)
            before = snapshot(destination)
            with self.assertRaises(ValueError):
                module.import_runtime(apk, destination)
            self.assertEqual(before, snapshot(destination))

    def test_loose_two_library_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source, destination = root / "source", root / "dest"
            source.mkdir()
            for name in module.RUNTIMES[:2]:
                (source / name).write_bytes(b"unverified")
            existing_runtime(destination)
            before = snapshot(destination)
            with self.assertRaises(ValueError):
                module.import_runtime(source, destination)
            self.assertEqual(before, snapshot(destination))

    def assert_preflight_preserves_output(self, entries):
        for existing in (False, True):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                apk, destination = root / "fixture.apk", root / "dest"
                digest = write_apk(apk, entries)
                if existing:
                    existing_runtime(destination)
                before = snapshot(destination)
                # Test-only trust of a controlled fixture; the production CLI has no override.
                with patch.object(module, "EXPECTED_APK_SHA256", digest), self.assertRaises(ValueError):
                    module.import_runtime(apk, destination)
                self.assertEqual(before, snapshot(destination))
                self.assertEqual(existing, destination.exists())
                self.assertFalse((root / "unexpected").exists())

    def test_traversal_archive_is_rejected_before_any_output(self):
        for path in ("../unexpected", "..\\unexpected", "/unexpected", "C:/unexpected"):
            with self.subTest(path=path):
                self.assert_preflight_preserves_output({**fixture_entries(), path: b"not imported"})

    def test_every_missing_group_member_prevents_partial_output(self):
        for missing in module.FILES:
            with self.subTest(missing=missing):
                self.assert_preflight_preserves_output({k: v for k, v in fixture_entries().items() if k != missing})

    def test_wrong_native_abi_prevents_partial_output(self):
        entries = fixture_entries()
        name = "lib/arm64-v8a/libc++_shared.so"
        invalid = bytearray(entries[name])
        invalid[18:20] = (40).to_bytes(2, "little")  # ARM32 machine in an arm64-labelled entry.
        entries[name] = bytes(invalid)
        self.assert_preflight_preserves_output(entries)

    def test_duplicate_entry_prevents_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, destination = root / "duplicate.apk", root / "dest"
            write_apk(apk, fixture_entries())
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(apk, "a") as archive:
                    archive.writestr("classes.dex", b"duplicate")
            existing_runtime(destination)
            before = snapshot(destination)
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            with patch.object(module, "EXPECTED_APK_SHA256", digest), self.assertRaises(ValueError):
                module.import_runtime(apk, destination)
            self.assertEqual(before, snapshot(destination))

    def test_complete_fixture_writes_exact_five_assets_and_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            apk, destination = root / "fixture.apk", root / "dest"
            entries = fixture_entries()
            digest = write_apk(apk, {**entries, "unrelated.txt": b"must not extract"})
            with patch.object(module, "EXPECTED_APK_SHA256", digest):
                manifest = module.import_runtime(apk, destination)
            self.assertEqual(set(module.FILES.values()) | {"runtime.json"}, set(snapshot(destination)))
            self.assertEqual({"sourceApkSha256", "abi", "files"}, set(manifest))
            self.assertEqual(digest, manifest["sourceApkSha256"])
            self.assertEqual("arm64-v8a", manifest["abi"])
            self.assertEqual(manifest, json.loads((destination / "runtime.json").read_text()))
            self.assertEqual(5, len(manifest["files"]))
            for row in manifest["files"]:
                data = entries[row["apkEntry"]]
                self.assertEqual(data, (destination / row["asset"]).read_bytes())
                self.assertEqual(len(data), row["bytes"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), row["sha256"])

    def test_cli_defaults_remain_the_reviewed_apk_and_bridge_asset_directory(self):
        with patch.object(module, "import_runtime", return_value={}) as importer, patch("builtins.print"):
            module.main([])
        importer.assert_called_once_with(module.DEFAULT_APK, module.DEFAULT_DESTINATION)
        self.assertEqual("SenPlayer.apk", module.DEFAULT_APK.name)
        self.assertEqual("senplayerBridge/src/main/assets/senplayer",
                         module.DEFAULT_DESTINATION.relative_to(SCRIPT.resolve().parents[1]).as_posix())


if __name__ == "__main__":
    unittest.main()
