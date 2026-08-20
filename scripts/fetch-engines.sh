#!/usr/bin/env bash
# Fetches and verifies the native player-engine binaries in composeApp/libs/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/composeApp/libs"
CHECKSUMS="$ROOT/scripts/engine-checksums.sha256"

MPV_FILE="libmpv-release.aar"
MDK_FILE="mdk-sdk-android.7z"
MPV_RELEASE_TAG="native-mpv-fcf6745-yfuse2-arm64"
MPV_URL="https://github.com/zhuiyun/Yfuse/releases/download/$MPV_RELEASE_TAG/$MPV_FILE"
MDK_URL="https://github.com/wang-bin/mdk-sdk/releases/download/v0.37.0/$MDK_FILE"
MPV_CUSTOM_SHA="$LIBS/libmpv-release.aar.sha256"
MPV_CUSTOM_SOURCES="$LIBS/libmpv-release.sources.txt"
MPV_PINNED_SOURCES="$ROOT/scripts/yfuse-mpv-sources.txt"
MPV_VERIFIER="$ROOT/scripts/verify-yfuse-mpv-bluray-aar.sh"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

checksum_for() {
  local name="$1"
  local checksum
  checksum="$(awk -v file="$name" '$2 == file { print $1 }' "$CHECKSUMS")"
  [[ "$checksum" =~ ^[0-9a-fA-F]{64}$ ]] ||
    die "missing or invalid SHA-256 for $name in $CHECKSUMS"
  printf '%s\n' "${checksum,,}"
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

verify_file() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(sha256_of "$file")"
  [[ "$actual" == "$expected" ]] ||
    die "SHA-256 mismatch for $(basename "$file"): expected $expected, got $actual"
}

fetch_verified() {
  local name="$1"
  local url="$2"
  local expected="$3"
  local destination="$LIBS/$name"
  local temporary

  if [[ -f "$destination" ]]; then
    verify_file "$destination" "$expected"
    printf '==> %s already verified\n' "$name"
    return
  fi

  temporary="$(mktemp "$LIBS/.${name}.download.XXXXXX")"
  trap 'rm -f "${temporary:-}"' RETURN
  printf '==> downloading %s\n' "$name"
  curl \
    --fail \
    --location \
    --proto '=https' \
    --proto-redir '=https' \
    --retry 5 \
    --retry-all-errors \
    --retry-delay 2 \
    --connect-timeout 20 \
    --output "$temporary" \
    "$url"
  verify_file "$temporary" "$expected"
  mv -f "$temporary" "$destination"
  trap - RETURN
}

install_custom_mpv_sidecars() {
  local expected="$1"
  [[ -r "$MPV_PINNED_SOURCES" ]] || die "pinned Yfuse mpv source manifest is missing"
  [[ -x "$MPV_VERIFIER" ]] || chmod +x "$MPV_VERIFIER"
  printf '%s  %s\n' "$expected" "$MPV_FILE" >"$MPV_CUSTOM_SHA"
  cp -f "$MPV_PINNED_SOURCES" "$MPV_CUSTOM_SOURCES"
  "$MPV_VERIFIER" "$LIBS/$MPV_FILE" "$MPV_CUSTOM_SHA" "$MPV_CUSTOM_SOURCES"
}

extract_mdk() {
  local archive="$LIBS/$MDK_FILE"
  local seven_zip=""
  local staging
  local previous=""

  if command -v 7z >/dev/null 2>&1; then
    seven_zip="$(command -v 7z)"
  elif [[ -x "/c/Program Files/7-Zip/7z.exe" ]]; then
    seven_zip="/c/Program Files/7-Zip/7z.exe"
  else
    die "7z is required to extract $MDK_FILE"
  fi

  staging="$(mktemp -d "$LIBS/.mdk-sdk.extract.XXXXXX")"
  trap 'rm -rf "${staging:-}"' RETURN
  "$seven_zip" x -y "-o$staging" "$archive" >/dev/null
  [[ -f "$staging/mdk-sdk/lib/cmake/FindMDK.cmake" ]] ||
    die "the verified MDK archive does not contain the expected SDK layout"
  if [[ -d "$LIBS/mdk-sdk" ]]; then
    previous="$(mktemp -d "$LIBS/.mdk-sdk.previous.XXXXXX")"
    rmdir "$previous"
    mv "$LIBS/mdk-sdk" "$previous"
  fi
  if ! mv "$staging/mdk-sdk" "$LIBS/mdk-sdk"; then
    [[ -z "$previous" || ! -d "$previous" ]] || mv "$previous" "$LIBS/mdk-sdk"
    die "failed to install the verified MDK SDK"
  fi
  [[ -z "$previous" || ! -d "$previous" ]] || rm -rf "$previous"
  rm -rf "$staging"
  trap - RETURN
}

[[ -r "$CHECKSUMS" ]] || die "checksum manifest not found: $CHECKSUMS"
mkdir -p "$LIBS"

mpv_checksum="$(checksum_for "$MPV_FILE")"
fetch_verified "$MPV_FILE" "$MPV_URL" "$mpv_checksum"
install_custom_mpv_sidecars "$mpv_checksum"
fetch_verified "$MDK_FILE" "$MDK_URL" "$(checksum_for "$MDK_FILE")"
extract_mdk

printf 'done: verified native engines in %s\n' "$LIBS"
