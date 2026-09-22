/*
 * Yfuse local BDMV filesystem stream for mpv/libbluray.
 *
 * The ISO bridge already owns the difficult optical-disc state machine: title/chapter controls,
 * HDMV navigation, input serialization, overlay rendering and Java lifecycle callbacks. Reusing that
 * implementation here keeps ISO and extracted-BDMV behavior identical. The only transport change is
 * replacing bd_open_stream(read_blocks) with libbluray's public bd_open_files() VFS.
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libbluray/bluray.h>
#include <libbluray/filesystem.h>

/* Must be declared before the included ISO implementation calls it. The third argument matches the
 * bd_open_stream callback shape but is intentionally ignored for a directory VFS. */
static int bdmv_open_stream_adapter(
    BLURAY *bd,
    void *opaque,
    int (*unused_read_blocks)(void *handle, void *buf, int lba, int num_blocks));

/* Compile a private copy of the proven session implementation under BDMV-specific JNI/export names.
 * bluray.h has already been included above, so replacing bd_open_stream here does not rewrite the
 * libbluray declaration itself. */
#define bd_open_stream bdmv_open_stream_adapter
#define stream_info_yfuse_bluray stream_info_yfuse_bdmv_unused
#define Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeRegister \
    Java_dev_yfuse_mpv_YfuseBdmvRegistry_nativeRegister
#define Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeUnregister \
    Java_dev_yfuse_mpv_YfuseBdmvRegistry_nativeUnregister
#define Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSendMenuCommand \
    Java_dev_yfuse_mpv_YfuseBdmvRegistry_nativeSendMenuCommand
#define Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSelectMenuPoint \
    Java_dev_yfuse_mpv_YfuseBdmvRegistry_nativeSelectMenuPoint
#include "stream_yfuse_bluray.c"
#undef Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSelectMenuPoint
#undef Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeSendMenuCommand
#undef Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeUnregister
#undef Java_dev_yfuse_mpv_YfuseBluRayRegistry_nativeRegister
#undef stream_info_yfuse_bluray
#undef bd_open_stream

typedef struct {
    BD_FILE_H api;
    yfuse_source *source;
    jlong handle;
    jmethodID read;
    jmethodID seek;
    jmethodID tell;
    jmethodID close;
} yfuse_bdmv_file;

typedef struct {
    BD_DIR_H api;
    yfuse_source *source;
    jlong handle;
    jmethodID read;
    jmethodID close;
} yfuse_bdmv_dir;

static jmethodID bdmv_method(JNIEnv *env, yfuse_source *source, const char *name, const char *sig)
{
    if (!env || !source || !source->object)
        return NULL;
    jclass type = (*env)->GetObjectClass(env, source->object);
    if (!type)
        return NULL;
    jmethodID result = (*env)->GetMethodID(env, type, name, sig);
    (*env)->DeleteLocalRef(env, type);
    if (!result)
        yfuse_clear_exception(env);
    return result;
}

static jlong bdmv_call_open(JNIEnv *env, yfuse_source *source, const char *method_name,
                            const char *relative_path)
{
    jmethodID method = bdmv_method(env, source, method_name, "(Ljava/lang/String;)J");
    if (!method)
        return 0;
    jstring path = (*env)->NewStringUTF(env, relative_path ? relative_path : "");
    if (!path)
        return 0;
    jlong handle = (*env)->CallLongMethod(env, source->object, method, path);
    (*env)->DeleteLocalRef(env, path);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    return handle > 0 ? handle : 0;
}

static void bdmv_file_close(BD_FILE_H *api)
{
    yfuse_bdmv_file *file = api ? api->internal : NULL;
    if (!file)
        return;
    int attached = 0;
    JNIEnv *env = yfuse_env(file->source->vm, &attached);
    if (env && file->handle > 0) {
        (*env)->CallVoidMethod(env, file->source->object, file->close, file->handle);
        yfuse_clear_exception(env);
    }
    if (attached)
        (*file->source->vm)->DetachCurrentThread(file->source->vm);
    free(file);
}

