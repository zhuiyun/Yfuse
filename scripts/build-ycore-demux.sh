#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE="$ROOT/.native-build"
ARTIFACTS="$WORKSPACE/artifacts"
PURE_AAR="$ARTIFACTS/ycore-native.aar"
SOURCES_MANIFEST="$ARTIFACTS/NATIVE-SOURCES.txt"
DEMUX_SOURCE="$ROOT/scripts/native/ycore_demux_jni.cpp"
VULKAN_SOURCE="$ROOT/scripts/native/ycore_vulkan_jni.cpp"
VULKAN_RENDERER_SOURCE="$ROOT/scripts/native/ycore_vulkan_renderer.cpp"
GPU_CAPABILITY_HEADER="$ROOT/scripts/native/ycore_gpu_capability.h"
VERTEX_SHADER_SOURCE="$ROOT/scripts/native/shaders/ycore_fullscreen.vert"
FRAGMENT_SHADER_SOURCE="$ROOT/scripts/native/shaders/ycore_video.frag"
PACKAGER="$ROOT/scripts/package-ycore-native-aar.py"
MINI_DEBUGINFO="$ROOT/scripts/embed-mini-debuginfo.sh"
# Unstripped copies of the shipped YCore libraries, matched to tombstones by GNU build id.
SYMBOLS="$ARTIFACTS/symbols"
SYMBOLS_ZIP="$ARTIFACTS/ycore-native-symbols.zip"
NDK_VERSION="29.0.14206865"
ANDROID_API="26"
MAX_PAGE_SIZE="16384"

fail() {
  echo "[ycore-demux] $*" >&2
  exit 1
}

manifest_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$SOURCES_MANIFEST"
}

UPSTREAM="${YCORE_UPSTREAM_ROOT:-$WORKSPACE/ycore-source}"

[[ -f "$SOURCES_MANIFEST" ]] || fail "missing native provenance: $SOURCES_MANIFEST"
[[ -f "$DEMUX_SOURCE" ]] || fail "missing JNI source: $DEMUX_SOURCE"
[[ -f "$VULKAN_SOURCE" ]] || fail "missing Vulkan JNI source: $VULKAN_SOURCE"
[[ -f "$VULKAN_RENDERER_SOURCE" ]] || fail "missing Vulkan renderer source: $VULKAN_RENDERER_SOURCE"
[[ -f "$GPU_CAPABILITY_HEADER" ]] || fail "missing GPU capability contract: $GPU_CAPABILITY_HEADER"
[[ -f "$VERTEX_SHADER_SOURCE" ]] || fail "missing Vulkan vertex shader: $VERTEX_SHADER_SOURCE"
[[ -f "$FRAGMENT_SHADER_SOURCE" ]] || fail "missing Vulkan fragment shader: $FRAGMENT_SHADER_SOURCE"
[[ -f "$PACKAGER" ]] || fail "missing standalone AAR packager: $PACKAGER"
[[ -f "$MINI_DEBUGINFO" ]] || fail "missing symbol tooling: $MINI_DEBUGINFO"
command -v pkg-config >/dev/null 2>&1 || fail "pkg-config is required to resolve static libbluray dependencies"
[[ -d "$UPSTREAM/buildscripts/prefix" ]] || fail "missing upstream FFmpeg prefix tree: $UPSTREAM/buildscripts/prefix"
FFMPEG_REVISION="$(manifest_value ffmpeg)"
[[ "$FFMPEG_REVISION" =~ ^[0-9a-f]{40}$ ]] || fail "native provenance has no pinned FFmpeg commit"

echo "[ycore-demux] reusing native workspace: $UPSTREAM"
echo "[ycore-demux] FFmpeg revision: $FFMPEG_REVISION"

