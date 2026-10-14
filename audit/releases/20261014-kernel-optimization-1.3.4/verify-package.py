"""Validate this local full-MDK delivery against the actual preceding signed APK."""

import hashlib
import json
import re
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "version.properties").is_file())
AUDIT = Path(__file__).resolve().parent
DELIVERY = ROOT / "artifacts-local/releases/kernel-optimization-1.3.4-286"
APK = DELIVERY / "Yfuse-1.3.4-full-arm64.apk"
PREVIOUS = ROOT / "artifacts-local/releases/history-smooth-1.3.3-285/Yfuse-1.3.3-full-arm64.apk"
CERT = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
MDK = "87aa236840134fe3b5c3b08e813c2b4f0557d4c2fc7034679417bc598dd785ba"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def load(path):
    return json.loads(read(path))


def package(path):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", read(path))
    assert match, f"No APK metadata in {path}"
    return match[1], int(match[2]), match[3]


def certificate(path):
    content = read(path)
    assert "Verifies" in content and "Number of signers: 1" in content
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in content
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", content)
    assert match, f"No verified signing certificate in {path}"
    return match[1]


def main():
    previous_package, previous_code, previous_name = package(DELIVERY / "previous-badging.txt")
    assert (previous_package, previous_code, previous_name) == ("com.yfuse", 285, "1.3.3")
    previous_record = load(ROOT / "audit/releases/20261013-history-smooth-1.3.3/verification.json")
    assert sha(PREVIOUS.read_bytes()) == previous_record["sha256"]
    package_name, code, name = package(DELIVERY / "full-badging.txt")
    assert (package_name, code, name) == ("com.yfuse", 286, "1.3.4")
    assert code > previous_code and tuple(map(int, name.split("."))) > tuple(map(int, previous_name.split(".")))
    assert re.fullmatch(r"\d\.\d\.\d", name)
    assert "application-debuggable" not in read(DELIVERY / "full-badging.txt")
    assert "native-code: 'arm64-v8a'" in read(DELIVERY / "full-badging.txt")
    assert certificate(DELIVERY / "full-signature.txt") == certificate(DELIVERY / "previous-signature.txt") == CERT
    assert "Verification successful" in read(DELIVERY / "full-alignment.txt")
    build = read(DELIVERY / "build-full.log")
    assert "BUILD SUCCESSFUL" in build
    dex = re.search(r"(\d+) classes, (\d+) methods with code, (\d+) findings, (\d+) methods not analysable", build)
    assert dex and tuple(map(int, dex.groups()[2:])) == (0, 0), "DEX verification incomplete or failed"
    (DELIVERY / "full-dex.txt").write_text(dex[0] + "\n", encoding="utf-8")
    apk_sha = sha(APK.read_bytes())
    for built in (ROOT / "composeApp/build/outputs/apk/release/composeApp-release.apk",
                  ROOT / "composeApp/build/outputs/distribution" / APK.name):
        assert sha(built.read_bytes()) == apk_sha
    props = dict(line.split("=", 1) for line in read(ROOT / "version.properties").splitlines()
                 if line and not line.startswith("#"))
    assert props["VERSION_NAME"] == name and int(props["VERSION_CODE"]) == code
    assert read(ROOT / "release-notes.txt").splitlines()[0] == name
    assert (DELIVERY / "release-notes.txt").read_bytes() == (ROOT / "release-notes.txt").read_bytes()
    approval = load(ROOT / ".github/mdk-distribution-approval.json")
    assert approval["confirmed"] is True and approval["scope"] == "package-only"
    assert (approval["versionName"], approval["versionCode"]) == (name, code)
    assert approval["mdkArtifactSha256"] == MDK == sha((ROOT / "composeApp/libs/mdk-sdk-android.7z").read_bytes())
    source = load(DELIVERY / "source-inputs.json")
    proof = load(DELIVERY / "build-verification.json")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    assert proof["sourceCommit"] == source["sourceCommit"] == commit
    assert proof["treeDirty"] is source["treeDirty"] is True
    assert proof["mdkIncluded"] is True and re.fullmatch(r"[0-9a-f]{12}", proof["diffHash"])
    assert proof["versionName"] == name and int(proof["versionCode"]) == code
    assert len(proof["artifacts"]) == 1 and proof["artifacts"][0]["sha256"].lower() == apk_sha
    assert proof["artifacts"][0]["length"] == APK.stat().st_size
    with zipfile.ZipFile(DELIVERY / "changed-source.zip") as archived:
        for entry in source["files"]:
            if entry.get("deleted"):
                assert not (ROOT / entry["path"]).exists()
            else:
                assert sha((ROOT / entry["path"]).read_bytes()) == entry["sha256"]
                assert sha(archived.read(entry["path"])) == entry["sha256"]
    libraries = {}
    with zipfile.ZipFile(APK) as archive:
        assert archive.testzip() is None
        assert {p.split("/")[1] for p in archive.namelist() if p.startswith("lib/") and p.endswith(".so")} == {"arm64-v8a"}
        dex_files = {p: sha(archive.read(p)) for p in archive.namelist() if re.fullmatch(r"classes\d*\.dex", p)}
        assert dex_files and any(commit.encode() in archive.read(p) for p in dex_files)
        for lib in ("libmdk.so", "libyfuse-mdk-jni.so"):
            path = f"lib/arm64-v8a/{lib}"
            data = archive.read(path)
            assert data
            libraries[path] = {"bytes": len(data), "sha256": sha(data)}
        assert archive.read("lib/arm64-v8a/libmdk.so") == (ROOT / "composeApp/libs/mdk-sdk/lib/arm64-v8a/libmdk.so").read_bytes()
        assert b"Java_com_mediadevkit_sdk_MDKPlayer_nativePlaybackSnapshot" in archive.read("lib/arm64-v8a/libyfuse-mdk-jni.so")
    regression = load(ROOT / "audit/kernel-optimization-20261014/validation-summary.json")
    for module in regression["gradle"]["modules"].values():
        assert module["tests"] > 0 and module["failures"] == module["errors"] == module["skipped"] == 0
    result = {
        "versionName": name, "versionCode": code, "packageName": package_name,
        "profile": "full", "abi": "arm64-v8a", "sourceCommit": commit,
        "treeDirtyAtBuild": True, "diffHash": proof["diffHash"], "sourceArchiveRecheckedAfterBuild": True,
        "file": APK.name, "bytes": APK.stat().st_size, "sha256": apk_sha,
        "certificateSha256": CERT, "signatureV2Verified": True, "numberOfSigners": 1,
        "zipAlignment16K": True, "dexVerified": True, "dexClasses": int(dex[1]),
        "dexMethodsWithCode": int(dex[2]), "dexFindings": 0, "dexMethodsNotAnalysable": 0, "dexFiles": dex_files,
        "mdkIncluded": True, "mdkLibraries": libraries, "mdkEngineMatchesPinnedSdk": True,
        "mdkPlaybackSnapshotJniIncluded": True, "mdkArtifactSha256": MDK,
        "previousActualVersionName": previous_name, "previousActualVersionCode": previous_code,
        "previousArtifactSha256": previous_record["sha256"], "releaseNotesFirstLine": name,
        "sourceRegression": regression, "deviceTestOfReleasePerformed": False,
        "published": False, "uploaded": False, "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
    }
    for dest in (AUDIT, DELIVERY):
        (dest / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (DELIVERY / "SHA256SUMS.txt").write_text(apk_sha + "  " + APK.name + "\n", encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("versionName", "versionCode", "packageName", "bytes", "sha256", "mdkIncluded", "dexFindings")}, indent=2))


if __name__ == "__main__":
    main()
