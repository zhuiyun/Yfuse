#!/usr/bin/env bash
# Installs a libbluray-enabled AAR produced by build-yfuse-mpv-bluray.sh into composeApp/libs/.
# The sidecar manifest is consumed by Gradle/runtime capability gates; never create it by hand.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEFAULT_ARTIFACTS="$ROOT/.native-build/artifacts"
AAR="${1:-$DEFAULT_ARTIFACTS/libmpv-yfuse-bluray.aar}"
SHA_FILE="${2:-$AAR.sha256}"
SOURCES="${3:-$DEFAULT_ARTIFACTS/NATIVE-SOURCES.txt}"
LIBS="$ROOT/composeApp/libs"
DEST="$LIBS/libmpv-release.aar"
DEST_SHA="$LIBS/libmpv-release.aar.sha256"
DEST_SOURCES="$LIBS/libmpv-release.sources.txt"

EXPECTED_MPV="fcf6745703dc1265bca88f12fee8fc355ddf251e"
EXPECTED_BLURAY="7d94f2660af5bfc16015291a03539329135c18f1"
EXPECTED_UDFREAD="139a2194525f2745b98a98e4d8fa627d07440176"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

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

[[ -f "$AAR" ]] || die "AAR not found: $AAR"
[[ -f "$SHA_FILE" ]] || die "SHA-256 sidecar not found: $SHA_FILE"
[[ -f "$SOURCES" ]] || die "native source manifest not found: $SOURCES"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

expected_sha="$(awk 'NR == 1 { print tolower($1) }' "$SHA_FILE")"
[[ "$expected_sha" =~ ^[0-9a-f]{64}$ ]] || die "invalid SHA-256 sidecar: $SHA_FILE"
actual_sha="$(sha256_of "$AAR")"
[[ "$actual_sha" == "$expected_sha" ]] ||
  die "AAR SHA-256 mismatch: expected $expected_sha, got $actual_sha"

[[ "$(manifest_value libmpv-android)" == "$EXPECTED_MPV" ]] ||
  die "unexpected libmpv-android source revision"
[[ "$(manifest_value libbluray)" == "$EXPECTED_BLURAY" ]] ||
  die "unexpected libbluray source revision"
[[ "$(manifest_value libudfread)" == "$EXPECTED_UDFREAD" ]] ||
  die "unexpected libudfread source revision"
[[ "$(manifest_value bdj_jar)" == "disabled" ]] ||
  die "native AAR provenance must explicitly state bdj_jar=disabled"

listing="$(mktemp)"
staging="$(mktemp -d)"
trap 'rm -f "${listing:-}"; rm -rf "${staging:-}"' EXIT
unzip -l "$AAR" >"$listing"
for entry in AndroidManifest.xml classes.jar jni/arm64-v8a/libmpv.so; do
  grep -Fq "$entry" "$listing" || die "AAR is missing required entry: $entry"
done

# Extract only the ARM64 lib for ELF sanity checks; the app itself is arm64-only today.
unzip -p "$AAR" jni/arm64-v8a/libmpv.so >"$staging/libmpv.so"
[[ -s "$staging/libmpv.so" ]] || die "could not extract arm64 libmpv.so"
if command -v readelf >/dev/null 2>&1; then
  readelf -h "$staging/libmpv.so" | grep -Eq 'Class:[[:space:]]+ELF64' ||
    die "arm64 libmpv.so is not ELF64"
  readelf -h "$staging/libmpv.so" | grep -Eq 'Machine:[[:space:]]+(AArch64|ARM aarch64)' ||
    die "libmpv.so is not AArch64"
fi

mkdir -p "$LIBS"
tmp_aar="$(mktemp "$LIBS/.libmpv-release.aar.XXXXXX")"
tmp_sha="$(mktemp "$LIBS/.libmpv-release.sha256.XXXXXX")"
tmp_sources="$(mktemp "$LIBS/.libmpv-release.sources.XXXXXX")"
cp "$AAR" "$tmp_aar"
printf '%s  libmpv-release.aar\n' "$actual_sha" >"$tmp_sha"
cp "$SOURCES" "$tmp_sources"

# Sidecars first, AAR last: Gradle can only observe a new capability after the complete binary has
# been copied. A later verification task also checks all three before packaging.
mv -f "$tmp_sha" "$DEST_SHA"
mv -f "$tmp_sources" "$DEST_SOURCES"
mv -f "$tmp_aar" "$DEST"

printf 'installed: %s\n' "$DEST"
printf 'sha256:   %s\n' "$actual_sha"
printf 'capability: native Blu-ray (libbluray), BD-J disabled\n'
