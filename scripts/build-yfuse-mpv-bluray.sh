#!/usr/bin/env bash
# Builds the Yfuse libmpv variant with libbluray, authenticated remote ISO access and HDMV menus.
# BD-J remains deliberately disabled and is never inferred from native libbluray availability.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK_ROOT="${YFUSE_MPV_WORK_ROOT:-$ROOT/.native-build/yfuse-mpv}"
OUT_DIR="${YFUSE_MPV_OUT_DIR:-$ROOT/.native-build/artifacts}"
UPSTREAM_REPO="https://github.com/jarnedemeulemeester/libmpv-android.git"
UPSTREAM_COMMIT="fcf6745703dc1265bca88f12fee8fc355ddf251e" # v1.0.0
LIBBLURAY_TAG="1.4.1"
LIBBLURAY_COMMIT="7d94f2660af5bfc16015291a03539329135c18f1"
LIBUDFREAD_COMMIT="139a2194525f2745b98a98e4d8fa627d07440176"
CAPABILITY_CLASS_PATH="dev/yfuse/mpv/YfuseMpvCapabilities.class"
REGISTRY_CLASS_PATH="dev/yfuse/mpv/YfuseBluRayRegistry.class"
YFUSE_STREAM_SOURCE="$ROOT/scripts/native/stream_yfuse_bluray.c"

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
[[ -f "$YFUSE_STREAM_SOURCE" ]] || {
  printf 'error: native stream source not found: %s\n' "$YFUSE_STREAM_SOURCE" >&2
  exit 1
}

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
CAPABILITY_SOURCE="$SOURCE/libmpv/src/main/java/dev/yfuse/mpv/YfuseMpvCapabilities.java"
REGISTRY_SOURCE="$SOURCE/libmpv/src/main/java/dev/yfuse/mpv/YfuseBluRayRegistry.java"

mkdir -p "$(dirname "$CAPABILITY_SOURCE")"
cat >"$CAPABILITY_SOURCE" <<EOF
package dev.yfuse.mpv;

public final class YfuseMpvCapabilities {
    public static final boolean LIBBLURAY = true;
    public static final boolean BDJ = false;
    public static final boolean REMOTE_RAW_BLURAY = true;
    public static final boolean HDMV_MENU = true;
    public static final String LIBMPV_ANDROID_REVISION = "$UPSTREAM_COMMIT";
    public static final String LIBBLURAY_REVISION = "$LIBBLURAY_COMMIT";
    public static final String LIBUDFREAD_REVISION = "$LIBUDFREAD_COMMIT";

    private YfuseMpvCapabilities() {}
}
EOF

# Stock libmpv-android has no class with this name. Runtime reflection therefore proves that the
# exact custom AAR was installed rather than guessing from Kotlin source or stale sidecars.
cat >"$REGISTRY_SOURCE" <<'EOF'
package dev.yfuse.mpv;

public final class YfuseBluRayRegistry {
    static {
        System.loadLibrary("mpv");
    }

    private YfuseBluRayRegistry() {}

    public static long register(Object source) {
        if (source == null) return 0L;
        return nativeRegister(source);
    }

    public static void unregister(long id) {
        if (id > 0L) nativeUnregister(id);
    }

    public static boolean sendMenuCommand(long id, int command) {
        return id > 0L && nativeSendMenuCommand(id, command);
    }

    public static boolean selectMenuPoint(long id, int x, int y, boolean activate) {
        return id > 0L && x >= 0 && y >= 0 && nativeSelectMenuPoint(id, x, y, activate);
    }

    private static native long nativeRegister(Object source);
    private static native void nativeUnregister(long id);
    private static native boolean nativeSendMenuCommand(long id, int command);
    private static native boolean nativeSelectMenuPoint(long id, int x, int y, boolean activate);
}
EOF

python3 - "$DEPINFO" "$DOWNLOAD_DEPS" "$LIBBLURAY_COMMIT" "$LIBUDFREAD_COMMIT" <<'PY'
from pathlib import Path
import sys

depinfo = Path(sys.argv[1])
download = Path(sys.argv[2])
bluray_commit = sys.argv[3]
udfread_commit = sys.argv[4]

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
bluray = f'''# libbluray — pinned VideoLAN source. Its bundled libudfread is a git submodule, and the\n# upstream cross file forbids Meson downloads, so both revisions are initialized and verified here.\nif [ ! -d libbluray ]; then\n\tgit clone --recurse-submodules --branch $v_libbluray https://code.videolan.org/videolan/libbluray.git libbluray\n\t[ \"$(git -C libbluray rev-parse HEAD)\" = \"{bluray_commit}\" ] || {{\n\t\techo 'libbluray tag resolved to an unexpected commit' >&2; exit 1;\n\t}}\n\tgit -C libbluray submodule update --init --recursive\n\t[ \"$(git -C libbluray/contrib/libudfread rev-parse HEAD)\" = \"{udfread_commit}\" ] || {{\n\t\techo 'libbluray libudfread submodule resolved to an unexpected commit' >&2; exit 1;\n\t}}\nfi\n\n'''
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

