"""Verify the actual local 1.3.9 signed full APK and retain delivery evidence."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = next(p for p in Path(__file__).resolve().parents if (p / "version.properties").is_file())
DELIVERY = ROOT / "artifacts-local/releases/left-back-1.3.9-291"
AUDIT = ROOT / "audit/releases/20261016-left-back-1.3.9"
TOOLS = Path("D:/AndroidSDK/build-tools/36.0.0")
NAME, CODE = "1.3.9", 291
APK = DELIVERY / f"Yfuse-{NAME}-full-arm64.apk"
PREVIOUS = ROOT / "artifacts-local/releases/predictive-back-1.3.8-290/Yfuse-1.3.8-full-arm64.apk"
CERT = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def load(path):
    return json.loads(read(path))


def run(output, args):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (DELIVERY / output).write_bytes(result.stdout)
    assert result.returncode == 0, f"Command failed; see {output}"
    return result.stdout.decode("utf-8", errors="replace")


def package(text):
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text)
    assert match, "No actual APK version metadata"
    return match[1], int(match[2]), match[3]


def certificate(text):
    assert "Verifies" in text and "Number of signers: 1" in text
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in text
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", text)
    assert match and match[1] == CERT, "Unexpected production certificate"
    return match[1]


def main():
    os.environ["JAVA_HOME"] = "C:/Users/app-inkbird/.jdks/corretto-17.0.15"
    os.environ.update(GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="safe.directory", GIT_CONFIG_VALUE_0="D:/Demo/Yfuse")
    distribution = ROOT / "composeApp/build/outputs/distribution"
    shutil.copy2(distribution / APK.name, APK)
    shutil.copy2(distribution / "verification.json", DELIVERY / "build-verification.json")
    proof = load(DELIVERY / "build-verification.json")
    build = read(DELIVERY / "build-full.log")
    assert "BUILD SUCCESSFUL" in build
    dex = re.search(r"(\d+) classes, (\d+) methods with code, (\d+) findings, (\d+) methods not analysable", build)
    assert dex and tuple(map(int, dex.groups()[2:])) == (0, 0), "DEX check not clean or incomplete"
    (DELIVERY / "full-dex.txt").write_text(dex[0] + "\n", encoding="utf-8")
    badging = run("full-badging.txt", [TOOLS / "aapt2.exe", "dump", "badging", APK])
    previous_badging = run("previous-badging.txt", [TOOLS / "aapt2.exe", "dump", "badging", PREVIOUS])
    assert package(badging) == ("com.yfuse", CODE, NAME)
    old_package, old_code, old_name = package(previous_badging)
    assert (old_package, old_code, old_name) == ("com.yfuse", 290, "1.3.8")
    assert CODE > old_code and tuple(map(int, NAME.split("."))) > tuple(map(int, old_name.split(".")))
    assert re.fullmatch(r"\d\.\d\.\d", NAME)
    assert "application-debuggable" not in badging
    assert "native-code: 'arm64-v8a'" in badging
    certificate(run("full-signature.txt", [TOOLS / "apksigner.bat", "verify", "--verbose", "--print-certs", APK]))
    certificate(run("previous-signature.txt", [TOOLS / "apksigner.bat", "verify", "--verbose", "--print-certs", PREVIOUS]))
    assert "Verification successful" in run("full-alignment.txt", [TOOLS / "zipalign.exe", "-c", "-P", "16", "-v", "4", APK])
    manifest = run("full-manifest.txt", [TOOLS / "aapt2.exe", "dump", "xmltree", APK, "--file", "AndroidManifest.xml"])
    assert re.search(r"enableOnBackInvokedCallback[^\n]*=(?:\(type 0x12\))?\s*(?:0xffffffff|true)", manifest), "Predictive back disabled in final APK"
    previous_record = load(ROOT / "audit/releases/20261016-predictive-back-1.3.8/verification.json")
    assert sha(PREVIOUS) == previous_record["sha256"]
    apk_sha = sha(APK)
    assert apk_sha == sha(distribution / APK.name) == sha(ROOT / "composeApp/build/outputs/apk/release/composeApp-release.apk")
    props = dict(line.split("=", 1) for line in read(ROOT / "version.properties").splitlines() if line and not line.startswith("#"))
    assert (props["VERSION_NAME"], int(props["VERSION_CODE"])) == (NAME, CODE)
    assert read(ROOT / "release-notes.txt").splitlines()[0] == NAME
    for name in ("version.properties", "release-notes.txt"):
        assert sha(DELIVERY / name) == sha(ROOT / name)
    source = load(DELIVERY / "source-inputs.json")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    assert proof["sourceCommit"] == source["sourceCommit"] == commit
    assert proof["treeDirty"] is source["treeDirty"] is True
    assert proof["mdkIncluded"] is True and re.fullmatch(r"[0-9a-f]{12}", proof["diffHash"])
    assert (proof["versionName"], int(proof["versionCode"])) == (NAME, CODE)
    assert len(proof["artifacts"]) == 1
    assert proof["artifacts"][0]["sha256"].lower() == apk_sha
    assert proof["artifacts"][0]["length"] == APK.stat().st_size
    current_diff = subprocess.check_output(["git", "diff", "--name-only", "HEAD"], cwd=ROOT, text=True).splitlines()
    assert set(current_diff) == {f["path"] for f in source["files"] if f["tracked"]}
    for entry in source["files"]:
        assert sha(ROOT / entry["path"]) == sha(DELIVERY / "build-inputs" / entry["path"]) == entry["sha256"]
    approval = load(ROOT / ".github/mdk-distribution-approval.json")
    assert approval["confirmed"] is True and approval["scope"] == "package-only"
    assert (approval["versionName"], approval["versionCode"]) == (NAME, CODE)
    assert approval["mdkArtifactSha256"] == sha(ROOT / "composeApp/libs/mdk-sdk-android.7z") == previous_record["mdkArtifactSha256"]
    for entry in load(DELIVERY / "native-inputs.json"):
        assert sha(ROOT / entry["file"]) == entry["sha256"]
    libraries = {}
    with zipfile.ZipFile(APK) as archive:
        assert archive.testzip() is None
        assert {p.split("/")[1] for p in archive.namelist() if p.startswith("lib/") and p.endswith(".so")} == {"arm64-v8a"}
        dex_files = {p: hashlib.sha256(archive.read(p)).hexdigest() for p in archive.namelist() if re.fullmatch(r"classes\d*\.dex", p)}
        assert dex_files and any(commit.encode() in archive.read(p) for p in dex_files)
        for lib in ("libmdk.so", "libyfuse-mdk-jni.so"):
            path = f"lib/arm64-v8a/{lib}"
            data = archive.read(path)
            assert data
            libraries[path] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        assert archive.read("lib/arm64-v8a/libmdk.so") == (ROOT / "composeApp/libs/mdk-sdk/lib/arm64-v8a/libmdk.so").read_bytes()
        assert b"Java_com_mediadevkit_sdk_MDKPlayer_nativePlaybackSnapshot" in archive.read("lib/arm64-v8a/libyfuse-mdk-jni.so")
    import xml.etree.ElementTree as ET
    validation_dir = DELIVERY / "navigation-validation"
    validation = load(validation_dir / "verification.json")
    assert validation["status"] == "passed"
    assert "BUILD SUCCESSFUL" in read(validation_dir / "unit-tests-final.log")
    assert "BUILD SUCCESSFUL" in read(validation_dir / "lint-final.log")
    assert "OK (7 tests)" in read(validation_dir / "device-tests.log")
    for tested_source in validation["sources"]:
        assert sha(ROOT / tested_source["path"]) == tested_source["sha256"], "Tested source changed before packaging"
    assert len(validation["sources"]) == 14
    xml_files = sorted((validation_dir / "unit-results").glob("TEST-*.xml"))
    assert xml_files, "Missing navigation regression reports"
    stats = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "suites": len(xml_files)}
    manifests = []
    with zipfile.ZipFile(DELIVERY / "test-results.zip", "w", zipfile.ZIP_DEFLATED) as archived:
        for path in xml_files:
            suite = ET.parse(path).getroot()
            for key in ("tests", "failures", "errors", "skipped"):
                stats[key] += int(suite.attrib.get(key, 0))
            manifests.append({"file": path.name, "sha256": sha(path), "timestamp": suite.attrib.get("timestamp")})
            archived.write(path, path.name)
    assert stats["tests"] == 75 and stats["failures"] == stats["errors"] == stats["skipped"] == 0
    (DELIVERY / "test-xml-manifest.json").write_text(json.dumps(manifests, indent=2) + "\n", encoding="utf-8")
    modules = {"phoneShared": stats}
    validated_sources = {entry["path"]: entry["sha256"] for entry in source["files"]}
    shutil.copy2(ROOT / "composeApp/build/outputs/mapping/release/mapping.txt", DELIVERY / "mapping.txt")
    result = {
        "versionName": NAME, "versionCode": CODE, "packageName": "com.yfuse",
        "profile": "full", "abi": "arm64-v8a", "sourceCommit": commit,
        "treeDirtyAtBuild": True, "diffHash": proof["diffHash"],
        "uncommittedInputs": [f["path"] for f in source["files"]], "buildInputsRecheckedAfterBuild": True,
        "file": APK.name, "bytes": APK.stat().st_size, "sha256": apk_sha,
        "certificateSha256": CERT, "signatureV2Verified": True, "numberOfSigners": 1,
        "zipAlignment16K": True, "dexVerified": True, "dexClasses": int(dex[1]),
        "dexMethodsWithCode": int(dex[2]), "dexFindings": 0, "dexMethodsNotAnalysable": 0,
        "dexFiles": dex_files, "mdkIncluded": True, "mdkLibraries": libraries,
        "mdkEngineMatchesPinnedSdk": True, "mdkPlaybackSnapshotJniIncluded": True,
        "mdkArtifactSha256": approval["mdkArtifactSha256"],
        "previousActualVersionName": old_name, "previousActualVersionCode": old_code,
        "previousArtifactSha256": sha(PREVIOUS), "releaseNotesFirstLine": NAME,
        "sourceRegression": {"reusedPriorResults": False, "scope": "Fresh navigation and handoff tests for uniform leftward exit; tested source hashes rechecked", "navigationSourceHashesRechecked": 14, "validatedSourceHashesRechecked": len(validated_sources), "modules": modules, "testTask": ":phoneShared:testAndroidHostTest", "testXmlManifestSha256": sha(DELIVERY / "test-xml-manifest.json")},
        "buildWarnings": ["No update-manifest public key is configured; the local build permits this and APK signature verification passed."],
        "sourceDeviceValidation": {"tests": 7, "failures": 0, "platform": validation["validation"]["device"], "android17DeviceTestPerformed": validation["validation"]["android17DeviceTestPerformed"], "fixture": "Production navigation host with colored routes; full-size opaque pixels, leftward exit from either edge, real edge input, nested return, cancellation, Home/resume"}, "deviceTestOfReleasePerformed": False, "serverDeployed": False, "published": False, "uploaded": False,
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
    }
    AUDIT.mkdir(parents=True, exist_ok=True)
    for dest in (AUDIT, DELIVERY):
        (dest / "verification.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (DELIVERY / "SHA256SUMS.txt").write_text(apk_sha + "  " + APK.name + "\n", encoding="utf-8")
    for dest in (AUDIT, DELIVERY):
        target = dest / "verify-package.py"
        if Path(__file__).resolve() != target.resolve():
            shutil.copy2(__file__, target)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
