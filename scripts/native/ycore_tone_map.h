#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace ycore_tone_map {

enum class Transfer {
    Pq,
    Hlg,
};

/** One output pixel, in the byte order Android's ARGB_8888 bitmaps store (R, G, B, A). */
struct RgbaPixel {
    uint8_t red;
    uint8_t green;
    uint8_t blue;
    uint8_t alpha;
};

// BT.2408: SDR reference white sits at 203 cd/m2 in both HDR systems.
constexpr double kSdrReferenceWhiteNits = 203.0;
constexpr double kPqPeakNits = 10000.0;
// BT.2100 HLG nominal peak and its system gamma at that peak.
constexpr double kHlgNominalPeakNits = 1000.0;
constexpr double kHlgSystemGamma = 1.2;

inline double clamp_unit(double value) {
    return std::clamp(value, 0.0, 1.0);
}

/** PQ EOTF: encoded [0, 1] to linear light relative to 10,000 cd/m2. */
inline double pq_to_linear(double encoded) {
    constexpr double m1 = 2610.0 / 16384.0;
    constexpr double m2 = 2523.0 / 32.0;
    constexpr double c1 = 3424.0 / 4096.0;
    constexpr double c2 = 2413.0 / 128.0;
    constexpr double c3 = 2392.0 / 128.0;
    const double powered = std::pow(clamp_unit(encoded), 1.0 / m2);
    const double numerator = std::max(powered - c1, 0.0);
    const double denominator = std::max(c2 - c3 * powered, 1e-9);
    return std::pow(numerator / denominator, 1.0 / m1);
}

/** HLG inverse OETF: encoded [0, 1] to normalised scene light [0, 1]. */
inline double hlg_inverse_oetf(double encoded) {
    constexpr double a = 0.17883277;
    constexpr double b = 0.28466892;
    constexpr double c = 0.55991073;
    const double value = clamp_unit(encoded);
    return value <= 0.5 ? value * value / 3.0 : (std::exp((value - c) / a) + b) / 12.0;
}

inline double srgb_encode(double linear) {
    const double value = std::max(0.0, linear);
    return value <= 0.0031308 ? 12.92 * value : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
}

inline uint8_t linear_to_srgb_byte(double linear) {
    return static_cast<uint8_t>(std::lround(clamp_unit(srgb_encode(linear)) * 255.0));
}

inline double bt2020_luminance(double red, double green, double blue) {
    return std::max(0.2627 * red + 0.6780 * green + 0.0593 * blue, 0.0);
}

/**
 * Extended Reinhard on luminance: 1.0 (SDR reference white) stays near white and the source peak
 * lands exactly on 1.0, so highlights compress instead of clipping.
 */
inline double compress_luminance(double luminance, double extended_white_squared) {
    return luminance * (1.0 + luminance / extended_white_squared) / (1.0 + luminance);
}

/** Display light of the source, scaled so SDR reference white is 1.0. */
inline void source_display_light(
    double red_code,
    double green_code,
    double blue_code,
    Transfer transfer,
    double* red,
    double* green,
    double* blue) {
    if (transfer == Transfer::Pq) {
        constexpr double scale = kPqPeakNits / kSdrReferenceWhiteNits;
        *red = pq_to_linear(red_code) * scale;
        *green = pq_to_linear(green_code) * scale;
        *blue = pq_to_linear(blue_code) * scale;
        return;
    }
    // BT.2100 OOTF acts on scene luminance (Ys^(gamma-1) * RGBs), not per channel, so hue and
    // saturation survive; the result is relative to the 1,000 cd/m2 nominal peak.
    const double scene_red = hlg_inverse_oetf(red_code);
    const double scene_green = hlg_inverse_oetf(green_code);
    const double scene_blue = hlg_inverse_oetf(blue_code);
    const double scene_luminance = bt2020_luminance(scene_red, scene_green, scene_blue);
    const double gain =
        scene_luminance > 0.0
        ? std::pow(scene_luminance, kHlgSystemGamma - 1.0) * (kHlgNominalPeakNits / kSdrReferenceWhiteNits)
        : 0.0;
    *red = scene_red * gain;
    *green = scene_green * gain;
    *blue = scene_blue * gain;
}

inline double source_peak_over_reference_white(Transfer transfer, double mastering_peak_nits) {
    const double peak =
        transfer == Transfer::Pq ? std::clamp(mastering_peak_nits, 100.0, kPqPeakNits) : kHlgNominalPeakNits;
    return std::max(peak / kSdrReferenceWhiteNits, 1.0);
}

/** BT.2020 linear to BT.709 linear, then sRGB-encoded bytes. Out-of-gamut values clip. */
inline RgbaPixel bt2020_linear_to_srgb(double red, double green, double blue) {
    const double bt709_red = 1.6605 * red - 0.5876 * green - 0.0728 * blue;
    const double bt709_green = -0.1246 * red + 1.1329 * green - 0.0083 * blue;
    const double bt709_blue = -0.0182 * red - 0.1006 * green + 1.1187 * blue;
    return RgbaPixel{
        linear_to_srgb_byte(bt709_red),
        linear_to_srgb_byte(bt709_green),
        linear_to_srgb_byte(bt709_blue),
        0xff,
    };
}

/**
 * Reference (double precision) mapping of one BT.2020 RGB48 pixel to SDR sRGB. [Mapper] produces
 * the same result from tables and is what decode uses; this is its oracle.
 */
