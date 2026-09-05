#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <unordered_map>
#include <vector>
#include <unistd.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/codec_par.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/display.h>
#include <libavutil/error.h>
#include <libavutil/mastering_display_metadata.h>
#include <libavutil/mem.h>
#include <ass/ass.h>
#include <libbluray/bluray.h>
#include <libbluray/filesystem.h>
#include <libbluray/keys.h>
#include <libbluray/overlay.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#include "ycore_tone_map.h"
#include "ycore_disc_uri.h"
#include "ycore_overlay_plane.h"

namespace {

constexpr int kTrackUnknown = 0;
constexpr int kTrackVideo = 1;
constexpr int kTrackAudio = 2;
constexpr int kTrackSubtitle = 3;
constexpr int kTrackData = 4;

constexpr int kHdrSdr = 0;
constexpr int kHdrPq = 1;
constexpr int kHdrHlg = 2;
constexpr int kHdr10Plus = 3;

constexpr int kPackingUnknown = 0;
constexpr int kPackingAnnexB = 1;
constexpr int kPackingLengthPrefixed = 2;

constexpr int kPacketStatusEof = 0;
constexpr int kPacketStatusData = 1;
constexpr int kPacketStatusGrowBuffer = -1;
constexpr int kFailureAuthorization = -2;
constexpr int kFailureNetwork = -3;
constexpr int kFailureContainer = -4;

// Human-readable reason for the most recent native_open failure on this thread. The Kotlin
// bridge reads it right after a negative open status so diagnostics can name the FFmpeg error
// and the stage that produced it instead of only its coarse classification. It never carries
// the source URI or headers.
thread_local std::string g_last_open_failure;

void record_open_failure(const char* stage, int error) {
    char text[AV_ERROR_MAX_STRING_SIZE] = {0};
    if (av_strerror(error, text, sizeof(text)) < 0) {
        std::snprintf(text, sizeof(text), "unknown error");
    }
    g_last_open_failure = std::string(stage) + ": " + text + " (" + std::to_string(error) + ")";
}

constexpr int kSampleFlagSync = 1 << 0;
constexpr int kSampleFlagEncrypted = 1 << 1;

constexpr jlong kNoTimestamp = std::numeric_limits<jlong>::min();
constexpr uint32_t kSubtitlePayloadMagic = 0x42555359;
constexpr uint32_t kSubtitlePayloadVersion = 1;
constexpr size_t kMaxSubtitlePayloadBytes = 32U * 1024U * 1024U;
constexpr size_t kMaxSoftwareVideoFrameBytes = 128U * 1024U * 1024U;
constexpr size_t kMaxSoftwareAudioFrameBytes = 8U * 1024U * 1024U;
constexpr size_t kMaxSoftwareToneMapPixels = 4096U * 2160U;
constexpr int kSoftwareFrameAgain = 0;
constexpr int kSoftwareFrameData = 1;
constexpr int kSoftwareFrameEof = 2;
constexpr int kSoftwareFrameGrowBuffer = -1;
constexpr int kSoftwareDecoderApiVersion = 2;
constexpr int kDiscApiVersion = 2;
constexpr int kAssRendererApiVersion = 1;
// Version 2: session handles are positive registry ids. Version 1 (artifacts without this
// getter) returned the DemuxSession pointer, which Android's pointer tagging turns negative.
constexpr int kDemuxHandleContractVersion = 2;
constexpr int kDiscBlockSize = 2048;
constexpr int kDiscAvioBufferBytes = 64 * 1024;
constexpr int64_t kBlurayClock = 90000;
constexpr int64_t kMaxDiscOverlayPixels = 4096LL * 2160LL;

struct BlurayIo;

struct DiscSource {
    JavaVM* vm = nullptr;
    jobject object = nullptr;
    jmethodID read_blocks = nullptr;
    jmethodID publish_state = nullptr;
    jmethodID overlay_frame = nullptr;
    jmethodID overlay_cleared = nullptr;
    jmethodID close_source = nullptr;
    jmethodID open_file = nullptr;
    jmethodID read_file = nullptr;
    jmethodID seek_file = nullptr;
    jmethodID tell_file = nullptr;
    jmethodID close_file = nullptr;
    jmethodID open_dir = nullptr;
    jmethodID read_dir = nullptr;
    jmethodID close_dir = nullptr;
    bool filesystem_source = false;
    std::string path;
    std::mutex mutex;
    BlurayIo* active = nullptr;
    int preferred_title = -1;

    ~DiscSource();
};

struct BlurayIo {
    std::shared_ptr<DiscSource> source;
    BLURAY* bd = nullptr;
    BLURAY_TITLE_INFO* title_info = nullptr;
    int title_count = 0;
    int current_title = 0;
    int current_angle = 0;
    bool menu_supported = false;
    bool menu_active = false;
    bool popup_available = false;
    bool navigation_mode = false;
    int overlay_width = 0;
    int overlay_height = 0;
    bool have_palette = false;
    BD_PG_PALETTE_ENTRY palette[256] = {};
    std::vector<uint32_t> overlay;

    ~BlurayIo();
};

struct BdmvFile {
    BD_FILE_H api = {};
    DiscSource* source = nullptr;
    jlong handle = 0;
};

struct BdmvDirectory {
    BD_DIR_H api = {};
    DiscSource* source = nullptr;
    jlong handle = 0;
};

std::mutex g_disc_sources_mutex;
std::unordered_map<int64_t, std::shared_ptr<DiscSource>> g_disc_sources;
std::atomic<int64_t> g_next_disc_source_id{1};

struct DemuxSession;

// Open demux sessions are handed to Kotlin as small positive ids, never as the session pointer.
// Android 11+ tags every arm64 heap pointer in its top byte (0xb4...), so a raw `DemuxSession*`
// cast to jlong is negative and the Kotlin bridge read every successful open as a packed
// failure status: the pointer bits decoded as a random class and stage, the source was
// reported as unplayable, and the session itself leaked with its network connection.
std::mutex g_demux_sessions_mutex;
std::unordered_map<int64_t, DemuxSession*> g_demux_sessions;
std::atomic<int64_t> g_next_demux_session_id{1};

struct SoftwareDecoder {
    AVCodecContext* codec = nullptr;
    AVFrame* frame = nullptr;
    SwsContext* scaler = nullptr;
    SwrContext* resampler = nullptr;
    std::vector<uint16_t> tone_map_rgb48;
    bool frame_pending = false;
    bool tone_map_hdr_to_sdr = false;

    ~SoftwareDecoder() {
        swr_free(&resampler);
        sws_freeContext(scaler);
        if (frame) av_frame_free(&frame);
        if (codec) avcodec_free_context(&codec);
    }
};

struct DemuxSession {
    AVFormatContext* format = nullptr;
    AVIOContext* custom_io = nullptr;
    std::shared_ptr<BlurayIo> disc;
    AVPacket* packet = nullptr;
    bool packet_pending = false;
    bool remote_source = false;
    std::vector<uint8_t> selected;
    std::vector<AVCodecContext*> subtitle_decoders;
    ASS_Library* ass_library = nullptr;
    ASS_Renderer* ass_renderer = nullptr;
    std::vector<ASS_Track*> ass_tracks;
    std::vector<std::unique_ptr<SoftwareDecoder>> software_decoders;

    ~DemuxSession() {
        for (ASS_Track*& track : ass_tracks) {
            if (track) ass_free_track(track);
            track = nullptr;
        }
        if (ass_renderer) ass_renderer_done(ass_renderer);
        if (ass_library) ass_library_done(ass_library);
        for (AVCodecContext*& decoder : subtitle_decoders) {
            avcodec_free_context(&decoder);
        }
        if (packet) {
            av_packet_free(&packet);
        }
        if (format) avformat_close_input(&format);
        if (custom_io) {
            av_freep(&custom_io->buffer);
            avio_context_free(&custom_io);
        }
        disc.reset();
    }
};

DemuxSession* from_handle(jlong handle) {
    if (handle <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_demux_sessions_mutex);
    const auto found = g_demux_sessions.find(static_cast<int64_t>(handle));
    return found == g_demux_sessions.end() ? nullptr : found->second;
}

// Registers an opened session and returns its handle, or 0 when the id space is exhausted.
// Handles are always positive so the Kotlin bridge can keep reserving negative values for
// classified failure statuses.
jlong to_handle(DemuxSession* session) {
    if (!session) return 0;
    const int64_t session_id = g_next_demux_session_id.fetch_add(1);
    if (session_id <= 0) return 0;
    std::lock_guard<std::mutex> lock(g_demux_sessions_mutex);
    g_demux_sessions.emplace(session_id, session);
    return static_cast<jlong>(session_id);
}

// Removes a handle from the registry and returns the session it named, if any.
DemuxSession* release_handle(jlong handle) {
    if (handle <= 0) return nullptr;
    std::lock_guard<std::mutex> lock(g_demux_sessions_mutex);
    const auto found = g_demux_sessions.find(static_cast<int64_t>(handle));
    if (found == g_demux_sessions.end()) return nullptr;
    DemuxSession* session = found->second;
    g_demux_sessions.erase(found);
    return session;
}

JNIEnv* disc_env(JavaVM* vm, bool* attached) {
    *attached = false;
    if (!vm) return nullptr;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    *attached = true;
    return env;
}

void clear_java_exception(JNIEnv* env) {
    if (env && env->ExceptionCheck()) env->ExceptionClear();
}

jlong open_bdmv_handle(
    JNIEnv* env,
    DiscSource* source,
    jmethodID method,
    const char* relative_path) {
    if (!env || !source || !source->object || !method || !relative_path) return 0;
    jstring path = env->NewStringUTF(relative_path);
    if (!path) return 0;
    const jlong handle = env->CallLongMethod(source->object, method, path);
    env->DeleteLocalRef(path);
    if (env->ExceptionCheck()) {
        clear_java_exception(env);
        return 0;
    }
    return handle > 0 ? handle : 0;
}

void close_bdmv_file(BD_FILE_H* api) {
    auto* file = api ? static_cast<BdmvFile*>(api->internal) : nullptr;
    if (!file) return;
    bool attached = false;
    JNIEnv* env = disc_env(file->source ? file->source->vm : nullptr, &attached);
    if (env && file->source && file->source->object && file->handle > 0) {
        env->CallVoidMethod(file->source->object, file->source->close_file, file->handle);
        clear_java_exception(env);
    }
    if (attached) file->source->vm->DetachCurrentThread();
    delete file;
}

int64_t seek_bdmv_file(BD_FILE_H* api, int64_t offset, int32_t origin) {
    auto* file = api ? static_cast<BdmvFile*>(api->internal) : nullptr;
    if (!file || !file->source) return -1;
    bool attached = false;
    JNIEnv* env = disc_env(file->source->vm, &attached);
    if (!env) return -1;
    jlong result =
        env->CallLongMethod(
            file->source->object,
            file->source->seek_file,
            file->handle,
            static_cast<jlong>(offset),
            static_cast<jint>(origin));
    if (env->ExceptionCheck()) {
        clear_java_exception(env);
        result = -1;
    }
    if (attached) file->source->vm->DetachCurrentThread();
    return static_cast<int64_t>(result);
}

int64_t tell_bdmv_file(BD_FILE_H* api) {
    auto* file = api ? static_cast<BdmvFile*>(api->internal) : nullptr;
    if (!file || !file->source) return -1;
    bool attached = false;
    JNIEnv* env = disc_env(file->source->vm, &attached);
    if (!env) return -1;
    jlong result =
        env->CallLongMethod(file->source->object, file->source->tell_file, file->handle);
    if (env->ExceptionCheck()) {
        clear_java_exception(env);
        result = -1;
    }
    if (attached) file->source->vm->DetachCurrentThread();
    return static_cast<int64_t>(result);
}

int64_t read_bdmv_file(BD_FILE_H* api, uint8_t* destination, int64_t size) {
    auto* file = api ? static_cast<BdmvFile*>(api->internal) : nullptr;
    if (
        !file ||
        !file->source ||
        !destination ||
        size < 0 ||
        size > std::numeric_limits<jint>::max()
    ) {
        return -1;
    }
    if (size == 0) return 0;
    bool attached = false;
    JNIEnv* env = disc_env(file->source->vm, &attached);
    if (!env) return -1;
    jbyteArray target = env->NewByteArray(static_cast<jsize>(size));
    if (!target) {
        if (attached) file->source->vm->DetachCurrentThread();
        return -1;
    }
    jint result =
        env->CallIntMethod(
            file->source->object,
            file->source->read_file,
            file->handle,
            target,
            0,
            static_cast<jint>(size));
    if (env->ExceptionCheck()) {
        clear_java_exception(env);
        result = -1;
    }
    if (result > 0 && result <= size) {
        env->GetByteArrayRegion(target, 0, result, reinterpret_cast<jbyte*>(destination));
        if (env->ExceptionCheck()) {
            clear_java_exception(env);
            result = -1;
        }
    } else if (result > size) {
        result = -1;
    }
    env->DeleteLocalRef(target);
    if (attached) file->source->vm->DetachCurrentThread();
    return result;
}

BD_FILE_H* open_bdmv_file(void* opaque, const char* relative_path) {
    auto* source = static_cast<DiscSource*>(opaque);
    if (!source || !source->filesystem_source || !relative_path) return nullptr;
    bool attached = false;
    JNIEnv* env = disc_env(source->vm, &attached);
    if (!env) return nullptr;
    const jlong handle = open_bdmv_handle(env, source, source->open_file, relative_path);
    if (handle <= 0) {
        if (attached) source->vm->DetachCurrentThread();
        return nullptr;
    }
    auto* file = new (std::nothrow) BdmvFile();
    if (!file) {
        env->CallVoidMethod(source->object, source->close_file, handle);
        clear_java_exception(env);
        if (attached) source->vm->DetachCurrentThread();
        return nullptr;
    }
    file->source = source;
    file->handle = handle;
    file->api.internal = file;
    file->api.close = close_bdmv_file;
    file->api.seek = seek_bdmv_file;
    file->api.tell = tell_bdmv_file;
    file->api.eof = nullptr;
    file->api.read = read_bdmv_file;
    file->api.write = nullptr;
    if (attached) source->vm->DetachCurrentThread();
    return &file->api;
}

void close_bdmv_directory(BD_DIR_H* api) {
    auto* directory = api ? static_cast<BdmvDirectory*>(api->internal) : nullptr;
    if (!directory) return;
    bool attached = false;
    JNIEnv* env = disc_env(directory->source ? directory->source->vm : nullptr, &attached);
    if (env && directory->source && directory->source->object && directory->handle > 0) {
        env->CallVoidMethod(
            directory->source->object,
            directory->source->close_dir,
            directory->handle);
        clear_java_exception(env);
    }
    if (attached) directory->source->vm->DetachCurrentThread();
    delete directory;
}

int read_bdmv_directory(BD_DIR_H* api, BD_DIRENT* entry) {
    auto* directory = api ? static_cast<BdmvDirectory*>(api->internal) : nullptr;
    if (!directory || !directory->source || !entry) return -1;
    bool attached = false;
    JNIEnv* env = disc_env(directory->source->vm, &attached);
    if (!env) return -1;
    auto value = static_cast<jstring>(
        env->CallObjectMethod(
            directory->source->object,
            directory->source->read_dir,
            directory->handle));
    if (env->ExceptionCheck()) {
        clear_java_exception(env);
        value = nullptr;
    }
    if (!value) {
        if (attached) directory->source->vm->DetachCurrentThread();
        return 1;
    }
    const char* name = env->GetStringUTFChars(value, nullptr);
    if (!name) {
        env->DeleteLocalRef(value);
        if (attached) directory->source->vm->DetachCurrentThread();
        return -1;
    }
    const size_t length = std::strlen(name);
    int result = 0;
    if (
        length == 0 ||
        length >= sizeof(entry->d_name) ||
        std::strchr(name, '/') ||
        std::strchr(name, '\\')
    ) {
        result = -1;
    } else {
        std::memcpy(entry->d_name, name, length + 1);
    }
    env->ReleaseStringUTFChars(value, name);
    env->DeleteLocalRef(value);
    if (attached) directory->source->vm->DetachCurrentThread();
    return result;
}

BD_DIR_H* open_bdmv_directory(void* opaque, const char* relative_path) {
    auto* source = static_cast<DiscSource*>(opaque);
    if (!source || !source->filesystem_source || !relative_path) return nullptr;
    bool attached = false;
    JNIEnv* env = disc_env(source->vm, &attached);
    if (!env) return nullptr;
    const jlong handle = open_bdmv_handle(env, source, source->open_dir, relative_path);
    if (handle <= 0) {
        if (attached) source->vm->DetachCurrentThread();
        return nullptr;
    }
    auto* directory = new (std::nothrow) BdmvDirectory();
    if (!directory) {
        env->CallVoidMethod(source->object, source->close_dir, handle);
        clear_java_exception(env);
        if (attached) source->vm->DetachCurrentThread();
        return nullptr;
    }
    directory->source = source;
    directory->handle = handle;
    directory->api.internal = directory;
    directory->api.close = close_bdmv_directory;
    directory->api.read = read_bdmv_directory;
    if (attached) source->vm->DetachCurrentThread();
    return &directory->api;
}

DiscSource::~DiscSource() {
    bool attached = false;
    JNIEnv* env = disc_env(vm, &attached);
    if (env && object) {
        env->CallVoidMethod(object, close_source);
        clear_java_exception(env);
        env->DeleteGlobalRef(object);
        object = nullptr;
    }
    if (attached) vm->DetachCurrentThread();
}

BlurayIo::~BlurayIo() {
    if (source) {
        std::lock_guard<std::mutex> lock(source->mutex);
        if (source->active == this) source->active = nullptr;
    }
    if (title_info) bd_free_title_info(title_info);
    if (bd) {
        bd_register_overlay_proc(bd, nullptr, nullptr);
        bd_close(bd);
    }
}

std::shared_ptr<DiscSource> find_disc_source(int64_t source_id) {
    std::lock_guard<std::mutex> lock(g_disc_sources_mutex);
    const auto found = g_disc_sources.find(source_id);
    return found == g_disc_sources.end() ? nullptr : found->second;
}

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass klass = env->FindClass(class_name);
    if (klass) {
        env->ThrowNew(klass, message.c_str());
        env->DeleteLocalRef(klass);
    }
}

