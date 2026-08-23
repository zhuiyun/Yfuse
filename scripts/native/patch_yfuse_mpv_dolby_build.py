#!/usr/bin/env python3
"""Transforms the proven optical-disc builder into the Dolby Vision/FEL builder.

Keeping the optical builder as the base preserves its libbluray, remote ISO/BDMV, HDMV and
multi-angle gates. This transformer replaces the codec/render stack, adds post-render Dolby
runtime evidence, and fails on every source/build anchor drift.
"""
from pathlib import Path
import sys

MPV_CORE_COMMIT = "b955aa28f3dc93dc6b21485a0d5b7feb8e6dc10f"
FFMPEG_COMMIT = "b79d4c4c0a160fc46988e98505af6039a53ad53e"
LIBPLACEBO_COMMIT = "22ee762e8e0890fc54068beb670310f0edce7263"

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_yfuse_mpv_dolby_build.py <generated-builder.sh>")

path = Path(sys.argv[1])
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if text.count(old) != 1:
        raise SystemExit(f"unexpected optical builder: {label} anchor count={text.count(old)}")
    text = text.replace(old, new, 1)


replace_once(
    'UPSTREAM_COMMIT="fcf6745703dc1265bca88f12fee8fc355ddf251e" # v1.0.0\n',
    'UPSTREAM_COMMIT="fcf6745703dc1265bca88f12fee8fc355ddf251e" # Android wrapper\n'
    f'MPV_CORE_COMMIT="{MPV_CORE_COMMIT}"\n'
    f'FFMPEG_COMMIT="{FFMPEG_COMMIT}"\n'
    f'LIBPLACEBO_COMMIT="{LIBPLACEBO_COMMIT}"\n',
    "native stack pins",
)

replace_once(
    'YFUSE_ANGLE_PATCH="$ROOT/scripts/native/patch_yfuse_bluray_angle.py"\n',
    'YFUSE_ANGLE_PATCH="$ROOT/scripts/native/patch_yfuse_bluray_angle.py"\n'
    'YFUSE_DOLBY_PATCH="$ROOT/scripts/native/patch_yfuse_dolby_fel.py"\n'
    'YFUSE_DOLBY_JNI_PATCH="$ROOT/scripts/native/patch_yfuse_dolby_jni.py"\n',
    "Dolby patch paths",
)

replace_once(
    'for native_source in "$YFUSE_STREAM_SOURCE" "$YFUSE_BDMV_STREAM_SOURCE" "$YFUSE_ANGLE_PATCH"; do\n',
    'for native_source in "$YFUSE_STREAM_SOURCE" "$YFUSE_BDMV_STREAM_SOURCE" "$YFUSE_ANGLE_PATCH" "$YFUSE_DOLBY_PATCH" "$YFUSE_DOLBY_JNI_PATCH"; do\n',
    "native source verification",
)

replace_once(
    'LIBBLURAY_BUILD="$SOURCE/buildscripts/scripts/libbluray.sh"\n',
    'LIBBLURAY_BUILD="$SOURCE/buildscripts/scripts/libbluray.sh"\n'
    'LIBPLACEBO_BUILD="$SOURCE/buildscripts/scripts/libplacebo.sh"\n'
    'MPV_JNI_MAIN="$SOURCE/libmpv/src/main/cpp/main.cpp"\n',
    "native builder paths",
)

replace_once(
    'public final class YfuseMpvCapabilities {\n',
    'public final class YfuseMpvCapabilities {\n'
    '    static {\n'
    '        System.loadLibrary("mpv");\n'
    '        System.loadLibrary("player");\n'
    '    }\n',
    "Java native library initializer",
)

replace_once(
    '    public static final boolean MULTI_ANGLE = true;\n',
    '    public static final boolean MULTI_ANGLE = true;\n'
    '    // These flags prove the built path contains current mpv/FFmpeg/libplacebo DOVI support.\n'
    '    // Per-file RPU/FEL claims still require the runtime evidence accessors below.\n'
    '    public static final boolean DOLBY_VISION_RPU = true;\n'
    '    public static final boolean DOLBY_VISION_FEL = true;\n',
    "Java Dolby capability flags",
)

replace_once(
    '    public static final String LIBUDFREAD_REVISION = "$LIBUDFREAD_COMMIT";\n',
    '    public static final String LIBUDFREAD_REVISION = "$LIBUDFREAD_COMMIT";\n'
    '    public static final String MPV_CORE_REVISION = "$MPV_CORE_COMMIT";\n'
    '    public static final String FFMPEG_REVISION = "$FFMPEG_COMMIT";\n'
    '    public static final String LIBPLACEBO_REVISION = "$LIBPLACEBO_COMMIT";\n',
    "Java native revision fields",
)

replace_once(
    '    private YfuseMpvCapabilities() {}\n',
    '    public static long dolbyVisionRuntimeGeneration() {\n'
    '        return nativeDolbyVisionGeneration();\n'
    '    }\n\n'
    '    public static int dolbyVisionRuntimeEvidence() {\n'
    '        return nativeDolbyVisionEvidence();\n'
    '    }\n\n'
    '    private static native long nativeDolbyVisionGeneration();\n'
    '    private static native int nativeDolbyVisionEvidence();\n\n'
    '    private YfuseMpvCapabilities() {}\n',
    "Java runtime evidence accessors",
)

