"""Verify the signed motion release without changing historical diagnostic artifacts."""
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

output = Path(__file__).resolve().parent
root = output.parents[2]
apk = output / "Yfuse-1.0.46-signed-arm64.apk"

assert "BUILD SUCCESSFUL" in (output / "build.log").read_text(encoding="utf-8-sig")
badging = (output / "badging.txt").read_text(encoding="utf-8-sig")
signature = (output / "signature.txt").read_text(encoding="utf-8-sig")
assert re.search(r"package: name='com.yfuse' versionCode='208' versionName='1.0.46'", badging)
assert "native-code: 'arm64-v8a'" in badging
certificate = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
assert f"Signer #1 certificate SHA-256 digest: {certificate}" in signature
assert "Verifies" in signature
assert "Verification successful" in (output / "alignment.txt").read_text(encoding="utf-8-sig")
assert apk.stat().st_size < 30_000_000

native = []
with zipfile.ZipFile(apk) as package, zipfile.ZipFile(root / "composeApp/libs/ycore-native.aar") as source:
    for entry in sorted(source.namelist()):
        if entry.startswith("jni/") and entry.endswith(".so"):
            data = source.read(entry)
            assert package.read("lib/" + entry.removeprefix("jni/")) == data, entry
            native.append(dict(path=entry, sha256=hashlib.sha256(data).hexdigest()))
assert len(native) == 8

unit = []
for name in ("com.yfuse.app.RootTabMotionTest", "com.yfuse.feature.search.SearchResultsHandoffTest", "com.yfuse.core.data.ThemePreferencesTest"):
    path = root / f"composeApp/build/test-results/testDebugUnitTest/TEST-{name}.xml"
    suite = ET.parse(path).getroot()
    assert all(suite.attrib.get(key, "0") == "0" for key in ("failures", "errors", "skipped"))
    unit.append(dict(name=name, tests=int(suite.attrib["tests"])))
    shutil.copy2(path, output / path.name)
assert sum(item["tests"] for item in unit) == 15
device_path = root / "composeApp/build/outputs/androidTest-results/connected/debug/TEST-SM-G973U - 9-_composeApp-.xml"
device = ET.parse(device_path).getroot()
assert device.attrib["tests"] == "3"
assert all(device.attrib.get(key, "0") == "0" for key in ("failures", "errors", "skipped"))
shutil.copy2(device_path, output / device_path.name)

digest = hashlib.sha256(apk.read_bytes()).hexdigest()
Path(str(apk) + ".sha256").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
report = dict(
    apk=apk.name, bytes=apk.stat().st_size, sha256=digest,
    applicationId="com.yfuse", versionName="1.0.46", versionCode=208,
    certificateSha256=certificate, alignment="16 KB verified", abi="arm64-v8a",
    versionSource="Gradle overrides; repository release metadata left unchanged",
    tvBaseline="805af51c81247e87de6e1a0ee10101b97c64e990",
    nativeLibraries=native, unitTests=unit, deviceTests=3, device="Samsung SM-G973U / Android 9",
    testScope="Motion and preference tests passed before packaging; version-only overrides used for Release",
    included=["previous diagnostics fixes", "optional update public key", "search pulse/sweep and staged reveal", "liquid tab dragging", "persistent motion toggle"],
    notImplemented="Finly panel animation remains a proposal",
)
(output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: report[key] for key in ("apk", "bytes", "sha256", "versionName", "versionCode", "certificateSha256")}, indent=2))