inline RgbaPixel bt2020_to_sdr(
    uint16_t red_code,
    uint16_t green_code,
    uint16_t blue_code,
    Transfer transfer,
    double mastering_peak_nits = 1000.0) {
    double red = 0.0;
    double green = 0.0;
    double blue = 0.0;
    source_display_light(
        red_code / 65535.0,
        green_code / 65535.0,
        blue_code / 65535.0,
        transfer,
        &red,
        &green,
        &blue);
    const double extended_white = source_peak_over_reference_white(transfer, mastering_peak_nits);
    const double luminance = bt2020_luminance(red, green, blue);
    if (luminance > 1e-9) {
        const double scale = compress_luminance(luminance, extended_white * extended_white) / luminance;
        red *= scale;
        green *= scale;
        blue *= scale;
    }
    return bt2020_linear_to_srgb(red, green, blue);
}

/**
 * Table-driven tone mapper for whole frames.
 *
 * The per-pixel double-precision path cost about half a second per 1080p frame. The transfer
 * function, the HLG system gain and the sRGB encode are tabulated once per stream; what remains
 * per pixel is a few multiply-adds in float. map() is const and safe to call from several threads
 * at once.
 */
class Mapper {
public:
    Mapper(Transfer transfer, double mastering_peak_nits)
        : transfer_(transfer) {
        set_peak_nits(mastering_peak_nits);
        for (std::size_t index = 0; index < kTransferEntries; ++index) {
            const double encoded = static_cast<double>(index) / static_cast<double>(kTransferEntries - 1);
            transfer_lut_[index] =
                static_cast<float>(
                    transfer == Transfer::Pq
                        ? pq_to_linear(encoded) * (kPqPeakNits / kSdrReferenceWhiteNits)
                        : hlg_inverse_oetf(encoded));
        }
        for (std::size_t index = 0; index < kSrgbEntries; ++index) {
            const double linear = static_cast<double>(index) / static_cast<double>(kSrgbEntries - 1);
            srgb_lut_[index] = linear_to_srgb_byte(linear);
        }
        if (transfer == Transfer::Hlg) {
            for (std::size_t index = 0; index < kTransferEntries; ++index) {
                // Indexed by the fourth root of scene luminance: Ys^(gamma - 1) is steepest next
                // to black, where dark saturated blues (low luminance weight) still show.
                const double root = static_cast<double>(index) / static_cast<double>(kTransferEntries - 1);
                hlg_gain_lut_[index] = static_cast<float>(
                    std::pow(root, 4.0 * (kHlgSystemGamma - 1.0)) *
                    (kHlgNominalPeakNits / kSdrReferenceWhiteNits));
            }
        }
    }

    /** Changes the scene shoulder without rebuilding the transfer/encoding lookup tables. */
    void set_peak_nits(double peak_nits) {
        const double finite_peak = std::isfinite(peak_nits) ? peak_nits : 1000.0;
        const double white = source_peak_over_reference_white(transfer_, finite_peak);
        extended_white_squared_ = static_cast<float>(white * white);
    }

    RgbaPixel map(uint16_t red_code, uint16_t green_code, uint16_t blue_code) const {
        float red = transfer(red_code);
        float green = transfer(green_code);
        float blue = transfer(blue_code);
        if (transfer_ == Transfer::Hlg) {
            const float gain = hlg_gain(luminance(red, green, blue));
            red *= gain;
            green *= gain;
            blue *= gain;
        }
        const float light = luminance(red, green, blue);
        if (light > 1e-9f) {
            const float scale = (1.0f + light / extended_white_squared_) / (1.0f + light);
            red *= scale;
            green *= scale;
            blue *= scale;
        }
        return RgbaPixel{
            encode(1.6605f * red - 0.5876f * green - 0.0728f * blue),
            encode(-0.1246f * red + 1.1329f * green - 0.0083f * blue),
            encode(-0.0182f * red - 0.1006f * green + 1.1187f * blue),
            0xff,
        };
    }

private:
    static constexpr std::size_t kTransferEntries = 4097;
    static constexpr std::size_t kSrgbEntries = 16384;

    static float luminance(float red, float green, float blue) {
        return std::max(0.2627f * red + 0.6780f * green + 0.0593f * blue, 0.0f);
    }

    /** Linear interpolation between 4,096 steps of the 16-bit code. */
    float transfer(uint16_t code) const {
        const std::size_t index = code >> 4;
        const float fraction = static_cast<float>(code & 0x0f) / 16.0f;
        const float low = transfer_lut_[index];
        return low + (transfer_lut_[index + 1] - low) * fraction;
    }

    /** Ys^(gamma - 1) times the nominal peak over reference white. */
    float hlg_gain(float scene_luminance) const {
        const float root = std::sqrt(std::sqrt(std::clamp(scene_luminance, 0.0f, 1.0f)));
        const float position = root * static_cast<float>(kTransferEntries - 1);
        const auto index = std::min(static_cast<std::size_t>(position), kTransferEntries - 2);
        const float fraction = position - static_cast<float>(index);
        const float low = hlg_gain_lut_[index];
        return low + (hlg_gain_lut_[index + 1] - low) * fraction;
    }

    uint8_t encode(float linear) const {
        const float clamped = std::clamp(linear, 0.0f, 1.0f);
        return srgb_lut_[static_cast<std::size_t>(clamped * static_cast<float>(kSrgbEntries - 1) + 0.5f)];
    }

    Transfer transfer_;
    float extended_white_squared_ = 1.0f;
    std::array<float, kTransferEntries> transfer_lut_{};
    std::array<float, kTransferEntries> hlg_gain_lut_{};
    std::array<uint8_t, kSrgbEntries> srgb_lut_{};
};

}  // namespace ycore_tone_map
