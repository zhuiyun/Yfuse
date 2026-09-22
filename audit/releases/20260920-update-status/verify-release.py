"""Verify the delivered Release APK without reading signing credentials.

Run after copying the signed APK, version.properties, and release-notes.txt into
the delivery directory. The audit directory must contain build.log,
source-hashes.json (repository-relative path -> SHA-256), and test-summary.json.
Only verification output is written; this script does not build, sign, install,
publish, or update version metadata.
"""

import argparse
import hashlib
import json
import re
import struct
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parents[2]
TOOLS = Path("D:/AndroidSDK/build-tools/36.0.0")
JAVA = Path("C:/Users/app-inkbird/.jdks/ms-21.0.9/bin/java.exe")
CERT = "373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84"
MDK_BUILD = ROOT / ".gradle-tmp/kernel-fixes-build/mdkAndroid/build"
YCORE_AAR = ROOT / "composeApp/libs/ycore-native.aar"
YCORE_RESULT = ROOT / "audit/kernel-fixes-20260920/native/result.json"
PAGE = 16 * 1024


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def relative(path):
    return path.resolve().relative_to(ROOT).as_posix()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def run(arguments, destination):
    result = subprocess.run([str(value) for value in arguments], capture_output=True)
    output = result.stdout.decode("utf-8", errors="replace")
    if result.stderr:
        output += "\n" + result.stderr.decode("utf-8", errors="replace")
    destination.write_text(output, encoding="utf-8")
    require(result.returncode == 0, f"Verification tool failed; see {destination}")
    return output


def metadata(apk, destination):
    badging = run([TOOLS / "aapt.exe", "dump", "badging", apk], destination)
    package = re.search(r"^package: (.+)$", badging, re.M)
    require(package is not None, "APK package metadata missing")
    return dict(re.findall(r"(\w+)='([^']*)'", package.group(1))), badging


def signature(apk, destination):
    output = run([JAVA, "-jar", TOOLS / "lib/apksigner.jar", "verify",
                  "--verbose", "--print-certs", apk], destination)
    require("Verified using v2 scheme (APK Signature Scheme v2): true" in output,
            "APK v2 signature not verified")
    require(re.search(r"^Number of signers: 1\s*$", output, re.M), "Unexpected signer count")
    require(f"Signer #1 certificate SHA-256 digest: {CERT}" in output,
            "Unexpected production signing certificate")


def elf_alignment(content, name):
    """Check every PT_LOAD, including bounds and offset/address congruence."""
    require(len(content) >= 64 and content[:4] == b"\x7fELF", f"Invalid ELF header: {name}")
    require(content[4] == 2, f"Expected ELF64 native library: {name}")
    require(content[5] in (1, 2), f"Unsupported ELF byte order: {name}")
    require(content[6] == 1, f"Unsupported ELF identification version: {name}")
    endian = "<" if content[5] == 1 else ">"
    header = struct.unpack_from(endian + "HHIQQQIHHHHHH", content, 16)
    _, machine, version, _, phoff, _, _, ehsize, phentsize, phnum, _, _, _ = header
    require(machine == 183 and version == 1, f"Expected AArch64 ELF version 1: {name}")
    require(ehsize == 64 and phentsize == 56, f"Invalid ELF64 header sizes: {name}")
    require(0 < phnum < 0xffff, f"Missing or unsupported extended ELF program headers: {name}")
    require(phoff >= ehsize and phoff + phnum * phentsize <= len(content),
            f"ELF program header table is out of bounds: {name}")
    segments = []
    for index in range(phnum):
        kind, _, offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            endian + "IIQQQQQQ", content, phoff + index * phentsize)
        if kind != 1:
            continue
        require(offset <= len(content) and file_size <= len(content) - offset,
                f"ELF PT_LOAD {index} file range is out of bounds: {name}")
        require(memory_size >= file_size, f"ELF PT_LOAD {index} memory size is invalid: {name}")
        require(alignment >= PAGE and alignment & (alignment - 1) == 0,
                f"ELF PT_LOAD {index} is not 16 KiB compatible (p_align={alignment}): {name}")
        require((address - offset) % alignment == 0,
                f"ELF PT_LOAD {index} address/offset alignment differs: {name}")
        segments.append({"index": index, "offset": offset, "virtualAddress": address,
                         "fileBytes": file_size, "memoryBytes": memory_size,
                         "alignmentBytes": alignment})
    require(segments, f"ELF has no PT_LOAD segments: {name}")
    return {"class": 64, "machine": "AArch64",
            "minimumLoadAlignmentBytes": min(segment["alignmentBytes"] for segment in segments),
            "loadSegments": segments, "alignment16KiBVerified": True}


