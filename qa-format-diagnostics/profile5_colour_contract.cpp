#include "ycore_dovi_profile5.h"
#include "ycore_tone_map.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
}

#include <cassert>
#include <cstdio>
#include <iostream>

static unsigned pq_code(float light) {
    const double linear = std::clamp(light * (203.0 / 10000.0), 0.0, 1.0);
    const double p = std::pow(linear, 2610.0 / 16384.0);
    const double encoded = std::pow((3424.0/4096.0 + 2413.0/128.0*p) / (1.0 + 2392.0/128.0*p), 2523.0/32.0);
    return static_cast<unsigned>(std::lround(encoded*65535));
}

static void sample(AVFrame* frame, int index) {
    ycore_dovi::Profile5Mapping mapping;
    assert(!mapping.read(nullptr));
    auto* side = av_frame_get_side_data(frame, AV_FRAME_DATA_DOVI_METADATA);
    assert(mapping.read(side) && ycore_dovi::supported_planes(*frame));
    assert(frame->width == 96 && frame->height == 64);
    const double peak = ycore_tone_map::pq_to_linear(mapping.source_max_pq / 4095.0) * 10000.0;
    const ycore_tone_map::Mapper mapper(ycore_tone_map::Transfer::Pq, peak);
    for (int y : {16, 48}) {
        ycore_dovi::render_row(mapping, *frame, y, [&](float v) { return mapper.pq_display_light(v); },
            [&](int x, const ycore_dovi::Triple& rgb) {
                if (x % 24 != 12) return;
                const auto sdr = mapper.display_light_to_sdr(rgb[0], rgb[1], rgb[2]);
                std::cout << index << ' ' << frame->best_effort_timestamp << ' ' << x << ' ' << y
                          << ' ' << pq_code(rgb[0]) << ' ' << pq_code(rgb[1]) << ' ' << pq_code(rgb[2])
                          << ' ' << unsigned(sdr.red) << ' ' << unsigned(sdr.green) << ' ' << unsigned(sdr.blue)
                          << ' ' << mapping.source_max_pq << '\n';
            });
    }
    // Reject metadata we cannot apply. Restoring the method must restore this frame's mapping.
    auto* metadata = reinterpret_cast<AVDOVIMetadata*>(side->data);
    auto& method = av_dovi_get_mapping(metadata)->curves[1].mapping_idc[0];
    const auto original = method;
    method = AV_DOVI_MAPPING_MMR;
    assert(!mapping.read(side));
    method = original;
    assert(mapping.read(side));
}

int main(int argc, char** argv) {
    assert(argc == 2);
    AVFormatContext* format = nullptr;
    assert(avformat_open_input(&format, argv[1], nullptr, nullptr) == 0);
    assert(avformat_find_stream_info(format, nullptr) >= 0);
    const AVCodec* codec = nullptr;
    const int video = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    assert(video >= 0 && codec);
    AVCodecContext* decoder = avcodec_alloc_context3(codec);
    assert(decoder && avcodec_parameters_to_context(decoder, format->streams[video]->codecpar) >= 0);
    assert(avcodec_open2(decoder, codec, nullptr) >= 0);
    AVFrame* frame = av_frame_alloc();
    AVPacket* packet = av_packet_alloc();
    assert(frame && packet);
    int count = 0;
    auto receive = [&] {
        int status;
        while ((status = avcodec_receive_frame(decoder, frame)) == 0) {
            sample(frame, count++);
            av_frame_unref(frame);
        }
        assert(status == AVERROR(EAGAIN) || status == AVERROR_EOF);
    };
    int status;
    while ((status = av_read_frame(format, packet)) == 0) {
        if (packet->stream_index == video) {
            assert(avcodec_send_packet(decoder, packet) == 0);
            receive();
        }
        av_packet_unref(packet);
    }
    assert(status == AVERROR_EOF && avcodec_send_packet(decoder, nullptr) == 0);
    receive();
    assert(count == 6);
    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&decoder);
    avformat_close_input(&format);
}