static int64_t bdmv_file_seek(BD_FILE_H *api, int64_t offset, int32_t origin)
{
    yfuse_bdmv_file *file = api ? api->internal : NULL;
    if (!file)
        return -1;
    int attached = 0;
    JNIEnv *env = yfuse_env(file->source->vm, &attached);
    if (!env)
        return -1;
    jlong result = (*env)->CallLongMethod(env, file->source->object, file->seek,
                                          file->handle, (jlong)offset, (jint)origin);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = -1;
    }
    if (attached)
        (*file->source->vm)->DetachCurrentThread(file->source->vm);
    return (int64_t)result;
}

static int64_t bdmv_file_tell(BD_FILE_H *api)
{
    yfuse_bdmv_file *file = api ? api->internal : NULL;
    if (!file)
        return -1;
    int attached = 0;
    JNIEnv *env = yfuse_env(file->source->vm, &attached);
    if (!env)
        return -1;
    jlong result = (*env)->CallLongMethod(env, file->source->object, file->tell, file->handle);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = -1;
    }
    if (attached)
        (*file->source->vm)->DetachCurrentThread(file->source->vm);
    return (int64_t)result;
}

static int64_t bdmv_file_read(BD_FILE_H *api, uint8_t *buf, int64_t size)
{
    yfuse_bdmv_file *file = api ? api->internal : NULL;
    if (!file || !buf || size < 0 || size > INT32_MAX)
        return -1;
    if (size == 0)
        return 0;
    int attached = 0;
    JNIEnv *env = yfuse_env(file->source->vm, &attached);
    if (!env)
        return -1;
    jbyteArray array = (*env)->NewByteArray(env, (jsize)size);
    if (!array) {
        if (attached)
            (*file->source->vm)->DetachCurrentThread(file->source->vm);
        return -1;
    }
    jint read = (*env)->CallIntMethod(env, file->source->object, file->read,
                                      file->handle, array, (jint)0, (jint)size);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        read = -1;
    }
    if (read > 0 && read <= size) {
        (*env)->GetByteArrayRegion(env, array, 0, read, (jbyte *)buf);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            read = -1;
        }
    } else if (read > size) {
        read = -1;
    }
    (*env)->DeleteLocalRef(env, array);
    if (attached)
        (*file->source->vm)->DetachCurrentThread(file->source->vm);
    return read;
}

static BD_FILE_H *bdmv_open_file(void *opaque, const char *relative_path)
{
    yfuse_source *source = opaque;
    if (!source || !relative_path)
        return NULL;
    int attached = 0;
    JNIEnv *env = yfuse_env(source->vm, &attached);
    if (!env)
        return NULL;
    jlong handle = bdmv_call_open(env, source, "openFileNative", relative_path);
    if (handle <= 0) {
        if (attached)
            (*source->vm)->DetachCurrentThread(source->vm);
        return NULL;
    }
    yfuse_bdmv_file *file = calloc(1, sizeof(*file));
    if (!file) {
        jmethodID close = bdmv_method(env, source, "closeFileNative", "(J)V");
        if (close) {
            (*env)->CallVoidMethod(env, source->object, close, handle);
            yfuse_clear_exception(env);
        }
        if (attached)
            (*source->vm)->DetachCurrentThread(source->vm);
        return NULL;
    }
    file->source = source;
    file->handle = handle;
    file->read = bdmv_method(env, source, "readFileNative", "(J[BII)I");
    file->seek = bdmv_method(env, source, "seekFileNative", "(JJI)J");
    file->tell = bdmv_method(env, source, "tellFileNative", "(J)J");
    file->close = bdmv_method(env, source, "closeFileNative", "(J)V");
    if (!file->read || !file->seek || !file->tell || !file->close) {
        if (file->close) {
            (*env)->CallVoidMethod(env, source->object, file->close, handle);
            yfuse_clear_exception(env);
        }
        free(file);
        if (attached)
            (*source->vm)->DetachCurrentThread(source->vm);
        return NULL;
    }
    file->api.internal = file;
    file->api.close = bdmv_file_close;
    file->api.seek = bdmv_file_seek;
    file->api.tell = bdmv_file_tell;
    file->api.eof = NULL;
    file->api.read = bdmv_file_read;
    file->api.write = NULL;
    if (attached)
        (*source->vm)->DetachCurrentThread(source->vm);
    return &file->api;
}