void throw_illegal_argument(JNIEnv* env, const std::string& message) {
    throw_java(env, "java/lang/IllegalArgumentException", message);
}

void throw_illegal_state(JNIEnv* env, const std::string& message) {
    throw_java(env, "java/lang/IllegalStateException", message);
}

std::string ffmpeg_error(int error) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {};
    av_strerror(error, buffer, sizeof(buffer));
    return std::string(buffer);
}

bool is_remote_source(const std::string& source) {
    const size_t separator = source.find(':');
    if (separator == std::string::npos) return false;
    std::string scheme = source.substr(0, separator);
    std::transform(scheme.begin(), scheme.end(), scheme.begin(), [](unsigned char value) {
        return static_cast<char>(std::tolower(value));
    });
    return scheme == "http" || scheme == "https" || scheme == "smb" || scheme == "webdav";
}

constexpr const char* kProbeSizeBytes = "2097152";
constexpr const char* kProbeAnalyzeDurationUs = "1000000";

constexpr int kOpenStageDisc = 1;
constexpr int kOpenStageOpenInput = 2;
constexpr int kOpenStageStreamInfo = 3;

int failure_status(int error, bool remote_source);

// Packs the classified failure with the raw AVERROR magnitude and the open stage so the managed
// side can record which call failed and why. Only numeric codes cross the boundary; no URL,
// header or FFmpeg log text is ever attached. Legacy readers that only understand -2/-3/-4 still
// see a negative status and fall back to the container class.
jlong open_failure_status(int error, bool remote_source, int stage) {
    const uint64_t category = static_cast<uint64_t>(-failure_status(error, remote_source)) & 0xFFu;
    const uint64_t magnitude = static_cast<uint64_t>(static_cast<uint32_t>(-error));
    const uint64_t packed =
        (static_cast<uint64_t>(stage & 0xFF) << 40) | (category << 32) | magnitude;
    return -static_cast<jlong>(packed);
}

int failure_status(int error, bool remote_source) {
    if (error == AVERROR_HTTP_UNAUTHORIZED || error == AVERROR_HTTP_FORBIDDEN) {
        return kFailureAuthorization;
    }
    if (error == AVERROR_INVALIDDATA || error == AVERROR_DEMUXER_NOT_FOUND) {
        return kFailureContainer;
    }
    if (!remote_source) return kFailureContainer;
    switch (error) {
        case AVERROR_HTTP_BAD_REQUEST:
        case AVERROR_HTTP_NOT_FOUND:
        case AVERROR_HTTP_TOO_MANY_REQUESTS:
        case AVERROR_HTTP_OTHER_4XX:
        case AVERROR_HTTP_SERVER_ERROR:
        case AVERROR(EAGAIN):
        case AVERROR(ETIMEDOUT):
        case AVERROR(ECONNRESET):
        case AVERROR(ECONNREFUSED):
        case AVERROR(EHOSTUNREACH):
        case AVERROR(ENETDOWN):
        case AVERROR(ENETUNREACH):
        case AVERROR(EPIPE):
        case AVERROR(EIO):
            return kFailureNetwork;
        default:
            return kFailureNetwork;
    }
}

std::string to_utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool contains_line_break(const std::string& value) {
    return value.find('\r') != std::string::npos || value.find('\n') != std::string::npos;
}

std::string build_headers(
    JNIEnv* env,
    jobjectArray names,
    jobjectArray values,
    bool* valid) {
    *valid = false;
    if (!names && !values) {
        *valid = true;
        return {};
    }
    if (!names || !values) {
        throw_illegal_argument(env, "Header names and values must be supplied together");
        return {};
    }
    const jsize name_count = env->GetArrayLength(names);
    const jsize value_count = env->GetArrayLength(values);
    if (name_count != value_count) {
        throw_illegal_argument(env, "Header names and values must have equal length");
        return {};
    }
    std::string output;
    for (jsize index = 0; index < name_count; ++index) {
        auto name_ref = static_cast<jstring>(env->GetObjectArrayElement(names, index));
        auto value_ref = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        const std::string name = to_utf8(env, name_ref);
        const std::string value = to_utf8(env, value_ref);
        env->DeleteLocalRef(name_ref);
        env->DeleteLocalRef(value_ref);
        if (env->ExceptionCheck()) return {};
        if (name.empty() || contains_line_break(name) || contains_line_break(value) ||
            name.find(':') != std::string::npos) {
            throw_illegal_argument(env, "Invalid HTTP header name or value");
            return {};
        }
        output.append(name).append(": ").append(value).append("\r\n");
    }
    *valid = true;
    return output;
}

int disc_read_blocks(void* opaque, void* destination, int lba, int block_count) {
    auto* source = static_cast<DiscSource*>(opaque);
    if (!source || !source->object || !destination || lba < 0 || block_count <= 0) return -1;
    const int64_t byte_count = static_cast<int64_t>(block_count) * kDiscBlockSize;
    if (byte_count <= 0 || byte_count > std::numeric_limits<jsize>::max()) return -1;

    bool attached = false;
    JNIEnv* env = disc_env(source->vm, &attached);
    if (!env) return -1;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(byte_count));
    if (!bytes) {
        clear_java_exception(env);
        if (attached) source->vm->DetachCurrentThread();
        return -1;
    }
    jint blocks = env->CallIntMethod(
        source->object,
        source->read_blocks,
        static_cast<jint>(lba),
        static_cast<jint>(block_count),
        bytes,
        0);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        blocks = -1;
    }
    if (blocks > 0 && blocks <= block_count) {
        const jsize copied = static_cast<jsize>(static_cast<int64_t>(blocks) * kDiscBlockSize);
        env->GetByteArrayRegion(bytes, 0, copied, static_cast<jbyte*>(destination));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            blocks = -1;
        }
    } else if (blocks > block_count) {
        blocks = -1;
    }
    env->DeleteLocalRef(bytes);
    if (attached) source->vm->DetachCurrentThread();
    return blocks;
}

void refresh_disc_title_info(BlurayIo* disc) {
    if (!disc || !disc->bd) return;
    if (disc->title_info) {
        bd_free_title_info(disc->title_info);
        disc->title_info = nullptr;
    }
    disc->current_title = bd_get_current_title(disc->bd);
    if (disc->current_title < 0 || disc->current_title >= disc->title_count) {
        disc->current_title = 0;
    }
    disc->current_angle = static_cast<int>(bd_get_current_angle(disc->bd));
    disc->title_info = bd_get_title_info(disc->bd, disc->current_title, disc->current_angle);
}

void publish_disc_state(BlurayIo* disc) {
    if (!disc || !disc->source || !disc->source->object || !disc->bd) return;
    const int chapter_count = disc->title_info ? static_cast<int>(disc->title_info->chapter_count) : 0;
    int chapter = static_cast<int>(bd_get_current_chapter(disc->bd));
    if (chapter < 0 || (chapter_count > 0 && chapter >= chapter_count)) chapter = 0;
    const int angle_count = disc->title_info ? static_cast<int>(disc->title_info->angle_count) : 0;

    bool attached = false;
    JNIEnv* env = disc_env(disc->source->vm, &attached);
    if (env) {
        env->CallVoidMethod(
            disc->source->object,
            disc->source->publish_state,
            static_cast<jint>(disc->title_count),
            static_cast<jint>(disc->current_title),
            static_cast<jint>(chapter_count),
            static_cast<jint>(chapter),
            static_cast<jint>(angle_count),
            static_cast<jint>(disc->current_angle),
            static_cast<jboolean>(disc->menu_supported),
            static_cast<jboolean>(disc->menu_active));
        clear_java_exception(env);
    }
    if (attached) disc->source->vm->DetachCurrentThread();
}

void clear_disc_overlay(BlurayIo* disc) {
    if (!disc || !disc->source || !disc->source->object) return;
    bool attached = false;
    JNIEnv* env = disc_env(disc->source->vm, &attached);
    if (env) {
        env->CallVoidMethod(disc->source->object, disc->source->overlay_cleared);
        clear_java_exception(env);
    }
    if (attached) disc->source->vm->DetachCurrentThread();
}

void publish_disc_overlay(BlurayIo* disc) {
    if (
        !disc ||
        !disc->menu_active ||
        !disc->source ||
        !disc->source->object ||
        disc->overlay_width <= 0 ||
        disc->overlay_height <= 0 ||
        disc->overlay.empty()
    ) {
        clear_disc_overlay(disc);
        return;
    }
    const int64_t pixels = static_cast<int64_t>(disc->overlay_width) * disc->overlay_height;
    if (pixels <= 0 || pixels > kMaxDiscOverlayPixels || pixels != static_cast<int64_t>(disc->overlay.size())) {
        return;
    }
    bool attached = false;
    JNIEnv* env = disc_env(disc->source->vm, &attached);
    if (env) {
        jintArray array = env->NewIntArray(static_cast<jsize>(pixels));
        if (array) {
            env->SetIntArrayRegion(
                array,
                0,
                static_cast<jsize>(pixels),
                reinterpret_cast<const jint*>(disc->overlay.data()));
            if (!env->ExceptionCheck()) {
                env->CallVoidMethod(
                    disc->source->object,
                    disc->source->overlay_frame,
                    static_cast<jint>(disc->overlay_width),
                    static_cast<jint>(disc->overlay_height),
                    array);
            }
            clear_java_exception(env);
            env->DeleteLocalRef(array);
        }
    }
    if (attached) disc->source->vm->DetachCurrentThread();
}

