"""The public SCore input is the unchanged, complete reviewed runtime, not its source APK."""
import importlib.util
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "senplayerBridge/src/main/assets/senplayer"
SPEC = importlib.util.spec_from_file_location("vendored_playback_inputs", ROOT / "scripts/prepare-playback-inputs.py")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class VendoredSenPlayerAssetsTest(unittest.TestCase):
    def test_real_committed_assets_match_the_original_reviewed_identity(self):
        pins = json.loads((ROOT / "scripts/playback-runtime-inputs.json").read_text())
        manifest = PREPARE.verify_sen_assets(ASSETS, pins["senPlayerSourceApkSha256"])
        expected = {record["asset"] for record in manifest["files"]} | {"runtime.json"}
        actual = {path.relative_to(ASSETS).as_posix() for path in ASSETS.rglob("*") if path.is_file()}
        self.assertEqual(expected, actual)
        self.assertFalse(any(path.is_symlink() for path in ASSETS.rglob("*")))

    def test_the_checkout_tracks_only_the_six_reviewed_asset_files(self):
        manifest = json.loads((ASSETS / "runtime.json").read_text())
        prefix = ASSETS.relative_to(ROOT).as_posix() + "/"
        expected = {prefix + record["asset"] for record in manifest["files"]} | {prefix + "runtime.json"}
        tracked = subprocess.check_output(
            ["git", "-C", str(ROOT), "ls-files", "--", prefix], text=True).splitlines()
        self.assertEqual(expected, set(tracked))
        self.assertFalse(any(path.lower().endswith(".apk") for path in tracked))


if __name__ == "__main__":
    unittest.main()
