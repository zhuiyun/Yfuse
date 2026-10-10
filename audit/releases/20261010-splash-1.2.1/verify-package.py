"""Verify this signed, local-only release against its source and previous delivery."""

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
DELIVERY = ROOT / "artifacts-local/releases/splash-1.2.1-273"
APK = DELIVERY / "Yfuse-1.2.1-full-arm64.apk"
PREVIOUS = ROOT / "artifacts-local/releases/splash-1.2.0-272/Yfuse-1.2.0-full-arm64.apk"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_log(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def package_from_badging(path):
    found = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", read_log(path))
    assert found, f"Missing APK metadata: {path}"
    return found[1], int(found[2]), found[3]


def certificate_from_signature(path):
    signature = read_log(path)
    assert "Verifies" in signature
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    assert "Number of signers: 1" in signature
    return re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", signature)[1]


previous_package, previous_code, previous_name = package_from_badging(AUDIT / "previous-badging.txt")
assert (previous_package, previous_code, previous_name) == ("com.yfuse", 272, "1.2.0")
assert digest(PREVIOUS.read_bytes()) == "37159b4f89870988d2f47646012f1385f479998e1e10465e054486eedc5be1a3"
package, code, name = package_from_badging(DELIVERY / "full-badging.txt")
assert (package, code, name) == ("com.yfuse", 273, "1.2.1")
assert code > previous_code and tuple(map(int, name.split("."))) > tuple(map(int, previous_name.split(".")))
assert re.fullmatch(r"\d\.\d\.\d", name)
badging = read_log(DELIVERY / "full-badging.txt")
assert "application-debuggable" not in badging
assert "native-code: 'arm64-v8a'" in badging
certificate = certificate_from_signature(DELIVERY / "full-signature.txt")
assert certificate == certificate_from_signature(AUDIT / "previous-signature.txt")
assert certificate == "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
assert "Verification successful" in read_log(DELIVERY / "full-alignment.txt")
build = read_log(DELIVERY / "build-full.log")
assert "BUILD SUCCESSFUL" in build
dex = re.search(r"(\d+) classes, (\d+) methods with code, (\d+) findings, (\d+) methods not analysable", build)
assert dex and tuple(map(int, dex.groups()[2:])) == (0, 0)
(DELIVERY / "full-dex.txt").write_text(dex[0] + "\n", encoding="utf-8")
apk_digest = digest(APK.read_bytes())
assert apk_digest == digest((ROOT / "composeApp/build/outputs/apk/release/composeApp-release.apk").read_bytes())
assert apk_digest == digest((ROOT / "composeApp/build/outputs/distribution" / APK.name).read_bytes())

properties = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text(encoding="utf-8-sig").splitlines() if line and not line.startswith("#"))
assert properties["VERSION_NAME"] == name and int(properties["VERSION_CODE"]) == code
assert (ROOT / "release-notes.txt").read_text(encoding="utf-8-sig").splitlines()[0] == name
assert (DELIVERY / "release-notes.txt").read_bytes() == (ROOT / "release-notes.txt").read_bytes()
approval = read_json(ROOT / ".github/mdk-distribution-approval.json")
assert approval["confirmed"] is True and (approval["versionName"], approval["versionCode"]) == (name, code)
assert approval["scope"] == "package-only"
assert digest((ROOT / "composeApp/libs/mdk-sdk-android.7z").read_bytes()) == approval["mdkArtifactSha256"]
assert approval["mdkArtifactSha256"] == "87aa236840134fe3b5c3b08e813c2b4f0557d4c2fc7034679417bc598dd785ba"
source = read_json(AUDIT / "source-inputs.json")
build_proof = read_json(DELIVERY / "build-verification.json")
assert build_proof["sourceCommit"] == source["sourceCommit"] and build_proof["treeDirty"] is True
assert build_proof["versionName"] == name and int(build_proof["versionCode"]) == code
assert build_proof["mdkIncluded"] is True
assert build_proof["artifacts"][0]["sha256"].lower() == apk_digest
with zipfile.ZipFile(DELIVERY / "changed-source.zip") as archived:
    for item in source["files"]:
        assert digest((ROOT / item["path"]).read_bytes()) == item["sha256"], item["path"]
        assert digest(archived.read(item["path"])) == item["sha256"], item["path"]

