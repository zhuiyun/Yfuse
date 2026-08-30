#!/usr/bin/env bash
set -euo pipefail

APK="${1:-}"
[[ -n "$APK" && -f "$APK" ]] || {
  echo "usage: $0 <native-only.apk>" >&2
  exit 2
}

entries="$(mktemp)"
stage="$(mktemp -d)"
trap 'rm -f "$entries"; rm -rf "$stage"' EXIT
unzip -Z1 "$APK" > "$entries"

if grep -E '^lib/[^/]+/(libycore_demux|libycore_gpu|libmpv|libplayer|libmdk|libavcodec|libavformat|libavutil|libswresample|libswscale)\.so$' "$entries" >/dev/null; then
  echo "error: system-native APK contains a forbidden player/demux/codec runtime" >&2
  grep -E '^lib/[^/]+/(libycore_demux|libycore_gpu|libmpv|libplayer|libmdk|libavcodec|libavformat|libavutil|libswresample|libswscale)\.so$' "$entries" >&2
  exit 1
fi
if grep -E '^lib/(armeabi-v7a|x86|x86_64)/' "$entries" >/dev/null; then
  echo "error: native-only APK contains an unsupported ABI" >&2
  exit 1
fi

unzip -q "$APK" 'lib/*/*.so' -d "$stage"
while IFS= read -r library; do
  count=0
  while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*LOAD[[:space:]] ]] || continue
    count=$((count + 1))
    alignment="$(awk '{ print $NF }' <<<"$line")"
    [[ "$alignment" =~ ^0x[0-9a-fA-F]+$ ]] || {
      echo "error: cannot parse PT_LOAD alignment for $library" >&2
      exit 1
    }
    (( alignment >= 16 * 1024 )) || {
      echo "error: $library is not 16 KiB page aligned" >&2
      exit 1
    }
  done < <(readelf -lW "$library")
  (( count > 0 )) || {
    echo "error: $library contains no PT_LOAD segments" >&2
    exit 1
  }
done < <(find "$stage/lib" -type f -name '*.so' -print | sort)

echo "verified system-native APK: external player/demux/codec runtimes absent, native libraries 16 KiB aligned"