int clamp_disc_color(int value) {
    return std::max(0, std::min(255, value));
}

uint32_t disc_palette_argb(const BD_PG_PALETTE_ENTRY& entry) {
    const int c = std::max(0, static_cast<int>(entry.Y) - 16);
    const int d = static_cast<int>(entry.Cb) - 128;
    const int e = static_cast<int>(entry.Cr) - 128;
    const int r = clamp_disc_color((298 * c + 459 * e + 128) >> 8);
    const int g = clamp_disc_color((298 * c - 55 * d - 136 * e + 128) >> 8);
    const int b = clamp_disc_color((298 * c + 541 * d + 128) >> 8);
    return (static_cast<uint32_t>(entry.T) << 24U) |
        (static_cast<uint32_t>(r) << 16U) |
        (static_cast<uint32_t>(g) << 8U) |
        static_cast<uint32_t>(b);
}

void clear_disc_overlay_rect(BlurayIo* disc, int x, int y, int width, int height) {
    if (!disc || disc->overlay.empty()) return;
    const auto rect =
        ycore_overlay::clip_to_plane(disc->overlay_width, disc->overlay_height, x, y, width, height);
    // A wipe rectangle arrives straight from the disc's interactive-graphics stream and libbluray
    // does not clip it against the plane it announced. One starting past the right edge inverts
    // the row span, and std::fill over a reversed range is undefined behaviour, not a no-op.
    if (rect.empty()) return;
    for (int row = rect.y0; row < rect.y1; ++row) {
        const auto begin = disc->overlay.begin() + static_cast<int64_t>(row) * disc->overlay_width;
        std::fill(begin + rect.x0, begin + rect.x1, 0U);
    }
}

void draw_disc_overlay(BlurayIo* disc, const BD_OVERLAY* event) {
    if (!disc || disc->overlay.empty() || !event || !event->img || !disc->have_palette) return;
    const int64_t total = static_cast<int64_t>(event->w) * event->h;
    if (total <= 0 || total > kMaxDiscOverlayPixels) return;
    // Zero-length elements are ordinary, not malformed: PGS/IG encodes an end-of-line marker per
    // raster line, so a valid object always carries more elements than the pixels it paints.
    // Bounding the walk by the pixel count alone can therefore stop a barely-compressible object
    // before its last rows are drawn. One run per pixel plus one marker per line is the most a
    // libbluray-validated object can hold, so use that as the guard instead.
    const int64_t maximum_elements = total + event->h;
    int64_t pixel = 0;
    int64_t elements = 0;
    const BD_PG_RLE_ELEM* rle = event->img;
    while (pixel < total && elements < maximum_elements) {
        const int run = rle->len;
        const int color = rle->color & 0xff;
        ++rle;
        ++elements;
        if (run <= 0) continue;
        const uint32_t argb = disc_palette_argb(disc->palette[color]);
        const int64_t end = std::min(total, pixel + run);
        while (pixel < end) {
            const int destination_x = event->x + static_cast<int>(pixel % event->w);
            const int destination_y = event->y + static_cast<int>(pixel / event->w);
            if (
                destination_x >= 0 &&
                destination_x < disc->overlay_width &&
                destination_y >= 0 &&
                destination_y < disc->overlay_height
            ) {
                disc->overlay[static_cast<int64_t>(destination_y) * disc->overlay_width + destination_x] = argb;
            }
            ++pixel;
        }
    }
}

void disc_overlay_proc(void* handle, const BD_OVERLAY* event) {
    auto* disc = static_cast<BlurayIo*>(handle);
    if (!disc || !event || event->plane != BD_OVERLAY_IG) return;
    if (event->palette) {
        std::memcpy(disc->palette, event->palette, sizeof(disc->palette));
        disc->have_palette = true;
    }
    switch (event->cmd) {
        case BD_OVERLAY_INIT: {
            const int64_t pixels = static_cast<int64_t>(event->w) * event->h;
            if (pixels <= 0 || pixels > kMaxDiscOverlayPixels) break;
            disc->overlay.assign(static_cast<size_t>(pixels), 0U);
            disc->overlay_width = event->w;
            disc->overlay_height = event->h;
            break;
        }
        case BD_OVERLAY_CLOSE:
            disc->overlay.clear();
            disc->overlay_width = 0;
            disc->overlay_height = 0;
            clear_disc_overlay(disc);
            break;
        case BD_OVERLAY_CLEAR:
        case BD_OVERLAY_HIDE:
            std::fill(disc->overlay.begin(), disc->overlay.end(), 0U);
            if (event->cmd == BD_OVERLAY_HIDE) clear_disc_overlay(disc);
            break;
        case BD_OVERLAY_DRAW:
            draw_disc_overlay(disc, event);
            break;
        case BD_OVERLAY_WIPE:
            clear_disc_overlay_rect(disc, event->x, event->y, event->w, event->h);
            break;
        case BD_OVERLAY_FLUSH:
            publish_disc_overlay(disc);
            break;
        default:
            break;
    }
}

bool handle_disc_event(BlurayIo* disc, const BD_EVENT& event) {
    if (!disc || !disc->bd) return false;
    bool changed = false;
    switch (event.event) {
            case BD_EVENT_MENU:
                disc->menu_active = event.param != 0;
                changed = true;
                if (!disc->menu_active) clear_disc_overlay(disc);
                break;
            case BD_EVENT_POPUP:
                disc->popup_available = event.param != 0;
                changed = true;
                break;
            case BD_EVENT_TITLE:
            case BD_EVENT_PLAYLIST:
                refresh_disc_title_info(disc);
                changed = true;
                break;
            case BD_EVENT_CHAPTER:
                changed = true;
                break;
            case BD_EVENT_ANGLE:
                disc->current_angle = static_cast<int>(event.param);
                refresh_disc_title_info(disc);
                changed = true;
                break;
            case BD_EVENT_STILL_TIME:
                bd_read_skip_still(disc->bd);
                break;
            case BD_EVENT_ERROR:
            case BD_EVENT_ENCRYPTED:
                disc->menu_active = false;
                changed = true;
                clear_disc_overlay(disc);
                break;
            default:
                break;
    }
    return changed;
}

void drain_disc_events(BlurayIo* disc) {
    if (!disc || !disc->bd) return;
    bool changed = false;
    BD_EVENT event = {};
    while (bd_get_event(disc->bd, &event)) {
        changed = handle_disc_event(disc, event) || changed;
    }
    if (changed) publish_disc_state(disc);
}

int disc_avio_read(void* opaque, uint8_t* destination, int size) {
    auto* disc = static_cast<BlurayIo*>(opaque);
    if (!disc || !disc->source || !disc->bd || !destination || size <= 0) return AVERROR(EINVAL);
    std::lock_guard<std::mutex> lock(disc->source->mutex);
    drain_disc_events(disc);
    if (!disc->navigation_mode) {
        const int result = bd_read(disc->bd, destination, size);
        drain_disc_events(disc);
        return result < 0 ? AVERROR(EIO) : result;
    }
    for (int attempts = 0; attempts < 64; ++attempts) {
        BD_EVENT event = {};
        const int result = bd_read_ext(disc->bd, destination, size, &event);
        if (event.event != BD_EVENT_NONE && handle_disc_event(disc, event)) publish_disc_state(disc);
        drain_disc_events(disc);
        if (result != 0) return result < 0 ? AVERROR(EIO) : result;
        if (event.event == BD_EVENT_NONE || event.event == BD_EVENT_END_OF_TITLE) return 0;
    }
    return 0;
}

int64_t disc_avio_seek(void* opaque, int64_t offset, int whence) {
    auto* disc = static_cast<BlurayIo*>(opaque);
    if (!disc || !disc->source || !disc->bd) return AVERROR(EINVAL);
    std::lock_guard<std::mutex> lock(disc->source->mutex);
    if (whence & AVSEEK_SIZE) return static_cast<int64_t>(bd_get_title_size(disc->bd));
    const int origin = whence & ~AVSEEK_FORCE;
    int64_t target = offset;
    if (origin == SEEK_CUR) {
        target += static_cast<int64_t>(bd_tell(disc->bd));
    } else if (origin == SEEK_END) {
        target += static_cast<int64_t>(bd_get_title_size(disc->bd));
    } else if (origin != SEEK_SET) {
        return AVERROR(EINVAL);
    }
    if (target < 0) return AVERROR(EINVAL);
    const uint64_t position = bd_seek(disc->bd, static_cast<uint64_t>(target));
    drain_disc_events(disc);
    return static_cast<int64_t>(position);
}

int open_bluray_demux(
    int64_t source_id,
    DemuxSession* session) {
    const std::shared_ptr<DiscSource> source = find_disc_source(source_id);
    if (!source || !session) return AVERROR(ENOENT);
    auto disc = std::make_shared<BlurayIo>();
    disc->source = source;
    if (source->path.empty()) {
        disc->bd = bd_init();
        const int opened =
            !disc->bd ? 0 :
            source->filesystem_source ?
                bd_open_files(disc->bd, source.get(), open_bdmv_directory, open_bdmv_file) :
                bd_open_stream(disc->bd, source.get(), disc_read_blocks);
        if (!opened) {
            return AVERROR_INVALIDDATA;
        }
    } else {
        disc->bd = bd_open(source->path.c_str(), nullptr);
        if (!disc->bd) return AVERROR_INVALIDDATA;
    }
    const BLURAY_DISC_INFO* info = bd_get_disc_info(disc->bd);
    if (
        !info ||
        !info->bluray_detected ||
        (info->aacs_detected && !info->aacs_handled) ||
        (info->bdplus_detected && !info->bdplus_handled)
    ) {
        return AVERROR(EACCES);
    }
    disc->menu_supported = !info->no_menu_support && info->num_hdmv_titles > 0;
    bd_register_overlay_proc(disc->bd, disc.get(), disc_overlay_proc);
    disc->title_count = static_cast<int>(bd_get_titles(disc->bd, TITLES_RELEVANT, 0));
    if (disc->title_count <= 0) return AVERROR_INVALIDDATA;
    bd_get_event(disc->bd, nullptr);
    int title = source->preferred_title;
    if (title < 0 || title >= disc->title_count) title = bd_get_main_title(disc->bd);
    if (title < 0 || title >= disc->title_count) title = 0;
    if (!bd_select_title(disc->bd, static_cast<uint32_t>(title))) return AVERROR_INVALIDDATA;
    refresh_disc_title_info(disc.get());

    {
        std::lock_guard<std::mutex> lock(source->mutex);
        if (source->active) return AVERROR(EBUSY);
        source->active = disc.get();
    }
    session->disc = disc;
    uint8_t* io_buffer = static_cast<uint8_t*>(av_malloc(kDiscAvioBufferBytes));
    if (!io_buffer) return AVERROR(ENOMEM);
    session->custom_io =
        avio_alloc_context(
            io_buffer,
            kDiscAvioBufferBytes,
            0,
            disc.get(),
            disc_avio_read,
            nullptr,
            disc_avio_seek);
    if (!session->custom_io) {
        av_free(io_buffer);
        return AVERROR(ENOMEM);
    }
    session->custom_io->seekable = AVIO_SEEKABLE_NORMAL;
    session->format = avformat_alloc_context();
    if (!session->format) return AVERROR(ENOMEM);
    session->format->pb = session->custom_io;
    session->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    const AVInputFormat* input = av_find_input_format("mpegts");
    if (!input) return AVERROR_DEMUXER_NOT_FOUND;
    AVDictionary* options = nullptr;
    av_dict_set(&options, "scan_all_pmts", "1", 0);
    const int error = avformat_open_input(&session->format, nullptr, input, &options);
    av_dict_free(&options);
    if (error < 0) return error;
    publish_disc_state(disc.get());
    return 0;
}

AVStream* checked_stream(JNIEnv* env, DemuxSession* session, jint index) {
    if (!session || !session->format) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return nullptr;
    }
    if (index < 0 || static_cast<unsigned int>(index) >= session->format->nb_streams) {
        throw_illegal_argument(env, "Track index is outside the demux session");
        return nullptr;
    }
    return session->format->streams[index];
}

int track_type(const AVCodecParameters* parameters) {
    if (!parameters) return kTrackUnknown;
    switch (parameters->codec_type) {
        case AVMEDIA_TYPE_VIDEO:
            return kTrackVideo;
        case AVMEDIA_TYPE_AUDIO:
            return kTrackAudio;
        case AVMEDIA_TYPE_SUBTITLE:
            return kTrackSubtitle;
        case AVMEDIA_TYPE_DATA:
        case AVMEDIA_TYPE_ATTACHMENT:
            return kTrackData;
        default:
            return kTrackUnknown;
    }
}

int hdr_type(const AVCodecParameters* parameters) {
    if (!parameters) return kHdrSdr;
    switch (parameters->color_trc) {
        case AVCOL_TRC_SMPTE2084:
            return av_packet_side_data_get(
                       parameters->coded_side_data,
                       parameters->nb_coded_side_data,
                       AV_PKT_DATA_DYNAMIC_HDR10_PLUS)
                ? kHdr10Plus
                : kHdrPq;
        case AVCOL_TRC_ARIB_STD_B67:
            return kHdrHlg;
        default:
            return kHdrSdr;
    }
}

