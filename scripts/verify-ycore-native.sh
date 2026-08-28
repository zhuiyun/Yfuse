#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ARTIFACTS="$ROOT/.native-build/artifacts"
AAR="${1:-$DEFAULT_ARTIFACTS/ycore-native.aar}"
SHA_FILE="${2:-$AAR.sha256}"
SOURCES="${3:-$DEFAULT_ARTIFACTS/NATIVE-SOURCES.txt}"
PAGE_ALIGNMENT=$((16 * 1024))

fail() {
  echo "[verify-ycore-native] $*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

manifest_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$SOURCES"
}

verify_alignment() {
  local so="$1" count=0 line align_hex align_dec
  while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*LOAD[[:space:]] ]] || continue
    count=$((count + 1))
    align_hex="$(awk '{ print $NF }' <<<"$line")"
    [[ "$align_hex" =~ ^0x[0-9a-fA-F]+$ ]] || fail "cannot parse PT_LOAD alignment for $so"
    align_dec=$((align_hex))
    (( align_dec >= PAGE_ALIGNMENT )) || fail "$so PT_LOAD alignment $align_hex is below 16 KiB"
  done < <(readelf -lW "$so")
  (( count > 0 )) || fail "$so contains no PT_LOAD segments"
}

is_android_system_library() {
  case "$1" in
    libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libz.so|libjnigraphics.so|\
      libmediandk.so|libOpenSLES.so|libEGL.so|libGLESv2.so|libGLESv3.so|libvulkan.so)
      return 0
      ;;
    *) return 1 ;;
  esac
}

for command in awk cmp find readelf sha256sum strings unzip; do
  need "$command"
done
[[ -f "$AAR" ]] || fail "missing standalone YCore AAR: $AAR"
[[ -f "$SHA_FILE" ]] || fail "missing YCore checksum: $SHA_FILE"
[[ -f "$SOURCES" ]] || fail "missing YCore provenance: $SOURCES"

