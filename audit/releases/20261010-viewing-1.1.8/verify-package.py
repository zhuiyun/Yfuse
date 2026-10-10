"""Verify the actual local delivery and retain its evidence; never publish it."""

import hashlib
import json
from pathlib import Path
import re
from datetime import datetime, timezone
import zipfile

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
DELIVERY = ROOT / "artifacts-local/releases/viewing-1.1.8-270"
APK = DELIVERY / "Yfuse-1.1.8-full-arm64.apk"
PREVIOUS = ROOT / "artifacts-local/releases/schedule-1.1.7-269/Yfuse-1.1.7-full-arm64.apk"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_log(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


badging = read_log(DELIVERY / "full-badging.txt")
signature = read_log(DELIVERY / "full-signature.txt")
alignment = read_log(DELIVERY / "full-alignment.txt")
build = read_log(DELIVERY / "build-full.log")
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
assert package and package.groups() == ("com.yfuse", "270", "1.1.8")
assert "application-debuggable" not in badging
assert "native-code: 'arm64-v8a'" in badging
assert "Verifies" in signature and "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signature).group(1)
assert certificate == "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
assert "Number of signers: 1" in signature
assert "Verification successful" in alignment
assert "BUILD SUCCESSFUL" in build
dex = re.search(r"(\d+) classes, (\d+) methods with code, (\d+) findings, (\d+) methods not analysable", build)
assert dex and tuple(map(int, dex.groups()[2:])) == (0, 0)
assert digest(APK.read_bytes()) == digest((ROOT / "composeApp/build/outputs/apk/release/composeApp-release.apk").read_bytes())
assert digest(PREVIOUS.read_bytes()) == "760311d02b6418c007d01c53d3464dec45da54f3f824f0936529e0a06dc6948a"
previous = json.loads((ROOT / "audit/releases/20261009-schedule-1.1.7/verification.json").read_text(encoding="utf-8-sig"))
assert previous["versionCode"] < 270 and previous["certificateSha256"] == certificate

properties = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text(encoding="utf-8-sig").splitlines() if line and not line.startswith("#"))
assert properties["VERSION_NAME"] == "1.1.8" and properties["VERSION_CODE"] == "270"
assert (ROOT / "release-notes.txt").read_text(encoding="utf-8-sig").splitlines()[0] == "1.1.8"
approval = json.loads((ROOT / ".github/mdk-distribution-approval.json").read_text(encoding="utf-8-sig"))
assert approval["confirmed"] is True and approval["versionName"] == "1.1.8" and approval["versionCode"] == 270
assert approval["scope"] == "package-only"
assert digest((ROOT / "composeApp/libs/mdk-sdk-android.7z").read_bytes()) == approval["mdkArtifactSha256"]
source = json.loads((AUDIT / "source-inputs.json").read_text(encoding="utf-8"))
build_proof = json.loads((DELIVERY / "build-verification.json").read_text(encoding="utf-8-sig"))
assert build_proof["sourceCommit"] == source["sourceCommit"] and build_proof["treeDirty"] is True
assert build_proof["versionName"] == "1.1.8" and str(build_proof["versionCode"]) == "270"
assert build_proof["mdkIncluded"] is True
for item in source["files"]:
    assert digest((ROOT / item["path"]).read_bytes()) == item["sha256"], item["path"]
tests = json.loads((ROOT / "audit/viewing-features-20261010/verification.json").read_text(encoding="utf-8-sig"))
for item in tests["source_files"]:
    assert digest((ROOT / item["path"]).read_bytes()) == item["sha256"], item["path"]

mdk = {}
native = {}
with zipfile.ZipFile(APK) as archive, zipfile.ZipFile(PREVIOUS) as old:
    dex_files = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
    assert dex_files and all(archive.getinfo(name).file_size > 0 for name in dex_files)
    assert {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")} == {"arm64-v8a"}
    for library in ("libmdk.so", "libyfuse-mdk-jni.so"):
        name = f"lib/arm64-v8a/{library}"
        data = archive.read(name)
        assert data and data == old.read(name), name
        mdk[name] = {"bytes": len(data), "sha256": digest(data), "matchesPrevious": True}
    assert archive.read("lib/arm64-v8a/libmdk.so") == (ROOT / "composeApp/libs/mdk-sdk/lib/arm64-v8a/libmdk.so").read_bytes()
    for library in ("libmpv.so", "libycore_demux.so", "libycore_gpu.so", "libavcodec.so", "libavformat.so", "libavutil.so", "libswresample.so", "libswscale.so", "libc++_shared.so"):
        name = f"lib/arm64-v8a/{library}"
        data = archive.read(name)
        assert data and data == old.read(name), name
        native[name] = digest(data)

sbom_path = DELIVERY / "dependency-locks.spdx.json"
sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
result = {
    "versionName": "1.1.8", "versionCode": 270, "packageName": "com.yfuse", "profile": "full", "abi": "arm64-v8a",
    "sourceCommit": source["sourceCommit"], "treeDirtyAtBuild": True, "diffHash": build_proof["diffHash"], "sourceFilesArchived": len(source["files"]),
    "file": APK.name, "bytes": APK.stat().st_size, "sha256": digest(APK.read_bytes()),
    "certificateSha256": certificate, "signatureV2Verified": True, "numberOfSigners": 1, "zipAlignment16K": True,
    "dexVerified": True, "dexClasses": int(dex[1]), "dexMethodsWithCode": int(dex[2]), "dexFindings": 0, "dexMethodsNotAnalysable": 0, "dexFiles": dex_files,
    "mdkIncluded": True, "mdkLibraries": mdk, "mdkEngineMatchesPinnedSdk": True, "mdkArtifactSha256": approval["mdkArtifactSha256"],
    "unchangedNativeLibraries": native, "previousActualVersionName": "1.1.7", "previousActualVersionCode": 269, "previousArtifactSha256": digest(PREVIOUS.read_bytes()),
    "releaseNotesFirstLine": "1.1.8", "tests": tests["tests"], "spdxLockPackages": len(sbom["packages"]), "spdxSha256": digest(sbom_path.read_bytes()),
    "onlineVulnerabilityScanPerformed": False, "deviceTestPerformed": False, "published": False, "uploaded": False,
    "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
}
for path in (AUDIT / "verification.json", DELIVERY / "verification.json"):
    path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(json.dumps({key: result[key] for key in ("versionName", "versionCode", "packageName", "file", "bytes", "sha256", "mdkIncluded", "dexClasses", "dexMethodsWithCode")}, indent=2))
