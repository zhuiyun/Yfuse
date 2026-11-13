#pragma once

// This reader can only access playback's cached bytes. Demuxers cannot open a secondary URL.
namespace cached_preview {
constexpr int kWidth = 320;
constexpr int kHeight = 180;
constexpr int kChunk = 32768;

struct Reader {
    JNIEnv* env;
    jobject input;
    jbyteArray buffer;
    jmethodID read;
    int64_t length;
    int64_t offset = 0;
    int bytes_left = 8 * 1024 * 1024;
    std::chrono::steady_clock::time_point deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
};

inline int expired(void* opaque) {
    return std::chrono::steady_clock::now() >= static_cast<Reader*>(opaque)->deadline;
}
inline int read(void* opaque, uint8_t* dst, int size) {
    auto& r = *static_cast<Reader*>(opaque);
    if (expired(opaque) || r.bytes_left <= 0) return AVERROR_EXIT;
    const int want = std::min({size, kChunk, r.bytes_left});
    const jint n = r.env->CallIntMethod(r.input, r.read, static_cast<jlong>(r.offset), r.buffer, 0, want);
    if (r.env->ExceptionCheck()) { r.env->ExceptionClear(); return AVERROR(EIO); }
    if (n < 0) return AVERROR_EOF;
    if (n == 0 || n > want) return AVERROR(EIO);
    r.env->GetByteArrayRegion(r.buffer, 0, n, reinterpret_cast<jbyte*>(dst));
    r.offset += n;
    r.bytes_left -= n;
    return n;
}
inline int64_t seek(void* opaque, int64_t offset, int whence) {
    auto& r = *static_cast<Reader*>(opaque);
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return r.length;
    if (whence != SEEK_SET && whence != SEEK_CUR && whence != SEEK_END) return AVERROR(EINVAL);
    const int64_t base = whence == SEEK_CUR ? r.offset : whence == SEEK_END ? r.length : 0;
    if (offset < -base || offset > r.length - base) return AVERROR(EINVAL);
    return r.offset = base + offset;
}

struct Resources {
    AVIOContext* io = nullptr;
    AVFormatContext* format = nullptr;
    AVCodecContext* decoder = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    ~Resources() {
        av_frame_free(&frame);
        av_packet_free(&packet);
        avcodec_free_context(&decoder);
        avformat_close_input(&format);
        if (io) { av_freep(&io->buffer); avio_context_free(&io); }
    }
};

inline jbyteArray scale(JNIEnv* env, AVFrame* frame) {
    if (frame->width <= 0 || frame->height <= 0) return nullptr;
    auto* scaler = sws_getContext(frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
        kWidth, kHeight, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!scaler) return nullptr;
    std::vector<uint8_t> rgba(kWidth * kHeight * 4);
    uint8_t* dst[] = {rgba.data(), nullptr, nullptr, nullptr};
    int lines[] = {kWidth * 4, 0, 0, 0};
    const int output = sws_scale(scaler, frame->data, frame->linesize, 0, frame->height, dst, lines);
    sws_freeContext(scaler);
    if (output != kHeight) return nullptr;
    auto bytes = env->NewByteArray(static_cast<jsize>(rgba.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(rgba.size()), reinterpret_cast<jbyte*>(rgba.data()));
    return bytes;
}

inline jbyteArray decode(JNIEnv* env, jclass, jobject source, jlong length, jlong time_us) {
    if (!source || length <= 0 || time_us < 0) return nullptr;
    auto cls = env->GetObjectClass(source);
    if (!cls) return nullptr;
    auto method = env->GetMethodID(cls, "readAt", "(J[BII)I");
    if (!method) return nullptr;
    auto bytes = env->NewByteArray(kChunk);
    if (!bytes) return nullptr;
    Reader reader{env, source, bytes, method, length};
    Resources r;
    auto* buffer = static_cast<uint8_t*>(av_malloc(kChunk));
    if (!buffer) return nullptr;
    r.io = avio_alloc_context(buffer, kChunk, 0, &reader, read, nullptr, seek);
    if (!r.io) { av_free(buffer); return nullptr; }
    r.format = avformat_alloc_context();
    if (!r.format) return nullptr;
    r.format->pb = r.io;
    r.format->flags |= AVFMT_FLAG_CUSTOM_IO;
    r.format->interrupt_callback = {expired, &reader};
    r.format->io_open = [](AVFormatContext*, AVIOContext**, const char*, int, AVDictionary**) { return AVERROR(EACCES); };
    AVDictionary* options = nullptr;
    av_dict_set(&options, "format_whitelist", "mov,matroska,webm,mpegts", 0);
    const int opened = avformat_open_input(&r.format, nullptr, nullptr, &options);
    av_dict_free(&options);
    if (opened < 0) return nullptr;
    const AVCodec* codec = nullptr;
    int index = av_find_best_stream(r.format, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (index < 0 || !codec) return nullptr;
    auto* stream = r.format->streams[index];
    r.decoder = avcodec_alloc_context3(codec);
    if (!r.decoder || avcodec_parameters_to_context(r.decoder, stream->codecpar) < 0) return nullptr;
    r.decoder->thread_count = 1;
    r.decoder->max_pixels = 4096LL * 2304;
    if (avcodec_open2(r.decoder, codec, nullptr) < 0) return nullptr;
    const int64_t start = stream->start_time == AV_NOPTS_VALUE ? 0 : stream->start_time;
    const int64_t delta = av_rescale_q(time_us, AVRational{1,1000000}, stream->time_base);
    if (delta < 0 || start > INT64_MAX - delta) return nullptr;
    const int64_t target = start + delta;
    if (time_us && av_seek_frame(r.format, index, target, AVSEEK_FLAG_BACKWARD) < 0) return nullptr;
    r.packet = av_packet_alloc();
    r.frame = av_frame_alloc();
    if (!r.packet || !r.frame) return nullptr;
    for (int i = 0; i < 512 && !expired(&reader); ++i) {
        const int status = av_read_frame(r.format, r.packet);
        bool eof = status == AVERROR_EOF;
        if (status < 0 && !eof) return nullptr;
        bool video = eof || r.packet->stream_index == index;
        int sent = video ? avcodec_send_packet(r.decoder, eof ? nullptr : r.packet) : 0;
        av_packet_unref(r.packet);
        if (!video) continue;
        if (sent < 0) return nullptr;
        while (!expired(&reader) && avcodec_receive_frame(r.decoder, r.frame) == 0) {
            const auto pts = r.frame->best_effort_timestamp;
            if (pts != AV_NOPTS_VALUE && pts >= target) {
                if (av_rescale_q(pts - target, stream->time_base, AVRational{1,1000000}) > 1000000) return nullptr;
                return scale(env, r.frame);
            }
            av_frame_unref(r.frame);
        }
        if (eof) break;
    }
    return nullptr;
}
}  // namespace cached_preview
