#!/usr/bin/env bash
# Builds the release GPU-only YCore companion directly from the checked-out Vulkan sources.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${1:-$ROOT/.native-build/gpu-companion}"
NDK_VERSION="29.0.14206865"
ANDROID_API="26"
MAX_PAGE_SIZE="16384"

fail() {
  echo "[ycore-gpu] $*" >&2
  exit 1
}

if [[ -n "${YCORE_NDK_ROOT:-}" ]]; then
  NDK_ROOT="$YCORE_NDK_ROOT"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
  NDK_ROOT="$ANDROID_NDK_ROOT"
elif [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_ROOT="$ANDROID_NDK_HOME"
else
  ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  [[ -n "$ANDROID_SDK" ]] || fail "Android SDK root is unavailable"
  NDK_ROOT="$ANDROID_SDK/ndk/$NDK_VERSION"
fi

[[ -d "$NDK_ROOT" ]] || fail "Android NDK is unavailable: $NDK_ROOT"
mapfile -t TOOLCHAINS < <(
  find "$NDK_ROOT/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print
)
[[ ${#TOOLCHAINS[@]} -eq 1 ]] || fail "cannot resolve the NDK LLVM toolchain"
TOOLCHAIN="${TOOLCHAINS[0]}"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
READELF="$TOOLCHAIN/bin/llvm-readelf"
STRIP="$TOOLCHAIN/bin/llvm-strip"
GLSLC="$(find "$NDK_ROOT/shader-tools" -type f -name glslc -print -quit)"
[[ -x "$CXX" && -x "$READELF" && -x "$STRIP" ]] || fail "required NDK tools are unavailable"
[[ -n "$GLSLC" && -x "$GLSLC" ]] || fail "NDK glslc is unavailable"

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/ycore-gpu-companion.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/generated" "$STAGE/source/jni/arm64-v8a" "$OUTPUT_DIR"

"$GLSLC" \
  -fshader-stage=vert \
  "$ROOT/scripts/native/shaders/ycore_fullscreen.vert" \
  -o "$STAGE/generated/ycore_fullscreen.vert.spv"
"$GLSLC" \
  -fshader-stage=frag \
  "$ROOT/scripts/native/shaders/ycore_video.frag" \
  -o "$STAGE/generated/ycore_video.frag.spv"

python3 - "$STAGE/generated" <<'PY'
import pathlib
import struct
import sys

root = pathlib.Path(sys.argv[1])
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
(root / "ycore_gpu_shaders.inc").write_text("\n".join(arrays), encoding="utf-8")
PY

GPU_LIBRARY="$STAGE/source/jni/arm64-v8a/libycore_gpu.so"
"$CXX" \
  -shared \
  -fPIC \
  -O2 \
  -std=c++17 \
  -fvisibility=hidden \
  -I"$ROOT/scripts/native" \
  -I"$STAGE/generated" \
  "$ROOT/scripts/native/ycore_vulkan_jni.cpp" \
  "$ROOT/scripts/native/ycore_vulkan_renderer.cpp" \
  -Wl,--no-undefined \
  -Wl,-z,max-page-size="$MAX_PAGE_SIZE" \
  -Wl,-soname,libycore_gpu.so \
  -landroid \
  -lvulkan \
  -o "$GPU_LIBRARY"
"$STRIP" --strip-unneeded "$GPU_LIBRARY"

PROVENANCE="$OUTPUT_DIR/NATIVE-SOURCES.txt"
printf '%s\n' \
  "ycore-gpu-api=2" \
  "ycore-gpu-source=scripts/native/ycore_vulkan_jni.cpp" \
  "ycore-gpu-renderer-source=scripts/native/ycore_vulkan_renderer.cpp" \
  "ycore-gpu-vertex-shader=scripts/native/shaders/ycore_fullscreen.vert" \
  "ycore-gpu-fragment-shader=scripts/native/shaders/ycore_video.frag" \
  "ycore-gpu-capability-source=scripts/native/ycore_gpu_capability.h" \
  "ycore-gpu-frame-ring=3" \
  "ycore-gpu-import-cache=12" \
  "ycore-gpu-hdr10plus=per-frame-st2094-40" \
  "ycore-gpu-color-metadata=range,matrix,chroma,sar,rotation,crop,st2086,maxcll,maxfall" \
  "ycore-gpu-entry=libycore_gpu.so" \
  "ycore-gpu-abis=arm64-v8a" \
  "android-ndk=$NDK_VERSION" \
  "android-api=$ANDROID_API" \
  > "$PROVENANCE"

SOURCE_AAR="$STAGE/ycore-gpu-source.aar"
python3 - "$STAGE/source" "$SOURCE_AAR" <<'PY'
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
library = root / "jni/arm64-v8a/libycore_gpu.so"
info = zipfile.ZipInfo("jni/arm64-v8a/libycore_gpu.so", date_time=(1980, 1, 1, 0, 0, 0))
info.compress_type = zipfile.ZIP_DEFLATED
info.external_attr = 0o755 << 16
with zipfile.ZipFile(output, "w") as archive:
    archive.writestr(info, library.read_bytes())
PY

python3 "$ROOT/scripts/package-ycore-native-aar.py" \
  --gpu-only \
  --readelf "$READELF" \
  "$SOURCE_AAR" \
  "$OUTPUT_DIR/ycore-gpu.aar" \
  "$PROVENANCE"

echo "[ycore-gpu] wrote $OUTPUT_DIR/ycore-gpu.aar"
