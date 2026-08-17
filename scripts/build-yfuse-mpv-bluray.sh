#!/usr/bin/env bash
# Builds the Yfuse libmpv variant with libbluray linked in for local and remote ISO/BDMV access.
#
# BD-J stays deliberately disabled. HDMV interactive overlays are a separate gate; this build first
# establishes reproducible libbluray + remote UDF block access while keeping ordinary mpv intact.
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
CAPABILITY_SOURCE="$SOURCE/libmpv/src/main/java/dev/yfuse/mpv/YfuseMpvCapabilities.java"
REGISTRY_SOURCE="$SOURCE/libmpv/src/main/java/dev/yfuse/mpv/YfuseBluRayRegistry.java"

mkdir -p "$(dirname "$CAPABILITY_SOURCE")"
cat >"$CAPABILITY_SOURCE" <<EOF
package dev.yfuse.mpv;

public final class YfuseMpvCapabilities {
    public static final boolean LIBBLURAY = true;
    public static final boolean BDJ = false;
    public static final boolean REMOTE_RAW_BLURAY = true;
    public static final boolean HDMV_MENU = false;
    public static final String LIBMPV_ANDROID_REVISION = "$UPSTREAM_COMMIT";
    public static final String LIBBLURAY_REVISION = "$LIBBLURAY_COMMIT";
    public static final String LIBUDFREAD_REVISION = "$LIBUDFREAD_COMMIT";

    private YfuseMpvCapabilities() {}
}
EOF

# Kept separate from MPVLib so the app can discover it through reflection. Stock AARs do not contain
# this class, therefore ordinary builds stay ABI/API compatible and can never accidentally enable raw ISO.
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

    private static native long nativeRegister(Object source);
    private static native void nativeUnregister(long id);
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

# Add a private Yfuse stream protocol directly inside libmpv. The registry is JNI-backed but the
# actual Blu-ray session remains inside the same libbluray/mpv stream stack as local bd:// playback.
MPV_ROOT="$SOURCE/buildscripts/deps/mpv"
YFUSE_STREAM="$MPV_ROOT/stream/stream_yfuse_bluray.c"
cat >"$YFUSE_STREAM" <<'C'
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libbluray/bluray.h>
#include <libbluray/meta_data.h>

#include "common/common.h"
#include "common/msg.h"
#include "mpv_talloc.h"
#include "stream.h"

#define YFUSE_MAX_REGISTERED_SOURCES 64
#define YFUSE_BD_TIMEBASE 90000.0

typedef struct yfuse_source {
    int64_t id;
    JavaVM *vm;
    jobject object;
    jmethodID read_blocks;
    jmethodID close_source;
    atomic_int refs;
    struct yfuse_source *next;
} yfuse_source;

static pthread_mutex_t g_source_lock = PTHREAD_MUTEX_INITIALIZER;
static yfuse_source *g_sources;
static int64_t g_next_source_id = 1;
static int g_source_count;

static JNIEnv *yfuse_env(JavaVM *vm, int *attached)
{
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK)
        return env;
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK)
        return NULL;
    *attached = 1;
    return env;
}

static void yfuse_source_destroy(yfuse_source *source)
{
    if (!source)
        return;
    int attached = 0;
    JNIEnv *env = yfuse_env(source->vm, &attached);
    if (env && source->object) {
        (*env)->CallVoidMethod(env, source->object, source->close_source);
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        (*env)->DeleteGlobalRef(env, source->object);
    }
    if (attached)
        (*source->vm)->DetachCurrentThread(source->vm);
    free(source);
}

static void yfuse_source_release(yfuse_source *source)
{
    if (source && atomic_fetch_sub_explicit(&source->refs, 1, memory_order_acq_rel) == 1)
        yfuse_source_destroy(source);
}

/*
 * Registration is one-shot once mpv opens the URI: ownership moves from the registry to that stream.
 * This prevents every successful movie from leaving a Java global reference behind for the lifetime
 * of the process. Explicit unregister still cleans a source whose URI was prepared but never opened.
 */
static yfuse_source *yfuse_source_take(int64_t id)
{
    yfuse_source *result = NULL;
    pthread_mutex_lock(&g_source_lock);
    yfuse_source **link = &g_sources;
    while (*link) {
        if ((*link)->id == id) {
            result = *link;
            *link = result->next;
            result->next = NULL;
            g_source_count--;
            break;
        }
        link = &(*link)->next;
    }
    pthread_mutex_unlock(&g_source_lock);
    return result;
}

