#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE="$ROOT/.native-build"
ARTIFACTS="$WORKSPACE/artifacts"
AAR="$ARTIFACTS/libmpv-yfuse-bluray.aar"
SOURCE="$ROOT/scripts/native/ycore_demux_jni.cpp"
NDK_VERSION="29.0.14206865"
ANDROID_API="26"
MAX_PAGE_SIZE="16384"

fail() {
  echo "[ycore-demux] $*" >&2
  exit 1
}

# build-ycore-native.sh builds libmpv first via build-yfuse-mpv-bluray.sh. That script keeps its
# pinned checkout under .native-build/yfuse-mpv/source. Reuse the exact same FFmpeg prefix and NDK
# tree so the demux bridge is compiled against the libraries that are packaged into the AAR.
# Keep the legacy .native-build/upstream fallback for older/local workspaces and allow an explicit
# override for reproducible debugging.
if [[ -n "${YCORE_UPSTREAM_ROOT:-}" ]]; then
  UPSTREAM="$YCORE_UPSTREAM_ROOT"
elif [[ -d "$WORKSPACE/yfuse-mpv/source/buildscripts/prefix" ]]; then
  UPSTREAM="$WORKSPACE/yfuse-mpv/source"
elif [[ -d "$WORKSPACE/upstream/buildscripts/prefix" ]]; then
  UPSTREAM="$WORKSPACE/upstream"
else
  fail "missing upstream FFmpeg prefix tree"
fi

[[ -f "$AAR" ]] || fail "missing native AAR: $AAR"
[[ -f "$SOURCE" ]] || fail "missing JNI source: $SOURCE"
[[ -d "$UPSTREAM/buildscripts/prefix" ]] || fail "missing upstream FFmpeg prefix tree: $UPSTREAM/buildscripts/prefix"

echo "[ycore-demux] reusing native workspace: $UPSTREAM"

TOOLCHAIN_ROOT=("$UPSTREAM"/buildscripts/sdk/android-sdk-linux/ndk/"$NDK_VERSION"/toolchains/llvm/prebuilt/*)
[[ ${#TOOLCHAIN_ROOT[@]} -eq 1 && -d "${TOOLCHAIN_ROOT[0]}" ]] || fail "cannot resolve NDK toolchain"
TOOLCHAIN="${TOOLCHAIN_ROOT[0]}"

mapfile -t ABIS < <(
  unzip -Z1 "$AAR" |
    sed -n 's#^jni/\([^/]*\)/libavformat\.so$#\1#p' |
    sort -u
)
[[ ${#ABIS[@]} -gt 0 ]] || fail "AAR contains no FFmpeg libavformat.so"

STAGE="$(mktemp -d "$WORKSPACE/ycore-demux.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/libs"

compiler_for_abi() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android${ANDROID_API}-clang++" ;;
    armeabi-v7a) echo "armv7a-linux-androideabi${ANDROID_API}-clang++" ;;
    x86) echo "i686-linux-android${ANDROID_API}-clang++" ;;
    x86_64) echo "x86_64-linux-android${ANDROID_API}-clang++" ;;
    *) fail "unsupported AAR ABI: $1" ;;
  esac
}

for ABI in "${ABIS[@]}"; do
  PREFIX="$UPSTREAM/buildscripts/prefix/$ABI"
  [[ -d "$PREFIX/include/libavformat" ]] || fail "missing FFmpeg headers for $ABI"
  [[ -f "$PREFIX/lib/libavformat.so" ]] || fail "missing FFmpeg shared library for $ABI"
  CXX="$TOOLCHAIN/bin/$(compiler_for_abi "$ABI")"
  [[ -x "$CXX" ]] || fail "missing compiler for $ABI: $CXX"

  OUT_DIR="$STAGE/libs/$ABI"
  mkdir -p "$OUT_DIR"
  OUT="$OUT_DIR/libycore_demux.so"
  echo "[ycore-demux] compiling $ABI"
  "$CXX" \
    -shared \
    -fPIC \
    -O2 \
    -std=c++17 \
    -fvisibility=hidden \
    -I"$PREFIX/include" \
    "$SOURCE" \
    -L"$PREFIX/lib" \
    -Wl,--no-undefined \
    -Wl,-z,max-page-size="$MAX_PAGE_SIZE" \
    -Wl,-soname,libycore_demux.so \
    -lavformat \
    -lavcodec \
    -lavutil \
    -o "$OUT"

  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$OUT"
done

export YCORE_AAR="$AAR"
export YCORE_STAGE="$STAGE/libs"
python3 - <<'PY'
import os
import pathlib
import shutil
import tempfile
import zipfile

source = pathlib.Path(os.environ["YCORE_AAR"])
stage = pathlib.Path(os.environ["YCORE_STAGE"])
fd, temp_name = tempfile.mkstemp(prefix=source.name + ".", suffix=".tmp", dir=source.parent)
os.close(fd)
temp = pathlib.Path(temp_name)
try:
    replacements = {
        f"jni/{path.parent.name}/libycore_demux.so": path
        for path in stage.glob("*/libycore_demux.so")
    }
    with zipfile.ZipFile(source, "r") as old, zipfile.ZipFile(temp, "w") as new:
        for info in old.infolist():
            if info.filename in replacements:
                continue
            new.writestr(info, old.read(info.filename))
        for archive_name, path in sorted(replacements.items()):
            info = zipfile.ZipInfo(archive_name)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o755 << 16
            new.writestr(info, path.read_bytes())
    shutil.move(temp, source)
finally:
    temp.unlink(missing_ok=True)
PY

sha256sum "$AAR" | awk '{print $1}' > "$AAR.sha256"
{
  echo "ycore-demux=true"
  echo "ycore-demux-ffmpeg=n8.1"
  echo "ycore-demux-source=scripts/native/ycore_demux_jni.cpp"
  echo "ycore-demux-abis=$(IFS=,; echo "${ABIS[*]}")"
} >> "$ARTIFACTS/NATIVE-SOURCES.txt"

echo "[ycore-demux] injected libycore_demux.so into $AAR"
