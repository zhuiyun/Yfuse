"""Rebuild the fixed arm64 JNI with pinned FFmpeg headers/shared libraries and cached static deps.

Requires the matching GitHub source archive already downloaded into the native build cache.
This does not modify the pinned MPV carrier or the existing Vulkan GPU companion.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tarfile
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[3]
STAGE = ROOT / ".native-build/kernel-fixes-20260920"
LIBS = ROOT / "composeApp/libs"
PREFIX = ROOT / ".native-build/ycore-arm64-prefix"
BLURAY = ROOT / ".native-build/libbluray-prefix"
SHARED = ("libavcodec.so", "libavformat.so", "libavutil.so", "libswresample.so", "libswscale.so", "libc++_shared.so")
SOURCE_NAMES = ("ycore_demux_jni.cpp",) + tuple(sorted(path.name for path in (ROOT / "scripts/native").glob("ycore_*.h")))


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_manifest(path: pathlib.Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line)


def run(*args: object) -> str:
    result = subprocess.run([str(arg) for arg in args], check=True, capture_output=True, text=True)
    if result.stderr:
        print(result.stderr)
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=pathlib.Path, required=True)
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    STAGE.mkdir(parents=True, exist_ok=True)
    tools = args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    carrier = LIBS / "libmpv-release.aar"
    carrier_digest = digest(carrier.read_bytes())
    expected = next(line.split()[0] for line in (ROOT / "scripts/engine-checksums.sha256").read_text().splitlines() if line.endswith("  libmpv-dolby-installed.aar"))
    assert carrier_digest == expected, "The MPV carrier must match its unchanged release pin"
    gpu_digest_before = digest((LIBS / "ycore-gpu.aar").read_bytes())
    origin = read_manifest(LIBS / "ycore-native.sources.txt")
    pinned = read_manifest(LIBS / "libmpv-release.sources.txt")
    revision = pinned["ffmpeg"]
    assert revision == "b79d4c4c0a160fc46988e98505af6039a53ad53e"
    archive_path = ROOT / ".native-build/diagnostics-fix-20260909/ffmpeg-b79d4c4.tar.gz"
    headers = STAGE / "headers" / f"FFmpeg-{revision}"
    # Safe extraction also refreshes any edited public headers before each compile.
    with tarfile.open(archive_path) as archive:
        archive.extractall(STAGE / "headers", filter="data")
    (headers / "libavutil/avconfig.h").write_text(
        "/* Generated for Android arm64, little-endian, unaligned access supported. */\n"
        "#ifndef AVUTIL_AVCONFIG_H\n#define AVUTIL_AVCONFIG_H\n"
        "#define AV_HAVE_BIGENDIAN 0\n#define AV_HAVE_FAST_UNALIGNED 1\n#endif\n",
        encoding="utf-8",
    )
    link = STAGE / "link"
    link.mkdir(exist_ok=True)
    with zipfile.ZipFile(carrier) as archive:
        for name in SHARED:
            (link / name).write_bytes(archive.read(f"jni/arm64-v8a/{name}"))
    sources = {name: digest((ROOT / "scripts/native" / name).read_bytes()) for name in SOURCE_NAMES}
    output = STAGE / "libycore_demux.so"
    run(
        tools / "clang++.exe", "--target=aarch64-linux-android26", "-shared", "-fPIC", "-O2",
        "-std=c++17", "-fvisibility=hidden", f"-I{headers}", f"-I{PREFIX / 'include'}",
        f"-I{BLURAY / 'include'}", ROOT / "scripts/native/ycore_demux_jni.cpp",
        f"-L{link}", f"-L{PREFIX / 'lib'}", f"-L{BLURAY / 'lib'}",
        "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384", "-Wl,-soname,libycore_demux.so",
        "-lavformat", "-lavcodec", "-lswscale", "-lswresample", "-lavutil", "-Wl,--start-group",
        "-lass", "-lharfbuzz", "-lfreetype", "-lfribidi", "-lunibreak", "-lbluray", "-Wl,--end-group",
        "-lz", "-lm", "-llog", "-o", output,
    )
    assert sources == {name: digest((ROOT / "scripts/native" / name).read_bytes()) for name in SOURCE_NAMES}, "Native source changed during compilation"
    run(tools / "llvm-strip.exe", "--strip-unneeded", output)
    native_strings = run(tools / "llvm-strings.exe", output)
    for name in ("nativeSubtitleDisplaySetApiVersion", "nativeOpenCancellable", "nativeOpenWithAnalysis", "nativeCreateCancellation", "nativeRenderAss", "nativeDemuxHandleContractVersion", "nativeDemuxReadControlApiVersion", "nativeInterruptDemuxRead", "nativeResumeDemuxRead"):
        assert name in native_strings, f"JNI output is missing {name}"
    with zipfile.ZipFile(LIBS / "ycore-native.aar") as archive:
        gpu = archive.read("jni/arm64-v8a/libycore_gpu.so")
    source_aar = STAGE / "jni-input.aar"
    with zipfile.ZipFile(source_aar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in SHARED:
            archive.writestr(f"jni/arm64-v8a/{name}", (link / name).read_bytes())
        archive.writestr("jni/arm64-v8a/libycore_demux.so", output.read_bytes())
        archive.writestr("jni/arm64-v8a/libycore_gpu.so", gpu)
    origin.update({
        "ffmpeg": revision,
        "ycore-demux-ffmpeg": revision,
        "ycore-libass-api": "2",
        "ycore-subtitle-display-set-api": "2",
        "ycore-demux-cancellation": "independent-token-monotonic-deadline",
        "ycore-demux-cancellation-api": "1",
        "ycore-demux-startup-analysis-api": "1",
        "ycore-demux-handle-contract": "2",
        "ycore-demux-read-control-api": "1",
        "ycore-demux-extradata-budget": "32MiB-codec-32MiB-font-128-fonts",
        "ycore-shared-carrier-sha256": carrier_digest,
        "ycore-demux-ffmpeg-headers": revision,
        "ycore-demux-ffmpeg-archive-sha256": digest(archive_path.read_bytes()),
        "ycore-demux-source-sha256": sources["ycore_demux_jni.cpp"],
        "ycore-demux-binary-sha256": digest(output.read_bytes()),
        "ycore-build-ndk": "29.0.14206865",
        "ycore-build-android-api": "26",
        "ycore-gpu-preserved-binary-sha256": digest(gpu),
    })
    origin.update({f"ycore-source-{name}-sha256": value for name, value in sources.items()})
    provenance = STAGE / "NATIVE-SOURCES.txt"
    provenance.write_text("".join(f"{key}={value}\n" for key, value in origin.items()), encoding="utf-8", newline="\n")
    artifact = STAGE / "ycore-native.aar"
    print(run(pathlib.Path(__import__("sys").executable), ROOT / "scripts/package-ycore-native-aar.py", "--readelf", tools / "llvm-readelf.exe", source_aar, artifact, provenance))
    with zipfile.ZipFile(artifact) as archive, zipfile.ZipFile(carrier) as pinned_archive:
        assert archive.read("META-INF/ycore-native-sources.txt") == provenance.read_bytes()
        for name in SHARED:
            member = f"jni/arm64-v8a/{name}"
            assert archive.read(member) == pinned_archive.read(member)
        assert archive.read("jni/arm64-v8a/libycore_gpu.so") == gpu
        for member in (name for name in archive.namelist() if name.endswith(".so")):
            path = STAGE / "verify" / pathlib.PurePosixPath(member).name
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(archive.read(member))
            loads = [line for line in run(tools / "llvm-readelf.exe", "-lW", path).splitlines() if line.lstrip().startswith("LOAD ")]
            assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads), f"{member} is not 16KiB aligned"
    static = [PREFIX / "lib" / name for name in ("libass.a", "libharfbuzz.a", "libfreetype.a", "libfribidi.a", "libunibreak.a")]
    static.append(BLURAY / "lib/libbluray.a")
    result = {
        "compiled_source_sha256": sources,
        "static_dependency_sha256": {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in static},
        "ffmpeg_revision": revision,
        "ffmpeg_archive_sha256": digest(archive_path.read_bytes()),
        "carrier_sha256": carrier_digest,
        "gpu_companion_sha256": gpu_digest_before,
        "demux_sha256": digest(output.read_bytes()),
        "aar_sha256": digest(artifact.read_bytes()),
        "validation": ["current source compiled", "--no-undefined linked", "required JNI methods present", "16KiB LOAD alignment for all libraries", "pinned shared dependencies identical", "GPU bytes unchanged", "embedded provenance equals sidecar"],
        "device_validation": "not run",
    }
    (pathlib.Path(__file__).parent / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.install:
        shutil.copyfile(artifact, LIBS / "ycore-native.aar")
        shutil.copyfile(artifact.with_suffix(".aar.sha256"), LIBS / "ycore-native.aar.sha256")
        shutil.copyfile(provenance, LIBS / "ycore-native.sources.txt")
    assert digest(carrier.read_bytes()) == carrier_digest, "MPV carrier was modified"
    assert digest((LIBS / "ycore-gpu.aar").read_bytes()) == gpu_digest_before, "GPU companion was modified"
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

