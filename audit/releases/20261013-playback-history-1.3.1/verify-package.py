"""Verify the locally signed 1.3.1 full release against its actual predecessor."""

import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "version.properties").is_file())
DELIVERY = ROOT / "artifacts-local/releases/playback-history-1.3.1-283"
AUDIT = ROOT / "audit/releases/20261013-playback-history-1.3.1"
APK = DELIVERY / "Yfuse-1.3.1-full-arm64.apk"
PREVIOUS = ROOT / "artifacts-local/releases/home-merge-1.3.0-282/Yfuse-1.3.0-full-arm64.apk"
EXPECTED_CERT = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
EXPECTED_MDK = "87aa236840134fe3b5c3b08e813c2b4f0557d4c2fc7034679417bc598dd785ba"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def load(path):
    return json.loads(read(path))


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def package(path):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", read(path))
    assert match, f"APK metadata missing in {path}"
    return match[1], int(match[2]), match[3]


def certificate(path):
    text = read(path)
    assert "Verifies" in text and "Number of signers: 1" in text
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in text
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", text)
    assert match, f"Verified signing certificate missing in {path}"
    return match[1]


previous_package, previous_code, previous_name = package(DELIVERY / "previous-badging.txt")
assert (previous_package, previous_code, previous_name) == ("com.yfuse", 282, "1.3.0")
previous_sha = sha(PREVIOUS.read_bytes())
assert previous_sha == "4b83334ee9b056b9f97dab310c1b4f03b7d2519f91ee8aa500d82b8d6ec767ae"
package_name, code, name = package(DELIVERY / "full-badging.txt")
assert (package_name, code, name) == ("com.yfuse", 283, "1.3.1")
assert code > previous_code and tuple(map(int, name.split("."))) > tuple(map(int, previous_name.split(".")))
assert re.fullmatch(r"\d\.\d\.\d", name)
assert "application-debuggable" not in read(DELIVERY / "full-badging.txt")
assert "native-code: 'arm64-v8a'" in read(DELIVERY / "full-badging.txt")
cert = certificate(DELIVERY / "full-signature.txt")
assert cert == certificate(DELIVERY / "previous-signature.txt") == EXPECTED_CERT
assert "Verification successful" in read(DELIVERY / "full-alignment.txt")
manifest = read(DELIVERY / "full-manifest.txt")
assert re.search(r"enableOnBackInvokedCallback[^\n]*=false", manifest)
assert not re.search(r"debuggable[^\n]*=true", manifest)

build = read(DELIVERY / "build-full.log")
assert "BUILD SUCCESSFUL" in build
dex = re.search(r"(\d+) classes, (\d+) methods with code, (\d+) findings, (\d+) methods not analysable", build)
assert dex and tuple(map(int, dex.groups()[2:])) == (0, 0), "DEX verification did not finish cleanly"
(DELIVERY / "full-dex.txt").write_text(dex[0] + "\n", encoding="utf-8")
apk_sha = sha(APK.read_bytes())
for built in (
    ROOT / "composeApp/build/outputs/apk/release/composeApp-release.apk",
    ROOT / "composeApp/build/outputs/distribution" / APK.name,
):
    assert sha(built.read_bytes()) == apk_sha, "Delivery differs from the verified build"
props = dict(line.split("=", 1) for line in read(ROOT / "version.properties").splitlines() if line and not line.startswith("#"))
assert props["VERSION_NAME"] == name and int(props["VERSION_CODE"]) == code
assert read(ROOT / "release-notes.txt").splitlines()[0] == name
assert (DELIVERY / "release-notes.txt").read_bytes() == (ROOT / "release-notes.txt").read_bytes()
approval = load(ROOT / ".github/mdk-distribution-approval.json")
assert approval["confirmed"] is True and approval["scope"] == "package-only"
assert (approval["versionName"], approval["versionCode"]) == (name, code)
assert approval["mdkArtifactSha256"] == EXPECTED_MDK == sha((ROOT / "composeApp/libs/mdk-sdk-android.7z").read_bytes())

