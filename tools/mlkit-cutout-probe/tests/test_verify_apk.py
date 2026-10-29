from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_apk import parse_badging


class BadgingRegression(unittest.TestCase):
    def test_android_37_codename_cannot_overwrite_package(self):
        actual = parse_badging("package: name='com.yfuse.cutoutprobe.v2' versionCode='2' "
                               "versionName='0.0.2' platformBuildVersionName='17' "
                               "compileSdkVersion='37' compileSdkVersionCodename='17'\n"
                               "sdkVersion:'24'\n")
        self.assertEqual(actual, {"name": "com.yfuse.cutoutprobe.v2", "versionCode": "2",
                                  "versionName": "0.0.2"})

    def test_missing_or_duplicate_identity_fails(self):
        for invalid in ("", "package: name='x' versionCode='2'", "package: name='x' name='y' "
                        "versionCode='2' versionName='0.0.2'"):
            with self.assertRaises(ValueError):
                parse_badging(invalid)


if __name__ == "__main__":
    unittest.main()