int rational_to_u16(AVRational value, double scale) {
    if (value.den <= 0 || value.num < 0) return 0;
    const double scaled = av_q2d(value) * scale;
    if (!std::isfinite(scaled) || scaled <= 0.0) return 0;
    return static_cast<int>(std::clamp(std::llround(scaled), 0LL, 65535LL));
}

int bit_depth(const AVCodecParameters* parameters) {
    if (!parameters) return 0;
    if (parameters->bits_per_raw_sample > 0) return parameters->bits_per_raw_sample;
    switch (parameters->codec_id) {
        case AV_CODEC_ID_HEVC:
            return parameters->profile == AV_PROFILE_HEVC_MAIN_10 ? 10 : 8;
        case AV_CODEC_ID_H264:
            return parameters->profile == AV_PROFILE_H264_HIGH_10 ? 10 : 8;
        default:
            return 0;
    }
}

std::pair<int, int> sample_packing(const AVCodecParameters* parameters) {
    if (!parameters || !parameters->extradata || parameters->extradata_size <= 0) {
        return {kPackingUnknown, 0};
    }
    const uint8_t* extra = parameters->extradata;
    const int size = parameters->extradata_size;
    if (size >= 4 && extra[0] == 0 && extra[1] == 0 &&
        (extra[2] == 1 || (extra[2] == 0 && extra[3] == 1))) {
        return {kPackingAnnexB, 0};
    }
    if (parameters->codec_id == AV_CODEC_ID_H264 && size >= 5 && extra[0] == 1) {
        return {kPackingLengthPrefixed, (extra[4] & 0x03) + 1};
    }
    if (parameters->codec_id == AV_CODEC_ID_HEVC && size >= 22 && extra[0] == 1) {
        return {kPackingLengthPrefixed, (extra[21] & 0x03) + 1};
    }
    return {kPackingUnknown, 0};
}

jlong timestamp_us(int64_t value, AVRational time_base) {
    if (value == AV_NOPTS_VALUE) return kNoTimestamp;
    return static_cast<jlong>(av_rescale_q(value, time_base, AV_TIME_BASE_Q));
}

jstring nullable_string(JNIEnv* env, const char* value) {
    if (!value || !*value) return nullptr;
    return env->NewStringUTF(value);
}

jstring dictionary_value(JNIEnv* env, AVDictionary* dictionary, const char* key) {
    const AVDictionaryEntry* entry = av_dict_get(dictionary, key, nullptr, 0);
    return nullable_string(env, entry ? entry->value : nullptr);
}

jbyteArray copy_bytes(JNIEnv* env, const uint8_t* data, int size) {
    if (!data || size <= 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    if (!result) return nullptr;
    env->SetByteArrayRegion(
        result,
        0,
        size,
        reinterpret_cast<const jbyte*>(data));
    return result;
}

void append_u32(std::vector<uint8_t>* output, uint32_t value) {
    output->push_back(static_cast<uint8_t>(value));
    output->push_back(static_cast<uint8_t>(value >> 8));
    output->push_back(static_cast<uint8_t>(value >> 16));
    output->push_back(static_cast<uint8_t>(value >> 24));
}

void write_u32(std::vector<uint8_t>* output, size_t offset, uint32_t value) {
    (*output)[offset] = static_cast<uint8_t>(value);
    (*output)[offset + 1] = static_cast<uint8_t>(value >> 8);
    (*output)[offset + 2] = static_cast<uint8_t>(value >> 16);
    (*output)[offset + 3] = static_cast<uint8_t>(value >> 24);
}

AVCodecContext* subtitle_decoder(JNIEnv* env, DemuxSession* session, jint index) {
    AVStream* stream = checked_stream(env, session, index);
    if (!stream) return nullptr;
    if (stream->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE) {
        throw_illegal_argument(env, "Requested track is not subtitle");
        return nullptr;
    }
    AVCodecContext*& existing = session->subtitle_decoders[index];
    if (existing) return existing;

    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        throw_illegal_state(env, "FFmpeg subtitle decoder is unavailable");
        return nullptr;
    }
    AVCodecContext* context = avcodec_alloc_context3(codec);
    if (!context) {
        throw_illegal_state(env, "Unable to allocate FFmpeg subtitle decoder");
        return nullptr;
    }
    int error = avcodec_parameters_to_context(context, stream->codecpar);
    if (error >= 0) {
        context->pkt_timebase = stream->time_base;
        error = avcodec_open2(context, codec, nullptr);
    }
    if (error < 0) {
        avcodec_free_context(&context);
        throw_illegal_state(env, "FFmpeg subtitle decoder open failed: " + ffmpeg_error(error));
        return nullptr;
    }
    existing = context;
    return existing;
}

bool is_ass_subtitle_codec(AVCodecID codec_id) {
    return codec_id == AV_CODEC_ID_ASS || codec_id == AV_CODEC_ID_SSA;
}

bool subtitle_canvas(const DemuxSession* session, int* width, int* height) {
    if (!session || !session->format || !width || !height) return false;
    for (unsigned int index = 0; index < session->format->nb_streams; ++index) {
        const AVCodecParameters* parameters = session->format->streams[index]->codecpar;
        if (parameters && parameters->codec_type == AVMEDIA_TYPE_VIDEO &&
            parameters->width > 0 && parameters->height > 0) {
            *width = parameters->width;
            *height = parameters->height;
            return true;
        }
    }
    return false;
}

const char* android_ass_fallback_font() {
    static constexpr const char* kCandidates[] = {
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
    };
    for (const char* candidate : kCandidates) {
        if (access(candidate, R_OK) == 0) return candidate;
    }
    return nullptr;
}

void configure_ass_fonts(ASS_Renderer* renderer) {
    if (!renderer) return;
    ass_set_fonts(
        renderer,
        android_ass_fallback_font(),
        "sans-serif",
        ASS_FONTPROVIDER_AUTODETECT,
        nullptr,
        1);
}

ASS_Track* ass_subtitle_track(JNIEnv* env, DemuxSession* session, jint index) {
    AVStream* stream = checked_stream(env, session, index);
    if (!stream) return nullptr;
    if (stream->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE ||
        !is_ass_subtitle_codec(stream->codecpar->codec_id)) {
        throw_illegal_argument(env, "Requested track is not ASS/SSA");
        return nullptr;
    }
    ASS_Track*& existing = session->ass_tracks[index];
    if (existing) return existing;

    if (!session->ass_library) {
        session->ass_library = ass_library_init();
        if (!session->ass_library) {
            throw_illegal_state(env, "Unable to initialize libass");
            return nullptr;
        }
        ass_set_fonts_dir(session->ass_library, "/system/fonts");
    }
    if (!session->ass_renderer) {
        session->ass_renderer = ass_renderer_init(session->ass_library);
        if (!session->ass_renderer) {
            throw_illegal_state(env, "Unable to initialize libass renderer");
            return nullptr;
        }
        configure_ass_fonts(session->ass_renderer);
    }

    ASS_Track* track = ass_new_track(session->ass_library);
    if (!track) {
        throw_illegal_state(env, "Unable to create libass subtitle track");
        return nullptr;
    }
    const AVCodecParameters* parameters = stream->codecpar;
    if (parameters->extradata && parameters->extradata_size > 0) {
        ass_process_codec_private(
            track,
            reinterpret_cast<char*>(parameters->extradata),
            parameters->extradata_size);
    }
    existing = track;
    return existing;
}

SoftwareDecoder* software_decoder(
    JNIEnv* env,
    DemuxSession* session,
    jint index) {
    AVStream* stream = checked_stream(env, session, index);
    if (!stream) return nullptr;
    if (stream->codecpar->codec_type != AVMEDIA_TYPE_VIDEO &&
        stream->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
        throw_illegal_argument(env, "FFmpeg software decoder requires a video or audio track");
        return nullptr;
    }
    std::unique_ptr<SoftwareDecoder>& existing = session->software_decoders[index];
    if (existing) return existing.get();

    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        throw_illegal_state(env, "FFmpeg software decoder is unavailable");
        return nullptr;
    }
    auto decoder = std::make_unique<SoftwareDecoder>();
    decoder->codec = avcodec_alloc_context3(codec);
    decoder->frame = av_frame_alloc();
    if (!decoder->codec || !decoder->frame) {
        throw_illegal_state(env, "Unable to allocate FFmpeg software decoder");
        return nullptr;
    }
    int error = avcodec_parameters_to_context(decoder->codec, stream->codecpar);
    if (error >= 0) {
        decoder->codec->pkt_timebase = stream->time_base;
        decoder->codec->thread_count = 0;
        error = avcodec_open2(decoder->codec, codec, nullptr);
    }
    if (error < 0) {
        throw_illegal_state(env, "FFmpeg software decoder open failed: " + ffmpeg_error(error));
        return nullptr;
    }
    SoftwareDecoder* result = decoder.get();
    existing = std::move(decoder);
    return result;
}

double hdr_mastering_peak_nits(const AVCodecParameters* parameters) {
    const AVPacketSideData* light_side = av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_CONTENT_LIGHT_LEVEL);
    if (light_side && light_side->data && light_side->size >= sizeof(AVContentLightMetadata)) {
        const auto* light = reinterpret_cast<const AVContentLightMetadata*>(light_side->data);
        if (light->MaxCLL > 0) return light->MaxCLL;
    }
    const AVPacketSideData* mastering_side = av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_MASTERING_DISPLAY_METADATA);
    if (mastering_side && mastering_side->data &&
        mastering_side->size >= sizeof(AVMasteringDisplayMetadata)) {
        const auto* mastering =
            reinterpret_cast<const AVMasteringDisplayMetadata*>(mastering_side->data);
        if (mastering->has_luminance) {
            const double maximum = av_q2d(mastering->max_luminance);
            if (maximum > 0.0) return maximum;
        }
    }
    return 1000.0;
}

bool tone_map_hdr_frame(
    JNIEnv* env,
    SoftwareDecoder* decoder,
    AVStream* stream,
    uint8_t* destination) {
    const int width = decoder->frame->width;
    const int height = decoder->frame->height;
    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (pixel_count == 0 || pixel_count > kMaxSoftwareToneMapPixels) {
        throw_illegal_state(env, "FFmpeg HDR tone-map frame exceeds the 4K safety limit");
        return false;
    }
    const AVColorTransferCharacteristic transfer =
        decoder->frame->color_trc != AVCOL_TRC_UNSPECIFIED
        ? decoder->frame->color_trc
        : stream->codecpar->color_trc;
    if (transfer != AVCOL_TRC_SMPTE2084 && transfer != AVCOL_TRC_ARIB_STD_B67) {
        throw_illegal_state(env, "FFmpeg HDR tone-map transfer is unsupported or unspecified");
        return false;
    }
    const AVColorPrimaries primaries =
        decoder->frame->color_primaries != AVCOL_PRI_UNSPECIFIED
        ? decoder->frame->color_primaries
        : stream->codecpar->color_primaries;
    if (primaries != AVCOL_PRI_BT2020 && primaries != AVCOL_PRI_UNSPECIFIED) {
        throw_illegal_state(env, "FFmpeg HDR tone-map primaries are not BT.2020");
        return false;
    }

    try {
        decoder->tone_map_rgb48.resize(pixel_count * 3U);
    } catch (const std::bad_alloc&) {
        throw_illegal_state(env, "Unable to allocate the FFmpeg HDR tone-map buffer");
        return false;
    }
    decoder->scaler = sws_getCachedContext(
        decoder->scaler,
        width,
        height,
        static_cast<AVPixelFormat>(decoder->frame->format),
        width,
        height,
        AV_PIX_FMT_RGB48LE,
        SWS_BILINEAR,
        nullptr,
        nullptr,
        nullptr);
    if (!decoder->scaler) {
        throw_illegal_state(env, "FFmpeg HDR tone-map scaler is unavailable");
        return false;
    }
    const int* source_coefficients = sws_getCoefficients(SWS_CS_BT2020);
    const int* target_coefficients = sws_getCoefficients(SWS_CS_ITU709);
    const int source_full_range = decoder->frame->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
    if (!source_coefficients || !target_coefficients ||
        sws_setColorspaceDetails(
            decoder->scaler,
            source_coefficients,
            source_full_range,
            target_coefficients,
            1,
            0,
            1 << 16,
            1 << 16) < 0) {
        throw_illegal_state(env, "FFmpeg HDR tone-map colorspace configuration failed");
        return false;
    }
    uint8_t* intermediate_data[] = {
        reinterpret_cast<uint8_t*>(decoder->tone_map_rgb48.data()),
        nullptr,
        nullptr,
        nullptr,
    };
    const int intermediate_linesize[] = {width * 6, 0, 0, 0};
    const int scaled = sws_scale(
        decoder->scaler,
        decoder->frame->data,
        decoder->frame->linesize,
        0,
        height,
        intermediate_data,
        intermediate_linesize);
    if (scaled != height) {
        throw_illegal_state(env, "FFmpeg HDR tone-map conversion was incomplete");
        return false;
    }

    const ycore_tone_map::Transfer tone_map_transfer =
        transfer == AVCOL_TRC_SMPTE2084
        ? ycore_tone_map::Transfer::Pq
        : ycore_tone_map::Transfer::Hlg;
    const double mastering_peak_nits = hdr_mastering_peak_nits(stream->codecpar);
    for (size_t pixel = 0; pixel < pixel_count; ++pixel) {
        const size_t source_offset = pixel * 3U;
        const ycore_tone_map::BgraPixel output = ycore_tone_map::bt2020_to_sdr(
            decoder->tone_map_rgb48[source_offset],
            decoder->tone_map_rgb48[source_offset + 1U],
            decoder->tone_map_rgb48[source_offset + 2U],
            tone_map_transfer,
            mastering_peak_nits);
        const size_t target_offset = pixel * 4U;
        destination[target_offset] = output.blue;
        destination[target_offset + 1U] = output.green;
        destination[target_offset + 2U] = output.red;
        destination[target_offset + 3U] = output.alpha;
    }
    return true;
}