expected_sha="$(awk 'NR == 1 { print tolower($1) }' "$SHA_FILE")"
actual_sha="$(sha256sum "$AAR" | awk '{ print tolower($1) }')"
[[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || fail "invalid YCore SHA-256 sidecar"
[[ "$actual_sha" == "$expected_sha" ]] || fail "YCore AAR SHA-256 mismatch"

FFMPEG_REVISION="$(manifest_value ffmpeg)"
[[ "$FFMPEG_REVISION" =~ ^[0-9a-f]{40}$ ]] || fail "native provenance is missing the pinned FFmpeg commit"
[[ "$(manifest_value ycore-demux)" == "true" ]] || fail "native provenance is missing ycore-demux=true"
[[ "$(manifest_value ycore-demux-ffmpeg)" == "$FFMPEG_REVISION" ]] ||
  fail "YCore was not built against the pinned FFmpeg revision"
[[ "$(manifest_value ycore-demux-source)" == "scripts/native/ycore_demux_jni.cpp" ]] ||
  fail "native provenance points at an unexpected YCore source"
[[ "$(manifest_value ycore-software-decoder-api)" == "2" ]] ||
  fail "YCore software decoder API v2 is missing"
[[ "$(manifest_value ycore-tone-map-source)" == "scripts/native/ycore_tone_map.h" ]] ||
  fail "YCore HDR tone-map provenance is missing"
[[ "$(manifest_value ycore-disc-api)" == "2" ]] || fail "YCore disc API v2 (HDMV overlay/input) is missing"
[[ "$(manifest_value ycore-libbluray)" == "1.4.1" ]] || fail "unexpected libbluray revision"
[[ "$(manifest_value ycore-disc-uri-source)" == "scripts/native/ycore_disc_uri.h" ]] ||
  fail "YCore disc URI boundary provenance is missing"
[[ "$(manifest_value ycore-gpu-api)" == "2" ]] || fail "YCore GPU API v2 is missing"
[[ "$(manifest_value ycore-gpu-source)" == "scripts/native/ycore_vulkan_jni.cpp" ]] ||
  fail "YCore Vulkan source provenance is missing"
[[ "$(manifest_value ycore-gpu-renderer-source)" == "scripts/native/ycore_vulkan_renderer.cpp" ]] ||
  fail "YCore Vulkan renderer provenance is missing"
[[ "$(manifest_value ycore-gpu-vertex-shader)" == "scripts/native/shaders/ycore_fullscreen.vert" ]] ||
  fail "YCore vertex shader provenance is missing"
[[ "$(manifest_value ycore-gpu-fragment-shader)" == "scripts/native/shaders/ycore_video.frag" ]] ||
  fail "YCore fragment shader provenance is missing"
[[ "$(manifest_value ycore-gpu-capability-source)" == "scripts/native/ycore_gpu_capability.h" ]] ||
  fail "YCore GPU truth-gate provenance is missing"
[[ "$(manifest_value ycore-gpu-frame-ring)" == "3" ]] || fail "YCore GPU frame ring is missing"
[[ "$(manifest_value ycore-gpu-import-cache)" == "12" ]] || fail "YCore GPU import cache is missing"
[[ "$(manifest_value ycore-gpu-hdr10plus)" == "per-frame-st2094-40" ]] ||
  fail "YCore per-frame HDR10+ provenance is missing"
[[ "$(manifest_value ycore-native-aar)" == "true" ]] || fail "standalone YCore AAR marker is missing"
[[ "$(manifest_value ycore-native-entry)" == "libycore_demux.so" ]] ||
  fail "unexpected standalone YCore entry library"
[[ "$(manifest_value ycore-gpu-entry)" == "libycore_gpu.so" ]] ||
  fail "unexpected standalone YCore GPU entry library"
[[ "$(manifest_value ycore-native-forbidden)" == "libmpv.so,libplayer.so,libmdk.so" ]] ||
  fail "standalone YCore legacy-runtime exclusion marker is missing"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
unzip -q "$AAR" -d "$stage/aar"

[[ -f "$stage/aar/AndroidManifest.xml" ]] || fail "AAR is missing AndroidManifest.xml"
[[ -f "$stage/aar/classes.jar" ]] || fail "AAR is missing classes.jar"
[[ -f "$stage/aar/META-INF/ycore-native-sources.txt" ]] ||
  fail "AAR is missing embedded provenance"
[[ -f "$stage/aar/META-INF/NOTICE" ]] || fail "AAR is missing third-party notices"
cmp -s "$SOURCES" "$stage/aar/META-INF/ycore-native-sources.txt" ||
  fail "embedded YCore provenance differs from its sidecar"
unzip -Z1 "$stage/aar/classes.jar" > "$stage/classes.txt"
if grep -E '\.class$' "$stage/classes.txt" >/dev/null; then
  fail "standalone YCore carrier must not contain Java engine classes"
fi

for forbidden in libmpv.so libplayer.so libmdk.so; do
  if find "$stage/aar/jni" -type f -name "$forbidden" -print -quit | grep -q .; then
    fail "standalone YCore AAR contains forbidden legacy runtime $forbidden"
  fi
done

mapfile -t bridges < <(find "$stage/aar/jni" -mindepth 2 -maxdepth 2 -type f -name 'libycore_demux.so' -print | sort)
(( ${#bridges[@]} > 0 )) || fail "AAR contains no libycore_demux.so"
[[ -f "$stage/aar/jni/arm64-v8a/libycore_demux.so" ]] ||
  fail "AAR is missing arm64-v8a/libycore_demux.so"
[[ -f "$stage/aar/jni/arm64-v8a/libycore_gpu.so" ]] ||
  fail "AAR is missing arm64-v8a/libycore_gpu.so"

for bridge in "${bridges[@]}"; do
  abi="$(basename "$(dirname "$bridge")")"
  symbols="$stage/$abi-ycore-symbols.txt"
  dynamic="$stage/$abi-ycore-dynamic.txt"
  bridge_strings="$stage/$abi-ycore-strings.txt"
  readelf -Ws "$bridge" > "$symbols"
  readelf -dW "$bridge" > "$dynamic"
  strings "$bridge" > "$bridge_strings"

  grep -F 'JNI_OnLoad' "$symbols" >/dev/null || fail "$abi bridge does not export JNI_OnLoad"
  for dependency in libavformat.so libavcodec.so libavutil.so libswscale.so libswresample.so; do
    grep -F "Shared library: [$dependency]" "$dynamic" >/dev/null ||
      fail "$abi bridge is not dynamically linked to $dependency"
  done
  for symbol in avcodec_send_packet avcodec_receive_frame; do
    grep -F "$symbol" "$symbols" >/dev/null ||
      fail "$abi bridge is missing software-decode symbol $symbol"
  done
  if grep -E 'avcodec_(send_frame|receive_packet)' "$symbols" >/dev/null; then
    fail "$abi bridge unexpectedly links FFmpeg encode entry points"
  fi
  grep -E 'bd_(open|open_files)' "$symbols" >/dev/null ||
    fail "$abi bridge is not linked to libbluray navigation"
  grep -F 'ycorebd://' "$bridge_strings" >/dev/null ||
    fail "$abi bridge is missing the opaque Blu-ray URI boundary"
done

mapfile -t gpu_bridges < <(find "$stage/aar/jni" -mindepth 2 -maxdepth 2 -type f -name 'libycore_gpu.so' -print | sort)
(( ${#gpu_bridges[@]} > 0 )) || fail "AAR contains no libycore_gpu.so"
for bridge in "${gpu_bridges[@]}"; do
  abi="$(basename "$(dirname "$bridge")")"
  symbols="$stage/$abi-ycore-gpu-symbols.txt"
  dynamic="$stage/$abi-ycore-gpu-dynamic.txt"
  readelf -Ws "$bridge" > "$symbols"
  readelf -dW "$bridge" > "$dynamic"
  for dependency in libandroid.so libvulkan.so; do
    grep -F "Shared library: [$dependency]" "$dynamic" >/dev/null ||
      fail "$abi GPU bridge is not dynamically linked to $dependency"
  done
  grep -F 'nativeProbeGpuFeatures' "$symbols" >/dev/null ||
    fail "$abi GPU bridge is missing the Vulkan/AHardwareBuffer probe"
  grep -F 'nativeCreateRenderer' "$symbols" >/dev/null ||
    fail "$abi GPU bridge is missing the Vulkan swapchain renderer"
  grep -F 'nativeRenderHardwareBuffer' "$symbols" >/dev/null ||
    fail "$abi GPU bridge is missing decoded HardwareBuffer rendering"
done

mapfile -t native_libraries < <(find "$stage/aar/jni" -mindepth 2 -maxdepth 2 -type f -name '*.so' -print | sort)
(( ${#native_libraries[@]} > 0 )) || fail "AAR contains no native libraries"
for library in "${native_libraries[@]}"; do
  abi_dir="$(dirname "$library")"
  dynamic="$stage/$(basename "$abi_dir")-$(basename "$library")-dynamic.txt"
  readelf -dW "$library" > "$dynamic"
  while IFS= read -r dependency; do
    is_android_system_library "$dependency" && continue
    [[ -f "$abi_dir/$dependency" ]] ||
      fail "$(basename "$library") links unpackaged dependency $dependency"
  done < <(sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' "$dynamic")
  verify_alignment "$library"
done

printf 'verified standalone YCore runtime: %s\n' "$AAR"
printf 'FFmpeg: %s; demux + software decode + HDR tone map\n' "$FFMPEG_REVISION"
printf 'Blu-ray: libbluray 1.4.1 through opaque YCore block I/O\n'
printf 'GPU: ImageReader/AHardwareBuffer + Vulkan YCbCr shader + measured swapchain presentation\n'
printf 'purity: no mpv, player, MDK, or Java engine classes\n'
printf 'page alignment: all packaged native libraries PT_LOAD >= 16 KiB\n'