TOOLCHAIN_ROOT=("$UPSTREAM"/buildscripts/sdk/android-sdk-linux/ndk/"$NDK_VERSION"/toolchains/llvm/prebuilt/*)
[[ ${#TOOLCHAIN_ROOT[@]} -eq 1 && -d "${TOOLCHAIN_ROOT[0]}" ]] || fail "cannot resolve NDK toolchain"
TOOLCHAIN="${TOOLCHAIN_ROOT[0]}"

ABIS=(arm64-v8a)

STAGE="$(mktemp -d "$WORKSPACE/ycore-demux.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/libs"
rm -rf "$SYMBOLS" "$SYMBOLS_ZIP"

NDK_ROOT="$(cd "$TOOLCHAIN/../../../.." && pwd)"
GLSLC="$(find "$NDK_ROOT/shader-tools" -type f -name glslc -print -quit)"
[[ -n "$GLSLC" && -x "$GLSLC" ]] || fail "missing NDK glslc"
mkdir -p "$STAGE/generated"
"$GLSLC" -fshader-stage=vert "$VERTEX_SHADER_SOURCE" -o "$STAGE/generated/ycore_fullscreen.vert.spv"
"$GLSLC" -fshader-stage=frag "$FRAGMENT_SHADER_SOURCE" -o "$STAGE/generated/ycore_video.frag.spv"
python3 - "$STAGE/generated" <<'PY'
import pathlib
import struct
import sys

root = pathlib.Path(sys.argv[1])
output = root / "ycore_gpu_shaders.inc"
arrays = []
for path, name in (
    (root / "ycore_fullscreen.vert.spv", "kYCoreFullscreenVertexShader"),
    (root / "ycore_video.frag.spv", "kYCoreVideoFragmentShader"),
):
    data = path.read_bytes()
    if len(data) % 4:
        raise SystemExit(f"unaligned SPIR-V: {path}")
    words = struct.unpack(f"<{len(data) // 4}I", data)
    body = ",\n    ".join(f"0x{word:08x}U" for word in words)
    arrays.append(f"static const uint32_t {name}[] = {{\n    {body}\n}};\n")
output.write_text("\n".join(arrays), encoding="utf-8")
PY

compiler_for_abi() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android${ANDROID_API}-clang++" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi${ANDROID_API}-clang++" ;;
    x86) echo "i686-linux-android${ANDROID_API}-clang++" ;;
    x86_64) echo "x86_64-linux-android${ANDROID_API}-clang++" ;;
    *) fail "unsupported AAR ABI: $1" ;;
  esac
}

for ABI in "${ABIS[@]}"; do
  PREFIX="$UPSTREAM/buildscripts/prefix/$ABI"
  [[ -d "$PREFIX/include/libavformat" ]] || fail "missing FFmpeg headers for $ABI"
  [[ -f "$PREFIX/lib/libavformat.so" ]] || fail "missing FFmpeg shared library for $ABI"
  [[ -f "$PREFIX/lib/libswscale.so" ]] || fail "missing FFmpeg libswscale for $ABI"
  [[ -f "$PREFIX/lib/libswresample.so" ]] || fail "missing FFmpeg libswresample for $ABI"
  [[ -f "$PREFIX/lib/libbluray.so" || -f "$PREFIX/lib/libbluray.a" ]] ||
    fail "missing libbluray for $ABI"
  BLURAY_PC_DIR="$PREFIX/lib/pkgconfig"
  [[ -f "$BLURAY_PC_DIR/libbluray.pc" ]] ||
    fail "missing libbluray pkg-config metadata for $ABI"
  BLURAY_LINK_FLAGS="$(
    PKG_CONFIG_LIBDIR="$BLURAY_PC_DIR" pkg-config --static --libs libbluray
  )"
  read -r -a BLURAY_LINK_ARGS <<<"$BLURAY_LINK_FLAGS"
  [[ ${#BLURAY_LINK_ARGS[@]} -gt 0 ]] ||
    fail "libbluray pkg-config returned no linker flags for $ABI"
  [[ -f "$BLURAY_PC_DIR/libass.pc" ]] ||
    fail "missing libass pkg-config metadata for $ABI"
  ASS_LINK_FLAGS="$(
    PKG_CONFIG_LIBDIR="$BLURAY_PC_DIR" pkg-config --static --libs libass
  )"
  read -r -a ASS_LINK_ARGS <<<"$ASS_LINK_FLAGS"
  [[ ${#ASS_LINK_ARGS[@]} -gt 0 ]] ||
    fail "libass pkg-config returned no linker flags for $ABI"
  CXX="$TOOLCHAIN/bin/$(compiler_for_abi "$ABI")"
  [[ -x "$CXX" ]] || fail "missing compiler for $ABI: $CXX"

  OUT_DIR="$STAGE/libs/$ABI"
  mkdir -p "$OUT_DIR"
  OUT="$OUT_DIR/libycore_demux.so"
  GPU_OUT="$OUT_DIR/libycore_gpu.so"
  echo "[ycore-demux] compiling $ABI"
  "$CXX" \
    -shared \
    -fPIC \
    -O2 \
    -g \
    -std=c++17 \
    -fvisibility=hidden \
    -I"$PREFIX/include" \
    "$DEMUX_SOURCE" \
    -L"$PREFIX/lib" \
    -Wl,--build-id=sha1 \
    -Wl,--no-undefined \
    -Wl,-z,max-page-size="$MAX_PAGE_SIZE" \
    -Wl,-soname,libycore_demux.so \
    -lavformat \
    -lavcodec \
    -lswscale \
    -lswresample \
    -lavutil \
    "${ASS_LINK_ARGS[@]}" \
    "${BLURAY_LINK_ARGS[@]}" \
    -o "$OUT"

  echo "[ycore-gpu] compiling isolated Vulkan executor for $ABI"
  "$CXX" \
    -shared \
    -fPIC \
    -O2 \
    -g \
    -std=c++17 \
    -fvisibility=hidden \
    -I"$ROOT/scripts/native" \
    -I"$STAGE/generated" \
    "$VULKAN_SOURCE" \
    "$VULKAN_RENDERER_SOURCE" \
    -Wl,--build-id=sha1 \
    -Wl,--no-undefined \
    -Wl,-z,max-page-size="$MAX_PAGE_SIZE" \
    -Wl,-soname,libycore_gpu.so \
    -landroid \
    -lvulkan \
    -o "$GPU_OUT"

  bash "$MINI_DEBUGINFO" "$TOOLCHAIN/bin" "$OUT" "$SYMBOLS/$ABI/libycore_demux.so"
  bash "$MINI_DEBUGINFO" "$TOOLCHAIN/bin" "$GPU_OUT" "$SYMBOLS/$ABI/libycore_gpu.so"
done

export YCORE_SYMBOLS="$SYMBOLS"
export YCORE_SYMBOLS_ZIP="$SYMBOLS_ZIP"
export YCORE_READELF="$TOOLCHAIN/bin/llvm-readelf"
python3 - <<'PY'
import os
import pathlib
import re
import subprocess
import zipfile

root = pathlib.Path(os.environ["YCORE_SYMBOLS"])
lines = []
with zipfile.ZipFile(os.environ["YCORE_SYMBOLS_ZIP"], "w", zipfile.ZIP_DEFLATED) as archive:
    for library in sorted(root.glob("*/*.so")):
        notes = subprocess.run(
            [os.environ["YCORE_READELF"], "-n", str(library)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        build_id = re.search(r"Build ID: ([0-9a-f]+)", notes)
        if not build_id:
            raise SystemExit(f"{library} has no GNU build id")
        relative = library.relative_to(root).as_posix()
        lines.append(f"{build_id.group(1)}  {relative}")
        archive.write(library, relative)
    archive.writestr("BUILD-IDS.txt", "\n".join(lines) + "\n")
print("[ycore-demux] symbols:", *lines, sep="\n  ")
PY

# Assemble the exact JNI and FFmpeg inputs built above. The packager verifies the ELF closure.
AAR="$STAGE/compiled-libraries.zip"
python3 - "$STAGE/libs/arm64-v8a" "$UPSTREAM/buildscripts/prefix/arm64-v8a/lib" "$AAR" <<'PYTHON'
import sys
import zipfile
from pathlib import Path

jni, prefix, archive = map(Path, sys.argv[1:])
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as out:
    for library in sorted(jni.glob("libycore_*.so")):
        out.write(library, "jni/arm64-v8a/" + library.name)
    for name in ("avcodec", "avformat", "avutil", "swscale", "swresample", "c++_shared"):
        library = "lib" + name + ".so"
        out.write(prefix / library, "jni/arm64-v8a/" + library)
PYTHON

# The license FFmpeg was configured under (avutil_license(): LGPL unless --enable-gpl, version 3
# with --enable-version3) decides what distributing this runtime obliges. Recorded, not assumed.
FFMPEG_LICENSE="$(
  LC_ALL=C grep -a -o -m1 -E 'L?GPL version [0-9.]+ or later|nonfree and unredistributable' \
    "$UPSTREAM/buildscripts/prefix/${ABIS[0]}/lib/libavutil.so" 2>/dev/null || true
)"
if [[ -z "$FFMPEG_LICENSE" ]]; then
  echo "[ycore-demux] warning: could not read FFmpeg's license from libavutil" >&2
  FFMPEG_LICENSE=unknown
fi
echo "[ycore-demux] FFmpeg license: $FFMPEG_LICENSE"
PROVENANCE_TEMP="$(mktemp "$ARTIFACTS/.NATIVE-SOURCES.XXXXXX")"
awk -F= '
  $1 != "ycore-demux" &&
  $1 != "ycore-demux-ffmpeg" &&
  $1 != "ycore-demux-ffmpeg-license" &&
  $1 != "ycore-demux-source" &&
  $1 != "ycore-tone-map-source" &&
  $1 != "ycore-libass" &&
  $1 != "ycore-libass-api" &&
  $1 != "ycore-subtitle-display-set-api" &&
  $1 != "ycore-demux-cancellation-api" &&
  $1 != "ycore-demux-read-control-api" &&
  $1 != "ycore-demux-extradata-budget" &&
  $1 != "ycore-software-decoder-api" &&
  $1 != "ycore-disc-api" &&
  $1 != "ycore-bdmv-vfs" &&
  $1 != "ycore-gpu-api" &&
  $1 != "ycore-gpu-source" &&
  $1 != "ycore-gpu-renderer-source" &&
  $1 != "ycore-gpu-vertex-shader" &&
  $1 != "ycore-gpu-fragment-shader" &&
  $1 != "ycore-gpu-capability-source" &&
  $1 != "ycore-gpu-frame-ring" &&
  $1 != "ycore-gpu-import-cache" &&
  $1 != "ycore-gpu-hdr10plus" &&
  $1 != "ycore-gpu-color-metadata" &&
  $1 != "ycore-libbluray" &&
  $1 != "ycore-disc-uri-source" &&
  $1 != "ycore-demux-abis" &&
  $1 != "ycore-native-aar" &&
  $1 != "ycore-native-entry" &&
  $1 != "ycore-gpu-entry" &&
  $1 != "ycore-native-forbidden" { print }
' "$SOURCES_MANIFEST" > "$PROVENANCE_TEMP"
{
  echo "ycore-demux=true"
  echo "ycore-demux-ffmpeg=$FFMPEG_REVISION"
  echo "ycore-demux-ffmpeg-license=$FFMPEG_LICENSE"
  echo "ycore-demux-source=scripts/native/ycore_demux_jni.cpp"
  echo "ycore-tone-map-source=scripts/native/ycore_tone_map.h"
  echo "ycore-libass=$(manifest_value libass)"
  echo "ycore-libass-api=2"
  echo "ycore-subtitle-display-set-api=2"
  echo "ycore-demux-cancellation-api=1"
  echo "ycore-demux-read-control-api=1"
  echo "ycore-demux-extradata-budget=32MiB-codec-32MiB-font-128-fonts"
  echo "ycore-software-decoder-api=4"
  echo "ycore-disc-api=2"
  echo "ycore-bdmv-vfs=read-only-saf"
  echo "ycore-gpu-api=3"
  echo "ycore-gpu-source=scripts/native/ycore_vulkan_jni.cpp"
  echo "ycore-gpu-renderer-source=scripts/native/ycore_vulkan_renderer.cpp"
  echo "ycore-gpu-vertex-shader=scripts/native/shaders/ycore_fullscreen.vert"
  echo "ycore-gpu-fragment-shader=scripts/native/shaders/ycore_video.frag"
  echo "ycore-gpu-capability-source=scripts/native/ycore_gpu_capability.h"
  echo "ycore-gpu-frame-ring=3"
  echo "ycore-gpu-import-cache=12"
  echo "ycore-gpu-hdr10plus=per-frame-st2094-40"
  echo "ycore-gpu-color-metadata=range,matrix,chroma,sar,rotation,crop,st2086,maxcll,maxfall"
  echo "ycore-libbluray=1.4.1"
  echo "ycore-disc-uri-source=scripts/native/ycore_disc_uri.h"
  echo "ycore-demux-abis=$(IFS=,; echo "${ABIS[*]}")"
  echo "ycore-native-aar=true"
  echo "ycore-native-entry=libycore_demux.so"
  echo "ycore-gpu-entry=libycore_gpu.so"
  echo "ycore-native-forbidden=libmpv.so,libplayer.so,libmdk.so"
} >> "$PROVENANCE_TEMP"
mv -f "$PROVENANCE_TEMP" "$SOURCES_MANIFEST"

python3 "$PACKAGER" \
  --readelf "$TOOLCHAIN/bin/llvm-readelf" \
  "$AAR" \
  "$PURE_AAR" \
  "$SOURCES_MANIFEST"

python3 "$PACKAGER" \
  --gpu-only \
  --readelf "$TOOLCHAIN/bin/llvm-readelf" \
  "$PURE_AAR" \
  "$ARTIFACTS/ycore-gpu.aar" \
  "$SOURCES_MANIFEST"

echo "[ycore-demux] packaged standalone YCore runtime into $PURE_AAR"
echo "[ycore-demux] packaged full-app YCore GPU companion into $ARTIFACTS/ycore-gpu.aar"
