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

legacy_native='^lib/[^/]+/(libmpv|libplayer|libmdk)\.so$'
if grep -E "$legacy_native" "$entries" >/dev/null; then
  echo "error: native-only APK contains a forbidden legacy player runtime" >&2
  grep -E "$legacy_native" "$entries" >&2
  exit 1
fi

for required in libycore_demux.so libycore_gpu.so libavcodec.so libavformat.so libavutil.so; do
  if ! grep -E "^lib/[^/]+/$required$" "$entries" >/dev/null; then
    echo "error: native-only APK is missing required YCore runtime $required" >&2
    exit 1
  fi
done
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

apkanalyzer_bin="${APKANALYZER:-}"
if [[ -z "$apkanalyzer_bin" ]]; then
  apkanalyzer_bin="$(command -v apkanalyzer || true)"
fi
if [[ -z "$apkanalyzer_bin" ]]; then
  for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
    [[ -n "$sdk_root" ]] || continue
    candidate="$sdk_root/cmdline-tools/latest/bin/apkanalyzer"
    if [[ -x "$candidate" ]]; then
      apkanalyzer_bin="$candidate"
      break
    fi
  done
fi
[[ -n "$apkanalyzer_bin" && -x "$apkanalyzer_bin" ]] || {
  echo "error: apkanalyzer is required to prove that legacy runtime classes are absent" >&2
  exit 1
}

defined_classes="$stage/defined-classes.txt"
"$apkanalyzer_bin" dex packages --defined-only "$APK" > "$defined_classes"
for forbidden_package in androidx.media3.exoplayer dev.jdtech.mpv com.mediadevkit.sdk; do
  # apkanalyzer also prints method signatures, whose parameter types may mention a compile-only
  # API. Only P/C records prove that the forbidden package or one of its classes is defined in
  # the APK; matching arbitrary signature text would reject valid compile-only isolation.
  escaped_package="${forbidden_package//./\\.}"
  defined_pattern="^[PC][[:space:]]+d[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+${escaped_package}([.[:space:]]|$)"
  if grep -E "$defined_pattern" "$defined_classes" >/dev/null; then
    echo "error: native-only APK defines forbidden playback runtime $forbidden_package" >&2
    grep -E "$defined_pattern" "$defined_classes" | head -n 20 >&2
    exit 1
  fi
done

echo "verified native-only APK: dependency-closed YCore present, legacy native and DEX runtimes absent"
