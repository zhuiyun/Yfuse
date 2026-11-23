#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <iterator>

extern "C" {
#include <libavutil/dovi_meta.h>
#include <libavutil/frame.h>
#include <libavutil/intreadwrite.h>
}

namespace ycore_dovi {

using Triple = std::array<float, 3>;
using Matrix = std::array<Triple, 3>;

inline float dot(const Triple& row, const Triple& value) {
    return row[0] * value[0] + row[1] * value[1] + row[2] * value[2];
}

// Inverse of the BT.2020/D65 to Hunt-Pointer-Estevez LMS transform used for IPT.
constexpr Matrix kLmsToBt2020 = {{
    {3.064397f, -2.165961f, 0.101557f},
    {-0.656131f, 1.785569f, -0.129440f},
    {0.017361f, -0.047245f, 1.029889f}
}};

/** One decoded frame's single-layer, full-range Profile 5 polynomial mapping. */
class Profile5Mapping {
public:
    bool read(const AVFrameSideData* side) {
        if (!side || !side->data || side->size < sizeof(AVDOVIMetadata)) return false;
        const auto* data = reinterpret_cast<const AVDOVIMetadata*>(side->data);
        auto fits = [side](size_t offset, size_t bytes) {
            return offset >= sizeof(AVDOVIMetadata) && offset <= side->size && bytes <= side->size - offset;
        };
        if (!fits(data->header_offset, sizeof(AVDOVIRpuDataHeader)) ||
            !fits(data->mapping_offset, sizeof(AVDOVIDataMapping)) ||
            !fits(data->color_offset, sizeof(AVDOVIColorMetadata))) return false;
        const auto& header = *av_dovi_get_header(data);
        const auto& mapping = *av_dovi_get_mapping(data);
        const auto& color = *av_dovi_get_color(data);
        if (header.rpu_type != 2 || !header.bl_video_full_range_flag || !header.disable_residual_flag ||
            header.bl_bit_depth != 10 || header.vdr_bit_depth != 12 || header.coef_log2_denom > 32 ||
            header.chroma_resampling_explicit_filter_flag || header.spatial_resampling_filter_flag ||
            mapping.mapping_color_space != 0 || mapping.mapping_chroma_format_idc != 0 ||
            mapping.num_x_partitions != 1 || mapping.num_y_partitions != 1 ||
            mapping.nlq_method_idc != AV_DOVI_NLQ_NONE || color.signal_color_space != 2 ||
            color.signal_eotf != 65535 || !color.signal_full_range_flag) return false;
        const float scale = std::ldexp(1.0f, -int(header.coef_log2_denom));
        for (size_t component = 0; component < 3; ++component) {
            if (!build_curve(mapping.curves[component], scale, curves_[component])) return false;
        }
        Matrix rgb_to_lms{};
        for (size_t i = 0; i < 3; ++i) {
            if (!read_number(color.ycc_to_rgb_offset[i], offset_[i])) return false;
            for (size_t j = 0; j < 3; ++j) {
                if (!read_number(color.ycc_to_rgb_matrix[3*i+j], nonlinear_[i][j]) ||
                    !read_number(color.rgb_to_lms_matrix[3*i+j], rgb_to_lms[i][j])) return false;
            }
        }
        for (size_t i = 0; i < 3; ++i) {
            for (size_t j = 0; j < 3; ++j) {
                linear_[i][j] = dot(kLmsToBt2020[i], {rgb_to_lms[0][j], rgb_to_lms[1][j], rgb_to_lms[2][j]});
            }
        }
        source_max_pq = color.source_max_pq;
        return true;
    }

    float reshape(size_t component, unsigned code) const {
        return curves_[component][std::min(code, 1023u)];
    }

    template<class PqToLight>
    Triple bt2020_light(Triple reshaped, const PqToLight& pq_to_light) const {
        for (size_t i = 0; i < 3; ++i) reshaped[i] -= offset_[i];
        const Triple light = {pq_to_light(dot(nonlinear_[0], reshaped)),
                              pq_to_light(dot(nonlinear_[1], reshaped)),
                              pq_to_light(dot(nonlinear_[2], reshaped))};
        return {std::max(dot(linear_[0], light), 0.f), std::max(dot(linear_[1], light), 0.f),
                std::max(dot(linear_[2], light), 0.f)};
    }

    unsigned source_max_pq = 0;

private:
    static bool read_number(AVRational value, float& output) {
        if (!value.den) return false;
        output = static_cast<float>(value.num) / static_cast<float>(value.den);
        return std::isfinite(output);
    }

