#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ARTIFACTS="$ROOT/.native-build/artifacts"
AAR="${1:-$DEFAULT_ARTIFACTS/libmpv-yfuse-bluray.aar}"
SHA_FILE="${2:-$AAR.sha256}"
SOURCES="${3:-$DEFAULT_ARTIFACTS/NATIVE-SOURCES.txt}"
PAGE_ALIGNMENT=$((16 * 1024))

fail() {
  echo "[verify-ycore-native] $*" >&2
  exit 1
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

"$ROOT/scripts/verify-yfuse-mpv-bluray-aar.sh" "$AAR" "$SHA_FILE" "$SOURCES"

[[ "$(manifest_value ycore-demux)" == "true" ]] || fail "native provenance is missing ycore-demux=true"
[[ "$(manifest_value ycore-demux-ffmpeg)" == "n8.1" ]] || fail "YCore demux is not pinned to FFmpeg n8.1"
[[ "$(manifest_value ycore-demux-source)" == "scripts/native/ycore_demux_jni.cpp" ]] ||
  fail "native provenance points at an unexpected YCore demux source"

stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
unzip -q "$AAR" -d "$stage/aar"

mapfile -t bridges < <(find "$stage/aar/jni" -mindepth 2 -maxdepth 2 -type f -name 'libycore_demux.so' -print | sort)
(( ${#bridges[@]} > 0 )) || fail "AAR contains no libycore_demux.so"
[[ -f "$stage/aar/jni/arm64-v8a/libycore_demux.so" ]] || fail "AAR is missing arm64-v8a/libycore_demux.so"

for bridge in "${bridges[@]}"; do
  abi="$(basename "$(dirname "$bridge")")"
  readelf -Ws "$bridge" | grep -Fq 'JNI_OnLoad' || fail "$abi bridge does not export JNI_OnLoad"
  for dependency in libavformat.so libavcodec.so libavutil.so; do
    readelf -d "$bridge" | grep -Fq "Shared library: [$dependency]" ||
      fail "$abi bridge is not dynamically linked to $dependency"
  done
  if readelf -Ws "$bridge" | grep -Eq 'avcodec_(send_packet|receive_frame|send_frame|receive_packet)'; then
    fail "$abi bridge links FFmpeg decode/encode entry points; YCore enhanced demux must stay demux-only"
  fi
  verify_alignment "$bridge"

done

printf 'verified YCore demux bridge: %s\n' "$AAR"
printf 'ffmpeg: n8.1 shared ABI, demux-only JNI\n'
printf 'page alignment: all libycore_demux.so PT_LOAD >= 16 KiB\n'