download_block = """printf '==> downloading pinned native dependencies\\n'\n(\n  cd \"$SOURCE/buildscripts\"\n  ./download.sh\n)\n"""
pin_block = download_block + r'''

printf '==> pinning mpv/FFmpeg/libplacebo Dolby Vision stack\n'
pin_dependency() {
  local repo="$1" commit="$2" label="$3"
  [[ -d "$repo/.git" ]] || {
    printf 'error: missing dependency checkout: %s\n' "$repo" >&2
    exit 1
  }
  git -C "$repo" fetch -q --depth 1 origin "$commit"
  git -C "$repo" checkout -q --detach FETCH_HEAD
  [[ "$(git -C "$repo" rev-parse HEAD)" == "$commit" ]] || {
    printf 'error: %s commit mismatch\n' "$label" >&2
    exit 1
  }
}

pin_dependency "$SOURCE/buildscripts/deps/mpv" "$MPV_CORE_COMMIT" mpv
pin_dependency "$SOURCE/buildscripts/deps/ffmpeg" "$FFMPEG_COMMIT" ffmpeg
pin_dependency "$SOURCE/buildscripts/deps/libplacebo" "$LIBPLACEBO_COMMIT" libplacebo
git -C "$SOURCE/buildscripts/deps/libplacebo" submodule update -q --init --recursive

# libplacebo owns RPU processing and P7 enhancement-layer composition. FFmpeg/mpv already pass
# parsed Dolby metadata, so a separate Rust libdovi parser is not required in this Android build.
python3 - "$LIBPLACEBO_BUILD" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
if "-Ddovi=enabled" not in text:
    anchor = "-Dvulkan=disabled -Ddemos=false"
    if anchor not in text:
        raise SystemExit("unexpected libplacebo build script: meson option anchor missing")
    text = text.replace(anchor, anchor + " -Ddovi=enabled -Dlibdovi=disabled", 1)
path.write_text(text)
PY

MPV_DOLBY_ROOT="$SOURCE/buildscripts/deps/mpv"
FFMPEG_DOLBY_ROOT="$SOURCE/buildscripts/deps/ffmpeg"
LIBPLACEBO_DOLBY_ROOT="$SOURCE/buildscripts/deps/libplacebo"
[[ -f "$FFMPEG_DOLBY_ROOT/libavcodec/bsf/dovi_split.c" ]] || {
  echo 'error: pinned FFmpeg lacks dovi_split bitstream filter' >&2
  exit 1
}
[[ -f "$MPV_DOLBY_ROOT/demux/dovi_split.c" ]] || {
  echo 'error: pinned mpv lacks Dolby Vision split demuxer' >&2
  exit 1
}
[[ -f "$MPV_DOLBY_ROOT/filters/f_enhancement_pair.c" ]] || {
  echo 'error: pinned mpv lacks enhancement-layer pairing filter' >&2
  exit 1
}
grep -Fq 'frame->enhancement_layer = &fp->el_frame' "$MPV_DOLBY_ROOT/video/out/vo_gpu_next.c" || {
  echo 'error: pinned mpv does not feed the enhancement layer to libplacebo' >&2
  exit 1
}
grep -Rqs 'enhancement_layer' "$LIBPLACEBO_DOLBY_ROOT/src/include/libplacebo" || {
  echo 'error: pinned libplacebo API lacks enhancement-layer rendering' >&2
  exit 1
}
'''
replace_once(download_block, pin_block, "dependency download block")

replace_once(
    'MPV_ROOT="$SOURCE/buildscripts/deps/mpv"\n',
    'MPV_ROOT="$SOURCE/buildscripts/deps/mpv"\n'
    'python3 "$YFUSE_DOLBY_PATCH" "$MPV_ROOT/video/out/vo_gpu_next.c"\n'
    'python3 "$YFUSE_DOLBY_JNI_PATCH" "$MPV_JNI_MAIN"\n',
    "mpv/JNI source patch point",
)

replace_once(
    "  printf 'multi-angle=true\\n'\n",
    "  printf 'multi-angle=true\\n'\n"
    "  printf 'mpv-core=%s\\n' \"$MPV_CORE_COMMIT\"\n"
    "  printf 'ffmpeg=%s\\n' \"$FFMPEG_COMMIT\"\n"
    "  printf 'libplacebo=%s\\n' \"$LIBPLACEBO_COMMIT\"\n"
    "  printf 'dolby-vision-rpu=true\\n'\n"
    "  printf 'dolby-vision-fel=true\\n'\n"
    "  printf 'ffmpeg-dovi-split=true\\n'\n"
    "  printf 'libplacebo-enhancement-layer=true\\n'\n"
    "  printf 'dolby-render-evidence=YFUSE_DOVI_RPU_RENDERED,YFUSE_DOVI_FEL_COMPOSED\\n'\n"
    "  printf 'dolby-runtime-jni=true\\n'\n",
    "native provenance block",
)

required = (
    f'MPV_CORE_COMMIT="{MPV_CORE_COMMIT}"',
    f'FFMPEG_COMMIT="{FFMPEG_COMMIT}"',
    f'LIBPLACEBO_COMMIT="{LIBPLACEBO_COMMIT}"',
    'DOLBY_VISION_RPU = true',
    'DOLBY_VISION_FEL = true',
    'dolbyVisionRuntimeEvidence',
    'patch_yfuse_dolby_fel.py',
    'patch_yfuse_dolby_jni.py',
    'dolby-vision-fel=true',
    'dolby-runtime-jni=true',
)
missing = [marker for marker in required if marker not in text]
if missing:
    raise SystemExit("generated Dolby builder is incomplete: " + ", ".join(missing))

path.write_text(text)