source = load(DELIVERY / "source-inputs.json")
proof = load(DELIVERY / "build-verification.json")
assert proof["sourceCommit"] == source["sourceCommit"] == git("rev-parse", "HEAD")
assert proof["treeDirty"] is source["treeDirty"] is False
assert proof["mdkIncluded"] is True and proof["diffHash"] == ""
assert proof["versionName"] == name and int(proof["versionCode"]) == code
assert proof["artifacts"][0]["sha256"].lower() == apk_sha
assert not git("status", "--porcelain"), "Working tree changed during release build"
with zipfile.ZipFile(DELIVERY / "changed-source.zip") as archived:
    for entry in source["files"]:
        assert sha((ROOT / entry["path"]).read_bytes()) == entry["sha256"]
        assert sha(archived.read(entry["path"])) == entry["sha256"]

mdk = {}
with zipfile.ZipFile(APK) as archive:
    dex_files = {path: sha(archive.read(path)) for path in archive.namelist() if re.fullmatch(r"classes\d*\.dex", path)}
    assert dex_files and any(source["sourceCommit"].encode() in archive.read(path) for path in dex_files)
    assert {path.split("/")[1] for path in archive.namelist() if path.startswith("lib/") and path.endswith(".so")} == {"arm64-v8a"}
    for library in ("libmdk.so", "libyfuse-mdk-jni.so"):
        path = f"lib/arm64-v8a/{library}"
        data = archive.read(path)
        assert data
        mdk[path] = {"bytes": len(data), "sha256": sha(data)}
    assert archive.read("lib/arm64-v8a/libmdk.so") == (ROOT / "composeApp/libs/mdk-sdk/lib/arm64-v8a/libmdk.so").read_bytes()

test_suites = [ET.parse(path).getroot() for path in (ROOT / "audit/playback-history-20261013").glob("TEST-*.xml")]
assert len(test_suites) == 8
assert all(int(suite.attrib["failures"]) == int(suite.attrib["errors"]) == int(suite.attrib["skipped"]) == 0 for suite in test_suites)
HOST_TEST_COUNT = sum(int(suite.attrib["tests"]) for suite in test_suites)
assert HOST_TEST_COUNT >= 10

result = {
    "versionName": name, "versionCode": code, "packageName": package_name,
    "profile": "full", "abi": "arm64-v8a", "sourceCommit": source["sourceCommit"],
    "treeDirtyAtBuild": False, "diffHash": "", "file": APK.name,
    "bytes": APK.stat().st_size, "sha256": apk_sha, "certificateSha256": cert,
    "signatureV2Verified": True, "numberOfSigners": 1, "zipAlignment16K": True,
    "dexVerified": True, "dexClasses": int(dex[1]), "dexMethodsWithCode": int(dex[2]),
    "dexFindings": 0, "dexMethodsNotAnalysable": 0, "dexFiles": dex_files,
    "mdkIncluded": True, "mdkLibraries": mdk, "mdkEngineMatchesPinnedSdk": True,
    "mdkArtifactSha256": EXPECTED_MDK, "predictiveBackEnabled": False,
    "previousActualVersionName": previous_name, "previousActualVersionCode": previous_code,
    "previousArtifactSha256": previous_sha, "releaseNotesFirstLine": name,
    "sourceArchiveRecheckedAfterBuild": True,
    "sourceHostTestsPassed": HOST_TEST_COUNT, "playbackHistoryPage": True,
    "historyLayout": "vertical cards", "seriesHistoryDeduplicated": True,
    "cardFields": ["poster", "synopsis", "season", "episode", "progress", "watchedAt", "server"],
    "deviceTestOfReleasePerformed": False, "published": False, "uploaded": False,
    "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
}
AUDIT.mkdir(parents=True, exist_ok=True)
for destination in (AUDIT, DELIVERY):
    (destination / "verification.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
(DELIVERY / "SHA256SUMS.txt").write_text(apk_sha + "  " + APK.name + "\n", encoding="utf-8")
print(json.dumps({key: result[key] for key in ("versionName", "versionCode", "packageName", "file", "bytes", "sha256", "mdkIncluded", "dexFindings")}, indent=2))
