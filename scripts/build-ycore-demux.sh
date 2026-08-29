#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE="$ROOT/.native-build"
ARTIFACTS="$WORKSPACE/artifacts"
AAR="$ARTIFACTS/libmpv-yfuse-bluray.aar"
PURE_AAR="$ARTIFACTS/ycore-native.aar"
SOURCES_MANIFEST="$ARTIFACTS/NATIVE-SOURCES.txt"
DEMUX_SOURCE="$ROOT/scripts/native/ycore_demux_jni.cpp"
VULKAN_SOURCE="$ROOT/scripts/native/ycore_vulkan_jni.cpp"
VULKAN_RENDERER_SOURCE="$ROOT/scripts/native/ycore_vulkan_renderer.cpp"
GPU_CAPABILITY_HEADER="$ROOT/scripts/native/ycore_gpu_capability.h"
VERTEX_SHADER_SOURCE="$ROOT/scripts/native/shaders/ycore_fullscreen.vert"
FRAGMENT_SHADER_SOURCE="$ROOT/scripts/native/shaders/ycore_video.frag"
PACKAGER="$ROOT/scripts/package-ycore-native-aar.py"
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

# build-ycore-native.sh builds libmpv first via build-yfuse-mpv-dolby.sh. That script keeps its
# pinned checkout under .native-build/yfuse-mpv/source. Reuse the exact same FFmpeg prefix and NDK
# tree so the demux bridge is compiled against the libraries that are packaged into the AAR.
# Keep the legacy .native-build/upstream fallback for older/local workspaces and allow an explicit
# override for reproducible debugging.
if [[ -n "${YCORE_UPSTREAM_ROOT:-}" ]]; then
  UPSTREAM="$YCORE_UPSTREAM_ROOT"
elif [[ -d "$WORKSPACE/yfuse-mpv/source/buildscripts/prefix" ]]; then
  UPSTREAM="$WORKSPACE/yfuse-mpv/source"
elif [[ -d "$WORKSPACE/upstream/buildscripts/prefix" ]]; then
  UPSTREAM="$WORKSPACE/upstream"
else
  fail "missing upstream FFmpeg prefix tree"
fi

[[ -f "$AAR" ]] || fail "missing native AAR: $AAR"
[[ -f "$SOURCES_MANIFEST" ]] || fail "missing native provenance: $SOURCES_MANIFEST"
[[ -f "$DEMUX_SOURCE" ]] || fail "missing JNI source: $DEMUX_SOURCE"
[[ -f "$VULKAN_SOURCE" ]] || fail "missing Vulkan JNI source: $VULKAN_SOURCE"
[[ -f "$VULKAN_RENDERER_SOURCE" ]] || fail "missing Vulkan renderer source: $VULKAN_RENDERER_SOURCE"
[[ -f "$GPU_CAPABILITY_HEADER" ]] || fail "missing GPU capability contract: $GPU_CAPABILITY_HEADER"
[[ -f "$VERTEX_SHADER_SOURCE" ]] || fail "missing Vulkan vertex shader: $VERTEX_SHADER_SOURCE"
[[ -f "$FRAGMENT_SHADER_SOURCE" ]] || fail "missing Vulkan fragment shader: $FRAGMENT_SHADER_SOURCE"
[[ -f "$PACKAGER" ]] || fail "missing standalone AAR packager: $PACKAGER"
command -v pkg-config >/dev/null 2>&1 || fail "pkg-config is required to resolve static libbluray dependencies"
[[ -d "$UPSTREAM/buildscripts/prefix" ]] || fail "missing upstream FFmpeg prefix tree: $UPSTREAM/buildscripts/prefix"
FFMPEG_REVISION="$(manifest_value ffmpeg)"
[[ "$FFMPEG_REVISION" =~ ^[0-9a-f]{40}$ ]] || fail "native provenance has no pinned FFmpeg commit"

echo "[ycore-demux] reusing native workspace: $UPSTREAM"
echo "[ycore-demux] FFmpeg revision: $FFMPEG_REVISION"