bool append_bitmap_rect(std::vector<uint8_t>* output, const AVSubtitleRect* rect) {
    if (!rect || rect->type != SUBTITLE_BITMAP || rect->w <= 0 || rect->h <= 0 ||
        rect->nb_colors <= 0 || rect->nb_colors > 256 || !rect->data[0] || !rect->data[1] ||
        rect->linesize[0] < rect->w) {
        return false;
    }
    const size_t pixel_count = static_cast<size_t>(rect->w) * static_cast<size_t>(rect->h);
    const size_t required = 7U * sizeof(uint32_t) + pixel_count * sizeof(uint32_t);
    if (pixel_count > kMaxSubtitlePayloadBytes / sizeof(uint32_t) ||
        required > kMaxSubtitlePayloadBytes ||
        output->size() > kMaxSubtitlePayloadBytes - required) {
        return false;
    }

    append_u32(output, static_cast<uint32_t>(rect->x));
    append_u32(output, static_cast<uint32_t>(rect->y));
    append_u32(output, static_cast<uint32_t>(rect->w));
    append_u32(output, static_cast<uint32_t>(rect->h));
    append_u32(output, static_cast<uint32_t>(rect->flags));
    append_u32(output, static_cast<uint32_t>(pixel_count));
    append_u32(output, 0);
    for (int y = 0; y < rect->h; ++y) {
        const uint8_t* indexes = rect->data[0] + static_cast<size_t>(y) * rect->linesize[0];
        for (int x = 0; x < rect->w; ++x) {
            const uint8_t palette_index = indexes[x];
            uint32_t color = 0;
            if (palette_index < rect->nb_colors) {
                std::memcpy(&color, rect->data[1] + palette_index * sizeof(color), sizeof(color));
            }
            append_u32(output, color);
        }
    }
    return true;
}

bool append_ass_image(
    std::vector<uint8_t>* output,
    const ASS_Image* image,
    int canvas_width,
    int canvas_height) {
    if (!output || !image || !image->bitmap || image->w <= 0 || image->h <= 0 ||
        image->stride < image->w || canvas_width <= 0 || canvas_height <= 0) {
        return false;
    }
    const int left = std::max(0, image->dst_x);
    const int top = std::max(0, image->dst_y);
    const int right = std::min(canvas_width, image->dst_x + image->w);
    const int bottom = std::min(canvas_height, image->dst_y + image->h);
    const int width = right - left;
    const int height = bottom - top;
    if (width <= 0 || height <= 0) return false;

    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    const size_t required = 7U * sizeof(uint32_t) + pixel_count * sizeof(uint32_t);
    if (pixel_count > kMaxSubtitlePayloadBytes / sizeof(uint32_t) ||
        required > kMaxSubtitlePayloadBytes ||
        output->size() > kMaxSubtitlePayloadBytes - required) {
        return false;
    }

    append_u32(output, static_cast<uint32_t>(left));
    append_u32(output, static_cast<uint32_t>(top));
    append_u32(output, static_cast<uint32_t>(width));
    append_u32(output, static_cast<uint32_t>(height));
    append_u32(output, 1U);  // libass-rendered rectangle
    append_u32(output, static_cast<uint32_t>(pixel_count));
    append_u32(output, 0U);

    const uint8_t red = static_cast<uint8_t>(image->color >> 24);
    const uint8_t green = static_cast<uint8_t>(image->color >> 16);
    const uint8_t blue = static_cast<uint8_t>(image->color >> 8);
    const uint8_t opacity = static_cast<uint8_t>(255U - (image->color & 0xffU));
    const int source_x = left - image->dst_x;
    const int source_y = top - image->dst_y;
    for (int y = 0; y < height; ++y) {
        const uint8_t* coverage =
            image->bitmap + static_cast<size_t>(source_y + y) * image->stride + source_x;
        for (int x = 0; x < width; ++x) {
            const uint32_t alpha =
                (static_cast<uint32_t>(coverage[x]) * opacity + 127U) / 255U;
            append_u32(
                output,
                (alpha << 24) |
                    (static_cast<uint32_t>(red) << 16) |
                    (static_cast<uint32_t>(green) << 8) |
                    static_cast<uint32_t>(blue));
        }
    }
    return true;
}

jlongArray make_packet_result(
    JNIEnv* env,
    jlong status,
    jlong stream_index,
    jlong size,
    jlong pts_us,
    jlong dts_us,
    jlong duration_us,
    jlong flags) {
    const jlong values[] = {
        status,
        stream_index,
        size,
        pts_us,
        dts_us,
        duration_us,
        flags,
    };
    jlongArray result = env->NewLongArray(sizeof(values) / sizeof(values[0]));
    if (result) {
        env->SetLongArrayRegion(result, 0, sizeof(values) / sizeof(values[0]), values);
    }
    return result;
}

jlongArray make_software_frame_result(
    JNIEnv* env,
    jlong status,
    jlong size,
    jlong pts_us,
    jlong first,
    jlong second,
    jlong third) {
    const jlong values[] = {status, size, pts_us, first, second, third};
    jlongArray result = env->NewLongArray(sizeof(values) / sizeof(values[0]));
    if (result) {
        env->SetLongArrayRegion(result, 0, sizeof(values) / sizeof(values[0]), values);
    }
    return result;
}

jint native_disc_api_version(JNIEnv*, jclass) {
    return kDiscApiVersion;
}

jlong native_register_bluray_source(JNIEnv* env, jclass, jobject source_object) {
    if (!source_object) {
        throw_illegal_argument(env, "Blu-ray source object is required");
        return 0;
    }
    jclass source_class = env->GetObjectClass(source_object);
    if (!source_class) return 0;
    auto source = std::make_shared<DiscSource>();
    env->GetJavaVM(&source->vm);
    source->read_blocks = env->GetMethodID(source_class, "readBlocksNative", "(II[BI)I");
    source->publish_state = env->GetMethodID(source_class, "onNativeDiscState", "(IIIIIIZZ)V");
    source->overlay_frame = env->GetMethodID(source_class, "onNativeOverlayFrame", "(II[I)V");
    source->overlay_cleared = env->GetMethodID(source_class, "onNativeOverlayCleared", "()V");
    source->close_source = env->GetMethodID(source_class, "closeNativeSource", "()V");
    const jmethodID path_method =
        env->GetMethodID(source_class, "discPathNative", "()Ljava/lang/String;");
    if (
        !source->read_blocks ||
        !source->publish_state ||
        !source->overlay_frame ||
        !source->overlay_cleared ||
        !source->close_source ||
        !path_method ||
        env->ExceptionCheck()
    ) {
        clear_java_exception(env);
        env->DeleteLocalRef(source_class);
        throw_illegal_argument(env, "Blu-ray source object has an incompatible callback contract");
        return 0;
    }
    source->open_file = env->GetMethodID(source_class, "openFileNative", "(Ljava/lang/String;)J");
    clear_java_exception(env);
    source->read_file = env->GetMethodID(source_class, "readFileNative", "(J[BII)I");
    clear_java_exception(env);
    source->seek_file = env->GetMethodID(source_class, "seekFileNative", "(JJI)J");
    clear_java_exception(env);
    source->tell_file = env->GetMethodID(source_class, "tellFileNative", "(J)J");
    clear_java_exception(env);
    source->close_file = env->GetMethodID(source_class, "closeFileNative", "(J)V");
    clear_java_exception(env);
    source->open_dir = env->GetMethodID(source_class, "openDirNative", "(Ljava/lang/String;)J");
    clear_java_exception(env);
    source->read_dir = env->GetMethodID(source_class, "readDirNative", "(J)Ljava/lang/String;");
    clear_java_exception(env);
    source->close_dir = env->GetMethodID(source_class, "closeDirNative", "(J)V");
    clear_java_exception(env);
    const bool filesystem_contract =
        source->open_file &&
        source->read_file &&
        source->seek_file &&
        source->tell_file &&
        source->close_file &&
        source->open_dir &&
        source->read_dir &&
        source->close_dir;
    const jmethodID filesystem_method =
        env->GetMethodID(source_class, "isBdmvFilesystemNative", "()Z");
    clear_java_exception(env);
    if (filesystem_contract && filesystem_method) {
        source->filesystem_source = env->CallBooleanMethod(source_object, filesystem_method) == JNI_TRUE;
        if (env->ExceptionCheck()) {
            clear_java_exception(env);
            source->filesystem_source = false;
        }
    }
    auto path_ref = static_cast<jstring>(env->CallObjectMethod(source_object, path_method));
    if (env->ExceptionCheck()) {
        clear_java_exception(env);
        env->DeleteLocalRef(source_class);
        throw_illegal_argument(env, "Blu-ray source path callback failed");
        return 0;
    }
    source->path = to_utf8(env, path_ref);
    if (path_ref) env->DeleteLocalRef(path_ref);
    source->object = env->NewGlobalRef(source_object);
    env->DeleteLocalRef(source_class);
    if (!source->object) {
        throw_illegal_state(env, "Unable to retain Blu-ray source object");
        return 0;
    }

    std::lock_guard<std::mutex> lock(g_disc_sources_mutex);
    if (g_disc_sources.size() >= 16) {
        throw_illegal_state(env, "Too many registered Blu-ray sources");
        return 0;
    }
    const int64_t source_id = g_next_disc_source_id.fetch_add(1);
    if (source_id <= 0) {
        throw_illegal_state(env, "Blu-ray source id space is exhausted");
        return 0;
    }
    g_disc_sources.emplace(source_id, std::move(source));
    return static_cast<jlong>(source_id);
}

void native_unregister_bluray_source(JNIEnv*, jclass, jlong source_id) {
    std::shared_ptr<DiscSource> removed;
    {
        std::lock_guard<std::mutex> lock(g_disc_sources_mutex);
        const auto found = g_disc_sources.find(static_cast<int64_t>(source_id));
        if (found == g_disc_sources.end()) return;
        removed = std::move(found->second);
        g_disc_sources.erase(found);
    }
}