unset CC CXX
meson setup "$build" --cross-file "$prefix_dir/crossfile.txt" \
  -Dbdj_jar=disabled \
  -Denable_docs=false \
  -Denable_tools=false \
  -Denable_devtools=false \
  -Denable_examples=false \
  -Dfontconfig=disabled
ninja -C "$build" -j"$cores"
DESTDIR="$prefix_dir" ninja -C "$build" install
SH
chmod +x "$LIBBLURAY_BUILD"

printf '==> downloading pinned native dependencies\n'
(
  cd "$SOURCE/buildscripts"
  ./download.sh
)

BLURAY_ROOT="$SOURCE/buildscripts/deps/libbluray"
BLURAY_HEAD="$(git -C "$BLURAY_ROOT" rev-parse HEAD)"
UDFREAD_HEAD="$(git -C "$BLURAY_ROOT/contrib/libudfread" rev-parse HEAD)"
[[ "$BLURAY_HEAD" == "$LIBBLURAY_COMMIT" ]] || {
  printf 'error: libbluray %s resolved to unexpected commit %s\n' "$LIBBLURAY_TAG" "$BLURAY_HEAD" >&2
  exit 1
}
[[ "$UDFREAD_HEAD" == "$LIBUDFREAD_COMMIT" ]] || {
  printf 'error: libudfread submodule resolved to unexpected commit %s\n' "$UDFREAD_HEAD" >&2
  exit 1
}

# Patch only the pinned throw-away mpv checkout. Yfuse source stays reviewable as a normal repository
# file instead of a multi-hundred-line shell heredoc.
MPV_ROOT="$SOURCE/buildscripts/deps/mpv"
cp "$YFUSE_STREAM_SOURCE" "$MPV_ROOT/stream/stream_yfuse_bluray.c"
python3 - "$MPV_ROOT/meson.build" "$MPV_ROOT/stream/stream.c" <<'PY'
from pathlib import Path
import sys

meson = Path(sys.argv[1])
stream_c = Path(sys.argv[2])

text = meson.read_text()
anchor = "    'stream/stream_cb.c',\n"
if anchor not in text:
    raise SystemExit("unexpected mpv meson: stream_cb anchor missing")
text = text.replace(anchor, anchor + "    'stream/stream_yfuse_bluray.c',\n", 1)
meson.write_text(text)

text = stream_c.read_text()
extern_anchor = "extern const stream_info_t stream_info_bluray;\n"
if extern_anchor not in text:
    raise SystemExit("unexpected mpv stream.c: bluray extern anchor missing")
text = text.replace(extern_anchor, extern_anchor + "extern const stream_info_t stream_info_yfuse_bluray;\n", 1)
list_anchor = "    &stream_info_bluray,\n"
if list_anchor not in text:
    raise SystemExit("unexpected mpv stream.c: bluray list anchor missing")
text = text.replace(list_anchor, list_anchor + "    &stream_info_yfuse_bluray,\n", 1)
stream_c.write_text(text)
PY

printf '==> building libmpv + libbluray + Yfuse remote-disc/HDMV bridge\n'
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

if ! grep -RqsE '^#define HAVE_LIBBLURAY[[:space:]]+1$' "$SOURCE/buildscripts/deps/mpv"/_build*/config.h; then
  echo 'error: mpv build did not enable HAVE_LIBBLURAY' >&2
  exit 1
fi

TMP_LIST="$WORK_ROOT/aar-list.txt"
TMP_CLASSES="$WORK_ROOT/classes.jar"
unzip -l "$AAR" >"$TMP_LIST"
grep -q 'jni/arm64-v8a/libmpv.so' "$TMP_LIST" || {
  echo 'error: AAR has no arm64-v8a libmpv.so' >&2
  exit 1
}
unzip -p "$AAR" classes.jar >"$TMP_CLASSES"
unzip -l "$TMP_CLASSES" | grep -Fq "$CAPABILITY_CLASS_PATH" || {
  echo 'error: AAR is missing the Yfuse native capability marker' >&2
  exit 1
}
unzip -l "$TMP_CLASSES" | grep -Fq "$REGISTRY_CLASS_PATH" || {
  echo 'error: AAR is missing the Yfuse remote Blu-ray registry class' >&2
  exit 1
}

DEST="$OUT_DIR/libmpv-yfuse-bluray.aar"
cp -f "$AAR" "$DEST"
sha256sum "$DEST" | tee "$DEST.sha256"
{
  printf 'libmpv-android=%s\n' "$UPSTREAM_COMMIT"
  printf 'libbluray=%s\n' "$LIBBLURAY_COMMIT"
  printf 'libudfread=%s\n' "$LIBUDFREAD_COMMIT"
  printf 'bdj_jar=disabled\n'
  printf 'remote-raw-bluray=true\n'
  printf 'hdmv-menu=true\n'
  printf 'capability-class=%s\n' "$CAPABILITY_CLASS_PATH"
  printf 'registry-class=%s\n' "$REGISTRY_CLASS_PATH"
} >"$OUT_DIR/NATIVE-SOURCES.txt"
printf 'done: %s\n' "$DEST"
