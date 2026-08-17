#!/usr/bin/env bash
# Builds the Yfuse libmpv variant with libbluray linked in for local ISO/BDMV title access.
#
# This intentionally disables the BD-J JAR. It is the native Blu-ray/title/HDMV foundation only;
# Android BD-J needs a separately verified Java runtime/provider and must not be inferred from this AAR.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK_ROOT="${YFUSE_MPV_WORK_ROOT:-$ROOT/.native-build/yfuse-mpv}"
OUT_DIR="${YFUSE_MPV_OUT_DIR:-$ROOT/.native-build/artifacts}"
UPSTREAM_REPO="https://github.com/jarnedemeulemeester/libmpv-android.git"
UPSTREAM_COMMIT="fcf6745703dc1265bca88f12fee8fc355ddf251e" # v1.0.0
LIBBLURAY_REPO="https://code.videolan.org/videolan/libbluray.git"
LIBBLURAY_TAG="1.4.1"
LIBBLURAY_COMMIT_PREFIX="7d94f266"

ARCH_ARGS=()
if [[ $# -gt 0 ]]; then
  ARCH_ARGS=("$@")
fi

need() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'error: required command not found: %s\n' "$1" >&2
    exit 1
  }
}

for tool in git python3 meson ninja unzip sha256sum; do
  need "$tool"
done

rm -rf "$WORK_ROOT"
mkdir -p "$WORK_ROOT" "$OUT_DIR"

printf '==> cloning pinned libmpv-android %s\n' "$UPSTREAM_COMMIT"
git init -q "$WORK_ROOT/source"
git -C "$WORK_ROOT/source" remote add origin "$UPSTREAM_REPO"
git -C "$WORK_ROOT/source" fetch -q --depth 1 origin "$UPSTREAM_COMMIT"
git -C "$WORK_ROOT/source" checkout -q --detach FETCH_HEAD
[[ "$(git -C "$WORK_ROOT/source" rev-parse HEAD)" == "$UPSTREAM_COMMIT" ]] || {
  echo 'error: libmpv-android commit mismatch' >&2
  exit 1
}

SOURCE="$WORK_ROOT/source"
DEPINFO="$SOURCE/buildscripts/include/depinfo.sh"
DOWNLOAD_DEPS="$SOURCE/buildscripts/include/download-deps.sh"
LIBBLURAY_BUILD="$SOURCE/buildscripts/scripts/libbluray.sh"

python3 - "$DEPINFO" "$DOWNLOAD_DEPS" <<'PY'
from pathlib import Path
import sys

depinfo = Path(sys.argv[1])
download = Path(sys.argv[2])

text = depinfo.read_text()
needle = "v_mpv=0.41.0\n"
if needle not in text:
    raise SystemExit("unexpected upstream depinfo: mpv version anchor missing")
text = text.replace(needle, needle + "v_libbluray=1.4.1\n", 1)
needle = "dep_mpv=(ffmpeg libass lua libplacebo)"
if needle not in text:
    raise SystemExit("unexpected upstream depinfo: dependency anchor missing")
text = text.replace(
    needle,
    "dep_libbluray=(libxml2 freetype)\n" +
    "dep_mpv=(ffmpeg libass lua libplacebo libbluray)",
    1,
)
depinfo.write_text(text)

text = download.read_text()
anchor = "# mpv\n[ ! -d mpv ] && git clone"
if anchor not in text:
    raise SystemExit("unexpected upstream download-deps: mpv anchor missing")
bluray = (
    "# libbluray — pinned VideoLAN source; BD-J JAR stays disabled in the build script.\n"
    "if [ ! -d libbluray ]; then\n"
    "\tgit clone --depth 1 --branch $v_libbluray https://code.videolan.org/videolan/libbluray.git libbluray\n"
    "\tcase \"$(git -C libbluray rev-parse HEAD)\" in\n"
    "\t\t7d94f266*) ;;\n"
    "\t\t*) echo 'libbluray tag resolved to an unexpected commit' >&2; exit 1 ;;\n"
    "\tesac\n"
    "fi\n\n"
)
text = text.replace("# mpv\n", bluray + "# mpv\n", 1)
download.write_text(text)
PY

cat >"$LIBBLURAY_BUILD" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
. ../../include/depinfo.sh
. ../../include/path.sh

build=_build$ndk_suffix
case "${1:-}" in
  build) ;;
  clean) rm -rf "$build"; exit 0 ;;
  *) exit 255 ;;
esac

# Meson cross-file already forces static dependencies and Android toolchains. Keeping BD-J JAR
# disabled is intentional: native libbluray access is useful on Android, while claiming a J2SE JVM
# exists inside ART would be incorrect. A future BD-J provider must be built/tested independently.
unset CC CXX
meson setup "$build" --cross-file "$prefix_dir/crossfile.txt" \
  -Dbdj_jar=disabled
ninja -C "$build" -j"$cores"
DESTDIR="$prefix_dir" ninja -C "$build" install
SH
chmod +x "$LIBBLURAY_BUILD"

printf '==> downloading pinned native dependencies\n'
(
  cd "$SOURCE/buildscripts"
  ./download.sh
)

# Double-check the VideoLAN tag after download; the short commit is recorded by the 1.4.1 release.
BLURAY_HEAD="$(git -C "$SOURCE/buildscripts/deps/libbluray" rev-parse HEAD)"
case "$BLURAY_HEAD" in
  "$LIBBLURAY_COMMIT_PREFIX"*) ;;
  *)
    printf 'error: libbluray %s resolved to unexpected commit %s\n' "$LIBBLURAY_TAG" "$BLURAY_HEAD" >&2
    exit 1
    ;;
esac

printf '==> building libmpv + libbluray\n'
(
  cd "$SOURCE/buildscripts"
  if [[ ${#ARCH_ARGS[@]} -gt 0 ]]; then
    ./build.sh "${ARCH_ARGS[@]}"
  else
    ./build.sh
  fi
)

AAR="$SOURCE/libmpv/build/outputs/aar/libmpv-release.aar"
[[ -f "$AAR" ]] || {
  echo 'error: libmpv-release.aar was not produced' >&2
  exit 1
}

# mpv generates HAVE_LIBBLURAY only when Meson actually found and linked the dependency. This gate
# prevents an AAR that merely ran the patch script from being promoted as Blu-ray-capable.
if ! grep -RqsE '^#define HAVE_LIBBLURAY[[:space:]]+1$' "$SOURCE/buildscripts/deps/mpv"/_build*/config.h; then
  echo 'error: mpv build did not enable HAVE_LIBBLURAY' >&2
  exit 1
fi

TMP_LIST="$WORK_ROOT/aar-list.txt"
unzip -l "$AAR" >"$TMP_LIST"
if ! grep -q 'jni/arm64-v8a/libmpv.so' "$TMP_LIST"; then
  echo 'error: AAR has no arm64-v8a libmpv.so' >&2
  exit 1
fi

DEST="$OUT_DIR/libmpv-yfuse-bluray.aar"
cp -f "$AAR" "$DEST"
sha256sum "$DEST" | tee "$DEST.sha256"
printf 'done: %s\n' "$DEST"