TOOLCHAIN_ROOT=("$UPSTREAM"/buildscripts/sdk/android-sdk-linux/ndk/"$NDK_VERSION"/toolchains/llvm/prebuilt/*)
[[ ${#TOOLCHAIN_ROOT[@]} -eq 1 && -d "${TOOLCHAIN_ROOT[0]}" ]] || fail "cannot resolve NDK toolchain"
TOOLCHAIN="${TOOLCHAIN_ROOT[0]}"

mapfile -t ABIS < <(
  unzip -Z1 "$AAR" |
    sed -n 's#^jni/\([^/]*\)/libavformat\.so$#\1#p' |
    sort -u
)
[[ ${#ABIS[@]} -gt 0 ]] || fail "AAR contains no FFmpeg libavformat.so"

STAGE="$(mktemp -d "$WORKSPACE/ycore-demux.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/libs"

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
    -std=c++17 \
    -fvisibility=hidden \
    -I"$PREFIX/include" \
    "$DEMUX_SOURCE" \
    -L"$PREFIX/lib" \
    -Wl,--no-undefined \
    -Wl,-z,max-page-size="$MAX_PAGE_SIZE" \
    -Wl,-soname,libycore_demux.so \
    -lavformat \
    -lavcodec \
    -lswscale \
    -lswresample \
    -lavutil \
    "${BLURAY_LINK_ARGS[@]}" \
    -o "$OUT"

  echo "[ycore-gpu] compiling isolated Vulkan executor for $ABI"
  "$CXX" \
    -shared \
    -fPIC \
    -O2 \
    -std=c++17 \
    -fvisibility=hidden \
    -I"$ROOT/scripts/native" \
    -I"$STAGE/generated" \
    "$VULKAN_SOURCE" \
    "$VULKAN_RENDERER_SOURCE" \
    -Wl,--no-undefined \
    -Wl,-z,max-page-size="$MAX_PAGE_SIZE" \
    -Wl,-soname,libycore_gpu.so \
    -landroid \
    -lvulkan \
    -o "$GPU_OUT"

  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$OUT"
  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$GPU_OUT"
done

export YCORE_AAR="$AAR"
export YCORE_STAGE="$STAGE/libs"
python3 - <<'PY'
import os
import pathlib
import shutil
import tempfile
import zipfile

source = pathlib.Path(os.environ["YCORE_AAR"])
stage = pathlib.Path(os.environ["YCORE_STAGE"])
fd, temp_name = tempfile.mkstemp(prefix=source.name + ".", suffix=".tmp", dir=source.parent)
os.close(fd)
temp = pathlib.Path(temp_name)
try:
    replacements = {
        f"jni/{path.parent.name}/{path.name}": path
        for path in stage.glob("*/libycore_*.so")
    }
    with zipfile.ZipFile(source, "r") as old, zipfile.ZipFile(temp, "w") as new:
        for info in old.infolist():
            if info.filename in replacements:
                continue
            new.writestr(info, old.read(info.filename))
        for archive_name, path in sorted(replacements.items()):
            info = zipfile.ZipInfo(archive_name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o755 << 16
            new.writestr(info, path.read_bytes())
    shutil.move(temp, source)
finally:
    temp.unlink(missing_ok=True)
PY

sha256sum "$AAR" | awk -v name="$(basename "$AAR")" '{print $1 "  " name}' > "$AAR.sha256"
PROVENANCE_TEMP="$(mktemp "$ARTIFACTS/.NATIVE-SOURCES.XXXXXX")"
awk -F= '
  $1 != "ycore-demux" &&
  $1 != "ycore-demux-ffmpeg" &&
  $1 != "ycore-demux-source" &&
  $1 != "ycore-tone-map-source" &&
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
  echo "ycore-demux-source=scripts/native/ycore_demux_jni.cpp"
  echo "ycore-tone-map-source=scripts/native/ycore_tone_map.h"
  echo "ycore-software-decoder-api=2"
  echo "ycore-disc-api=2"
  echo "ycore-bdmv-vfs=read-only-saf"
  echo "ycore-gpu-api=2"
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

echo "[ycore-demux] injected libycore_demux.so into $AAR"
echo "[ycore-demux] packaged standalone YCore runtime into $PURE_AAR"
echo "[ycore-demux] packaged full-app YCore GPU companion into $ARTIFACTS/ycore-gpu.aar"
