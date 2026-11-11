#!/usr/bin/env python3
"""Bind a newly source-built YCore AAR to its checkout and external CI identities.

The receipt is evidence, not a signature. Verification always needs the caller's
expected commit, AAR SHA and provenance SHA from the producing workflow outputs.
It never promotes the output to the historical production input pin.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIBRARIES = {
    "libavcodec.so", "libavformat.so", "libavutil.so", "libc++_shared.so",
    "libswresample.so", "libswscale.so", "libycore_demux.so", "libycore_gpu.so",
}
SOURCE_PATHS = (
    ".github/workflows/build-ycore-source.yml", "scripts/native",
    "scripts/build-ycore*.sh", "scripts/build-yfuse-mpv*.sh",
    "scripts/native-build-*.py", "scripts/ycore-source-receipt.py",
    "scripts/package-ycore-native-aar.py", "scripts/embed-mini-debuginfo.sh",
    "scripts/verify-ycore-native.sh", "scripts/yfuse-mpv-sources.txt",
    "scripts/test-ycore*", "scripts/native-test-build.sh", "NOTICE", "LICENSE",
    "docs/third-party-licenses",
)
REQUIRED_SOURCES = {
    ".github/workflows/build-ycore-source.yml", "scripts/build-ycore-native.sh",
    "scripts/build-ycore-demux.sh", "scripts/build-yfuse-mpv-bluray.sh",
    "scripts/build-yfuse-mpv-dolby.sh", "scripts/package-ycore-native-aar.py",
    "scripts/native/ycore_demux_jni.cpp", "scripts/native/ycore_vulkan_jni.cpp",
    "scripts/native/ycore_vulkan_renderer.cpp", "scripts/native-build-download.py",
    "scripts/ycore-source-receipt.py", "scripts/verify-ycore-native.sh", "NOTICE",
}
LICENSE_NAME = re.compile(r"^(copying|copyright|licen[cs]e|notice)([._-].*)?$", re.I)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git(root, *args, binary=False):
    result = subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)
    return result.stdout if binary else result.stdout.decode("utf-8").strip()


def regular_file(path, root):
    require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root.resolve()),
            "Missing, escaping or symbolic-link source/artifact file")
    return path.read_bytes()


def source_files(root, expected_commit):
    require(re.fullmatch(r"[0-9a-f]{40}", expected_commit or ""), "Invalid expected source commit")
    require(git(root, "rev-parse", "HEAD") == expected_commit, "Checkout commit differs from expected source commit")
    raw = git(root, "ls-files", "-z", "--", *SOURCE_PATHS, binary=True)
    names = sorted(set(raw.decode("utf-8").rstrip("\0").split("\0")))
    require(REQUIRED_SOURCES <= set(names), "Required native build sources are not tracked")
    result = {}
    for name in names:
        data = regular_file(root / name, root)
        committed = git(root, "show", f"{expected_commit}:{name}", binary=True)
        require(data == committed, f"Native build source differs from committed bytes: {name}")
        result[name] = sha256(data)
    return result


def artifact_identity(aar):
    directory = aar.parent
    require(aar.name == "ycore-native.aar", "Unexpected standalone AAR filename")
    data = regular_file(aar, directory)
    provenance = regular_file(directory / "NATIVE-SOURCES.txt", directory)
    sidecar = regular_file(directory / "ycore-native.aar.sha256", directory).decode("utf-8").split()
    require(sidecar == [sha256(data), "ycore-native.aar"], "Standalone AAR checksum sidecar mismatch")
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate standalone AAR ZIP entry")
        require({name for name in names if name.endswith(".so")} ==
                {f"jni/arm64-v8a/{name}" for name in LIBRARIES}, "Unexpected standalone native library inventory")
        require(archive.read("META-INF/ycore-native-sources.txt") == provenance,
                "Embedded provenance differs from sidecar")
        require(bool(archive.read("META-INF/NOTICE")), "Missing standalone notices")
        for name in names:
            parts = Path(name.replace("\\", "/")).parts
            require(not name.startswith("/") and ".." not in parts, "Unsafe AAR entry")
    return {"sha256": sha256(data), "provenanceSha256": sha256(provenance), "bytes": len(data)}


def dependency_evidence(upstream):
    """Record resolved commits and patched tracked-source hashes, not guessed pins."""
    require(upstream.is_dir(), "Missing native dependency workspace")
    roots = [("libmpv-android-build-tool", upstream)]
    deps = upstream / "buildscripts/deps"
    require(deps.is_dir(), "Missing resolved native dependencies")
    roots.extend((path.name, path) for path in sorted(deps.iterdir())
                 if path.is_dir() and (path / ".git").exists())
    roots.append(("libudfread", deps / "libbluray/contrib/libudfread"))
    evidence = {}
    for name, path in roots:
        commit = git(path, "rev-parse", "HEAD")
        require(re.fullmatch(r"[0-9a-f]{40}", commit), "Invalid resolved dependency commit")
        tracked = git(path, "ls-files", "-z", binary=True).decode().rstrip("\0").split("\0")
        hashes = {}
        for filename in sorted(tracked):
            source = path / filename
            if source.is_file() and source.resolve().is_relative_to(path.resolve()):
                hashes[filename] = sha256(source.read_bytes())
        evidence[name] = {"commit": commit, "trackedSourceSha256": sha256(
            json.dumps(hashes, sort_keys=True, separators=(",", ":")).encode())}
    for name, expected in {
        "libmpv-android-build-tool": "fcf6745703dc1265bca88f12fee8fc355ddf251e",
        "ffmpeg": "b79d4c4c0a160fc46988e98505af6039a53ad53e",
        "mpv": "b955aa28f3dc93dc6b21485a0d5b7feb8e6dc10f",
        "libplacebo": "22ee762e8e0890fc54068beb670310f0edce7263",
        "libbluray": "7d94f2660af5bfc16015291a03539329135c18f1",
        "libudfread": "139a2194525f2745b98a98e4d8fa627d07440176",
    }.items():
        require(evidence.get(name, {}).get("commit") == expected, f"Resolved {name} commit differs from source pin")
    return evidence


def collect_licenses(root, upstream, destination):
    destination.mkdir(parents=True, exist_ok=True)
    candidates = [(root / "NOTICE", "Yfuse/NOTICE"), (root / "LICENSE", "Yfuse/LICENSE")]
    for path in sorted((root / "docs/third-party-licenses").glob("*")):
        if path.is_file():
            candidates.append((path, "Yfuse/" + path.name))
    for dep in sorted((upstream / "buildscripts/deps").iterdir()):
        if not dep.is_dir():
            continue
        # Scan source notices only, not build outputs or arbitrary artifact files.
        for path in dep.rglob("*"):
            relative = path.relative_to(dep)
            if any(part.startswith((".git", "_build")) for part in relative.parts):
                continue
            if path.is_file() and (LICENSE_NAME.match(path.name) or path.name == "FTL.TXT"):
                require(path.resolve().is_relative_to(dep.resolve()), "Escaping dependency notice")
                candidates.append((path, dep.name + "/" + relative.as_posix()))
    result = {}
    total = 0
    for path, name in candidates:
        data = path.read_bytes()
        require(len(data) <= 2 * 1024 * 1024, "Dependency notice is unexpectedly large")
        total += len(data)
        require(total <= 32 * 1024 * 1024, "Dependency notices exceed size budget")
        output = destination / name
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        result["licenses/" + name] = sha256(data)
    require(any(name.startswith("licenses/ffmpeg/COPYING") for name in result), "FFmpeg license texts are missing")
    return result


def verify_receipt(root, aar, receipt, expected_aar_sha256, expected_provenance_sha256, expected_source_commit):
    root, aar, receipt = Path(root), Path(aar), Path(receipt)
    for expected in (expected_aar_sha256, expected_provenance_sha256):
        require(re.fullmatch(r"[0-9a-f]{64}", expected or ""), "External expected hashes are required")
    record = json.loads(regular_file(receipt, receipt.parent))
    require(type(record.get("schemaVersion")) is int and record["schemaVersion"] == 1, "Unsupported source receipt schema")
    require(record.get("sourceCommit") == expected_source_commit, "Receipt source commit mismatch")
    require(record.get("sourceFiles") == source_files(root, expected_source_commit), "Receipt source file hashes mismatch")
    actual = artifact_identity(aar)
    require(actual["sha256"] == expected_aar_sha256, "AAR differs from external workflow SHA")
    require(actual["provenanceSha256"] == expected_provenance_sha256, "Provenance differs from external workflow SHA")
    require(record.get("ycore") == actual, "Receipt artifact identity mismatch")
    for name, expected in record.get("licenseFiles", {}).items():
        require(name.startswith("licenses/") and ".." not in Path(name).parts, "Invalid notice path")
        require(sha256(regular_file(receipt.parent / name, receipt.parent)) == expected, "License material checksum mismatch")
    return record


def toolchain_evidence(upstream):
    ndk = upstream / "buildscripts/sdk/android-sdk-linux/ndk/29.0.14206865"
    require(ndk.is_dir(), "Expected Android NDK is missing")
    properties = regular_file(ndk / "source.properties", ndk)
    require(re.search(rb"(?m)^Pkg\.Revision\s*=\s*29\.0\.14206865\s*$", properties),
            "Installed Android NDK revision differs from requested toolchain")
    toolchains = list((ndk / "toolchains/llvm/prebuilt").glob("*"))
    require(len(toolchains) == 1, "Cannot resolve one Android LLVM toolchain")
    paths = [toolchains[0] / "bin" / name for name in
             ("clang", "llvm-readelf", "llvm-objcopy", "llvm-strip")]
    shaders = list((ndk / "shader-tools").rglob("glslc"))
    require(len(shaders) == 1, "Cannot resolve one Android shader compiler")
    paths.extend(shaders)
    hashes = {}
    for path in paths:
        require(path.resolve().is_relative_to(ndk.resolve()), "Escaping Android toolchain executable")
        hashes[path.relative_to(ndk).as_posix()] = sha256(path.read_bytes())
    return {"androidNdk": "29.0.14206865", "androidApi": 26,
            "ndkSourcePropertiesSha256": sha256(properties), "ndkToolFiles": hashes,
            "meson": subprocess.run(["meson", "--version"], check=True, capture_output=True, text=True).stdout.strip(),
            "python": sys.version.split()[0], "host": "ubuntu-24.04", "javaMajor": 21,
            "runnerImageVersion": os.environ.get("ImageVersion", "not-reported")}


def create_receipt(root, directory, source_commit, upstream, download_receipts):
    downloads = [json.loads(line) for line in regular_file(download_receipts, download_receipts.parent).decode().splitlines()]
    require(downloads, "Native download evidence is empty")
    record = {
        "schemaVersion": 1, "sourceCommit": source_commit,
        "ycore": artifact_identity(directory / "ycore-native.aar"),
        "sourceFiles": source_files(root, source_commit),
        "resolvedDependencies": dependency_evidence(upstream),
        "downloadedInputs": downloads,
        "licenseFiles": collect_licenses(root, upstream, directory / "licenses"),
        "sourceLockScope": "Core commits and gas/Lua content are pinned. Remaining upstream version tags are resolved and recorded, not claimed immutable or bit-reproducible.",
        "toolchain": toolchain_evidence(upstream),
    }
    receipt = directory / "source-build.json"
    receipt.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    verify_receipt(root, directory / "ycore-native.aar", receipt,
                   record["ycore"]["sha256"], record["ycore"]["provenanceSha256"], source_commit)
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--artifact-dir", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--aar-sha256")
    parser.add_argument("--provenance-sha256")
    parser.add_argument("--upstream", type=Path)
    parser.add_argument("--download-receipts", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "create":
            require(args.upstream is not None and args.download_receipts is not None,
                    "--upstream and --download-receipts are required for source receipt creation")
            record = create_receipt(args.root, args.artifact_dir, args.source_commit, args.upstream, args.download_receipts)
        else:
            record = verify_receipt(args.root, args.artifact_dir / "ycore-native.aar",
                                    args.artifact_dir / "source-build.json", args.aar_sha256,
                                    args.provenance_sha256, args.source_commit)
        outputs = {"source_commit": record["sourceCommit"], "aar_sha256": record["ycore"]["sha256"],
                   "provenance_sha256": record["ycore"]["provenanceSha256"], "aar_bytes": record["ycore"]["bytes"]}
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                for key, value in outputs.items():
                    output.write(f"{key}={value}\n")
        print(json.dumps(outputs, sort_keys=True))
        return 0
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"Source-built YCore receipt rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
