"""Verify the signed 1.0.48 package and save evidence for this build only.

Run after the build and the caller's aapt, apksigner and zipalign checks finish.
This script uses only the Python standard library and never reads signing secrets.
"""

import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath


OUTPUT = Path(__file__).resolve().parent
ROOT = OUTPUT.parents[2]
APK_NAME = "Yfuse-1.0.48-signed-arm64.apk"
CERTIFICATE = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
TV_BASELINE = "805af51c81247e87de6e1a0ee10101b97c64e990"
SUITES = (
    "com.yfuse.feature.search.SearchResultsHandoffTest",
    "com.yfuse.app.RootTabMotionTest",
    "com.yfuse.core.designsystem.LoadingMotionTest",
    "com.yfuse.core.data.ThemePreferencesTest",
)
NATIVE_NAMES = {
    "libandroidx.graphics.path.so",
    "libass.so",
    "libavcodec.so",
    "libavdevice.so",
    "libavfilter.so",
    "libavformat.so",
    "libavutil.so",
    "libc++_shared.so",
    "libdav1d.so",
    "libffmpeg.so",
    "libimage_processing_util_jni.so",
    "libmdk.so",
    "libmpv.so",
    "libplayer.so",
    "libsurface_util_jni.so",
    "libswresample.so",
    "libswscale.so",
    "libycore_demux.so",
    "libycore_gpu.so",
    "libyfuse-mdk-jni.so",
}
YCORE_NAMES = {
    "libavcodec.so",
    "libavformat.so",
    "libavutil.so",
    "libc++_shared.so",
    "libswresample.so",
    "libswscale.so",
    "libycore_demux.so",
    "libycore_gpu.so",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_bytes(path):
    require(path.is_file(), f"Missing required file: {path}")
    return path.read_bytes()


def read_text(path):
    return read_bytes(path).decode("utf-8-sig")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verify_snapshot():
    manifest_path = OUTPUT / "source-hashes.json"
    manifest_bytes = read_bytes(manifest_path)
    entries = json.loads(manifest_bytes.decode("utf-8-sig"))
    require(isinstance(entries, list) and entries, "Source hash manifest must be a nonempty list")
    snapshot = OUTPUT / "source/composeApp/src"
    recorded = set()
    kotlin_count = 0
    for entry in entries:
        relative = PurePosixPath(entry["Path"].replace("\\", "/"))
        require(
            not relative.is_absolute() and ".." not in relative.parts,
            f"Source manifest path is not relative: {relative}",
        )
        require(str(relative) not in recorded, f"Duplicate source manifest path: {relative}")
        recorded.add(str(relative))
        path = snapshot.joinpath(*relative.parts)
        require(path.resolve().is_relative_to(snapshot.resolve()), f"Source path escapes snapshot: {relative}")
        require(sha256(read_bytes(path)) == entry["SHA256"].lower(), f"Frozen source hash changed: {relative}")
        kotlin_count += relative.suffix in (".kt", ".kts")
    actual = {path.relative_to(snapshot).as_posix() for path in snapshot.rglob("*") if path.is_file()}
    require(actual == recorded, "Frozen source file set differs from source-hashes.json")
    require(kotlin_count > 0, "Frozen snapshot contains no Kotlin files")
    required_sources = {
        "commonMain/kotlin/com/yfuse/feature/search/SearchResultsHandoff.kt",
        "commonMain/kotlin/com/yfuse/feature/search/SearchScreen.kt",
        "commonTest/kotlin/com/yfuse/feature/search/SearchResultsHandoffTest.kt",
    }
    require(required_sources.issubset(recorded), "Search handoff source or regression test is missing from snapshot")
    init_path = OUTPUT / "frozen-mobile-sources.init.gradle"
    init_text = read_text(init_path)
    require(
        "sourceSets.kotlin" in init_text or "sourceSet.kotlin.setSrcDirs" in init_text,
        "Frozen Kotlin source redirection is missing from the init script",
    )
    baseline = ROOT / "audit/diagnostics-20260909/fix/tv-baseline/composeApp/src/androidMain/kotlin"
    require(baseline.is_dir(), f"TV baseline directory is missing: {baseline}")
    require("tv-baseline/composeApp/src/androidMain/kotlin" in init_text, "TV baseline redirection is missing")
    return {
        "description": "Frozen Kotlin snapshot; live workspace edits after capture are excluded",
        "path": snapshot.relative_to(ROOT).as_posix(),
        "manifest": manifest_path.name,
        "manifestSha256": sha256(manifest_bytes),
        "verifiedFiles": len(recorded),
        "kotlinFiles": kotlin_count,
        "initScript": init_path.name,
        "initScriptSha256": sha256(read_bytes(init_path)),
        "tvBaselinePath": baseline.relative_to(ROOT).as_posix(),
        "scope": "Mobile Kotlin uses the frozen snapshot; TV UI uses the recorded baseline and TV UI tests are excluded",
    }


def main():
    apk = OUTPUT / APK_NAME
    build_log = read_text(OUTPUT / "build.log")
    outcomes = re.findall(r"^BUILD (SUCCESSFUL|FAILED)\b.*$", build_log, re.MULTILINE)
    require(outcomes and outcomes[-1] == "SUCCESSFUL", "build.log does not end with a successful Gradle build")
    for task in ("verifyDesignSystemUsage", "testReleaseUnitTest", "assembleRelease"):
        matches = re.findall(rf"^> Task :composeApp:{task}(?=\s|$)([^\r\n]*)", build_log, re.MULTILINE)
        require(matches, f"build.log does not contain :composeApp:{task}")
        require(not re.search(r"\b(FAILED|SKIPPED|NO-SOURCE)\b", matches[-1]), f"Required task did not pass: {task}")
    print("PASS: release build, design-system check and release unit-test task")

    badging = read_text(OUTPUT / "badging.txt")
    package_line = re.search(r"^package: (.+)$", badging, re.MULTILINE)
    require(package_line is not None, "badging.txt is missing package metadata")
    package_values = dict(re.findall(r"(\w+)='([^']*)'", package_line.group(1)))
    require(package_values.get("name") == "com.yfuse", "APK application ID is not com.yfuse")
    require(package_values.get("versionCode") == "210", "APK versionCode is not 210")
    require(package_values.get("versionName") == "1.0.48", "APK versionName is not 1.0.48")
    abi_line = re.search(r"^native-code:\s*(.+)$", badging, re.MULTILINE)
    require(abi_line is not None and re.findall(r"'([^']+)'", abi_line.group(1)) == ["arm64-v8a"], "APK ABI is not exclusively arm64-v8a")
    signature = read_text(OUTPUT / "signature.txt")
    require(re.search(r"^Verifies\s*$", signature, re.MULTILINE), "apksigner did not report Verifies")
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in signature, "APK v2 signature did not verify")
    require(re.search(r"^Number of signers:\s*1\s*$", signature, re.MULTILINE), "APK does not have exactly one signer")
    require(f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}" in signature, "APK is not signed with the existing production certificate")
    require("Verification successful" in read_text(OUTPUT / "alignment.txt"), "zipalign verification did not pass")
    apk_bytes = read_bytes(apk)
    require(0 < len(apk_bytes) < 30_000_000, f"APK must be nonempty and below 30 MB; actual size: {len(apk_bytes)} bytes")
    print("PASS: com.yfuse / 1.0.48 (210) / ARM64, production signature, ZIP alignment and size")

    native = []
    ycore = []
    aar = ROOT / "composeApp/libs/ycore-native.aar"
    require(aar.is_file(), f"Missing current YCore AAR: {aar}")
    with zipfile.ZipFile(apk) as package, zipfile.ZipFile(aar) as source:
        package_names = package.namelist()
        require(len(package_names) == len(set(package_names)), "APK has duplicate ZIP entries")
        packaged_native = {name for name in package_names if name.startswith("lib/") and name.endswith(".so")}
        expected_native = {f"lib/arm64-v8a/{name}" for name in NATIVE_NAMES}
        require(
            packaged_native == expected_native,
            f"APK must contain the expected 20 ARM64 libraries; missing={sorted(expected_native - packaged_native)}, extra={sorted(packaged_native - expected_native)}",
        )
        require(package.testzip() is None, "APK contains a ZIP entry with an invalid CRC")
        for name in sorted(packaged_native):
            data = package.read(name)
            native.append({"path": name, "bytes": len(data), "sha256": sha256(data)})
        source_names = source.namelist()
        require(len(source_names) == len(set(source_names)), "YCore AAR has duplicate ZIP entries")
        aar_native = {name for name in source_names if name.startswith("jni/") and name.endswith(".so")}
        expected_ycore = {f"jni/arm64-v8a/{name}" for name in YCORE_NAMES}
        require(aar_native == expected_ycore, "Current YCore AAR does not contain exactly the expected 8 ARM64 libraries")
        for name in sorted(aar_native):
            original = source.read(name)
            target = "lib/" + name.removeprefix("jni/")
            require(package.read(target) == original, f"Packaged native library differs from current AAR: {name}")
            ycore.append({"sourcePath": name, "apkPath": target, "sha256": sha256(original), "byteIdentical": True})
    print("PASS: 20 expected native libraries; 8 YCore libraries match the current AAR byte for byte")

    tests = []
    test_xmls = []
    for name in SUITES:
        path = ROOT / f"composeApp/build/test-results/testReleaseUnitTest/TEST-{name}.xml"
        data = read_bytes(path)
        suite = ET.fromstring(data)
        require(suite.tag == "testsuite" and suite.attrib.get("name") == name, f"Unexpected suite identity in {path.name}")
        count = int(suite.attrib.get("tests", "0"))
        require(count > 0, f"No tests executed for {name}")
        require(all(int(suite.attrib.get(key, "0")) == 0 for key in ("failures", "errors", "skipped")), f"Suite has failed, errored or skipped tests: {name}")
        require(not any(suite.findall(f".//{tag}") for tag in ("failure", "error", "skipped")), f"Suite contains a failed, errored or skipped testcase: {name}")
        require(len(suite.findall("testcase")) == count, f"Testcase count does not match the summary for {name}")
        tests.append({"name": name, "tests": count, "failures": 0, "errors": 0, "skipped": 0, "xml": path.name, "sha256": sha256(data)})
        test_xmls.append((path.name, data))
    print(f"PASS: {len(tests)} release unit-test suites, {sum(item['tests'] for item in tests)} tests, no failures/errors/skips")

    snapshot = verify_snapshot()
    print(f"PASS: frozen source manifest matches {snapshot['verifiedFiles']} files ({snapshot['kotlinFiles']} Kotlin files)")
    digest = sha256(apk_bytes)
    report = {
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
        "apk": apk.name,
        "applicationId": "com.yfuse",
        "versionName": "1.0.48",
        "versionCode": 210,
        "packageProfile": "full",
        "bytes": len(apk_bytes),
        "sha256": digest,
        "certificateSha256": CERTIFICATE,
        "signatureSchemeV2": "verified",
        "abi": "arm64-v8a",
        "alignment": "zipalign verification passed; see alignment.txt for the caller's check",
        "nativeLibraryCount": len(native),
        "nativeLibraries": native,
        "ycoreSource": "composeApp/libs/ycore-native.aar",
        "ycoreSourceSha256": sha256(read_bytes(aar)),
        "ycoreByteIdentityChecks": ycore,
        "unitTestTask": ":composeApp:testReleaseUnitTest",
        "unitTests": tests,
        "unitTestTotal": sum(item["tests"] for item in tests),
        "deviceTests": 0,
        "deviceTestStatus": "Not run for this build; earlier device tests are not counted as this build's results",
        "visualChecks": "No device or screenshot verification performed for this build",
        "sourceSnapshot": snapshot,
        "tvBaseline": TV_BASELINE,
        "versionSource": "Gradle overrides yfuseVersionName=1.0.48 and yfuseVersionCode=210",
        "testScope": "Search handoff ordering, root tab motion, loading motion and theme preferences from the frozen Kotlin snapshot",
        "evidence": {
            name: {"path": name, "sha256": sha256(read_bytes(OUTPUT / name))}
            for name in ("build.log", "badging.txt", "signature.txt", "alignment.txt", "build-release.ps1", "frozen-mobile-sources.init.gradle")
        },
    }
    # Publish evidence only after every required check passes.
    for filename, data in test_xmls:
        (OUTPUT / filename).write_bytes(data)
    (OUTPUT / f"{apk.name}.sha256").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    (OUTPUT / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("apk", "versionName", "versionCode", "bytes", "sha256", "unitTestTotal", "deviceTestStatus")}, ensure_ascii=False, indent=2))
    print(f"PASS: saved verification.json, APK SHA-256 sidecar and {len(test_xmls)} unit-test XML reports in {OUTPUT}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