tests = read_json(DELIVERY / "splash-test-results.json")
assert len(tests) == 5 and sum(suite["tests"] for suite in tests) == 39
assert all(suite["failures"] == suite["errors"] == 0 for suite in tests)
assert "BUILD SUCCESSFUL" in read_log(DELIVERY / "splash-tests.log")

feature_proof = read_json(DELIVERY / "splash-feature-verification.json")
assert feature_proof["nativeRendering"]["frames"] == 180
assert feature_proof["referenceSamplesExact"] is True
for item in feature_proof["sourceFiles"]:
    assert digest((ROOT / item["path"]).read_bytes()) == item["sha256"], item["path"]

mdk = {}
native = {}
with zipfile.ZipFile(APK) as archive, zipfile.ZipFile(PREVIOUS) as old:
    dex_files = [entry for entry in archive.namelist() if re.fullmatch(r"classes\d*\.dex", entry)]
    assert dex_files and all(archive.getinfo(entry).file_size > 0 for entry in dex_files)
    assert {entry.split("/")[1] for entry in archive.namelist() if entry.startswith("lib/") and entry.endswith(".so")} == {"arm64-v8a"}
    for library in ("libmdk.so", "libyfuse-mdk-jni.so"):
        entry = f"lib/arm64-v8a/{library}"
        data = archive.read(entry)
        assert data and data == old.read(entry), entry
        mdk[entry] = {"bytes": len(data), "sha256": digest(data), "matchesPrevious": True}
    assert archive.read("lib/arm64-v8a/libmdk.so") == (ROOT / "composeApp/libs/mdk-sdk/lib/arm64-v8a/libmdk.so").read_bytes()
    for library in ("libmpv.so", "libycore_demux.so", "libycore_gpu.so", "libavcodec.so", "libavformat.so", "libavutil.so", "libswresample.so", "libswscale.so", "libc++_shared.so"):
        entry = f"lib/arm64-v8a/{library}"
        data = archive.read(entry)
        assert data and data == old.read(entry), entry
        native[entry] = digest(data)

result = {
    "versionName": name, "versionCode": code, "packageName": package, "profile": "full", "abi": "arm64-v8a",
    "sourceCommit": source["sourceCommit"], "treeDirtyAtBuild": True, "diffHash": build_proof["diffHash"], "sourceFilesArchived": len(source["files"]),
    "file": APK.name, "bytes": APK.stat().st_size, "sha256": apk_digest,
    "certificateSha256": certificate, "signatureV2Verified": True, "numberOfSigners": 1, "zipAlignment16K": True,
    "dexVerified": True, "dexClasses": int(dex[1]), "dexMethodsWithCode": int(dex[2]), "dexFindings": 0, "dexMethodsNotAnalysable": 0, "dexFiles": dex_files,
    "mdkIncluded": True, "mdkLibraries": mdk, "mdkEngineMatchesPinnedSdk": True, "mdkArtifactSha256": approval["mdkArtifactSha256"],
    "unchangedNativeLibraries": native, "previousActualVersionName": previous_name, "previousActualVersionCode": previous_code, "previousArtifactSha256": digest(PREVIOUS.read_bytes()),
    "releaseNotesFirstLine": name, "testEvidence": tests, "nativeRendererVerification": feature_proof["nativeRendering"],
    "sourceArchiveRecheckedAfterBuild": True, "deviceTestPerformed": False, "published": False, "uploaded": False,
    "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
}
for destination in (AUDIT, DELIVERY):
    (destination / "verification.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
(DELIVERY / "SHA256SUMS.txt").write_text(apk_digest + "  " + APK.name + "\n", encoding="utf-8")
print(json.dumps({key: result[key] for key in ("versionName", "versionCode", "packageName", "file", "bytes", "sha256", "mdkIncluded", "dexClasses", "dexMethodsWithCode")}, indent=2))
