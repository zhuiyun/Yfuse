import importlib.util
import json
import tempfile
from types import SimpleNamespace
import unittest
import zipfile
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).parents[1] / "prepare-playback-inputs.py"
spec = importlib.util.spec_from_file_location("playback_inputs", SCRIPT)
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class PreparePlaybackInputsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        self.ycore = self.root / "composeApp/libs/ycore-native.aar"
        self.ycore.parent.mkdir(parents=True)
        provenance = b"audited fixture provenance\n"
        with zipfile.ZipFile(self.ycore, "w") as archive:
            for name in prepare.YCORE_LIBRARIES:
                archive.writestr("jni/arm64-v8a/" + name, b"native fixture")
            archive.writestr("META-INF/ycore-native-sources.txt", provenance)
            archive.writestr("META-INF/NOTICE", b"fixture notices")
        self.pins = {
            "profile": "ycore-senplayer",
            "ycore": {"sha256": prepare.sha256(self.ycore.read_bytes()),
                      "bytes": self.ycore.stat().st_size,
                      "provenanceSha256": prepare.sha256(provenance)},
            "senPlayerSourceApkSha256": "a" * 64,
        }
        (self.root / "scripts/playback-runtime-inputs.json").write_text(json.dumps(self.pins))
        self.sen = self.root / "senplayerBridge/src/main/assets/senplayer"
        (self.sen / "native").mkdir(parents=True)
        records = []
        for asset in ["classes.dex", "license.json", "native/libFFmpegKit.so", "native/libMLibKit.so", "native/libc++_shared.so"]:
            data = b"reviewed original fixture"
            (self.sen / asset).write_bytes(data)
            records.append({"asset": asset, "bytes": len(data), "sha256": prepare.sha256(data)})
        (self.sen / "runtime.json").write_text(json.dumps({"abi": "arm64-v8a", "sourceApkSha256": "a" * 64, "files": records}))

    def test_existing_pinned_inputs_require_no_network(self):
        fetch = mock.Mock(side_effect=AssertionError("unexpected network"))
        self.assertEqual(5, prepare.prepare(self.root, {}, fetch)["senPlayerOriginalAssets"])
        fetch.assert_not_called()
        self.assertEqual(b"audited fixture provenance\n", (self.ycore.parent / "ycore-native.sources.txt").read_bytes())

    def test_cold_ci_without_explicit_ycore_input_fails(self):
        self.ycore.unlink()
        with self.assertRaisesRegex(ValueError, "Missing pinned YCORE_NATIVE_AAR"):
            prepare.prepare(self.root, {}, mock.Mock())

    def test_missing_sen_original_does_not_produce_a_ycore_only_success(self):
        (self.sen / "classes.dex").unlink()
        with self.assertRaisesRegex(ValueError, "Missing pinned SENPLAYER_SOURCE_APK"):
            prepare.prepare(self.root, {}, mock.Mock())

    def test_changed_existing_ycore_is_rejected_without_refetch(self):
        self.ycore.write_bytes(self.ycore.read_bytes() + b"changed")
        fetch = mock.Mock()
        with self.assertRaisesRegex(ValueError, "fixed release pin"):
            prepare.prepare(self.root, {"YCORE_NATIVE_AAR_URL": "https://example.test/input"}, fetch)
        fetch.assert_not_called()

    def test_downloaded_wrong_input_is_not_installed(self):
        self.ycore.unlink()
        def fetch(_url, destination):
            destination.write_bytes(b"not the pinned YCore")
        with self.assertRaisesRegex(ValueError, "fixed release pin"):
            prepare.prepare(self.root, {"YCORE_NATIVE_AAR_URL": "https://example.test/input"}, fetch)
        self.assertFalse(self.ycore.exists())
        self.assertEqual([], list((self.root / "build/playback-input-downloads").iterdir()))

    def test_configured_local_ycore_is_copied_only_after_identity_check(self):
        source = self.root / "reviewed.aar"
        self.ycore.rename(source)
        result = prepare.prepare(self.root, {"YCORE_NATIVE_AAR_PATH": str(source)}, mock.Mock())
        self.assertEqual(self.pins["ycore"]["sha256"], result["ycoreSha256"])
        self.assertEqual(source.read_bytes(), self.ycore.read_bytes())

    def test_corrupt_sen_asset_is_rejected(self):
        (self.sen / "classes.dex").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            prepare.prepare(self.root, {}, mock.Mock())

    def test_source_build_has_separate_verified_identity_without_rewriting_old_pin(self):
        original_pins = (self.root / "scripts/playback-runtime-inputs.json").read_bytes()
        self.ycore.write_bytes(self.ycore.read_bytes() + b"new source output")
        new_pin = dict(self.pins["ycore"], bytes=self.ycore.stat().st_size,
                       sha256=prepare.sha256(self.ycore.read_bytes()))
        verify = mock.Mock(return_value={"ycore": new_pin})
        module = SimpleNamespace(verify_receipt=verify)
        spec = SimpleNamespace(loader=SimpleNamespace(exec_module=mock.Mock()))
        env = {"YCORE_SOURCE_RECEIPT": str(self.root / "source-build.json"),
               "YCORE_SOURCE_COMMIT": "b" * 40, "GITHUB_SHA": "c" * 40, "SOURCE_COMMIT": "b" * 40,
               "YCORE_SOURCE_AAR_SHA256": new_pin["sha256"],
               "YCORE_SOURCE_PROVENANCE_SHA256": new_pin["provenanceSha256"]}
        with mock.patch.object(prepare.importlib.util, "spec_from_file_location", return_value=spec), \
             mock.patch.object(prepare.importlib.util, "module_from_spec", return_value=module):
            result = prepare.prepare(self.root, env, mock.Mock())
        self.assertEqual("verified-source-build", result["ycoreInputKind"])
        self.assertEqual(new_pin["sha256"], result["ycoreSha256"])
        verify.assert_called_once_with(self.root, self.ycore, self.root / "source-build.json",
                                       new_pin["sha256"], new_pin["provenanceSha256"], "b" * 40)
        self.assertEqual(original_pins, (self.root / "scripts/playback-runtime-inputs.json").read_bytes())

    def test_partial_or_different_commit_source_identity_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Incomplete source-built"):
            prepare.prepare(self.root, {"YCORE_SOURCE_RECEIPT": "receipt"}, mock.Mock())
        env = {key: "x" for key in ("YCORE_SOURCE_RECEIPT", "YCORE_SOURCE_AAR_SHA256",
                                   "YCORE_SOURCE_PROVENANCE_SHA256", "YCORE_SOURCE_COMMIT")}
        env["GITHUB_SHA"] = "different"
        with self.assertRaisesRegex(ValueError, "application commit"):
            prepare.prepare(self.root, env, mock.Mock())

    def test_http_input_and_downgrade_redirect_are_rejected_before_network(self):
        with self.assertRaisesRegex(ValueError, "must use HTTPS"):
            prepare.download("http://example.test/input", self.root / "download")
        handler = prepare.HttpsRedirectHandler()
        with self.assertRaisesRegex(ValueError, "remain HTTPS"):
            handler.redirect_request(None, None, 302, "", {}, "http://example.test/input")


if __name__ == "__main__":
    unittest.main()