jboolean native_select_disc_title(JNIEnv*, jclass, jlong source_id, jint index) {
    const std::shared_ptr<DiscSource> source = find_disc_source(source_id);
    if (!source || index < 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(source->mutex);
    BlurayIo* disc = source->active;
    if (!disc || index >= disc->title_count) return JNI_FALSE;
    if (!bd_select_title(disc->bd, static_cast<uint32_t>(index))) return JNI_FALSE;
    source->preferred_title = index;
    refresh_disc_title_info(disc);
    publish_disc_state(disc);
    return JNI_TRUE;
}

jlong native_disc_chapter_start_ms(JNIEnv*, jclass, jlong source_id, jint index) {
    const std::shared_ptr<DiscSource> source = find_disc_source(source_id);
    if (!source || index < 0) return -1;
    std::lock_guard<std::mutex> lock(source->mutex);
    BlurayIo* disc = source->active;
    if (
        !disc ||
        !disc->title_info ||
        static_cast<uint32_t>(index) >= disc->title_info->chapter_count
    ) {
        return -1;
    }
    return static_cast<jlong>(disc->title_info->chapters[index].start / (kBlurayClock / 1000));
}

jboolean native_select_disc_angle(JNIEnv*, jclass, jlong source_id, jint index) {
    const std::shared_ptr<DiscSource> source = find_disc_source(source_id);
    if (!source || index < 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(source->mutex);
    BlurayIo* disc = source->active;
    if (
        !disc ||
        !disc->title_info ||
        static_cast<uint32_t>(index) >= disc->title_info->angle_count
    ) {
        return JNI_FALSE;
    }
    bd_seamless_angle_change(disc->bd, static_cast<unsigned>(index));
    disc->current_angle = index;
    refresh_disc_title_info(disc);
    publish_disc_state(disc);
    return JNI_TRUE;
}

bool ensure_disc_navigation(BlurayIo* disc) {
    if (!disc || !disc->bd || !disc->menu_supported) return false;
    if (disc->navigation_mode) return true;
    if (!bd_play(disc->bd)) return false;
    disc->navigation_mode = true;
    return true;
}

jboolean native_send_disc_menu_command(JNIEnv*, jclass, jlong source_id, jint command) {
    const std::shared_ptr<DiscSource> source = find_disc_source(source_id);
    if (!source) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(source->mutex);
    BlurayIo* disc = source->active;
    if (!ensure_disc_navigation(disc)) return JNI_FALSE;
    const int64_t pts = static_cast<int64_t>(bd_tell_time(disc->bd));
    int result = -1;
    switch (command) {
        case 0:
            result = bd_menu_call(disc->bd, pts);
            break;
        case 1:
            result = bd_user_input(
                disc->bd,
                pts,
                disc->popup_available ? BD_VK_POPUP : BD_VK_ROOT_MENU);
            break;
        case 2:
            result = bd_user_input(disc->bd, pts, BD_VK_UP);
            break;
        case 3:
            result = bd_user_input(disc->bd, pts, BD_VK_DOWN);
            break;
        case 4:
            result = bd_user_input(disc->bd, pts, BD_VK_LEFT);
            break;
        case 5:
            result = bd_user_input(disc->bd, pts, BD_VK_RIGHT);
            break;
        case 6:
            result = bd_user_input(disc->bd, pts, BD_VK_ENTER);
            break;
        default:
            return JNI_FALSE;
    }
    drain_disc_events(disc);
    return result >= 0 ? JNI_TRUE : JNI_FALSE;
}

jboolean native_select_disc_menu_point(
    JNIEnv*,
    jclass,
    jlong source_id,
    jint x,
    jint y,
    jboolean activate) {
    if (x < 0 || y < 0 || x > UINT16_MAX || y > UINT16_MAX) return JNI_FALSE;
    const std::shared_ptr<DiscSource> source = find_disc_source(source_id);
    if (!source) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(source->mutex);
    BlurayIo* disc = source->active;
    if (!ensure_disc_navigation(disc)) return JNI_FALSE;
    const int64_t pts = static_cast<int64_t>(bd_tell_time(disc->bd));
    int selected = bd_mouse_select(
        disc->bd,
        pts,
        static_cast<uint16_t>(x),
        static_cast<uint16_t>(y));
    if (selected <= 0) return JNI_FALSE;
    if (activate) selected = bd_user_input(disc->bd, pts, BD_VK_MOUSE_ACTIVATE);
    drain_disc_events(disc);
    return selected >= 0 ? JNI_TRUE : JNI_FALSE;
}

jlong open_session(
    JNIEnv* env,
    jstring uri,
    jobjectArray header_names,
    jobjectArray header_values,
    bool probe_only) {
    if (!uri) {
        throw_illegal_argument(env, "Media URI is required");
        return 0;
    }
    const std::string source = to_utf8(env, uri);
    if (source.empty()) {
        throw_illegal_argument(env, "Media URI is empty");
        return 0;
    }

    bool headers_valid = false;
    const std::string headers = build_headers(env, header_names, header_values, &headers_valid);
    if (!headers_valid || env->ExceptionCheck()) return 0;

    g_last_open_failure.clear();
    auto session = std::make_unique<DemuxSession>();
    int64_t disc_source_id = 0;
    const bool disc_source = ycore_disc::parse_source_id(source, &disc_source_id);
    session->remote_source = is_remote_source(source);
    session->packet = av_packet_alloc();
    if (!session->packet) {
        throw_illegal_state(env, "Unable to allocate FFmpeg packet");
        return 0;
    }

    if (disc_source) {
        const int error = open_bluray_demux(disc_source_id, session.get());
        if (error < 0) {
            record_open_failure("open_bluray_demux", error);
            return open_failure_status(error, false, kOpenStageDisc);
        }
    }

    AVDictionary* options = nullptr;
    if (!headers.empty()) {
        av_dict_set(&options, "headers", headers.c_str(), 0);
    }
    if (session->remote_source) {
        av_dict_set(&options, "multiple_requests", "1", 0);
        av_dict_set(&options, "reconnect", "1", 0);
        av_dict_set(&options, "reconnect_on_network_error", "1", 0);
        av_dict_set(&options, "reconnect_on_http_error", "408,429,5xx", 0);
        av_dict_set(&options, "reconnect_streamed", "1", 0);
        av_dict_set(&options, "reconnect_delay_max", "5", 0);
        av_dict_set(&options, "reconnect_max_retries", "5", 0);
        av_dict_set(&options, "respect_retry_after", "1", 0);
        av_dict_set(&options, "rw_timeout", "15000000", 0);
    }

    if (probe_only) {
        // A truth probe only needs stream parameters. Keep FFmpeg from reading its default
        // 5 MB / 5 s analysis window over the network before it answers.
        av_dict_set(&options, "probesize", kProbeSizeBytes, 0);
        av_dict_set(&options, "analyzeduration", kProbeAnalyzeDurationUs, 0);
        av_dict_set(&options, "fflags", "nobuffer", 0);
    }
    int error =
        disc_source
        ? 0
        : avformat_open_input(&session->format, source.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (error < 0) {
        record_open_failure("avformat_open_input", error);
        return open_failure_status(error, session->remote_source, kOpenStageOpenInput);
    }
    error = avformat_find_stream_info(session->format, nullptr);
    if (error < 0) {
        record_open_failure("avformat_find_stream_info", error);
        return open_failure_status(error, session->remote_source, kOpenStageStreamInfo);
    }

    session->selected.assign(session->format->nb_streams, 0);
    session->subtitle_decoders.assign(session->format->nb_streams, nullptr);
    session->ass_tracks.assign(session->format->nb_streams, nullptr);
    session->software_decoders.resize(session->format->nb_streams);
    const jlong handle = to_handle(session.get());
    if (handle <= 0) {
        throw_illegal_state(env, "FFmpeg demux session id space is exhausted");
        return 0;
    }
    session.release();
    return handle;
}

jlong native_open(
    JNIEnv* env,
    jclass,
    jstring uri,
    jobjectArray header_names,
    jobjectArray header_values) {
    return open_session(env, uri, header_names, header_values, false);
}

jlong native_open_probe(
    JNIEnv* env,
    jclass,
    jstring uri,
    jobjectArray header_names,
    jobjectArray header_values) {
    return open_session(env, uri, header_names, header_values, true);
}

void native_close(JNIEnv*, jclass, jlong handle) {
    delete release_handle(handle);
}

jstring native_last_open_failure(JNIEnv* env, jclass) {
    return g_last_open_failure.empty() ? nullptr : nullable_string(env, g_last_open_failure.c_str());
}

jint native_track_count(JNIEnv* env, jclass, jlong handle) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return 0;
    }
    return static_cast<jint>(session->format->nb_streams);
}

jstring native_container_name(JNIEnv* env, jclass, jlong handle) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format || !session->format->iformat) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return nullptr;
    }
    return nullable_string(env, session->format->iformat->name);
}

jlong native_duration_us(JNIEnv* env, jclass, jlong handle) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return kNoTimestamp;
    }
    if (session->disc && session->disc->title_info) {
        return static_cast<jlong>(
            av_rescale_q(
                static_cast<int64_t>(session->disc->title_info->duration),
                AVRational{1, static_cast<int>(kBlurayClock)},
                AV_TIME_BASE_Q));
    }
    return session->format->duration == AV_NOPTS_VALUE
        ? kNoTimestamp
        : static_cast<jlong>(session->format->duration);
}

jlong native_bit_rate_bits_per_second(JNIEnv* env, jclass, jlong handle) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return 0;
    }
    return static_cast<jlong>(std::max<int64_t>(0, session->format->bit_rate));
}

jint native_track_type(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    return stream ? track_type(stream->codecpar) : kTrackUnknown;
}

jstring native_track_codec_name(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    if (!stream) return nullptr;
    return nullable_string(env, avcodec_get_name(stream->codecpar->codec_id));
}

jlongArray native_track_video_info(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    if (!stream) return nullptr;
    const AVCodecParameters* parameters = stream->codecpar;
    if (parameters->codec_type != AVMEDIA_TYPE_VIDEO) {
        throw_illegal_argument(env, "Requested track is not video");
        return nullptr;
    }
    const AVRational frame_rate = av_guess_frame_rate(from_handle(handle)->format, stream, nullptr);
    const AVRational sample_aspect_ratio = av_guess_sample_aspect_ratio(
        from_handle(handle)->format, stream, nullptr);
    const AVPacketSideData* display_matrix_side = av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_DISPLAYMATRIX);
    int rotation_degrees = 0;
    if (display_matrix_side && display_matrix_side->data &&
        display_matrix_side->size >= 9 * sizeof(int32_t)) {
        const double rotation = -av_display_rotation_get(
            reinterpret_cast<const int32_t*>(display_matrix_side->data));
        if (std::isfinite(rotation)) rotation_degrees = static_cast<int>(std::lround(rotation));
    }
    const auto packing = sample_packing(parameters);
    const jlong values[] = {
        parameters->width,
        parameters->height,
        frame_rate.num,
        frame_rate.den,
        bit_depth(parameters),
        hdr_type(parameters),
        parameters->profile,
        parameters->level,
        packing.first,
        packing.second,
        parameters->color_range,
        parameters->color_space,
        parameters->color_primaries,
        parameters->color_trc,
        parameters->chroma_location,
        sample_aspect_ratio.num > 0 ? sample_aspect_ratio.num : 1,
        sample_aspect_ratio.den > 0 ? sample_aspect_ratio.den : 1,
        rotation_degrees,
        0,
        0,
        0,
        0,
    };
    jlongArray result = env->NewLongArray(sizeof(values) / sizeof(values[0]));
    if (result) {
        env->SetLongArrayRegion(result, 0, sizeof(values) / sizeof(values[0]), values);
    }
    return result;
}

jlongArray native_track_audio_info(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    if (!stream) return nullptr;
    const AVCodecParameters* parameters = stream->codecpar;
    if (parameters->codec_type != AVMEDIA_TYPE_AUDIO) {
        throw_illegal_argument(env, "Requested track is not audio");
        return nullptr;
    }
    const jlong values[] = {
        parameters->ch_layout.nb_channels,
        parameters->sample_rate,
        parameters->profile,
        parameters->level,
    };
    jlongArray result = env->NewLongArray(sizeof(values) / sizeof(values[0]));
    if (result) {
        env->SetLongArrayRegion(result, 0, sizeof(values) / sizeof(values[0]), values);
    }
    return result;
}

jstring native_track_language(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    return stream ? dictionary_value(env, stream->metadata, "language") : nullptr;
}

jstring native_track_title(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    return stream ? dictionary_value(env, stream->metadata, "title") : nullptr;
}

jbyteArray native_track_extradata(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    if (!stream) return nullptr;
    return copy_bytes(env, stream->codecpar->extradata, stream->codecpar->extradata_size);
}

jintArray native_track_dolby_config(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    if (!stream) return nullptr;
    const AVCodecParameters* parameters = stream->codecpar;
    const AVPacketSideData* side = av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_DOVI_CONF);
    if (!side || !side->data || side->size < sizeof(AVDOVIDecoderConfigurationRecord)) {
        return nullptr;
    }
    const auto* config = reinterpret_cast<const AVDOVIDecoderConfigurationRecord*>(side->data);
    const jint values[] = {
        config->dv_version_major,
        config->dv_version_minor,
        config->dv_profile,
        config->dv_level,
        config->rpu_present_flag,
        config->el_present_flag,
        config->bl_present_flag,
        config->dv_bl_signal_compatibility_id,
        config->dv_md_compression,
    };
    jintArray result = env->NewIntArray(sizeof(values) / sizeof(values[0]));
    if (result) {
        env->SetIntArrayRegion(result, 0, sizeof(values) / sizeof(values[0]), values);
    }
    return result;
}

jintArray native_track_hdr_static_info(JNIEnv* env, jclass, jlong handle, jint index) {
    AVStream* stream = checked_stream(env, from_handle(handle), index);
    if (!stream) return nullptr;
    const AVCodecParameters* parameters = stream->codecpar;
    if (parameters->codec_type != AVMEDIA_TYPE_VIDEO) {
        throw_illegal_argument(env, "Requested track is not video");
        return nullptr;
    }

    const AVPacketSideData* mastering_side = av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_MASTERING_DISPLAY_METADATA);
    const AVPacketSideData* light_side = av_packet_side_data_get(
        parameters->coded_side_data,
        parameters->nb_coded_side_data,
        AV_PKT_DATA_CONTENT_LIGHT_LEVEL);
    if (!mastering_side && !light_side) return nullptr;

    const AVMasteringDisplayMetadata* mastering =
        mastering_side && mastering_side->size >= sizeof(AVMasteringDisplayMetadata)
        ? reinterpret_cast<const AVMasteringDisplayMetadata*>(mastering_side->data)
        : nullptr;
    const AVContentLightMetadata* light =
        light_side && light_side->size >= sizeof(AVContentLightMetadata)
        ? reinterpret_cast<const AVContentLightMetadata*>(light_side->data)
        : nullptr;

    jint values[12] = {};
    if (mastering && mastering->has_primaries) {
        for (int primary = 0; primary < 3; ++primary) {
            values[primary * 2] = rational_to_u16(mastering->display_primaries[primary][0], 50000.0);
            values[primary * 2 + 1] = rational_to_u16(mastering->display_primaries[primary][1], 50000.0);
        }
        values[6] = rational_to_u16(mastering->white_point[0], 50000.0);
        values[7] = rational_to_u16(mastering->white_point[1], 50000.0);
    }
    if (mastering && mastering->has_luminance) {
        values[8] = rational_to_u16(mastering->max_luminance, 1.0);
        values[9] = rational_to_u16(mastering->min_luminance, 10000.0);
    }
    if (light) {
        values[10] = static_cast<jint>(std::min(light->MaxCLL, 65535U));
        values[11] = static_cast<jint>(std::min(light->MaxFALL, 65535U));
    }
    jintArray result = env->NewIntArray(sizeof(values) / sizeof(values[0]));
    if (result) {
        env->SetIntArrayRegion(result, 0, sizeof(values) / sizeof(values[0]), values);
    }
    return result;
}

void native_select_tracks(JNIEnv* env, jclass, jlong handle, jintArray indexes) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return;
    }
    std::fill(session->selected.begin(), session->selected.end(), 0);
    if (!indexes) return;
    const jsize count = env->GetArrayLength(indexes);
    std::vector<jint> values(count);
    env->GetIntArrayRegion(indexes, 0, count, values.data());
    for (jint index : values) {
        if (index < 0 || static_cast<unsigned int>(index) >= session->format->nb_streams) {
            throw_illegal_argument(env, "Selected track index is outside the demux session");
            return;
        }
        session->selected[index] = 1;
    }
    if (session->packet_pending &&
        (session->packet->stream_index < 0 ||
         static_cast<size_t>(session->packet->stream_index) >= session->selected.size() ||
         !session->selected[session->packet->stream_index])) {
        av_packet_unref(session->packet);
        session->packet_pending = false;
    }
}