static void bdmv_dir_close(BD_DIR_H *api)
{
    yfuse_bdmv_dir *dir = api ? api->internal : NULL;
    if (!dir)
        return;
    int attached = 0;
    JNIEnv *env = yfuse_env(dir->source->vm, &attached);
    if (env && dir->handle > 0) {
        (*env)->CallVoidMethod(env, dir->source->object, dir->close, dir->handle);
        yfuse_clear_exception(env);
    }
    if (attached)
        (*dir->source->vm)->DetachCurrentThread(dir->source->vm);
    free(dir);
}

static int bdmv_dir_read(BD_DIR_H *api, BD_DIRENT *entry)
{
    yfuse_bdmv_dir *dir = api ? api->internal : NULL;
    if (!dir || !entry)
        return -1;
    int attached = 0;
    JNIEnv *env = yfuse_env(dir->source->vm, &attached);
    if (!env)
        return -1;
    jstring value = (jstring)(*env)->CallObjectMethod(env, dir->source->object, dir->read, dir->handle);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        value = NULL;
    }
    if (!value) {
        if (attached)
            (*dir->source->vm)->DetachCurrentThread(dir->source->vm);
        return 1;
    }
    const char *name = (*env)->GetStringUTFChars(env, value, NULL);
    if (!name) {
        (*env)->DeleteLocalRef(env, value);
        if (attached)
            (*dir->source->vm)->DetachCurrentThread(dir->source->vm);
        return -1;
    }
    size_t length = strlen(name);
    int result = 0;
    if (length == 0 || length >= sizeof(entry->d_name) || strchr(name, '/') || strchr(name, '\\')) {
        result = -1;
    } else {
        memcpy(entry->d_name, name, length + 1);
    }
    (*env)->ReleaseStringUTFChars(env, value, name);
    (*env)->DeleteLocalRef(env, value);
    if (attached)
        (*dir->source->vm)->DetachCurrentThread(dir->source->vm);
    return result;
}

static BD_DIR_H *bdmv_open_dir(void *opaque, const char *relative_path)
{
    yfuse_source *source = opaque;
    if (!source || !relative_path)
        return NULL;
    int attached = 0;
    JNIEnv *env = yfuse_env(source->vm, &attached);
    if (!env)
        return NULL;
    jlong handle = bdmv_call_open(env, source, "openDirNative", relative_path);
    if (handle <= 0) {
        if (attached)
            (*source->vm)->DetachCurrentThread(source->vm);
        return NULL;
    }
    yfuse_bdmv_dir *dir = calloc(1, sizeof(*dir));
    if (!dir) {
        jmethodID close = bdmv_method(env, source, "closeDirNative", "(J)V");
        if (close) {
            (*env)->CallVoidMethod(env, source->object, close, handle);
            yfuse_clear_exception(env);
        }
        if (attached)
            (*source->vm)->DetachCurrentThread(source->vm);
        return NULL;
    }
    dir->source = source;
    dir->handle = handle;
    dir->read = bdmv_method(env, source, "readDirNative", "(J)Ljava/lang/String;");
    dir->close = bdmv_method(env, source, "closeDirNative", "(J)V");
    if (!dir->read || !dir->close) {
        if (dir->close) {
            (*env)->CallVoidMethod(env, source->object, dir->close, handle);
            yfuse_clear_exception(env);
        }
        free(dir);
        if (attached)
            (*source->vm)->DetachCurrentThread(source->vm);
        return NULL;
    }
    dir->api.internal = dir;
    dir->api.close = bdmv_dir_close;
    dir->api.read = bdmv_dir_read;
    if (attached)
        (*source->vm)->DetachCurrentThread(source->vm);
    return &dir->api;
}

static int bdmv_open_stream_adapter(
    BLURAY *bd,
    void *opaque,
    int (*unused_read_blocks)(void *handle, void *buf, int lba, int num_blocks))
{
    (void)unused_read_blocks;
    if (!bd || !opaque)
        return 0;
    return bd_open_files(bd, opaque, bdmv_open_dir, bdmv_open_file);
}

/* The included implementation's stream-info object is intentionally private/unused because its
 * literal protocol is `yfusebd`. Export the same open function under the BDMV-only protocol. */
const stream_info_t stream_info_yfuse_bdmv = {
    .name = "yfuse local bdmv",
    .open = yfuse_bluray_open,
    .protocols = (const char *const[]){ "yfusebdmv", NULL },
    .stream_origin = STREAM_ORIGIN_UNSAFE,
};
