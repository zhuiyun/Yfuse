#!/usr/bin/env bash
# Adds Dolby Vision RPU/FEL gates on top of the existing optical-disc AAR verification.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_ARTIFACTS="$ROOT/.native-build/artifacts"
AAR="${1:-$DEFAULT_ARTIFACTS/libmpv-yfuse-bluray.aar}"
SHA_FILE="${2:-$AAR.sha256}"
SOURCES="${3:-$DEFAULT_ARTIFACTS/NATIVE-SOURCES.txt}"

EXPECTED_MPV_CORE="b955aa28f3dc93dc6b21485a0d5b7feb8e6dc10f"
EXPECTED_FFMPEG="b79d4c4c0a160fc46988e98505af6039a53ad53e"
EXPECTED_LIBPLACEBO="22ee762e8e0890fc54068beb670310f0edce7263"
CAPABILITY_CLASS="dev.yfuse.mpv.YfuseMpvCapabilities"

fail() { printf 'error: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"; }
manifest_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$SOURCES"
}

need unzip
need strings
need javap
need readelf

"$ROOT/scripts/verify-yfuse-mpv-bluray-aar.sh" "$AAR" "$SHA_FILE" "$SOURCES"

[[ "$(manifest_value mpv-core)" == "$EXPECTED_MPV_CORE" ]] || fail "unexpected mpv core revision"
[[ "$(manifest_value ffmpeg)" == "$EXPECTED_FFMPEG" ]] || fail "unexpected FFmpeg revision"
[[ "$(manifest_value libplacebo)" == "$EXPECTED_LIBPLACEBO" ]] || fail "unexpected libplacebo revision"
[[ "$(manifest_value dolby-vision-rpu)" == "true" ]] || fail "native provenance does not enable Dolby Vision RPU"
[[ "$(manifest_value dolby-vision-fel)" == "true" ]] || fail "native provenance does not enable Dolby Vision FEL"
[[ "$(manifest_value ffmpeg-dovi-split)" == "true" ]] || fail "FFmpeg dovi_split gate is missing"
[[ "$(manifest_value libplacebo-enhancement-layer)" == "true" ]] || fail "libplacebo enhancement-layer gate is missing"
[[ "$(manifest_value dolby-render-evidence)" == "YFUSE_DOVI_RPU_RENDERED,YFUSE_DOVI_FEL_COMPOSED" ]] ||
  fail "native provenance does not name the Dolby render evidence markers"
[[ "$(manifest_value dolby-runtime-jni)" == "true" ]] || fail "Dolby runtime JNI gate is missing"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
unzip -q "$AAR" -d "$stage/aar"

javap -classpath "$stage/aar/classes.jar" -private -constants "$CAPABILITY_CLASS" > "$stage/capabilities.txt"
grep -F 'DOLBY_VISION_RPU = true' "$stage/capabilities.txt" >/dev/null ||
  fail "capability marker does not prove DOLBY_VISION_RPU=true"
grep -F 'DOLBY_VISION_FEL = true' "$stage/capabilities.txt" >/dev/null ||
  fail "capability marker does not prove DOLBY_VISION_FEL=true"
grep -F "MPV_CORE_REVISION = \"$EXPECTED_MPV_CORE\"" "$stage/capabilities.txt" >/dev/null ||
  fail "capability marker mpv core revision mismatch"
grep -F "FFMPEG_REVISION = \"$EXPECTED_FFMPEG\"" "$stage/capabilities.txt" >/dev/null ||
  fail "capability marker FFmpeg revision mismatch"
grep -F "LIBPLACEBO_REVISION = \"$EXPECTED_LIBPLACEBO\"" "$stage/capabilities.txt" >/dev/null ||
  fail "capability marker libplacebo revision mismatch"
grep -F 'public static long dolbyVisionRuntimeGeneration();' "$stage/capabilities.txt" >/dev/null ||
  fail "capability marker is missing the runtime generation accessor"
grep -F 'public static int dolbyVisionRuntimeEvidence();' "$stage/capabilities.txt" >/dev/null ||
  fail "capability marker is missing the runtime evidence accessor"

arm64_mpv="$stage/aar/jni/arm64-v8a/libmpv.so"
arm64_player="$stage/aar/jni/arm64-v8a/libplayer.so"
[[ -f "$arm64_player" ]] || fail "AAR is missing arm64-v8a/libplayer.so"
strings "$arm64_mpv" | grep -F 'YFUSE_DOVI_RPU_RENDERED' >/dev/null ||
  fail "libmpv.so is missing post-render RPU evidence"
strings "$arm64_mpv" | grep -F 'YFUSE_DOVI_FEL_COMPOSED' >/dev/null ||
  fail "libmpv.so is missing post-render FEL evidence"
for symbol in yfuse_mpv_dolby_generation yfuse_mpv_dolby_evidence; do
  readelf -Ws "$arm64_mpv" | grep -F "$symbol" >/dev/null ||
    fail "libmpv.so is missing exported runtime symbol: $symbol"
done
for symbol in \
  Java_dev_yfuse_mpv_YfuseMpvCapabilities_nativeDolbyVisionGeneration \
  Java_dev_yfuse_mpv_YfuseMpvCapabilities_nativeDolbyVisionEvidence; do
  readelf -Ws "$arm64_player" | grep -F "$symbol" >/dev/null ||
    fail "libplayer.so is missing Dolby JNI symbol: $symbol"
done

printf 'Dolby Vision native gates verified\n'
printf 'mpv:       %s\n' "$EXPECTED_MPV_CORE"
printf 'FFmpeg:    %s\n' "$EXPECTED_FFMPEG"
printf 'libplacebo:%s\n' "$EXPECTED_LIBPLACEBO"
printf 'evidence:  rendered RPU + rendered P7 enhancement layer + JNI snapshot\n'
