from __future__ import annotations

import importlib.util
import pathlib
import unittest


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


if __name__ == "__main__":
    unittest.main()
