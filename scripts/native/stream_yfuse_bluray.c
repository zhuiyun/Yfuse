/*
 * Yfuse authenticated remote Blu-ray stream for mpv/libbluray.
 *
 * The URL contains only a process-local source id. HTTP credentials and byte-range reads stay in
 * the Android/Kotlin block source. This file is copied into the pinned mpv tree by the native build.
 */
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavutil/common.h>
#include <libbluray/bluray.h>
#include <libbluray/keys.h>
#include <libbluray/meta_data.h>
#include <libbluray/overlay.h>

#include "common/common.h"
#include "common/msg.h"
#include "mpv_talloc.h"
#include "stream.h"

#define YFUSE_MAX_REGISTERED_SOURCES 64
#define YFUSE_BD_TIMEBASE 90000.0
#define YFUSE_UDF_BLOCK_SIZE 2048
#define YFUSE_MAX_OVERLAY_PIXELS (4096 * 2160)
#define YFUSE_MENU_SHOW 0
#define YFUSE_MENU_BACK 1
#define YFUSE_MENU_UP 2
#define YFUSE_MENU_DOWN 3
#define YFUSE_MENU_LEFT 4
#define YFUSE_MENU_RIGHT 5
#define YFUSE_MENU_SELECT 6

typedef struct yfuse_source yfuse_source;
typedef struct yfuse_bluray_priv yfuse_bluray_priv;

struct yfuse_source {
    int64_t id;
    JavaVM *vm;
    jobject object;
    jmethodID read_blocks;
    jmethodID close_source;
    jmethodID session_state;
    jmethodID overlay_frame;
    jmethodID overlay_cleared;
    jmethodID session_closed;
    atomic_int refs;
    /** Serializes every libbluray API call made by mpv and Android menu input. */
    pthread_mutex_t session_lock;
    BLURAY *active_bd;
    yfuse_bluray_priv *active_priv;
    struct yfuse_source *next;
};

struct yfuse_bluray_priv {
    BLURAY *bd;
    BLURAY_TITLE_INFO *title_info;
    yfuse_source *source;
    int num_titles;
    int current_title;
    int current_playlist;
    int current_angle;
    int menu_supported;
    int menu_active;
    int popup_available;
    int navigation_mode;

    uint32_t *ig_overlay;
    int overlay_width;
    int overlay_height;
    BD_PG_PALETTE_ENTRY palette[256];
    int have_palette;
};

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