jlongArray native_read_packet(JNIEnv* env, jclass, jlong handle, jobject target) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format || !session->packet) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return nullptr;
    }
    auto* destination = static_cast<uint8_t*>(env->GetDirectBufferAddress(target));
    const jlong capacity = env->GetDirectBufferCapacity(target);
    if (!destination || capacity < 0) {
        throw_illegal_argument(env, "FFmpeg packet target must be a direct ByteBuffer");
        return nullptr;
    }

    while (!session->packet_pending) {
        const int error = av_read_frame(session->format, session->packet);
        if (error == AVERROR_EOF) {
            return make_packet_result(
                env,
                kPacketStatusEof,
                -1,
                0,
                kNoTimestamp,
                kNoTimestamp,
                kNoTimestamp,
                0);
        }
        if (error < 0) {
            return make_packet_result(
                env,
                failure_status(error, session->remote_source),
                -1,
                0,
                kNoTimestamp,
                kNoTimestamp,
                kNoTimestamp,
                0);
        }
        const int stream_index = session->packet->stream_index;
        if (stream_index < 0 ||
            static_cast<size_t>(stream_index) >= session->selected.size() ||
            !session->selected[stream_index]) {
            av_packet_unref(session->packet);
            continue;
        }
        session->packet_pending = true;
    }

    AVPacket* packet = session->packet;
    AVStream* stream = session->format->streams[packet->stream_index];
    if (packet->size > capacity) {
        return make_packet_result(
            env,
            kPacketStatusGrowBuffer,
            packet->stream_index,
            packet->size,
            timestamp_us(packet->pts, stream->time_base),
            timestamp_us(packet->dts, stream->time_base),
            packet->duration > 0 ? timestamp_us(packet->duration, stream->time_base) : kNoTimestamp,
            0);
    }

    if (packet->size > 0) {
        std::memcpy(destination, packet->data, static_cast<size_t>(packet->size));
    }
    int flags = 0;
    if (packet->flags & AV_PKT_FLAG_KEY) flags |= kSampleFlagSync;
    size_t encryption_size = 0;
    if (av_packet_get_side_data(packet, AV_PKT_DATA_ENCRYPTION_INFO, &encryption_size) != nullptr) {
        flags |= kSampleFlagEncrypted;
    }
    jlongArray result = make_packet_result(
        env,
        kPacketStatusData,
        packet->stream_index,
        packet->size,
        timestamp_us(packet->pts, stream->time_base),
        timestamp_us(packet->dts, stream->time_base),
        packet->duration > 0 ? timestamp_us(packet->duration, stream->time_base) : kNoTimestamp,
        flags);
    av_packet_unref(packet);
    session->packet_pending = false;
    return result;
}

jbyteArray native_decode_subtitle(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint index,
    jbyteArray encoded,
    jlong presentation_time_us,
    jlong duration_us) {
    DemuxSession* session = from_handle(handle);
    AVStream* stream = checked_stream(env, session, index);
    if (!stream || !encoded) return nullptr;

    const jsize encoded_size = env->GetArrayLength(encoded);
    if (encoded_size <= 0 || static_cast<size_t>(encoded_size) > kMaxSubtitlePayloadBytes) {
        return nullptr;
    }
    if (is_ass_subtitle_codec(stream->codecpar->codec_id)) {
        ASS_Track* track = ass_subtitle_track(env, session, index);
        if (!track || env->ExceptionCheck()) return nullptr;
        int canvas_width = 0;
        int canvas_height = 0;
        if (!subtitle_canvas(session, &canvas_width, &canvas_height)) return nullptr;

        std::vector<char> chunk(static_cast<size_t>(encoded_size));
        env->GetByteArrayRegion(encoded, 0, encoded_size, reinterpret_cast<jbyte*>(chunk.data()));
        if (env->ExceptionCheck()) return nullptr;
        const int64_t start_ms =
            presentation_time_us == kNoTimestamp
            ? 0
            : std::max<int64_t>(0, presentation_time_us / 1000);
        const int64_t duration_ms =
            duration_us > 0 ? std::max<int64_t>(1, duration_us / 1000) : 5000;
        ass_set_frame_size(session->ass_renderer, canvas_width, canvas_height);
        ass_set_storage_size(session->ass_renderer, canvas_width, canvas_height);
        ass_process_chunk(track, chunk.data(), encoded_size, start_ms, duration_ms);
        int changed = 0;
        ASS_Image* images = ass_render_frame(session->ass_renderer, track, start_ms, &changed);
        if (!images) return nullptr;

        std::vector<uint8_t> output;
        output.reserve(1024);
        append_u32(&output, kSubtitlePayloadMagic);
        append_u32(&output, kSubtitlePayloadVersion);
        append_u32(&output, static_cast<uint32_t>(canvas_width));
        append_u32(&output, static_cast<uint32_t>(canvas_height));
        append_u32(&output, 0U);
        append_u32(
            &output,
            static_cast<uint32_t>(std::min<int64_t>(duration_ms, UINT32_MAX)));
        const size_t rect_count_offset = output.size();
        append_u32(&output, 0U);
        uint32_t rect_count = 0;
        for (ASS_Image* image = images; image && rect_count < 64U; image = image->next) {
            if (append_ass_image(&output, image, canvas_width, canvas_height)) ++rect_count;
        }
        if (rect_count == 0 || output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            return nullptr;
        }
        write_u32(&output, rect_count_offset, rect_count);
        jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) {
            env->SetByteArrayRegion(
                result,
                0,
                static_cast<jsize>(output.size()),
                reinterpret_cast<const jbyte*>(output.data()));
        }
        return result;
    }

    AVCodecContext* decoder = subtitle_decoder(env, session, index);
    if (!decoder || env->ExceptionCheck()) return nullptr;
    AVPacket* packet = av_packet_alloc();
    if (!packet || av_new_packet(packet, encoded_size) < 0) {
        av_packet_free(&packet);
        throw_illegal_state(env, "Unable to allocate FFmpeg subtitle packet");
        return nullptr;
    }
    env->GetByteArrayRegion(encoded, 0, encoded_size, reinterpret_cast<jbyte*>(packet->data));
    if (env->ExceptionCheck()) {
        av_packet_free(&packet);
        return nullptr;
    }
    packet->stream_index = index;
    packet->pts = presentation_time_us == kNoTimestamp
        ? AV_NOPTS_VALUE
        : av_rescale_q(presentation_time_us, AV_TIME_BASE_Q, stream->time_base);
    packet->dts = packet->pts;
    packet->duration = duration_us > 0
        ? av_rescale_q(duration_us, AV_TIME_BASE_Q, stream->time_base)
        : 0;

    AVSubtitle subtitle = {};
    int got_subtitle = 0;
    const int error = avcodec_decode_subtitle2(decoder, &subtitle, &got_subtitle, packet);
    av_packet_free(&packet);
    if (error < 0) {
        avsubtitle_free(&subtitle);
        throw_illegal_state(env, "FFmpeg subtitle decode failed: " + ffmpeg_error(error));
        return nullptr;
    }
    if (!got_subtitle || subtitle.num_rects == 0) {
        avsubtitle_free(&subtitle);
        return nullptr;
    }

    int canvas_width = std::max(0, decoder->width);
    int canvas_height = std::max(0, decoder->height);
    for (unsigned int i = 0; i < subtitle.num_rects; ++i) {
        const AVSubtitleRect* rect = subtitle.rects[i];
        if (rect && rect->type == SUBTITLE_BITMAP && rect->x >= 0 && rect->y >= 0) {
            canvas_width = std::max(canvas_width, rect->x + rect->w);
            canvas_height = std::max(canvas_height, rect->y + rect->h);
        }
    }
    if (canvas_width <= 0 || canvas_height <= 0) {
        avsubtitle_free(&subtitle);
        return nullptr;
    }

    std::vector<uint8_t> output;
    output.reserve(256);
    append_u32(&output, kSubtitlePayloadMagic);
    append_u32(&output, kSubtitlePayloadVersion);
    append_u32(&output, static_cast<uint32_t>(canvas_width));
    append_u32(&output, static_cast<uint32_t>(canvas_height));
    append_u32(&output, subtitle.start_display_time);
    append_u32(&output, subtitle.end_display_time);
    const size_t rect_count_offset = output.size();
    append_u32(&output, 0);
    uint32_t rect_count = 0;
    for (unsigned int i = 0; i < subtitle.num_rects; ++i) {
        const AVSubtitleRect* rect = subtitle.rects[i];
        if (rect && rect->x >= 0 && rect->y >= 0 && append_bitmap_rect(&output, rect)) {
            ++rect_count;
        }
    }
    avsubtitle_free(&subtitle);
    if (rect_count == 0 || output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    write_u32(&output, rect_count_offset, rect_count);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(output.size()));
    if (result) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(output.size()),
            reinterpret_cast<const jbyte*>(output.data()));
    }
    return result;
}

jint native_software_decoder_api_version(JNIEnv*, jclass) {
    return kSoftwareDecoderApiVersion;
}

jint native_ass_renderer_api_version(JNIEnv*, jclass) {
    return kAssRendererApiVersion;
}

jint native_demux_handle_contract_version(JNIEnv*, jclass) {
    return kDemuxHandleContractVersion;
}

void native_configure_software_decoder(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint index,
    jboolean tone_map_hdr_to_sdr) {
    SoftwareDecoder* decoder = software_decoder(env, from_handle(handle), index);
    if (decoder && !env->ExceptionCheck()) {
        decoder->tone_map_hdr_to_sdr = tone_map_hdr_to_sdr == JNI_TRUE;
    }
}

jint native_send_software_packet(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint index,
    jbyteArray encoded,
    jlong presentation_time_us,
    jlong decode_time_us) {
    DemuxSession* session = from_handle(handle);
    AVStream* stream = checked_stream(env, session, index);
    if (!stream) return AVERROR(EINVAL);
    SoftwareDecoder* decoder = software_decoder(env, session, index);
    if (!decoder || env->ExceptionCheck()) return AVERROR(EINVAL);

    AVPacket* packet = nullptr;
    if (encoded) {
        const jsize size = env->GetArrayLength(encoded);
        if (size <= 0 || static_cast<size_t>(size) > kMaxSoftwareVideoFrameBytes) {
            throw_illegal_argument(env, "FFmpeg software packet size is invalid");
            return AVERROR(EINVAL);
        }
        packet = av_packet_alloc();
        if (!packet || av_new_packet(packet, size) < 0) {
            av_packet_free(&packet);
            throw_illegal_state(env, "Unable to allocate FFmpeg software packet");
            return AVERROR(ENOMEM);
        }
        env->GetByteArrayRegion(encoded, 0, size, reinterpret_cast<jbyte*>(packet->data));
        if (env->ExceptionCheck()) {
            av_packet_free(&packet);
            return AVERROR(EINVAL);
        }
        packet->stream_index = index;
        packet->pts = presentation_time_us == kNoTimestamp
            ? AV_NOPTS_VALUE
            : av_rescale_q(presentation_time_us, AV_TIME_BASE_Q, stream->time_base);
        packet->dts = decode_time_us == kNoTimestamp
            ? packet->pts
            : av_rescale_q(decode_time_us, AV_TIME_BASE_Q, stream->time_base);
    }
    const int error = avcodec_send_packet(decoder->codec, packet);
    av_packet_free(&packet);
    if (error == AVERROR(EAGAIN)) return 1;
    if (error < 0 && error != AVERROR_EOF) {
        throw_illegal_state(env, "FFmpeg software packet decode failed: " + ffmpeg_error(error));
        return error;
    }
    return 0;
}

