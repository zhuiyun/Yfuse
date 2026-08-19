#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/codec_par.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
}

namespace {

constexpr int kTrackUnknown = 0;
constexpr int kTrackVideo = 1;
constexpr int kTrackAudio = 2;
constexpr int kTrackSubtitle = 3;
constexpr int kTrackData = 4;

constexpr int kHdrSdr = 0;
constexpr int kHdrPq = 1;
constexpr int kHdrHlg = 2;

constexpr int kPackingUnknown = 0;
constexpr int kPackingAnnexB = 1;
constexpr int kPackingLengthPrefixed = 2;

constexpr int kPacketStatusEof = 0;
constexpr int kPacketStatusData = 1;
constexpr int kPacketStatusGrowBuffer = -1;

constexpr int kSampleFlagSync = 1 << 0;
constexpr int kSampleFlagEncrypted = 1 << 1;

constexpr jlong kNoTimestamp = std::numeric_limits<jlong>::min();

struct DemuxSession {
    AVFormatContext* format = nullptr;
    AVPacket* packet = nullptr;
    bool packet_pending = false;
    std::vector<uint8_t> selected;

    ~DemuxSession() {
        if (packet) {
            av_packet_free(&packet);
        }
        if (format) {
            avformat_close_input(&format);
        }
    }
};

DemuxSession* from_handle(jlong handle) {
    return reinterpret_cast<DemuxSession*>(static_cast<intptr_t>(handle));
}

jlong to_handle(DemuxSession* session) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
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
            return kHdrPq;
        case AVCOL_TRC_ARIB_STD_B67:
            return kHdrHlg;
        default:
            return kHdrSdr;
    }
}

int bit_depth(const AVCodecParameters* parameters) {
    if (!parameters) return 0;
    if (parameters->bits_per_raw_sample > 0) return parameters->bits_per_raw_sample;
    switch (parameters->codec_id) {
        case AV_CODEC_ID_HEVC:
            return parameters->profile == FF_PROFILE_HEVC_MAIN_10 ? 10 : 8;
        case AV_CODEC_ID_H264:
            return parameters->profile == FF_PROFILE_H264_HIGH_10 ? 10 : 8;
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

jlong native_open(
    JNIEnv* env,
    jclass,
    jstring uri,
    jobjectArray header_names,
    jobjectArray header_values) {
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

    auto session = std::make_unique<DemuxSession>();
    session->packet = av_packet_alloc();
    if (!session->packet) {
        throw_illegal_state(env, "Unable to allocate FFmpeg packet");
        return 0;
    }

    AVDictionary* options = nullptr;
    if (!headers.empty()) {
        av_dict_set(&options, "headers", headers.c_str(), 0);
    }
    av_dict_set(&options, "reconnect", "1", 0);
    av_dict_set(&options, "reconnect_streamed", "1", 0);
    av_dict_set(&options, "reconnect_delay_max", "5", 0);

    int error = avformat_open_input(&session->format, source.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (error < 0) {
        throw_illegal_state(env, "FFmpeg open failed: " + ffmpeg_error(error));
        return 0;
    }
    error = avformat_find_stream_info(session->format, nullptr);
    if (error < 0) {
        throw_illegal_state(env, "FFmpeg stream probe failed: " + ffmpeg_error(error));
        return 0;
    }

    session->selected.assign(session->format->nb_streams, 0);
    return to_handle(session.release());
}

void native_close(JNIEnv*, jclass, jlong handle) {
    delete from_handle(handle);
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
    return session->format->duration == AV_NOPTS_VALUE
        ? kNoTimestamp
        : static_cast<jlong>(session->format->duration);
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
            throw_illegal_state(env, "FFmpeg packet read failed: " + ffmpeg_error(error));
            return nullptr;
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

void native_seek(JNIEnv* env, jclass, jlong handle, jlong position_us) {
    DemuxSession* session = from_handle(handle);
    if (!session || !session->format) {
        throw_illegal_state(env, "FFmpeg demux session is closed");
        return;
    }
    const int64_t target = std::max<int64_t>(0, position_us);
    const int error = avformat_seek_file(
        session->format,
        -1,
        std::numeric_limits<int64_t>::min(),
        target,
        std::numeric_limits<int64_t>::max(),
        AVSEEK_FLAG_BACKWARD);
    if (error < 0) {
        throw_illegal_state(env, "FFmpeg seek failed: " + ffmpeg_error(error));
        return;
    }
    avformat_flush(session->format);
    if (session->packet_pending) {
        av_packet_unref(session->packet);
        session->packet_pending = false;
    }
}

static const JNINativeMethod kMethods[] = {
    {"nativeOpen", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)J", reinterpret_cast<void*>(native_open)},
    {"nativeClose", "(J)V", reinterpret_cast<void*>(native_close)},
    {"nativeTrackCount", "(J)I", reinterpret_cast<void*>(native_track_count)},
    {"nativeContainerName", "(J)Ljava/lang/String;", reinterpret_cast<void*>(native_container_name)},
    {"nativeDurationUs", "(J)J", reinterpret_cast<void*>(native_duration_us)},
    {"nativeTrackType", "(JI)I", reinterpret_cast<void*>(native_track_type)},
    {"nativeTrackCodecName", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(native_track_codec_name)},
    {"nativeTrackVideoInfo", "(JI)[J", reinterpret_cast<void*>(native_track_video_info)},
    {"nativeTrackAudioInfo", "(JI)[J", reinterpret_cast<void*>(native_track_audio_info)},
    {"nativeTrackLanguage", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(native_track_language)},
    {"nativeTrackTitle", "(JI)Ljava/lang/String;", reinterpret_cast<void*>(native_track_title)},
    {"nativeTrackExtradata", "(JI)[B", reinterpret_cast<void*>(native_track_extradata)},
    {"nativeTrackDolbyConfig", "(JI)[I", reinterpret_cast<void*>(native_track_dolby_config)},
    {"nativeSelectTracks", "(J[I)V", reinterpret_cast<void*>(native_select_tracks)},
    {"nativeReadPacket", "(JLjava/nio/ByteBuffer;)[J", reinterpret_cast<void*>(native_read_packet)},
    {"nativeSeek", "(JJ)V", reinterpret_cast<void*>(native_seek)},
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