static int yfuse_source_read_blocks(void *opaque, void *buf, int lba, int num_blocks)
{
    yfuse_source *source = opaque;
    if (!source || !buf || lba < 0 || num_blocks <= 0)
        return -1;
    int64_t byte_count = (int64_t)num_blocks * 2048;
    if (byte_count <= 0 || byte_count > INT32_MAX)
        return -1;

    int attached = 0;
    JNIEnv *env = yfuse_env(source->vm, &attached);
    if (!env)
        return -1;
    jbyteArray array = (*env)->NewByteArray(env, (jsize)byte_count);
    if (!array) {
        if (attached)
            (*source->vm)->DetachCurrentThread(source->vm);
        return -1;
    }
    jint blocks = (*env)->CallIntMethod(env, source->object, source->read_blocks,
                                         (jint)lba, (jint)num_blocks, array, (jint)0);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        blocks = -1;
    }
    if (blocks > 0 && blocks <= num_blocks) {
        jsize copied = (jsize)((int64_t)blocks * 2048);
        (*env)->GetByteArrayRegion(env, array, 0, copied, (jbyte *)buf);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            blocks = -1;
        }
    } else if (blocks > num_blocks) {
        blocks = -1;
    }
    (*env)->DeleteLocalRef(env, array);
    if (attached)
        (*source->vm)->DetachCurrentThread(source->vm);
    return blocks;
}

JNIEXPORT jlong JNICALL
Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeRegister(JNIEnv *env, jclass clazz, jobject object)
{
    (void)clazz;
    if (!object)
        return 0;
    jclass type = (*env)->GetObjectClass(env, object);
    if (!type)
        return 0;
    jmethodID read_blocks = (*env)->GetMethodID(env, type, "readBlocksNative", "(II[BI)I");
    jmethodID close_source = (*env)->GetMethodID(env, type, "closeNativeSource", "()V");
    (*env)->DeleteLocalRef(env, type);
    if (!read_blocks || !close_source) {
        if ((*env)->ExceptionCheck(env))
            (*env)->ExceptionClear(env);
        return 0;
    }

    JavaVM *vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK || !vm)
        return 0;
    jobject global = (*env)->NewGlobalRef(env, object);
    if (!global)
        return 0;
    yfuse_source *source = calloc(1, sizeof(*source));
    if (!source) {
        (*env)->DeleteGlobalRef(env, global);
        return 0;
    }
    source->vm = vm;
    source->object = global;
    source->read_blocks = read_blocks;
    source->close_source = close_source;
    atomic_init(&source->refs, 1);

    pthread_mutex_lock(&g_source_lock);
    if (g_source_count >= YFUSE_MAX_REGISTERED_SOURCES) {
        pthread_mutex_unlock(&g_source_lock);
        yfuse_source_release(source);
        return 0;
    }
    source->id = g_next_source_id++;
    if (g_next_source_id <= 0)
        g_next_source_id = 1;
    source->next = g_sources;
    g_sources = source;
    g_source_count++;
    pthread_mutex_unlock(&g_source_lock);
    return (jlong)source->id;
}

JNIEXPORT void JNICALL
Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeUnregister(JNIEnv *env, jclass clazz, jlong id)
{
    (void)env;
    (void)clazz;
    yfuse_source *removed = NULL;
    pthread_mutex_lock(&g_source_lock);
    yfuse_source **link = &g_sources;
    while (*link) {
        if ((*link)->id == (int64_t)id) {
            removed = *link;
            *link = removed->next;
            removed->next = NULL;
            g_source_count--;
            break;
        }
        link = &(*link)->next;
    }
    pthread_mutex_unlock(&g_source_lock);
    yfuse_source_release(removed);
}

typedef struct yfuse_bluray_priv {
    BLURAY *bd;
    BLURAY_TITLE_INFO *title_info;
    yfuse_source *source;
    int num_titles;
    int current_title;
    int current_playlist;
    int current_angle;
} yfuse_bluray_priv;

