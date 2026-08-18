#!/usr/bin/env bash
# Verifies the concrete Yfuse libmpv/libbluray AAR before it is installed or promoted.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_ARTIFACTS="$ROOT/.native-build/artifacts"
AAR="${1:-$DEFAULT_ARTIFACTS/libmpv-yfuse-bluray.aar}"
SHA_FILE="${2:-$AAR.sha256}"
SOURCES="${3:-$DEFAULT_ARTIFACTS/NATIVE-SOURCES.txt}"

EXPECTED_MPV="fcf6745703dc1265bca88f12fee8fc355ddf251e"
EXPECTED_BLURAY="7d94f2660af5bfc16015291a03539329135c18f1"
EXPECTED_UDFREAD="139a2194525f2745b98a98e4d8fa627d07440176"
EXPECTED_CAPABILITY_CLASS="dev/yfuse/mpv/YfuseMpvCapabilities.class"
EXPECTED_REGISTRY_CLASS="dev/yfuse/mpv/YfuseBluRayRegistry.class"
EXPECTED_BDMV_REGISTRY_CLASS="dev/yfuse/mpv/YfuseBdmvRegistry.class"
ANDROID_PAGE_ALIGNMENT=$((16 * 1024))

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

sha256_of() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{ print tolower($1) }'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{ print tolower($1) }'
  else
    die "sha256sum or shasum is required"
  fi
}

manifest_value() {
  local key="$1"
  awk -F= -v key="$key" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$SOURCES"
}

verify_load_alignment() {
  local so="$1" load_count=0 line align_hex align_dec
  while IFS= read -r line; do
    [[ "$line" =~ ^[[:space:]]*LOAD[[:space:]] ]] || continue
    load_count=$((load_count + 1))
    align_hex="$(awk '{ print $NF }' <<<"$line")"
    [[ "$align_hex" =~ ^0x[0-9a-fA-F]+$ ]] || die "cannot parse PT_LOAD alignment for $(basename "$so"): $line"
    align_dec=$((align_hex))
    (( align_dec >= ANDROID_PAGE_ALIGNMENT )) || die "$(basename "$so") has PT_LOAD alignment $align_hex; Android 16 KiB requires >= 0x4000"
  done < <(readelf -lW "$so")
  (( load_count > 0 )) || die "$(basename "$so") contains no PT_LOAD segments"
}

need unzip
need readelf
need strings
[[ -f "$AAR" ]] || die "AAR not found: $AAR"
[[ -f "$SHA_FILE" ]] || die "SHA-256 sidecar not found: $SHA_FILE"
[[ -f "$SOURCES" ]] || die "native source manifest not found: $SOURCES"

expected_sha="$(awk 'NR == 1 { print tolower($1) }' "$SHA_FILE")"
[[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || die "invalid SHA-256 sidecar: $SHA_FILE"
actual_sha="$(sha256_of "$AAR")"
[[ "$actual_sha" == "$expected_sha" ]] || die "AAR SHA-256 mismatch: expected $expected_sha, got $actual_sha"

[[ "$(manifest_value libmpv-android)" == "$EXPECTED_MPV" ]] || die "unexpected libmpv-android source revision"
[[ "$(manifest_value libbluray)" == "$EXPECTED_BLURAY" ]] || die "unexpected libbluray source revision"
[[ "$(manifest_value libudfread)" == "$EXPECTED_UDFREAD" ]] || die "unexpected libudfread source revision"
[[ "$(manifest_value bdj_jar)" == "disabled" ]] || die "native provenance must state bdj_jar=disabled"
[[ "$(manifest_value remote-raw-bluray)" == "true" ]] || die "remote raw Blu-ray bridge is not enabled in provenance"
[[ "$(manifest_value bdmv-vfs)" == "true" ]] || die "BDMV bd_open_files bridge is not enabled in provenance"
[[ "$(manifest_value hdmv-menu)" == "true" ]] || die "HDMV menu bridge is not enabled in provenance"
[[ "$(manifest_value multi-angle)" == "true" ]] || die "Blu-ray multi-angle bridge is not enabled in provenance"
[[ "$(manifest_value capability-class)" == "$EXPECTED_CAPABILITY_CLASS" ]] || die "native provenance is missing the Yfuse capability marker"
[[ "$(manifest_value registry-class)" == "$EXPECTED_REGISTRY_CLASS" ]] || die "native provenance is missing the Yfuse remote registry marker"
[[ "$(manifest_value bdmv-registry-class)" == "$EXPECTED_BDMV_REGISTRY_CLASS" ]] || die "native provenance is missing the Yfuse BDMV registry marker"

staging="$(mktemp -d)"
trap 'rm -rf "${staging:-}"' EXIT
unzip -q "$AAR" -d "$staging/aar"
[[ -f "$staging/aar/AndroidManifest.xml" ]] || die "AAR is missing AndroidManifest.xml"
[[ -f "$staging/aar/classes.jar" ]] || die "AAR is missing classes.jar"
[[ -f "$staging/aar/jni/arm64-v8a/libmpv.so" ]] || die "AAR is missing arm64-v8a/libmpv.so"
for required_class in "$EXPECTED_CAPABILITY_CLASS" "$EXPECTED_REGISTRY_CLASS" "$EXPECTED_BDMV_REGISTRY_CLASS"; do
  unzip -l "$staging/aar/classes.jar" | grep -F "$required_class" >/dev/null ||
    die "AAR classes.jar is missing $required_class"
done

arm64_mpv="$staging/aar/jni/arm64-v8a/libmpv.so"
for prefix in \
  Java_dev_yfuse_mpv_YfuseBluRayRegistry \
  Java_dev_yfuse_mpv_YfuseBdmvRegistry; do
  for suffix in nativeRegister nativeUnregister nativeSelectAngle nativeSendMenuCommand nativeSelectMenuPoint; do
    symbol="${prefix}_${suffix}"
    readelf -Ws "$arm64_mpv" | grep -F "$symbol" >/dev/null || die "libmpv.so is missing JNI symbol: $symbol"
  done
done
strings "$arm64_mpv" | grep -F 'yfusebd' >/dev/null || die "libmpv.so is missing the yfusebd stream protocol"
strings "$arm64_mpv" | grep -F 'yfusebdmv' >/dev/null || die "libmpv.so is missing the yfusebdmv stream protocol"
strings "$arm64_mpv" | grep -F 'Yfuse remote Blu-ray source opened with libbluray' >/dev/null ||
  die "libmpv.so is missing the Yfuse libbluray stream implementation"

mapfile -t arm64_libs < <(find "$staging/aar/jni/arm64-v8a" -maxdepth 1 -type f -name '*.so' -print | sort)
(( ${#arm64_libs[@]} > 0 )) || die "AAR contains no arm64 native libraries"
for so in "${arm64_libs[@]}"; do
  readelf -h "$so" | grep -E 'Class:[[:space:]]+ELF64' >/dev/null || die "$(basename "$so") is not ELF64"
  readelf -h "$so" | grep -E 'Machine:[[:space:]]+(AArch64|ARM aarch64)' >/dev/null || die "$(basename "$so") is not AArch64"
  verify_load_alignment "$so"
done

printf 'verified: %s\n' "$AAR"
printf 'sha256:  %s\n' "$actual_sha"
printf 'native:  libbluray + libudfread + remote ISO + local BDMV VFS + HDMV menu + multi-angle; BD-J disabled\n'
printf 'abi:     arm64-v8a, all PT_LOAD alignments >= 16 KiB\n'