static void yfuse_clear_exception(JNIEnv *env)
{
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

static void yfuse_source_destroy(yfuse_source *source)
{
    if (!source)
        return;
    int attached = 0;
    JNIEnv *env = yfuse_env(source->vm, &attached);
    if (env && source->object) {
        (*env)->CallVoidMethod(env, source->object, source->close_source);
        yfuse_clear_exception(env);
        (*env)->DeleteGlobalRef(env, source->object);
    }
    if (attached)
        (*source->vm)->DetachCurrentThread(source->vm);
    pthread_mutex_destroy(&source->session_lock);
    free(source);
}

static void yfuse_source_release(yfuse_source *source)
{
    if (source && atomic_fetch_sub_explicit(&source->refs, 1, memory_order_acq_rel) == 1)
        yfuse_source_destroy(source);
}

static yfuse_source *yfuse_source_acquire(int64_t id)
{
    yfuse_source *result = NULL;
    pthread_mutex_lock(&g_source_lock);
    for (yfuse_source *source = g_sources; source; source = source->next) {
        if (source->id == id) {
            atomic_fetch_add_explicit(&source->refs, 1, memory_order_relaxed);
            result = source;
            break;
        }
    }
    pthread_mutex_unlock(&g_source_lock);
    return result;
}

static yfuse_source *yfuse_source_remove(int64_t id)
{
    yfuse_source *removed = NULL;
    pthread_mutex_lock(&g_source_lock);
    yfuse_source **link = &g_sources;
    while (*link) {
        if ((*link)->id == id) {
            removed = *link;
            *link = removed->next;
            removed->next = NULL;
            g_source_count--;
            break;
        }
        link = &(*link)->next;
    }
    pthread_mutex_unlock(&g_source_lock);
    return removed;
}

static void yfuse_unregister_source(int64_t id)
{
    yfuse_source_release(yfuse_source_remove(id));
}

static int yfuse_source_read_blocks(void *opaque, void *buf, int lba, int num_blocks)
{
    yfuse_source *source = opaque;
    if (!source || !buf || lba < 0 || num_blocks <= 0)
        return -1;
    int64_t byte_count = (int64_t)num_blocks * YFUSE_UDF_BLOCK_SIZE;
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
        jsize copied = (jsize)((int64_t)blocks * YFUSE_UDF_BLOCK_SIZE);
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

static void yfuse_java_session_closed(yfuse_source *source)
{
    if (!source)
        return;
    int attached = 0;
    JNIEnv *env = yfuse_env(source->vm, &attached);
    if (env) {
        (*env)->CallVoidMethod(env, source->object, source->session_closed);
        yfuse_clear_exception(env);
    }
    if (attached)
        (*source->vm)->DetachCurrentThread(source->vm);
}

static void yfuse_java_overlay_cleared(yfuse_source *source)
{
    if (!source)
        return;
    int attached = 0;
    JNIEnv *env = yfuse_env(source->vm, &attached);
    if (env) {
        (*env)->CallVoidMethod(env, source->object, source->overlay_cleared);
        yfuse_clear_exception(env);
    }
    if (attached)
        (*source->vm)->DetachCurrentThread(source->vm);
}

static void yfuse_java_overlay_frame(yfuse_bluray_priv *priv)
{
    if (!priv || !priv->source || !priv->ig_overlay ||
        priv->overlay_width <= 0 || priv->overlay_height <= 0)
        return;
    int64_t pixels = (int64_t)priv->overlay_width * priv->overlay_height;
    if (pixels <= 0 || pixels > YFUSE_MAX_OVERLAY_PIXELS)
        return;

    int attached = 0;
    JNIEnv *env = yfuse_env(priv->source->vm, &attached);
    if (!env)
        return;
    jintArray array = (*env)->NewIntArray(env, (jsize)pixels);
    if (array) {
        (*env)->SetIntArrayRegion(env, array, 0, (jsize)pixels, (const jint *)priv->ig_overlay);
        if (!(*env)->ExceptionCheck(env)) {
            (*env)->CallVoidMethod(env, priv->source->object, priv->source->overlay_frame,
                                    (jint)priv->overlay_width, (jint)priv->overlay_height, array);
        }
        yfuse_clear_exception(env);
        (*env)->DeleteLocalRef(env, array);
    }
    if (attached)
        (*priv->source->vm)->DetachCurrentThread(priv->source->vm);
}

static void yfuse_refresh_title_info(yfuse_bluray_priv *priv)
{
    if (priv->title_info) {
        bd_free_title_info(priv->title_info);
        priv->title_info = NULL;
    }
    if (priv->current_playlist >= 0)
        priv->title_info = bd_get_playlist_info(priv->bd, priv->current_playlist, priv->current_angle);
    if (!priv->title_info && priv->current_title >= 0 && priv->current_title < priv->num_titles)
        priv->title_info = bd_get_title_info(priv->bd, priv->current_title, priv->current_angle);
}

static void yfuse_java_session_state(yfuse_bluray_priv *priv)
{
    if (!priv || !priv->source)
        return;
    int title = priv->current_title;
    if (title < 0)
        title = 0;
    int chapter_count = priv->title_info ? (int)priv->title_info->chapter_count : 0;
    int chapter = priv->bd ? (int)bd_get_current_chapter(priv->bd) : 0;
    if (chapter < 0 || (chapter_count > 0 && chapter >= chapter_count))
        chapter = 0;

    int attached = 0;
    JNIEnv *env = yfuse_env(priv->source->vm, &attached);
    if (env) {
        (*env)->CallVoidMethod(env, priv->source->object, priv->source->session_state,
                                (jint)priv->num_titles, (jint)title,
                                (jint)chapter_count, (jint)chapter,
                                (jboolean)(priv->menu_supported != 0),
                                (jboolean)(priv->menu_active != 0));
        yfuse_clear_exception(env);
    }
    if (attached)
        (*priv->source->vm)->DetachCurrentThread(priv->source->vm);
}

static int yfuse_clamp8(int value)
{
    if (value < 0) return 0;
    if (value > 255) return 255;
    return value;
}

static uint32_t yfuse_palette_argb(const BD_PG_PALETTE_ENTRY *entry)
{
    int c = (int)entry->Y - 16;
    int d = (int)entry->Cb - 128;
    int e = (int)entry->Cr - 128;
    if (c < 0) c = 0;
    int r = yfuse_clamp8((298 * c + 459 * e + 128) >> 8);
    int g = yfuse_clamp8((298 * c - 55 * d - 136 * e + 128) >> 8);
    int b = yfuse_clamp8((298 * c + 541 * d + 128) >> 8);
    return ((uint32_t)entry->T << 24) | ((uint32_t)r << 16) | ((uint32_t)g << 8) | (uint32_t)b;
}

static void yfuse_overlay_clear_rect(yfuse_bluray_priv *priv, int x, int y, int w, int h)
{
    if (!priv->ig_overlay || priv->overlay_width <= 0 || priv->overlay_height <= 0)
        return;
    int x0 = FFMAX(0, x);
    int y0 = FFMAX(0, y);
    int x1 = FFMIN(priv->overlay_width, x + w);
    int y1 = FFMIN(priv->overlay_height, y + h);
    for (int row = y0; row < y1; row++)
        memset(priv->ig_overlay + (int64_t)row * priv->overlay_width + x0,
               0, (size_t)FFMAX(0, x1 - x0) * sizeof(uint32_t));
}

static void yfuse_overlay_draw(yfuse_bluray_priv *priv, const BD_OVERLAY *event)
{
    if (!priv->ig_overlay || !event->img || !priv->have_palette || event->w == 0 || event->h == 0)
        return;
    int64_t total = (int64_t)event->w * event->h;
    if (total <= 0 || total > YFUSE_MAX_OVERLAY_PIXELS)
        return;
    int64_t pixel = 0;
    int64_t elements = 0;
    const BD_PG_RLE_ELEM *rle = event->img;
    while (pixel < total && elements <= total) {
        int run = rle->len;
        int color = rle->color & 0xff;
        rle++;
        elements++;
        if (run <= 0)
            continue;
        uint32_t argb = yfuse_palette_argb(&priv->palette[color]);
        int64_t end = FFMIN(total, pixel + run);
        while (pixel < end) {
            int rel_y = (int)(pixel / event->w);
            int rel_x = (int)(pixel % event->w);
            int dst_x = event->x + rel_x;
            int dst_y = event->y + rel_y;
            if (dst_x >= 0 && dst_x < priv->overlay_width && dst_y >= 0 && dst_y < priv->overlay_height)
                priv->ig_overlay[(int64_t)dst_y * priv->overlay_width + dst_x] = argb;
            pixel++;
        }
    }
}

static void yfuse_overlay_proc(void *handle, const BD_OVERLAY *event)
{
    yfuse_bluray_priv *priv = handle;
    if (!priv || !event || event->plane != BD_OVERLAY_IG)
        return;

    if (event->palette) {
        memcpy(priv->palette, event->palette, sizeof(priv->palette));
        priv->have_palette = 1;
    }

    switch (event->cmd) {
    case BD_OVERLAY_INIT: {
        int64_t pixels = (int64_t)event->w * event->h;
        if (pixels <= 0 || pixels > YFUSE_MAX_OVERLAY_PIXELS)
            break;
        free(priv->ig_overlay);
        priv->ig_overlay = calloc((size_t)pixels, sizeof(uint32_t));
        priv->overlay_width = event->w;
        priv->overlay_height = event->h;
        break;
    }
    case BD_OVERLAY_CLOSE:
        free(priv->ig_overlay);
        priv->ig_overlay = NULL;
        priv->overlay_width = 0;
        priv->overlay_height = 0;
        yfuse_java_overlay_cleared(priv->source);
        break;
    case BD_OVERLAY_CLEAR:
    case BD_OVERLAY_HIDE:
        if (priv->ig_overlay)
            memset(priv->ig_overlay, 0,
                   (size_t)priv->overlay_width * priv->overlay_height * sizeof(uint32_t));
        if (event->cmd == BD_OVERLAY_HIDE)
            yfuse_java_overlay_cleared(priv->source);
        break;
    case BD_OVERLAY_DRAW:
        yfuse_overlay_draw(priv, event);
        break;
    case BD_OVERLAY_WIPE:
        yfuse_overlay_clear_rect(priv, event->x, event->y, event->w, event->h);
        break;
    case BD_OVERLAY_FLUSH:
        if (priv->menu_active)
            yfuse_java_overlay_frame(priv);
        else
            yfuse_java_overlay_cleared(priv->source);
        break;
    default:
        break;
    }
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
    jmethodID session_state = (*env)->GetMethodID(env, type, "onNativeSessionState", "(IIIIZZ)V");
    jmethodID overlay_frame = (*env)->GetMethodID(env, type, "onNativeOverlayFrame", "(II[I)V");
    jmethodID overlay_cleared = (*env)->GetMethodID(env, type, "onNativeOverlayCleared", "()V");
    jmethodID session_closed = (*env)->GetMethodID(env, type, "onNativeSessionClosed", "()V");
    (*env)->DeleteLocalRef(env, type);
    if (!read_blocks || !close_source || !session_state || !overlay_frame || !overlay_cleared || !session_closed) {
        yfuse_clear_exception(env);
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
    source->session_state = session_state;
    source->overlay_frame = overlay_frame;
    source->overlay_cleared = overlay_cleared;
    source->session_closed = session_closed;
    atomic_init(&source->refs, 1); /* registry ownership */
    pthread_mutex_init(&source->session_lock, NULL);

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
    yfuse_unregister_source((int64_t)id);
}

static int yfuse_ensure_navigation(yfuse_source *source, BLURAY *bd)
{
    if (!source || !source->active_priv || !bd)
        return 0;
    if (source->active_priv->navigation_mode)
        return 1;
    if (!bd_play(bd))
        return 0;
    source->active_priv->navigation_mode = 1;
    return 1;
}

static int yfuse_menu_command_locked(yfuse_source *source, int command)
{
    BLURAY *bd = source ? source->active_bd : NULL;
    yfuse_bluray_priv *priv = source ? source->active_priv : NULL;
    if (!bd || !priv || !priv->menu_supported)
        return 0;

    int64_t pts = (int64_t)bd_tell_time(bd);
    switch (command) {
    case YFUSE_MENU_SHOW:
        if (!yfuse_ensure_navigation(source, bd))
            return 0;
        return bd_menu_call(bd, pts) > 0;
    case YFUSE_MENU_BACK:
        if (!yfuse_ensure_navigation(source, bd))
            return 0;
        if (priv->popup_available)
            return bd_user_input(bd, pts, BD_VK_POPUP) >= 0;
        return bd_user_input(bd, pts, BD_VK_ROOT_MENU) >= 0;
    case YFUSE_MENU_UP:
        return yfuse_ensure_navigation(source, bd) && bd_user_input(bd, pts, BD_VK_UP) >= 0;
    case YFUSE_MENU_DOWN:
        return yfuse_ensure_navigation(source, bd) && bd_user_input(bd, pts, BD_VK_DOWN) >= 0;
    case YFUSE_MENU_LEFT:
        return yfuse_ensure_navigation(source, bd) && bd_user_input(bd, pts, BD_VK_LEFT) >= 0;
    case YFUSE_MENU_RIGHT:
        return yfuse_ensure_navigation(source, bd) && bd_user_input(bd, pts, BD_VK_RIGHT) >= 0;
    case YFUSE_MENU_SELECT:
        return yfuse_ensure_navigation(source, bd) && bd_user_input(bd, pts, BD_VK_ENTER) >= 0;
    default:
        return 0;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSendMenuCommand(JNIEnv *env, jclass clazz, jlong id, jint command)
{
    (void)env;
    (void)clazz;
    yfuse_source *source = yfuse_source_acquire((int64_t)id);
    if (!source)
        return JNI_FALSE;
    pthread_mutex_lock(&source->session_lock);
    int handled = yfuse_menu_command_locked(source, command);
    pthread_mutex_unlock(&source->session_lock);
    yfuse_source_release(source);
    return handled ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSelectMenuPoint(JNIEnv *env, jclass clazz, jlong id,
                                                              jint x, jint y, jboolean activate)
{
    (void)env;
    (void)clazz;
    if (x < 0 || y < 0 || x > UINT16_MAX || y > UINT16_MAX)
        return JNI_FALSE;
    yfuse_source *source = yfuse_source_acquire((int64_t)id);
    if (!source)
        return JNI_FALSE;
    pthread_mutex_lock(&source->session_lock);
    BLURAY *bd = source->active_bd;
    yfuse_bluray_priv *priv = source->active_priv;
    int handled = 0;
    if (bd && priv && priv->menu_supported && yfuse_ensure_navigation(source, bd)) {
        int64_t pts = (int64_t)bd_tell_time(bd);
        int selected = bd_mouse_select(bd, pts, (uint16_t)x, (uint16_t)y);
        handled = selected > 0;
        if (handled && activate)
            handled = bd_user_input(bd, pts, BD_VK_MOUSE_ACTIVATE) >= 0;
    }
    pthread_mutex_unlock(&source->session_lock);
    yfuse_source_release(source);
    return handled ? JNI_TRUE : JNI_FALSE;
}

static void yfuse_handle_event(stream_t *stream, const BD_EVENT *event)
{
    yfuse_bluray_priv *priv = stream->priv;
    int publish_state = 0;
    switch (event->event) {
    case BD_EVENT_MENU:
        priv->menu_active = event->param != 0;
        publish_state = 1;
        if (!priv->menu_active)
            yfuse_java_overlay_cleared(priv->source);
        break;
    case BD_EVENT_POPUP:
        priv->popup_available = event->param != 0;
        publish_state = 1;
        break;
    case BD_EVENT_PLAYLIST:
        priv->current_playlist = event->param;
        priv->current_title = bd_get_current_title(priv->bd);
        yfuse_refresh_title_info(priv);
        publish_state = 1;
        break;
    case BD_EVENT_TITLE:
        if (event->param != BLURAY_TITLE_FIRST_PLAY)
            priv->current_title = event->param;
        else
            priv->current_title = bd_get_current_title(priv->bd);
        yfuse_refresh_title_info(priv);
        publish_state = 1;
        break;
    case BD_EVENT_CHAPTER:
        publish_state = 1;
        break;
    case BD_EVENT_ANGLE:
        priv->current_angle = event->param;
        yfuse_refresh_title_info(priv);
        publish_state = 1;
        break;
    case BD_EVENT_STILL_TIME:
        bd_read_skip_still(priv->bd);
        break;
    case BD_EVENT_SEEK:
    case BD_EVENT_DISCONTINUITY:
        stream_drop_buffers(stream);
        break;
    case BD_EVENT_ERROR:
    case BD_EVENT_ENCRYPTED:
        priv->menu_active = 0;
        publish_state = 1;
        yfuse_java_overlay_cleared(priv->source);
        break;
    default:
        break;
    }
    if (publish_state)
        yfuse_java_session_state(priv);
}

static int yfuse_fill_buffer_unlocked(stream_t *stream, void *buf, int len)
{
    yfuse_bluray_priv *priv = stream->priv;
    if (!priv->navigation_mode) {
        BD_EVENT event;
        while (bd_get_event(priv->bd, &event))
            yfuse_handle_event(stream, &event);
        return bd_read(priv->bd, buf, len);
    }

    for (int attempts = 0; attempts < 64; attempts++) {
        BD_EVENT event = {0, 0};
        int result = bd_read_ext(priv->bd, buf, len, &event);
        if (event.event != BD_EVENT_NONE)
            yfuse_handle_event(stream, &event);
        if (result != 0)
            return result;
        if (event.event == BD_EVENT_NONE || event.event == BD_EVENT_END_OF_TITLE)
            return 0;
    }
    return 0;
}

static int yfuse_fill_buffer(stream_t *stream, void *buf, int len)
{
    yfuse_bluray_priv *priv = stream->priv;
    if (!priv || !priv->source)
        return -1;
    pthread_mutex_lock(&priv->source->session_lock);
    int result = yfuse_fill_buffer_unlocked(stream, buf, len);
    pthread_mutex_unlock(&priv->source->session_lock);
    return result;
}

static void yfuse_bluray_close(stream_t *stream)
{
    yfuse_bluray_priv *priv = stream->priv;
    if (!priv)
        return;
    yfuse_source *source = priv->source;
    if (source) {
        pthread_mutex_lock(&source->session_lock);
        source->active_bd = NULL;
        source->active_priv = NULL;
        pthread_mutex_unlock(&source->session_lock);
    }
    if (priv->bd)
        bd_register_overlay_proc(priv->bd, NULL, NULL);
    if (priv->title_info)
        bd_free_title_info(priv->title_info);
    if (priv->bd)
        bd_close(priv->bd);
    free(priv->ig_overlay);
    priv->ig_overlay = NULL;
    if (source) {
        yfuse_java_overlay_cleared(source);
        yfuse_java_session_closed(source);
        yfuse_unregister_source(source->id); /* release registry ownership */
        yfuse_source_release(source);         /* release stream ownership */
        priv->source = NULL;
    }
}

static int yfuse_bluray_control_unlocked(stream_t *stream, int cmd, void *arg)
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
        priv->navigation_mode = 0;
        priv->menu_active = 0;
        priv->current_title = title;
        priv->current_playlist = -1;
        yfuse_refresh_title_info(priv);
        yfuse_java_overlay_cleared(priv->source);
        yfuse_java_session_state(priv);
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
    case STREAM_CTRL_GET_NUM_ANGLES:
        if (!priv->title_info)
            return STREAM_UNSUPPORTED;
        *(int *)arg = priv->title_info->angle_count;
        return STREAM_OK;
    case STREAM_CTRL_GET_ANGLE:
        *(int *)arg = priv->current_angle;
        return STREAM_OK;
    case STREAM_CTRL_SET_ANGLE: {
        if (!priv->title_info)
            return STREAM_UNSUPPORTED;
        int angle = *(int *)arg;
        if (angle < 0 || angle >= priv->title_info->angle_count)
            return STREAM_UNSUPPORTED;
        priv->current_angle = angle;
        bd_seamless_angle_change(priv->bd, angle);
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

static int yfuse_bluray_control(stream_t *stream, int cmd, void *arg)
{
    yfuse_bluray_priv *priv = stream->priv;
    if (!priv || !priv->source)
        return STREAM_UNSUPPORTED;
    pthread_mutex_lock(&priv->source->session_lock);
    int result = yfuse_bluray_control_unlocked(stream, cmd, arg);
    pthread_mutex_unlock(&priv->source->session_lock);
    return result;
}

static int yfuse_disc_supported(BLURAY *bd, int *menu_supported)
{
    const BLURAY_DISC_INFO *info = bd_get_disc_info(bd);
    if (!info || !info->bluray_detected)
        return 0;
    if (info->aacs_detected && !info->aacs_handled)
        return 0;
    if (info->bdplus_detected && !info->bdplus_handled)
        return 0;
    if (menu_supported)
        *menu_supported = !info->no_menu_support && info->num_hdmv_titles > 0;
    return 1;
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

    yfuse_source *source = yfuse_source_acquire(id);
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
    if (!yfuse_disc_supported(bd, &priv->menu_supported))
        goto fail;

    bd_register_overlay_proc(bd, priv, yfuse_overlay_proc);
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

    pthread_mutex_lock(&source->session_lock);
    source->active_bd = bd;
    source->active_priv = priv;
    pthread_mutex_unlock(&source->session_lock);

    stream->fill_buffer = yfuse_fill_buffer;
    stream->close = yfuse_bluray_close;
    stream->control = yfuse_bluray_control;
    stream->demuxer = "+disc";
    yfuse_java_session_state(priv);
    MP_INFO(stream, "Yfuse remote Blu-ray source opened with libbluray (HDMV=%s).\n",
            priv->menu_supported ? "yes" : "no");
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
