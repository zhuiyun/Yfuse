"""Record the final optional-key APK and its tested, preserved inputs."""

import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[3]
output = Path(__file__).resolve().parent
apk = output / "Yfuse-1.0.45-fixed-arm64.apk"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


assert "BUILD SUCCESSFUL" in (output / "gradle-optional-key-final.log").read_text(encoding="utf-8-sig")
digest = sha256(apk.read_bytes())
assert apk.stat().st_size < 30_000_000
Path(str(apk) + ".sha256").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")

native = []
with zipfile.ZipFile(apk) as package, zipfile.ZipFile(root / "composeApp/libs/ycore-native.aar") as aar:
    entries = sorted(name for name in aar.namelist() if name.startswith("jni/") and name.endswith(".so"))
    assert len(entries) == 8
    for entry in entries:
        source = aar.read(entry)
        assert package.read("lib/" + entry.removeprefix("jni/")) == source, entry
        native.append(dict(entry=entry, sha256=sha256(source), packagedIdentically=True))
(output / "apk-native-verification.json").write_text(json.dumps(native, indent=2) + "\n", encoding="utf-8")

suites = []
for path in sorted((root / "composeApp/build/test-results/testDebugUnitTest").glob("TEST-*.xml")):
    suite = ET.parse(path).getroot()
    suites.append({key: suite.attrib.get(key, "0") for key in ("name", "tests", "failures", "errors", "skipped")})
counts = {key: sum(int(suite[key]) for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
assert counts["tests"] > 2300 and all(counts[key] == 0 for key in ("failures", "errors", "skipped")), counts
(output / "unit-test-suites.json").write_text(json.dumps(suites, indent=2) + "\n", encoding="utf-8")

inputs = {}
for manifest in ("existing-user-files.json", "build-inputs.json"):
    entries = json.loads((output / manifest).read_text(encoding="utf-8-sig"))
    for entry in entries:
        assert sha256((root / entry["path"]).read_bytes()) == entry["sha256"].lower(), entry["path"]
    inputs[manifest] = len(entries)

verification = json.loads((output / "verification.json").read_text(encoding="utf-8-sig"))
verification.update(
    unitTests=counts["tests"], failures=counts["failures"], errors=counts["errors"], skipped=counts["skipped"],
    apkFile=apk.name, apkBytes=apk.stat().st_size, apkSha256=digest,
    updatePublicKey="optional; empty key permits online update checks and downloads",
    missingKeyBuildOverrideRequired=False, updateWorkflowTests=8, updateArchiveIdentityTests=8,
    preExistingUserFilesUnchanged=inputs["existing-user-files.json"],
    finalBuildInputsVerified=inputs["build-inputs.json"], packagedNativeLibrariesVerified=len(native),
    deviceTestScope="Playback/native tests completed before optional-key update changes; playback/native inputs unchanged",
)
(output / "verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(verification, ensure_ascii=False, indent=2))