    static bool build_curve(const AVDOVIReshapingCurve& curve, float scale, std::array<float, 1024>& lut) {
        if (curve.num_pivots < 2 || curve.num_pivots > std::size(curve.pivots)) return false;
        const int last = curve.num_pivots - 1;
        if (curve.pivots[last] > 1023) return false;
        for (int i = 0; i < last; ++i) {
            if (curve.mapping_idc[i] != AV_DOVI_MAPPING_POLYNOMIAL ||
                curve.pivots[i] >= curve.pivots[i+1] || curve.poly_order[i] < 1 || curve.poly_order[i] > 2) return false;
        }
        // ETSI GS CCM 001 section 5.4.2 uses the BL code divided by 2^BL_bit_depth.
        // Out-of-domain samples use the signalled end pivot.
        int piece = 0;
        for (int code = 0; code < 1024; ++code) {
            while (piece + 1 < last && code >= curve.pivots[piece+1]) ++piece;
            const float x = std::clamp(code, int(curve.pivots[0]), int(curve.pivots[last])) / 1024.f;
            const auto* coeff = curve.poly_coef[piece];
            const float quadratic = curve.poly_order[piece] == 2 ? static_cast<float>(coeff[2]) * scale : 0.f;
            const float value = (quadratic*x + static_cast<float>(coeff[1])*scale)*x +
                                static_cast<float>(coeff[0])*scale;
            lut[code] = std::clamp(value, 0.f, 4095.f / 4096.f);
        }
        return true;
    }

    std::array<std::array<float, 1024>, 3> curves_{};
    Triple offset_{};
    Matrix nonlinear_{}, linear_{};
};

inline bool supported_planes(const AVFrame& frame) {
    if (frame.format != AV_PIX_FMT_YUV420P10LE || frame.width <= 0 || frame.height <= 0) return false;
    for (int plane = 0; plane < 3; ++plane) {
        const int width = plane == 0 ? frame.width : (frame.width + 1) / 2;
        if (!frame.data[plane] || std::abs(static_cast<int64_t>(frame.linesize[plane])) < 2LL * width) return false;
    }
    return true;
}

// Offsets are quarter-luma-pixel units. Chroma sample centres in HEVC default to left.
inline std::array<int, 2> chroma_offset(AVChromaLocation location) {
    switch (location) {
    case AVCHROMA_LOC_CENTER:     return {2, 2};
    case AVCHROMA_LOC_TOPLEFT:    return {0, 0};
    case AVCHROMA_LOC_TOP:        return {2, 0};
    case AVCHROMA_LOC_BOTTOMLEFT: return {0, 4};
    case AVCHROMA_LOC_BOTTOM:     return {2, 4};
    default:                      return {0, 2};
    }
}

struct ChromaIndex { int low, high; float weight; };

inline ChromaIndex chroma_index(int pixel, int offset, int samples) {
    const int pos = std::max(0, 4 * pixel - offset);
    const int low = std::min(pos / 8, samples - 1);
    return {low, std::min(low + 1, samples - 1), static_cast<float>(pos % 8) / 8.f};
}

template<class PqToLight, class WritePixel>
void render_row(const Profile5Mapping& mapping, const AVFrame& frame, int y,
                const PqToLight& pq_to_light, const WritePixel& write_pixel) {
    const auto phase = chroma_offset(frame.chroma_location);
    const auto cy = chroma_index(y, phase[1], (frame.height + 1) / 2);
    auto row = [&frame](int plane, int line) {
        return frame.data[plane] + static_cast<ptrdiff_t>(line) * frame.linesize[plane];
    };
    const uint8_t* luma = row(0, y);
    const uint8_t* upper[] = {row(1, cy.low), row(2, cy.low)};
    const uint8_t* lower[] = {row(1, cy.high), row(2, cy.high)};
    for (int x = 0; x < frame.width; ++x) {
        const auto cx = chroma_index(x, phase[0], (frame.width + 1) / 2);
        Triple components{mapping.reshape(0, AV_RL16(luma + 2*x)), 0.f, 0.f};
        for (int c = 0; c < 2; ++c) {
            auto interpolate = [&](const uint8_t* plane) {
                const float a = mapping.reshape(c+1, AV_RL16(plane + 2*cx.low));
                const float b = mapping.reshape(c+1, AV_RL16(plane + 2*cx.high));
                return a + cx.weight * (b-a);
            };
            const float a = interpolate(upper[c]), b = interpolate(lower[c]);
            components[c+1] = a + cy.weight * (b-a);
        }
        write_pixel(x, mapping.bt2020_light(components, pq_to_light));
    }
}

}  // namespace ycore_dovi