static void yfuse_refresh_title_info(yfuse_bluray_priv *priv)
{
    if (priv->title_info) {
        bd_free_title_info(priv->title_info);
        priv->title_info = NULL;
    }
    if (priv->current_title >= 0)
        priv->title_info = bd_get_title_info(priv->bd, priv->current_title, priv->current_angle);
}

static void yfuse_handle_event(stream_t *stream, const BD_EVENT *event)
{
    yfuse_bluray_priv *priv = stream->priv;
    switch (event->event) {
    case BD_EVENT_PLAYLIST:
        priv->current_playlist = event->param;
        priv->current_title = bd_get_current_title(priv->bd);
        yfuse_refresh_title_info(priv);
        break;
    case BD_EVENT_TITLE:
        if (event->param != BLURAY_TITLE_FIRST_PLAY)
            priv->current_title = event->param;
        else
            priv->current_title = bd_get_current_title(priv->bd);
        yfuse_refresh_title_info(priv);
        break;
    case BD_EVENT_ANGLE:
        priv->current_angle = event->param;
        yfuse_refresh_title_info(priv);
        break;
    case BD_EVENT_STILL_TIME:
        bd_read_skip_still(priv->bd);
        break;
    default:
        break;
    }
}

static int yfuse_fill_buffer(stream_t *stream, void *buf, int len)
{
    yfuse_bluray_priv *priv = stream->priv;
    BD_EVENT event;
    while (bd_get_event(priv->bd, &event))
        yfuse_handle_event(stream, &event);
    return bd_read(priv->bd, buf, len);
}

static void yfuse_bluray_close(stream_t *stream)
{
    yfuse_bluray_priv *priv = stream->priv;
    if (!priv)
        return;
    if (priv->title_info)
        bd_free_title_info(priv->title_info);
    if (priv->bd)
        bd_close(priv->bd);
    yfuse_source_release(priv->source);
    priv->source = NULL;
}

static int yfuse_bluray_control(stream_t *stream, int cmd, void *arg)
{
    yfuse_bluray_priv *priv = stream->priv;
    switch (cmd) {
    case STREAM_CTRL_GET_NUM_CHAPTERS:
        if (!priv->title_info)
            return STREAM_UNSUPPORTED;
        *(unsigned int *)arg = priv->title_info->chapter_count;
        return STREAM_OK;
    case STREAM_CTRL_GET_CHAPTER_TIME: {
        if (!priv->title_info)
            return STREAM_UNSUPPORTED;
        int chapter = *(double *)arg;
        if (chapter < 0 || chapter >= priv->title_info->chapter_count)
            return STREAM_ERROR;
        *(double *)arg = priv->title_info->chapters[chapter].start / YFUSE_BD_TIMEBASE;
        return STREAM_OK;
    }
    case STREAM_CTRL_SET_CURRENT_TITLE: {
        uint32_t title = *(unsigned int *)arg;
        if (title >= (uint32_t)priv->num_titles || !bd_select_title(priv->bd, title))
            return STREAM_UNSUPPORTED;
        priv->current_title = title;
        yfuse_refresh_title_info(priv);
        return STREAM_OK;
    }
    case STREAM_CTRL_GET_CURRENT_TITLE:
        *(unsigned int *)arg = priv->current_title;
        return STREAM_OK;
    case STREAM_CTRL_GET_NUM_TITLES:
        *(unsigned int *)arg = priv->num_titles;
        return STREAM_OK;
    case STREAM_CTRL_GET_TIME_LENGTH:
        if (!priv->title_info)
            return STREAM_UNSUPPORTED;
        *(double *)arg = priv->title_info->duration / YFUSE_BD_TIMEBASE;
        return STREAM_OK;
    case STREAM_CTRL_GET_CURRENT_TIME:
        *(double *)arg = bd_tell_time(priv->bd) / YFUSE_BD_TIMEBASE;
        return STREAM_OK;
    case STREAM_CTRL_SEEK_TO_TIME: {
        double seconds = *(double *)arg;
        if (bd_seek_time(priv->bd, (uint64_t)(seconds * YFUSE_BD_TIMEBASE)) < 0)
            return STREAM_ERROR;
        stream_drop_buffers(stream);
        return STREAM_OK;
    }
    case STREAM_CTRL_GET_TITLE_LENGTH: {
        int title = *(double *)arg;
        if (title < 0 || title >= priv->num_titles)
            return STREAM_UNSUPPORTED;
        BLURAY_TITLE_INFO *info = bd_get_title_info(priv->bd, title, 0);
        if (!info)
            return STREAM_UNSUPPORTED;
        *(double *)arg = info->duration / YFUSE_BD_TIMEBASE;
        bd_free_title_info(info);
        return STREAM_OK;
    }
    case STREAM_CTRL_GET_TITLE_PLAYLIST: {
        int title = *(double *)arg;
        if (title < 0 || title >= priv->num_titles)
            return STREAM_UNSUPPORTED;
        BLURAY_TITLE_INFO *info = bd_get_title_info(priv->bd, title, 0);
        if (!info)
            return STREAM_UNSUPPORTED;
        *(double *)arg = info->playlist;
        bd_free_title_info(info);
        return STREAM_OK;
    }
    case STREAM_CTRL_GET_DISC_NAME: {
        const struct meta_dl *meta = bd_get_meta(priv->bd);
        if (!meta || !meta->di_name || !meta->di_name[0])
            return STREAM_UNSUPPORTED;
        *(char **)arg = talloc_strdup(NULL, meta->di_name);
        return STREAM_OK;
    }
    default:
        return STREAM_UNSUPPORTED;
    }
}

