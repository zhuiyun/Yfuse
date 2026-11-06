from __future__ import annotations

import importlib.util
import pathlib
import unittest
import tempfile
import zipfile
from unittest import mock


SCRIPT = pathlib.Path(__file__).parents[1] / "verify-tv-artifact.py"
SPEC = importlib.util.spec_from_file_location("verify_tv_artifact", SCRIPT)
if SPEC is None or SPEC.loader is None:  # pragma: no cover - importlib contract guard
    raise RuntimeError(f"cannot load {SCRIPT}")
VERIFY_TV_ARTIFACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY_TV_ARTIFACT)


VALID_BADGING = """\
package: name='com.yfuse' versionCode='1' versionName='1.0.0'
uses-feature: name='android.software.leanback'
uses-feature-not-required: name='android.hardware.touchscreen'
uses-permission: name='android.permission.INTERNET'
uses-permission-sdk-23: name='android.permission.POST_NOTIFICATIONS'
leanback-launchable-activity: name='com.yfuse.tv.TvMainActivity' label='Yfuse'
"""


class BadgingContractTest(unittest.TestCase):
    def test_accepts_canonical_aapt_named_records(self) -> None:
        VERIFY_TV_ARTIFACT.verify_badging_contract(VALID_BADGING, "com.yfuse")

        records = VERIFY_TV_ARTIFACT.named_badging_records(VALID_BADGING)
        self.assertEqual(
            records["uses-feature"],
            {"android.software.leanback"},
        )
        self.assertEqual(
            records["uses-feature-not-required"],
            {"android.hardware.touchscreen"},
        )

    def test_optional_leanback_does_not_satisfy_required_feature(self) -> None:
        badging = VALID_BADGING.replace(
            "uses-feature: name='android.software.leanback'",
            "uses-feature-not-required: name='android.software.leanback'",
        )

        with self.assertRaisesRegex(SystemExit, "leanback is not required"):
            VERIFY_TV_ARTIFACT.verify_badging_contract(badging, "com.yfuse")

    def test_required_touchscreen_does_not_satisfy_optional_feature(self) -> None:
        badging = VALID_BADGING.replace(
            "uses-feature-not-required: name='android.hardware.touchscreen'",
            "uses-feature: name='android.hardware.touchscreen'",
        )

        with self.assertRaisesRegex(SystemExit, "touchscreen must be declared required=false"):
            VERIFY_TV_ARTIFACT.verify_badging_contract(badging, "com.yfuse")

    def test_parses_whitespace_attributes_and_permission_variants(self) -> None:
        badging = """\
  uses-feature:   name='android.software.leanback' required='true'
uses-permission-sdk-33: name='android.permission.CAMERA' maxSdkVersion='35'
"""

        self.assertEqual(
            VERIFY_TV_ARTIFACT.named_badging_records(badging)["uses-feature"],
            {"android.software.leanback"},
        )
        self.assertEqual(
            VERIFY_TV_ARTIFACT.manifest_permissions(badging),
            {"android.permission.CAMERA"},
        )

    def test_rejects_forbidden_permission_variant(self) -> None:
        badging = VALID_BADGING + (
            "uses-permission-sdk-33: name='android.permission.CAMERA' maxSdkVersion='35'\n"
        )

        with self.assertRaisesRegex(SystemExit, "android.permission.CAMERA"):
            VERIFY_TV_ARTIFACT.verify_badging_contract(badging, "com.yfuse")


class NativeProfileContractTest(unittest.TestCase):
    def check_entries(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            apk = pathlib.Path(directory) / "tv.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name in entries:
                    archive.writestr(name, b"structural fixture")
            with mock.patch.object(VERIFY_TV_ARTIFACT, "run", return_value="LOAD 0x0 0x0 0x0 0x10 0x10 R 0x4000"):
                VERIFY_TV_ARTIFACT.verify_native_code(apk, "unused-readelf")

    def test_arm64_is_the_actual_two_engine_abi(self):
        self.check_entries(["lib/arm64-v8a/libycore_demux.so"])

    def test_empty_system_only_apk_cannot_pass_the_two_engine_contract(self):
        with self.assertRaisesRegex(SystemExit, "must include its arm64"):
            self.check_entries([])

    def test_unavailable_32_bit_runtime_is_not_claimed(self):
        with self.assertRaisesRegex(SystemExit, "unsupported TV native ABI"):
            self.check_entries(["lib/arm64-v8a/libycore_demux.so", "lib/armeabi-v7a/libycore_demux.so"])

    def test_mdk_bridge_alone_is_also_rejected(self):
        with self.assertRaisesRegex(SystemExit, "forbidden compatibility player"):
            self.check_entries(["lib/arm64-v8a/libyfuse-mdk-jni.so"])


if __name__ == "__main__":
    unittest.main()
