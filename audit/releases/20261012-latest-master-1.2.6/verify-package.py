"""Capture inputs and verify the local MDK-inclusive signed delivery."""

import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
AUDIT = Path(__file__).resolve().parent
DELIVERY = ROOT / "artifacts-local/releases/latest-master-1.2.6-278"
APK = DELIVERY / "Yfuse-1.2.6-full-arm64.apk"
PREVIOUS = ROOT / "artifacts-local/releases/github-1.2.3-275-baseline/Yfuse-275-1.2.3.apk"
EXPECTED_CERT = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
INPUTS = ("version.properties", "release-notes.txt", ".github/mdk-distribution-approval.json")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def read(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def load(path):
    return json.loads(read(path))


def package(path):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", read(path))
    assert match, f"APK metadata missing from {path}"
    return match[1], int(match[2]), match[3]


def certificate(path):
    text = read(path)
    assert "Verifies" in text and "Number of signers: 1" in text
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in text
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", text)
    assert match
    return match[1]


if sys.argv[1:] == ["capture"]:
    files = []
    with zipfile.ZipFile(DELIVERY / "changed-source.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for path in INPUTS:
            data = (ROOT / path).read_bytes()
            files.append({"path": path, "bytes": len(data), "sha256": sha(data)})
            archive.writestr(path, data)
    source = {"sourceCommit": git("rev-parse", "HEAD").decode().strip(), "treeDirty": True, "files": files}
    (DELIVERY / "source-inputs.json").write_text(json.dumps(source, indent=2) + "\n", encoding="utf-8")
    (DELIVERY / "source.patch").write_bytes(git("diff", "HEAD", "--binary", "--no-ext-diff"))
    print(f"Captured {len(files)} changed build inputs at {source['sourceCommit']}")
    sys.exit(0)

assert not sys.argv[1:]
previous_package, previous_code, previous_name = package(DELIVERY / "previous-badging.txt")
assert (previous_package, previous_code, previous_name) == ("com.yfuse", 275, "1.2.3")
assert sha(PREVIOUS.read_bytes()) == "5d7bc46b93793170ef9a4bfb0c2c48825387b33c57f952e95ad2ce440ac02539"
package_name, code, name = package(DELIVERY / "full-badging.txt")
assert (package_name, code, name) == ("com.yfuse", 278, "1.2.6")
assert code > previous_code and code > 277
assert tuple(map(int, name.split("."))) > (1, 2, 5)
assert re.fullmatch(r"\d\.\d\.\d", name)
assert "application-debuggable" not in read(DELIVERY / "full-badging.txt")
assert "native-code: 'arm64-v8a'" in read(DELIVERY / "full-badging.txt")
cert = certificate(DELIVERY / "full-signature.txt")
assert cert == certificate(DELIVERY / "previous-signature.txt") == EXPECTED_CERT
assert "Verification successful" in read(DELIVERY / "full-alignment.txt")
build = read(DELIVERY / "build-full.log")
assert "BUILD SUCCESSFUL" in build
dex = re.search(r"(\d+) classes, (\d+) methods with code, (\d+) findings, (\d+) methods not analysable", build)
assert dex and tuple(map(int, dex.groups()[2:])) == (0, 0)
(DELIVERY / "full-dex.txt").write_text(dex[0] + "\n", encoding="utf-8")
apk_sha = sha(APK.read_bytes())
assert apk_sha == sha((ROOT / "composeApp/build/outputs/apk/release/composeApp-release.apk").read_bytes())
assert apk_sha == sha((ROOT / "composeApp/build/outputs/distribution" / APK.name).read_bytes())
props = dict(line.split("=", 1) for line in read(ROOT / "version.properties").splitlines() if line and not line.startswith("#"))
assert props["VERSION_NAME"] == name and int(props["VERSION_CODE"]) == code
assert read(ROOT / "release-notes.txt").splitlines()[0] == name
assert (DELIVERY / "release-notes.txt").read_bytes() == (ROOT / "release-notes.txt").read_bytes()
approval = load(ROOT / ".github/mdk-distribution-approval.json")
assert approval["confirmed"] is True and approval["scope"] == "package-only"
assert (approval["versionName"], approval["versionCode"]) == (name, code)
assert approval["mdkArtifactSha256"] == sha((ROOT / "composeApp/libs/mdk-sdk-android.7z").read_bytes())
assert approval["mdkArtifactSha256"] == "87aa236840134fe3b5c3b08e813c2b4f0557d4c2fc7034679417bc598dd785ba"
source = load(DELIVERY / "source-inputs.json")
proof = load(DELIVERY / "build-verification.json")
assert proof["sourceCommit"] == source["sourceCommit"] == git("rev-parse", "HEAD").decode().strip()
assert proof["treeDirty"] is True and proof["mdkIncluded"] is True
assert proof["versionName"] == name and int(proof["versionCode"]) == code
assert proof["artifacts"][0]["sha256"].lower() == apk_sha
with zipfile.ZipFile(DELIVERY / "changed-source.zip") as archived:
    for entry in source["files"]:
        assert sha((ROOT / entry["path"]).read_bytes()) == entry["sha256"]
        assert sha(archived.read(entry["path"])) == entry["sha256"]
mdk = {}
with zipfile.ZipFile(APK) as archive:
    dex_files = {path: sha(archive.read(path)) for path in archive.namelist() if re.fullmatch(r"classes\d*\.dex", path)}
    assert dex_files
    assert {path.split("/")[1] for path in archive.namelist() if path.startswith("lib/") and path.endswith(".so")} == {"arm64-v8a"}
    for library in ("libmdk.so", "libyfuse-mdk-jni.so"):
        path = f"lib/arm64-v8a/{library}"
        data = archive.read(path)
        assert data
        mdk[path] = {"bytes": len(data), "sha256": sha(data)}
    assert archive.read("lib/arm64-v8a/libmdk.so") == (ROOT / "composeApp/libs/mdk-sdk/lib/arm64-v8a/libmdk.so").read_bytes()
result = {
    "versionName": name, "versionCode": code, "packageName": package_name,
    "profile": "full", "abi": "arm64-v8a", "sourceCommit": source["sourceCommit"],
    "treeDirtyAtBuild": True, "diffHash": proof["diffHash"], "file": APK.name,
    "bytes": APK.stat().st_size, "sha256": apk_sha, "certificateSha256": cert,
    "signatureV2Verified": True, "numberOfSigners": 1, "zipAlignment16K": True,
    "dexVerified": True, "dexClasses": int(dex[1]), "dexMethodsWithCode": int(dex[2]),
    "dexFindings": 0, "dexMethodsNotAnalysable": 0, "dexFiles": dex_files,
    "mdkIncluded": True, "mdkLibraries": mdk, "mdkEngineMatchesPinnedSdk": True,
    "mdkArtifactSha256": approval["mdkArtifactSha256"],
    "previousActualVersionName": previous_name, "previousActualVersionCode": previous_code,
    "previousArtifactSha256": sha(PREVIOUS.read_bytes()),
    "previousConfiguredVersionName": "1.2.5", "previousConfiguredVersionCode": 277,
    "releaseNotesFirstLine": name, "sourceArchiveRecheckedAfterBuild": True,
    "deviceTestPerformed": False, "published": False, "uploaded": False,
    "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
}
for destination in (AUDIT, DELIVERY):
    (destination / "verification.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
(DELIVERY / "SHA256SUMS.txt").write_text(apk_sha + "  " + APK.name + "\n", encoding="utf-8")
print(json.dumps({key: result[key] for key in ("versionName", "versionCode", "packageName", "file", "bytes", "sha256", "mdkIncluded", "dexFindings")}, indent=2))
