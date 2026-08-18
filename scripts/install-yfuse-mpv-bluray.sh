#!/usr/bin/env bash
# Installs a verified libbluray-enabled AAR into composeApp/libs/.
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
VERIFIER="$ROOT/scripts/verify-yfuse-mpv-bluray-aar.sh"

[[ -x "$VERIFIER" ]] || chmod +x "$VERIFIER"
"$VERIFIER" "$AAR" "$SHA_FILE" "$SOURCES"

if command -v sha256sum >/dev/null 2>&1; then
  actual_sha="$(sha256sum "$AAR" | awk '{ print tolower($1) }')"
elif command -v shasum >/dev/null 2>&1; then
  actual_sha="$(shasum -a 256 "$AAR" | awk '{ print tolower($1) }')"
else
  printf 'error: sha256sum or shasum is required\n' >&2
  exit 1
fi

mkdir -p "$LIBS"
tmp_aar="$(mktemp "$LIBS/.libmpv-release.aar.XXXXXX")"
tmp_sha="$(mktemp "$LIBS/.libmpv-release.sha256.XXXXXX")"
tmp_sources="$(mktemp "$LIBS/.libmpv-release.sources.XXXXXX")"
trap 'rm -f "${tmp_aar:-}" "${tmp_sha:-}" "${tmp_sources:-}"' EXIT

cp "$AAR" "$tmp_aar"
printf '%s  libmpv-release.aar\n' "$actual_sha" >"$tmp_sha"
cp "$SOURCES" "$tmp_sources"

# Sidecars first, AAR last. Runtime capability still comes from a marker embedded in the AAR itself,
# so stale metadata can never make the stock binary appear Blu-ray capable.
mv -f "$tmp_sha" "$DEST_SHA"
mv -f "$tmp_sources" "$DEST_SOURCES"
mv -f "$tmp_aar" "$DEST"
trap - EXIT

printf 'installed: %s\n' "$DEST"
printf 'sha256:   %s\n' "$actual_sha"
printf 'capability: native Blu-ray ISO + BDMV VFS + HDMV menu; BD-J disabled\n'
