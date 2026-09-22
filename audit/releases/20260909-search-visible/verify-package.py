import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

out = Path(__file__).resolve().parent
root = out.parents[2]
apk = out / "Yfuse-1.0.47-signed-arm64.apk"
certificate = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
assert "BUILD SUCCESSFUL" in (out / "build.log").read_text(encoding="utf-8-sig")
first_run = (out / "device-tests-with-tab.log").read_text(encoding="utf-8-sig")
passed = set(re.findall(r"INSTRUMENTATION_STATUS: test=([^\r\n]+)\r?\nINSTRUMENTATION_STATUS_CODE: 0", first_run))
assert {
    "real_search_field_has_visible_motion_in_both_themes_and_switches_remove_it",
    "disclosure_reverses_one_body_without_remeasuring_it_and_policy_changes_snap_to_the_target",
    "search_handoff_keeps_one_content_tree_and_layer_frames_do_not_recompose_it",
}.issubset(passed)
assert f"Signer #1 certificate SHA-256 digest: {certificate}" in (out / "signature.txt").read_text(encoding="utf-8-sig")
badging = (out / "badging.txt").read_text(encoding="utf-8-sig")
assert "name='com.yfuse' versionCode='209' versionName='1.0.47'" in badging
assert "native-code: 'arm64-v8a'" in badging
assert "Verification successful" in (out / "alignment.txt").read_text(encoding="utf-8-sig")
native = []
with zipfile.ZipFile(apk) as package, zipfile.ZipFile(root / "composeApp/libs/ycore-native.aar") as source:
    for name in source.namelist():
        if name.startswith("jni/") and name.endswith(".so"):
            original = source.read(name)
            assert package.read("lib/" + name.removeprefix("jni/")) == original
            native.append(name)
assert len(native) == 8
tests = []
for name in ("com.yfuse.app.RootTabMotionTest", "com.yfuse.feature.search.SearchResultsHandoffTest", "com.yfuse.core.data.ThemePreferencesTest"):
    path = root / f"composeApp/build/test-results/testDebugUnitTest/TEST-{name}.xml"
    suite = ET.parse(path).getroot()
    assert all(suite.attrib.get(key, "0") == "0" for key in ("failures", "errors", "skipped"))
    tests.append(dict(name=name, tests=int(suite.attrib["tests"])))
    (out / path.name).write_bytes(path.read_bytes())
assert sum(item["tests"] for item in tests) == 16
sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
assert apk.stat().st_size < 30_000_000
Path(str(apk) + ".sha256").write_text(f"{sha256}  {apk.name}\n", encoding="utf-8")
report = dict(
    apk=apk.name, versionName="1.0.47", versionCode=209, bytes=apk.stat().st_size, sha256=sha256,
    certificateSha256=certificate, abi="arm64-v8a", alignment="16 KB verified", nativeLibraries=native,
    unitTests=tests, deviceTests=3, device="Samsung SM-G973U / Android 9",
    deviceTestLimitations="First run passed all 3 search tests. Extra unchanged Tab test could not inject input into another foreground app; subsequent search repeat had zero-size window while another app was foreground. Repeated attempts stopped; both raw logs retained.",
    visualChecks="PixelCopy on actual SearchField: light/dark, visible waiting movement, retained-result refresh, disabled/reduced-motion fallback",
    tvBaseline="805af51c81247e87de6e1a0ee10101b97c64e990",
    versionSource="Build overrides; repository release metadata unchanged",
)
(out / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
