#include <jni.h>

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavcodec/codec_par.h>
#include <libavcodec/packet.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/dovi_meta.h>
#include <libavutil/error.h>
#include <libavutil/mastering_display_metadata.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
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

constexpr int kSampleFlagSync = 1 << 0;
constexpr int kSampleFlagEncrypted = 1 << 1;

constexpr jlong kNoTimestamp = std::numeric_limits<jlong>::min();
constexpr uint32_t kSubtitlePayloadMagic = 0x42555359;
constexpr uint32_t kSubtitlePayloadVersion = 1;
constexpr size_t kMaxSubtitlePayloadBytes = 32U * 1024U * 1024U;
constexpr size_t kMaxSoftwareVideoFrameBytes = 128U * 1024U * 1024U;
constexpr size_t kMaxSoftwareAudioFrameBytes = 8U * 1024U * 1024U;
constexpr int kSoftwareFrameAgain = 0;
constexpr int kSoftwareFrameData = 1;
constexpr int kSoftwareFrameEof = 2;
constexpr int kSoftwareFrameGrowBuffer = -1;
constexpr int kSoftwareDecoderApiVersion = 1;

struct SoftwareDecoder {
    AVCodecContext* codec = nullptr;
    AVFrame* frame = nullptr;
    SwsContext* scaler = nullptr;
    SwrContext* resampler = nullptr;
    bool frame_pending = false;

    ~SoftwareDecoder() {
        swr_free(&resampler);
        sws_freeContext(scaler);
        if (frame) av_frame_free(&frame);
        if (codec) avcodec_free_context(&codec);
    }
};

struct DemuxSession {
    AVFormatContext* format = nullptr;
    AVPacket* packet = nullptr;
    bool packet_pending = false;
    bool remote_source = false;
    std::vector<uint8_t> selected;
    std::vector<AVCodecContext*> subtitle_decoders;
    std::vector<std::unique_ptr<SoftwareDecoder>> software_decoders;

    ~DemuxSession() {
        for (AVCodecContext*& decoder : subtitle_decoders) {
            avcodec_free_context(&decoder);
        }
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

bool is_remote_source(const std::string& source) {
    const size_t separator = source.find(':');
    if (separator == std::string::npos) return false;
    std::string scheme = source.substr(0, separator);
    std::transform(scheme.begin(), scheme.end(), scheme.begin(), [](unsigned char value) {
        return static_cast<char>(std::tolower(value));
    });
    return scheme == "http" || scheme == "https" || scheme == "smb" || scheme == "webdav";
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
    session->remote_source = is_remote_source(source);
    session->packet = av_packet_alloc();
    if (!session->packet) {
        throw_illegal_state(env, "Unable to allocate FFmpeg packet");
        return 0;
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

    int error = avformat_open_input(&session->format, source.c_str(), nullptr, &options);
    av_dict_free(&options);
    if (error < 0) {
        return failure_status(error, session->remote_source);
    }
    error = avformat_find_stream_info(session->format, nullptr);
    if (error < 0) {
        return failure_status(error, session->remote_source);
    }

    session->selected.assign(session->format->nb_streams, 0);
    session->subtitle_decoders.assign(session->format->nb_streams, nullptr);
    session->software_decoders.resize(session->format->nb_streams);
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
    AVCodecContext* decoder = subtitle_decoder(env, session, index);
    if (!decoder || env->ExceptionCheck()) return nullptr;

    const jsize encoded_size = env->GetArrayLength(encoded);
    if (encoded_size <= 0 || static_cast<size_t>(encoded_size) > kMaxSubtitlePayloadBytes) {
        return nullptr;
    }
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

void native_configure_software_decoder(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint index) {
    software_decoder(env, from_handle(handle), index);
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
    const int error = avformat_seek_file(
        session->format,
        -1,
        std::numeric_limits<int64_t>::min(),
        target,
        std::numeric_limits<int64_t>::max(),
        AVSEEK_FLAG_BACKWARD);
    if (error < 0) {
        return failure_status(error, session->remote_source);
    }
    avformat_flush(session->format);
    for (AVCodecContext* decoder : session->subtitle_decoders) {
        if (decoder) avcodec_flush_buffers(decoder);
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
    {"nativeOpen", "(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)J", reinterpret_cast<void*>(native_open)},
    {"nativeClose", "(J)V", reinterpret_cast<void*>(native_close)},
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
    {"nativeConfigureSoftwareDecoder", "(JI)V", reinterpret_cast<void*>(native_configure_software_decoder)},
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