static bool yfuse_disc_supported(BLURAY *bd)
{
    const BLURAY_DISC_INFO *info = bd_get_disc_info(bd);
    if (!info || !info->bluray_detected)
        return false;
    if (info->aacs_detected && !info->aacs_handled)
        return false;
    if (info->bdplus_detected && !info->bdplus_handled)
        return false;
    return true;
}

static int yfuse_bluray_open(stream_t *stream)
{
    const char *text = stream->path;
    while (*text == '/')
        text++;
    char *end = NULL;
    long long id = strtoll(text, &end, 10);
    if (id <= 0 || !end || *end != '\0')
        return STREAM_ERROR;

    yfuse_source *source = yfuse_source_take(id);
    if (!source)
        return STREAM_ERROR;

    yfuse_bluray_priv *priv = talloc_zero(stream, yfuse_bluray_priv);
    stream->priv = priv;
    priv->source = source;
    priv->current_title = -1;
    priv->current_playlist = -1;
    priv->current_angle = 0;

    BLURAY *bd = bd_init();
    if (!bd || !bd_open_stream(bd, source, yfuse_source_read_blocks)) {
        if (bd)
            bd_close(bd);
        goto fail;
    }
    priv->bd = bd;
    if (!yfuse_disc_supported(bd))
        goto fail;

    priv->num_titles = bd_get_titles(bd, TITLES_RELEVANT, 0);
    if (priv->num_titles <= 0)
        goto fail;
    bd_get_event(bd, NULL);
    int main_title = bd_get_main_title(bd);
    if (main_title < 0 || main_title >= priv->num_titles)
        main_title = 0;
    if (!bd_select_title(bd, main_title))
        goto fail;
    priv->current_title = bd_get_current_title(bd);
    if (priv->current_title < 0 || priv->current_title >= priv->num_titles)
        priv->current_title = main_title;
    priv->current_angle = bd_get_current_angle(bd);
    yfuse_refresh_title_info(priv);

    stream->fill_buffer = yfuse_fill_buffer;
    stream->close = yfuse_bluray_close;
    stream->control = yfuse_bluray_control;
    stream->demuxer = "+disc";
    MP_INFO(stream, "Yfuse remote Blu-ray source opened with libbluray.\n");
    return STREAM_OK;

fail:
    yfuse_bluray_close(stream);
    talloc_free(priv);
    stream->priv = NULL;
    return STREAM_UNSUPPORTED;
}

const stream_info_t stream_info_yfuse_bluray = {
    .name = "yfuse remote bluray",
    .open = yfuse_bluray_open,
    .protocols = (const char *const[]){ "yfusebd", NULL },
    .stream_origin = STREAM_ORIGIN_UNSAFE,
};
C

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

printf '==> building libmpv + libbluray + Yfuse remote-disc bridge\n'
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
  printf 'hdmv-menu=false\n'
  printf 'capability-class=%s\n' "$CAPABILITY_CLASS_PATH"
  printf 'registry-class=%s\n' "$REGISTRY_CLASS_PATH"
} >"$OUT_DIR/NATIVE-SOURCES.txt"
printf 'done: %s\n' "$DEST"