def zip_library_alignment(apk_file, info):
    """Compressed libraries are extracted; their ZIP offset need not align."""
    apk_file.seek(info.header_offset)
    header = apk_file.read(30)
    require(len(header) == 30 and header[:4] == b"PK\x03\x04",
            f"Invalid ZIP local header: {info.filename}")
    name_bytes, extra_bytes = struct.unpack_from("<HH", header, 26)
    offset = info.header_offset + 30 + name_bytes + extra_bytes
    uncompressed = info.compress_type == zipfile.ZIP_STORED
    if uncompressed:
        require(offset % PAGE == 0, f"Stored native library ZIP offset not 16 KiB aligned: {info.filename}")
    return {"compressionMethod": info.compress_type, "dataOffset": offset,
            "alignment16KiBApplicable": uncompressed,
            "alignment16KiBVerified": True if uncompressed else None,
            "loading": "direct from ZIP" if uncompressed else "extracted during installation"}


def mdk_release_identity(content, explicit_source):
    if explicit_source:
        candidates = [explicit_source.resolve()]
    else:
        candidates = sorted(MDK_BUILD.rglob("libyfuse-mdk-jni.so"))
        candidates = [path for path in candidates
                      if "release" in [part.lower() for part in path.relative_to(MDK_BUILD).parts]]
    require(candidates, "No MDK Release JNI output found; specify --mdk-jni-source with a Release output")
    records = []
    for path in candidates:
        require(path.is_file() and path.is_relative_to(MDK_BUILD.resolve()),
                f"MDK JNI must be a current isolated module build output: {path}")
        parts = [part.lower() for part in path.relative_to(MDK_BUILD).parts]
        require("release" in parts and "debug" not in parts,
                f"MDK JNI source is not explicitly a Release variant output: {path}")
        source = path.read_bytes()
        records.append({"path": relative(path), "sha256": digest(source),
                        "byteIdenticalToApk": source == content})
    matches = [entry for entry in records if entry["byteIdenticalToApk"]]
    require(matches, "APK MDK JNI differs from every checked Release output (Debug is not accepted)")
    return {"releaseOutput": matches[0]["path"], "sha256": digest(content),
            "byteIdentical": True, "checkedReleaseOutputs": records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=ROOT / (
        "artifacts/releases/update-status-1.0.76-238/Yfuse-1.0.76-238-full-arm64-signed.apk"))
    parser.add_argument("--previous-apk", type=Path, default=ROOT / (
        "artifacts/releases/update-check-1.0.75-237/Yfuse-1.0.75-237-full-arm64-signed.apk"))
    parser.add_argument("--mdk-jni-source", type=Path,
                        help="Exact isolated mdkAndroid Release JNI output; never use Debug")
    args = parser.parse_args()
    apk, previous_apk = args.apk.resolve(), args.previous_apk.resolve()
    out = apk.parent
    require(apk.is_file() and previous_apk.is_file(), "Expected current and previous APKs")
    require(apk != previous_apk, "Current APK must differ from its predecessor")

    log = (AUDIT / "build.log").read_text(encoding="utf-8-sig")
    require(re.findall(r"^BUILD (SUCCESSFUL|FAILED)\b", log, re.M)[-1:] == ["SUCCESSFUL"],
            "Release build did not succeed")
    for task in ("verifyDesignSystemUsage", "assembleRelease", "verifyReleaseSigning",
                 "verifyReleaseMetadata", "verifyReleasePlaybackRuntime", "verifyProductionMdkRights",
                 "verifyProductionYCoreGpu", "verifyCustomMpvArtifact", "verifyStandaloneYCoreArtifact",
                 "verifyMdkArtifact"):
        require(re.search(rf"^> Task :composeApp:{task}(?: UP-TO-DATE| FROM-CACHE)?\s*$", log, re.M),
                f"Required successful task missing: {task}")
    require(re.search(r"^> Task :mdkAndroid:buildCMakeRelWithDebInfo\[arm64-v8a\]"
                      r"(?: UP-TO-DATE| FROM-CACHE)?\s*$", log, re.M),
            "This build log does not establish an MDK Release native build")

    previous, _ = metadata(previous_apk, out / "previous-badging.txt")
    signature(previous_apk, out / "previous-signature.txt")
    require(previous.get("versionName") == "1.0.75" and previous.get("versionCode") == "237",
            "Expected verified latest delivered baseline 1.0.75 (237)")
    current, badging = metadata(apk, out / "badging.txt")
    signature(apk, out / "signature.txt")
    properties = dict(re.findall(r"^(VERSION_NAME|VERSION_CODE)=(.+)$",
                                (ROOT / "version.properties").read_text(encoding="utf-8-sig"), re.M))
    properties = {key: value.strip() for key, value in properties.items()}
    require(current.get("name") == previous.get("name") == "com.yfuse", "Application ID mismatch")
    require(current.get("versionName") == properties.get("VERSION_NAME") == "1.0.76", "Version name mismatch")
    require(current.get("versionCode") == properties.get("VERSION_CODE") == "238", "Version code mismatch")
    require(tuple(map(int, current["versionName"].split("."))) >
            tuple(map(int, previous["versionName"].split("."))), "Version name must increase over previous APK")
    require(int(current["versionCode"]) > int(previous["versionCode"]), "Version code must increase over previous APK")
    require((ROOT / "release-notes.txt").read_text(encoding="utf-8-sig").splitlines()[0] == current["versionName"],
            "Release notes first line must match APK version")
    for name in ("version.properties", "release-notes.txt"):
        require((ROOT / name).read_bytes() == (out / name).read_bytes(), f"Delivery metadata differs: {name}")
    require(current.get("compileSdkVersion") == "37", "Expected API 37 compilation")
    require("sdkVersion:'26'" in badging and "targetSdkVersion:'37'" in badging, "Unexpected SDK metadata")
    require(re.search(r"^native-code: 'arm64-v8a'\s*$", badging, re.M), "Expected ARM64-only APK")
    require("application-debuggable" not in badging, "Production APK must not be debuggable")

    manifest = run([TOOLS / "aapt.exe", "dump", "xmltree", apk, "AndroidManifest.xml"], out / "manifest.txt")
    require("com.yfuse.shell.PhoneApplication" in manifest, "AGP 9 application shell missing")
    require(not re.search(r"android:debuggable[^\n]*\(type 0x12\)0xffffffff", manifest), "Manifest is debuggable")
    resources = run([TOOLS / "aapt2.exe", "dump", "resources", apk], out / "resources.txt")
    policy = re.search(r"resource\s+(0x[0-9a-f]+)\s+xml/network_security_config\s*\n"
                       r"\s*\(\)\s+\(file\)\s+(\S+)", resources)
    require(policy is not None, "Packaged network security configuration missing")
    require(re.search(r"android:networkSecurityConfig[^\n]*=@" + re.escape(policy.group(1)) + r"\b", manifest),
            "Manifest does not bind the checked network security configuration")
    network = run([TOOLS / "aapt2.exe", "dump", "xmltree", "--file", policy.group(2), apk], out / "network-policy.txt")
    require(re.search(r"E: base-config[^\n]*\n\s*A: cleartextTrafficPermitted=true\b", network),
            "User-configured media HTTP compatibility missing from base policy")
    require("47.112.219.60" not in network, "Shared media/account IP blocked by host-level policy")
    require(re.search(r"E: domain-config[^\n]*\n\s*A: cleartextTrafficPermitted=false\b", network),
            "Official provider HTTPS policy missing")
    require(all(host in network for host in ("themoviedb.org", "tmdb.org", "trakt.tv", "plex.tv")),
            "Official provider network policy changed")
    alignment = run([TOOLS / "zipalign.exe", "-c", "-P", "16", "-v", "4", apk], out / "alignment.txt")
    require("Verification successful" in alignment, "APK ZIP alignment failed")

    source_hashes = read_json(AUDIT / "source-hashes.json")
    require(isinstance(source_hashes, dict) and source_hashes, "Source manifest must be a nonempty path/hash map")
    mdk_source = ROOT / "mdkAndroid/src/main/cpp/MDKPlayerJNI.cpp"
    require(source_hashes.get(relative(mdk_source)) == digest(mdk_source.read_bytes()),
            "Current MDK JNI source differs from the release source manifest")
    ycore_result = read_json(YCORE_RESULT)
    require(digest(YCORE_AAR.read_bytes()) == ycore_result["aar_sha256"], "YCore AAR differs from audited rebuild")
    for name, sha256 in ycore_result["compiled_source_sha256"].items():
        require(Path(name).name == name, "Unexpected path in native source manifest")
        require(digest((ROOT / "scripts/native" / name).read_bytes()) == sha256,
                f"YCore native source changed after audited rebuild: {name}")

    native, ycore, differences = [], [], []
    with zipfile.ZipFile(apk) as package, zipfile.ZipFile(previous_apk) as baseline, \
            zipfile.ZipFile(YCORE_AAR) as source, apk.open("rb") as apk_file:
        names = package.namelist()
        require(len(names) == len(set(names)), "Duplicate APK ZIP entries")
        require(package.testzip() is None, "APK CRC failure")
        dex = b"".join(package.read(name) for name in names if re.fullmatch(r"classes\d*\.dex", name))
        fps_markers = ("player.showFrameRate", "显示帧率", "实时输出", "片源", "页面 FPS")
        require(dex and all(marker.encode("utf-8") in dex for marker in fps_markers),
                "Packaged DEX is missing FPS preference/readout markers; reject pre-FPS APK")
        old_dex = b"".join(baseline.read(name) for name in baseline.namelist()
                           if re.fullmatch(r"classes\d*\.dex", name))
        require("更新源版本落后于当前安装版本，请稍后重试".encode("utf-8") in old_dex, "Previous APK does not reproduce the older-feed error")
        require(b"RejectedNoKey" not in dex, "Release still contains the faulty missing-key rejection")
        require("更新源版本落后于当前安装版本，请稍后重试".encode("utf-8") not in dex, "Obsolete older-feed error remains in APK")
        require("暂无可用更新".encode("utf-8") in dex, "Successful check label missing from APK")
        require(b"manifest_accepted_without_key" in dex, "Optional-key update path missing from Release")
        require(baseline.testzip() is None, "Previous APK CRC failure")
        require(source.testzip() is None, "YCore AAR CRC failure")
        libraries = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        baseline_libraries = {name for name in baseline.namelist() if name.startswith("lib/") and name.endswith(".so")}
        required = {"libmpv.so", "libmdk.so", "libyfuse-mdk-jni.so", "libycore_demux.so", "libycore_gpu.so"}
        require(required <= {Path(name).name for name in libraries}, "Full MPV/MDK/YCore runtime missing")
        require(all(name.startswith("lib/arm64-v8a/") for name in libraries), "Unexpected native ABI")
        for name in sorted(libraries):
            content = package.read(name)
            native.append({"path": name, "bytes": len(content), "sha256": digest(content),
                           "elf": elf_alignment(content, name),
                           "zip": zip_library_alignment(apk_file, package.getinfo(name))})
        for name in sorted(libraries | baseline_libraries):
            old_sha = digest(baseline.read(name)) if name in baseline_libraries else None
            new_sha = digest(package.read(name)) if name in libraries else None
            differences.append({"path": name, "previousSha256": old_sha, "currentSha256": new_sha,
                                "status": "added" if old_sha is None else "removed" if new_sha is None
                                else "unchanged" if old_sha == new_sha else "changed"})
        entries = [name for name in source.namelist() if name.startswith("jni/") and name.endswith(".so")]
        require(len(entries) == len(set(entries)) == 8, "Unexpected YCore native source library set")
        for name in sorted(entries):
            require(name.startswith("jni/arm64-v8a/"), f"Unexpected YCore ABI: {name}")
            content = source.read(name)
            destination = "lib/" + name.removeprefix("jni/")
            require(package.read(destination) == content, f"YCore source differs from APK: {name}")
            ycore.append({"source": name, "packagedPath": destination,
                          "sha256": digest(content), "byteIdentical": True})
        demux = package.read("lib/arm64-v8a/libycore_demux.so")
        require(digest(demux) == ycore_result["demux_sha256"], "Packaged demux differs from audited native rebuild")
        jni_methods = ("nativeDemuxReadControlApiVersion", "nativeInterruptDemuxRead", "nativeResumeDemuxRead")
        for method in jni_methods:
            require(method.encode("ascii") + b"\0" in demux, f"Demux read-control JNI missing: {method}")
        mdk = mdk_release_identity(package.read("lib/arm64-v8a/libyfuse-mdk-jni.so"), args.mdk_jni_source)
        for asset in ("Anime4K_Upscale_Original_x2.glsl", "LICENSE"):
            require(package.read("assets/anime4k/" + asset) ==
                    (ROOT / "composeApp/src/androidMain/assets/anime4k" / asset).read_bytes(),
                    f"Anime4K asset missing or differs from source: {asset}")

    data = apk.read_bytes()
    report = {
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
        "apk": apk.name, "applicationId": current["name"], "versionName": current["versionName"],
        "versionCode": int(current["versionCode"]), "previousVersionName": previous["versionName"],
        "previousVersionCode": int(previous["versionCode"]), "previousSha256": digest(previous_apk.read_bytes()),
        "projectMetadataMatches": True, "releaseNotesMatch": True, "packagedNetworkPolicyVerified": True,
        "compileSdk": 37, "targetSdk": 37, "minSdk": 26, "applicationShellVerified": True,
        "packageProfile": "full", "abi": "arm64-v8a", "bytes": len(data), "sha256": digest(data),
        "certificateSha256": CERT, "signatureVerified": True, "signatureV2": True, "updateMissingKeyRejectionRemovedFromDex": True, "olderFeedErrorRemovedFromDex": True, "debuggable": False,
        "zipalign16KiBCheckPassed": True, "elf16KiBAlignmentVerified": True, "zipCrcVerified": True,
        "zipAlignmentScope": "16 KiB offsets checked for stored .so; compressed .so are extracted, so ZIP offsets do not apply",
        "nativeLibraries": native, "previousNativeLibraryComparison": differences,
        "ycoreByteIdentityChecks": ycore, "ycoreNativeAudit": relative(YCORE_RESULT),
        "ycoreReadControlJniMethodsPresent": list(jni_methods), "mdkReleaseJni": mdk,
        "frameRateFeatureDexMarkers": list(fps_markers),
        "sourceFileCount": len(source_hashes), "sourceManifest": relative(AUDIT / "source-hashes.json"),
        "sourceManifestSha256": digest((AUDIT / "source-hashes.json").read_bytes()),
        "tests": read_json(AUDIT / "test-summary.json"),
        "playbackTested": False, "installed": False, "published": False,
        "updateManifestPublicKeyConfigured": "production signing without an update-manifest public key" not in log,
    }
    encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    (out / "verification.json").write_text(encoded, encoding="utf-8")
    (AUDIT / "verification.json").write_text(encoded, encoding="utf-8")
    (out / "SHA256.txt").write_text(f'{report["sha256"]}  {apk.name}\n', encoding="utf-8")
    print(json.dumps({key: report[key] for key in
                      ("apk", "versionName", "versionCode", "bytes", "sha256", "signatureVerified")},
                     ensure_ascii=False, indent=2))
    print("PASS: actual version increase, metadata, production signature, ARM64 Full runtime, "
          "HTTP policy, YCore bytes/JNI, MDK Release JNI, ZIP CRC/alignment and every ELF PT_LOAD")


if __name__ == "__main__":
    main()
