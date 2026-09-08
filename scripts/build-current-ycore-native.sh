#!/usr/bin/env bash
# Rebuild current YCore JNI against the verified compatibility carrier, reusing only native dependencies.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/.native-build"
UPSTREAM="$WORK/yfuse-mpv/source"
PREFIX="$UPSTREAM/buildscripts/prefix/arm64-v8a"
DEPENDENCIES="$WORK/ycore-dependencies.sources.txt"
STAMP="$WORK/ycore-dependencies.key"
LIBS="$ROOT/composeApp/libs"
ARTIFACTS="$WORK/artifacts"
NDK_VERSION="29.0.14206865"

fail() { echo "[current-ycore] $*" >&2; exit 1; }
manifest_value() { awk -F= -v key="$1" '$1 == key { print substr($0, index($0, "=") + 1); exit }' "$2"; }

[[ -f "$LIBS/libmpv-release.aar" && -f "$LIBS/libmpv-release.sources.txt" ]] ||
  fail "run scripts/fetch-engines.sh before preparing current YCore"
expected="$(awk '$2 == "libmpv-release.aar" { print tolower($1) }' "$ROOT/scripts/engine-checksums.sha256")"
actual="$(sha256sum "$LIBS/libmpv-release.aar" | awk '{ print tolower($1) }')"
[[ "$expected" =~ ^[0-9a-f]{64}$ && "$actual" == "$expected" ]] || fail "compatibility carrier is not the pinned release"

# This key deliberately excludes YCore renderer/demux sources. Those are compiled on every run;
# the cache contains only pinned headers and static dependencies, never a previous JNI output.
dependency_key="$(
  cd "$ROOT"
  sha256sum scripts/build-yfuse-mpv-bluray.sh scripts/build-yfuse-mpv-dolby.sh \
    scripts/native/patch_yfuse*.py scripts/engine-checksums.sha256 scripts/yfuse-mpv-sources.txt |
    sha256sum | awk '{ print $1 }'
)"
if [[ ! -f "$STAMP" || "$(cat "$STAMP")" != "$dependency_key" || ! -f "$DEPENDENCIES" ||
      ! -f "$PREFIX/lib/libass.a" || ! -f "$PREFIX/include/libavformat/avformat.h" ]]; then
  echo '[current-ycore] dependency cache is cold; bootstrapping the pinned Android native toolchain'
  bash "$ROOT/scripts/build-ycore-native.sh" --arch arm64
  cp "$ARTIFACTS/NATIVE-SOURCES.txt" "$DEPENDENCIES"
  printf '%s\n' "$dependency_key" > "$STAMP"
else
  echo '[current-ycore] reusing pinned native headers and static dependency cache'
fi

for key in ffmpeg libbluray libudfread; do
  built="$(manifest_value "$key" "$DEPENDENCIES")"
  published="$(manifest_value "$key" "$LIBS/libmpv-release.sources.txt")"
  [[ -n "$built" && "$built" == "$published" ]] || fail "cached $key differs from the compatibility carrier"
done
[[ "$(manifest_value libass "$DEPENDENCIES")" == '0.17.4' ]] || fail 'cached libass revision is unsupported'

# The dependency builder embeds its NDK location in pkg-config/cross files. A restored cache needs
# that same path, but the large SDK/NDK itself comes from the runner rather than the cache archive.
ndk_link="$UPSTREAM/buildscripts/sdk/android-sdk-linux/ndk/$NDK_VERSION"
if [[ ! -d "$ndk_link" ]]; then
  sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  [[ -d "$sdk/ndk/$NDK_VERSION" ]] || fail "install Android NDK $NDK_VERSION first"
  mkdir -p "$(dirname "$ndk_link")"
  ln -s "$sdk/ndk/$NDK_VERSION" "$ndk_link"
fi

mkdir -p "$ARTIFACTS"
cp "$LIBS/libmpv-release.aar" "$ARTIFACTS/libmpv-yfuse-bluray.aar"
cp "$DEPENDENCIES" "$ARTIFACTS/NATIVE-SOURCES.txt"
printf 'ycore-shared-carrier-sha256=%s\n' "$actual" >> "$ARTIFACTS/NATIVE-SOURCES.txt"
# Link AND package the exact published shared libraries. Recompiling FFmpeg from the same commit
# can produce different bytes; full packaging intentionally rejects that ambiguity.
python3 - "$LIBS/libmpv-release.aar" "$PREFIX/lib" <<'PY'
import pathlib
import sys
import zipfile

prefix = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(sys.argv[1]) as archive:
    abis = {name.split('/')[1] for name in archive.namelist() if name.endswith('/libavformat.so') and name.startswith('jni/')}
    if abis != {'arm64-v8a'}:
        raise SystemExit('The current carrier builder requires the pinned arm64 release')
    for name in archive.namelist():
        parts = name.split('/')
        if len(parts) == 3 and parts[:2] == ['jni', 'arm64-v8a'] and name.endswith('.so'):
            # YCore outputs are always produced from the current checkout below.
            if not parts[2].startswith('libycore_'):
                (prefix / parts[2]).write_bytes(archive.read(name))
PY
export YCORE_UPSTREAM_ROOT="$UPSTREAM"
bash "$ROOT/scripts/build-ycore-demux.sh"
bash "$ROOT/scripts/install-ycore-native.sh" "$ARTIFACTS"
# Neither the checked-in release pins nor the installed MPV carrier may be replaced by a local build.
[[ "$(sha256sum "$LIBS/libmpv-release.aar" | awk '{ print tolower($1) }')" == "$actual" ]] || fail 'compatibility carrier was modified'
echo '[current-ycore] current demux/ASS/GPU sources built and installed with unchanged compatibility dependencies'