jlongArray native_receive_software_video_frame(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint index,
    jobject target) {
    DemuxSession* session = from_handle(handle);
    AVStream* stream = checked_stream(env, session, index);
    if (!stream) return nullptr;
    if (stream->codecpar->codec_type != AVMEDIA_TYPE_VIDEO) {
        throw_illegal_argument(env, "Requested software decoder track is not video");
        return nullptr;
    }
    SoftwareDecoder* decoder = software_decoder(env, session, index);
    if (!decoder || env->ExceptionCheck()) return nullptr;
    if (!decoder->frame_pending) {
        const int error = avcodec_receive_frame(decoder->codec, decoder->frame);
        if (error == AVERROR(EAGAIN)) {
            return make_software_frame_result(env, kSoftwareFrameAgain, 0, kNoTimestamp, 0, 0, 0);
        }
        if (error == AVERROR_EOF) {
            return make_software_frame_result(env, kSoftwareFrameEof, 0, kNoTimestamp, 0, 0, 0);
        }
        if (error < 0) {
            throw_illegal_state(env, "FFmpeg software video receive failed: " + ffmpeg_error(error));
            return nullptr;
        }
        decoder->frame_pending = true;
    }

    const int width = decoder->frame->width;
    const int height = decoder->frame->height;
    if (width <= 0 || height <= 0 ||
        static_cast<size_t>(width) > kMaxSoftwareVideoFrameBytes / 4U / static_cast<size_t>(height)) {
        throw_illegal_state(env, "FFmpeg software video dimensions exceed the safety limit");
        return nullptr;
    }
    const size_t required = static_cast<size_t>(width) * static_cast<size_t>(height) * 4U;
    if (required > kMaxSoftwareVideoFrameBytes) {
        throw_illegal_state(env, "FFmpeg software video frame exceeds the safety limit");
        return nullptr;
    }
    auto* destination = static_cast<uint8_t*>(env->GetDirectBufferAddress(target));
    const jlong capacity = env->GetDirectBufferCapacity(target);
    if (!destination || capacity < 0) {
        throw_illegal_argument(env, "FFmpeg software video target must be a direct ByteBuffer");
        return nullptr;
    }
    const jlong pts_us = timestamp_us(decoder->frame->best_effort_timestamp, stream->time_base);
    if (static_cast<uint64_t>(capacity) < required) {
        return make_software_frame_result(
            env,
            kSoftwareFrameGrowBuffer,
            static_cast<jlong>(required),
            pts_us,
            width,
            height,
            static_cast<jlong>(width) * 4L);
    }
    if (decoder->tone_map_hdr_to_sdr) {
        if (!tone_map_hdr_frame(env, decoder, stream, destination)) return nullptr;
    } else {
        decoder->scaler = sws_getCachedContext(
            decoder->scaler,
            width,
            height,
            static_cast<AVPixelFormat>(decoder->frame->format),
            width,
            height,
            AV_PIX_FMT_BGRA,
            SWS_BILINEAR,
            nullptr,
            nullptr,
            nullptr);
        if (!decoder->scaler) {
            throw_illegal_state(env, "FFmpeg software video scaler is unavailable");
            return nullptr;
        }
        uint8_t* output_data[] = {destination, nullptr, nullptr, nullptr};
        const int output_linesize[] = {width * 4, 0, 0, 0};
        const int scaled = sws_scale(
            decoder->scaler,
            decoder->frame->data,
            decoder->frame->linesize,
            0,
            height,
            output_data,
            output_linesize);
        if (scaled != height) {
            throw_illegal_state(env, "FFmpeg software video conversion was incomplete");
            return nullptr;
        }
    }
    av_frame_unref(decoder->frame);
    decoder->frame_pending = false;
    return make_software_frame_result(
        env,
        kSoftwareFrameData,
        static_cast<jlong>(required),
        pts_us,
        width,
        height,
        static_cast<jlong>(width) * 4L);
}

jlongArray native_receive_software_audio_frame(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint index,
    jobject target) {
    DemuxSession* session = from_handle(handle);
    AVStream* stream = checked_stream(env, session, index);
    if (!stream) return nullptr;
    if (stream->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) {
        throw_illegal_argument(env, "Requested software decoder track is not audio");
        return nullptr;
    }
    SoftwareDecoder* decoder = software_decoder(env, session, index);
    if (!decoder || env->ExceptionCheck()) return nullptr;
    if (!decoder->frame_pending) {
        const int error = avcodec_receive_frame(decoder->codec, decoder->frame);
        if (error == AVERROR(EAGAIN)) {
            return make_software_frame_result(env, kSoftwareFrameAgain, 0, kNoTimestamp, 0, 0, 0);
        }
        if (error == AVERROR_EOF) {
            return make_software_frame_result(env, kSoftwareFrameEof, 0, kNoTimestamp, 0, 0, 0);
        }
        if (error < 0) {
            throw_illegal_state(env, "FFmpeg software audio receive failed: " + ffmpeg_error(error));
            return nullptr;
        }
        decoder->frame_pending = true;
    }

    AVChannelLayout input_layout = {};
    if (decoder->frame->ch_layout.nb_channels > 0) {
        av_channel_layout_copy(&input_layout, &decoder->frame->ch_layout);
    } else {
        av_channel_layout_default(&input_layout, stream->codecpar->ch_layout.nb_channels);
    }
    const int channels = input_layout.nb_channels;
    const int sample_rate = decoder->frame->sample_rate > 0
        ? decoder->frame->sample_rate
        : stream->codecpar->sample_rate;
    if (channels <= 0 || channels > 32 || sample_rate <= 0 || sample_rate > 768000) {
        av_channel_layout_uninit(&input_layout);
        throw_illegal_state(env, "FFmpeg software audio format is invalid");
        return nullptr;
    }
    AVChannelLayout output_layout = {};
    av_channel_layout_copy(&output_layout, &input_layout);
    swr_free(&decoder->resampler);
    int error = swr_alloc_set_opts2(
        &decoder->resampler,
        &output_layout,
        AV_SAMPLE_FMT_S16,
        sample_rate,
        &input_layout,
        static_cast<AVSampleFormat>(decoder->frame->format),
        sample_rate,
        0,
        nullptr);
    av_channel_layout_uninit(&input_layout);
    av_channel_layout_uninit(&output_layout);
    if (error < 0 || !decoder->resampler || (error = swr_init(decoder->resampler)) < 0) {
        throw_illegal_state(env, "FFmpeg software audio resampler open failed: " + ffmpeg_error(error));
        return nullptr;
    }
    const int output_samples = swr_get_out_samples(decoder->resampler, decoder->frame->nb_samples);
    const int required = av_samples_get_buffer_size(nullptr, channels, output_samples, AV_SAMPLE_FMT_S16, 1);
    if (required <= 0 || static_cast<size_t>(required) > kMaxSoftwareAudioFrameBytes) {
        throw_illegal_state(env, "FFmpeg software audio frame exceeds the safety limit");
        return nullptr;
    }
    auto* destination = static_cast<uint8_t*>(env->GetDirectBufferAddress(target));
    const jlong capacity = env->GetDirectBufferCapacity(target);
    if (!destination || capacity < 0) {
        throw_illegal_argument(env, "FFmpeg software audio target must be a direct ByteBuffer");
        return nullptr;
    }
    const jlong pts_us = timestamp_us(decoder->frame->best_effort_timestamp, stream->time_base);
    if (capacity < required) {
        return make_software_frame_result(
            env,
            kSoftwareFrameGrowBuffer,
            required,
            pts_us,
            channels,
            sample_rate,
            output_samples);
    }
    uint8_t* output_data[] = {destination};
    const int converted = swr_convert(
        decoder->resampler,
        output_data,
        output_samples,
        const_cast<const uint8_t**>(decoder->frame->extended_data),
        decoder->frame->nb_samples);
    if (converted < 0) {
        throw_illegal_state(env, "FFmpeg software audio conversion failed: " + ffmpeg_error(converted));
        return nullptr;
    }
    const int output_bytes = converted * channels * static_cast<int>(sizeof(int16_t));
    av_frame_unref(decoder->frame);
    decoder->frame_pending = false;
    return make_software_frame_result(
        env,
        kSoftwareFrameData,
        output_bytes,
        pts_us,
        channels,
        sample_rate,
        converted);
}

void native_flush_software_decoder(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint index) {
    DemuxSession* session = from_handle(handle);
    checked_stream(env, session, index);
    if (env->ExceptionCheck()) return;
    SoftwareDecoder* decoder = session->software_decoders[index].get();
    if (!decoder) return;
    avcodec_flush_buffers(decoder->codec);
    av_frame_unref(decoder->frame);
    decoder->frame_pending = false;
    swr_free(&decoder->resampler);
}

jint native_seek(JNIEnv* env, jclass, jlong handle, jlong position_us) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return kFailureContainer;
    }
    const int64_t target = std::max<int64_t>(0, position_us);
    int error = 0;
    if (session->disc) {
        int64_t byte_position = -1;
        {
            std::lock_guard<std::mutex> lock(session->disc->source->mutex);
            const int64_t disc_time =
                av_rescale_q(
                    target,
                    AV_TIME_BASE_Q,
                    AVRational{1, static_cast<int>(kBlurayClock)});
            if (bd_seek_time(session->disc->bd, static_cast<uint64_t>(disc_time)) < 0) {
                error = AVERROR(EIO);
            } else {
                byte_position = static_cast<int64_t>(bd_tell(session->disc->bd));
                drain_disc_events(session->disc.get());
                publish_disc_state(session->disc.get());
            }
        }
        if (error >= 0 && avio_seek(session->custom_io, byte_position, SEEK_SET) < 0) {
            error = AVERROR(EIO);
        }
    } else {
        error = avformat_seek_file(
            session->format,
            -1,
            std::numeric_limits<int64_t>::min(),
            target,
            std::numeric_limits<int64_t>::max(),
            AVSEEK_FLAG_BACKWARD);
    }
    if (error < 0) {
        return failure_status(error, session->remote_source);
    }
    avformat_flush(session->format);
    for (AVCodecContext* decoder : session->subtitle_decoders) {
        if (decoder) avcodec_flush_buffers(decoder);
    }
    for (ASS_Track* track : session->ass_tracks) {
        if (track) ass_flush_events(track);
    }
    for (const std::unique_ptr<SoftwareDecoder>& decoder : session->software_decoders) {
        if (!decoder) continue;
        avcodec_flush_buffers(decoder->codec);
        av_frame_unref(decoder->frame);
        decoder->frame_pending = false;
        swr_free(&decoder->resampler);
    }
    if (session->packet_pending) {
        av_packet_unref(session->packet);
        session->packet_pending = false;
    }
    return 0;
}

static const JNINativeMethod kMethods[] = {
    {"nativeDiscApiVersion", "()I", reinterpret_cast<void*>(native_disc_api_version)},
    {"nativeAssRendererApiVersion", "()I", reinterpret_cast<void*>(native_ass_renderer_api_version)},
    {"nativeDemuxHandleContractVersion", "()I", reinterpret_cast<void*>(native_demux_handle_contract_version)},
    {"nativeRegisterBluRaySource", "(Ljava/lang/Object;)J", reinterpret_cast<void*>(native_register_bluray_source)},
    {"nativeUnregisterBluRaySource", "(J)V", reinterpret_cast<void*>(native_unregister_bluray_source)},
    {"nativeSelectDiscTitle", "(JI)Z", reinterpret_cast<void*>(native_select_disc_title)},
    {"nativeDiscChapterStartMs", "(JI)J", reinterpret_cast<void*>(native_disc_chapter_start_ms)},
    {"nativeSelectDiscAngle", "(JI)Z", reinterpret_cast<void*>(native_select_disc_angle)},
    {"nativeSendDiscMenuCommand", "(JI)Z", reinterpret_cast<void*>(native_send_disc_menu_command)},
    {"nativeSelectDiscMenuPoint", "(JIIZ)Z", reinterpret_cast<void*>(native_select_disc_menu_point)},
    {"nativeOpen", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)J", reinterpret_cast<void*>(native_open)},
    {"nativeOpenProbe", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)J", reinterpret_cast<void*>(native_open_probe)},
    {"nativeClose", "(J)V", reinterpret_cast<void*>(native_close)},
    {"nativeLastOpenFailure", "()Ljava/lang/String;", reinterpret_cast<void*>(native_last_open_failure)},
    {"nativeTrackCount", "(J)I", reinterpret_cast<void*>(native_track_count)},
    {"nativeContainerName", "(J)Ljava/lang/String;", reinterpret_cast<void*>(native_container_name)},
    {"nativeDurationUs", "(J)J", reinterpret_cast<void*>(native_duration_us)},
    {"nativeBitRateBitsPerSecond", "(J)J", reinterpret_cast<void*>(native_bit_rate_bits_per_second)},
    {"nativeTrackType", "(JI)I", reinterpret_cast<void*>(native_track_type)},
    {"nativeTrackCodecName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(native_track_codec_name)},
    {"nativeTrackVideoInfo", "(JI)[J", reinterpret_cast<void*>(native_track_video_info)},
    {"nativeTrackAudioInfo", "(JI)[J", reinterpret_cast<void*>(native_track_audio_info)},
    {"nativeTrackLanguage", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(native_track_language)},
    {"nativeTrackTitle", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(native_track_title)},
    {"nativeTrackExtradata", "(JI)[B", reinterpret_cast<void*>(native_track_extradata)},
    {"nativeTrackDolbyConfig", "(JI)[I", reinterpret_cast<void*>(native_track_dolby_config)},
    {"nativeTrackHdrStaticInfo", "(JI)[I", reinterpret_cast<void*>(native_track_hdr_static_info)},
    {"nativeSelectTracks", "(J[I)V", reinterpret_cast<void*>(native_select_tracks)},
    {"nativeReadPacket", "(JLjava/nio/ByteBuffer;)[J", reinterpret_cast<void*>(native_read_packet)},
    {"nativeDecodeSubtitle", "(JI[BJJ)[B", reinterpret_cast<void*>(native_decode_subtitle)},
    {"nativeSoftwareDecoderApiVersion", "()I", reinterpret_cast<void*>(native_software_decoder_api_version)},
    {"nativeConfigureSoftwareDecoder", "(JIZ)V", reinterpret_cast<void*>(native_configure_software_decoder)},
    {"nativeSendSoftwarePacket", "(JI[BJJ)I", reinterpret_cast<void*>(native_send_software_packet)},
    {"nativeReceiveSoftwareVideoFrame", "(JILjava/nio/ByteBuffer;)[J", reinterpret_cast<void*>(native_receive_software_video_frame)},
    {"nativeReceiveSoftwareAudioFrame", "(JILjava/nio/ByteBuffer;)[J", reinterpret_cast<void*>(native_receive_software_audio_frame)},
    {"nativeFlushSoftwareDecoder", "(JI)V", reinterpret_cast<void*>(native_flush_software_decoder)},
    {"nativeSeek", "(JJ)I", reinterpret_cast<void*>(native_seek)},
};

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }
    jclass bridge = env->FindClass("com/yfuse/core2/android/FfmpegNativeBridge");
    if (!bridge) return JNI_ERR;
    const int result = env->RegisterNatives(
        bridge,
        kMethods,
        sizeof(kMethods) / sizeof(kMethods[0]));
    env->DeleteLocalRef(bridge);
    return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
