#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace ycore_tone_map {

enum class Transfer {
    Pq,
    Hlg,
};

struct BgraPixel {
    uint8_t blue;
    uint8_t green;
    uint8_t red;
    uint8_t alpha;
};

inline double clamp_unit(double value) {
    return std::clamp(value, 0.0, 1.0);
}

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

inline double hlg_to_linear(double encoded) {
    constexpr double a = 0.17883277;
    constexpr double b = 0.28466892;
    constexpr double c = 0.55991073;
    const double value = clamp_unit(encoded);
    const double scene = value <= 0.5
        ? value * value / 3.0
        : (std::exp((value - c) / a) + b) / 12.0;
    return std::pow(clamp_unit(scene), 1.2);
}

inline uint8_t linear_to_srgb_byte(double linear) {
    const double value = std::max(0.0, linear);
    const double encoded = value <= 0.0031308
        ? 12.92 * value
        : 1.055 * std::pow(value, 1.0 / 2.4) - 0.055;
    return static_cast<uint8_t>(std::lround(clamp_unit(encoded) * 255.0));
}

inline BgraPixel bt2020_to_sdr(
    uint16_t red_code,
    uint16_t green_code,
    uint16_t blue_code,
    Transfer transfer,
    double mastering_peak_nits = 1000.0) {
    double red = red_code / 65535.0;
    double green = green_code / 65535.0;
    double blue = blue_code / 65535.0;
    if (transfer == Transfer::Pq) {
        constexpr double pq_peak_nits = 10000.0;
        constexpr double sdr_reference_white_nits = 203.0;
        const double safe_mastering_peak_nits = std::clamp(mastering_peak_nits, 100.0, pq_peak_nits);
        const double extended_white = safe_mastering_peak_nits / sdr_reference_white_nits;
        const double extended_white_squared = extended_white * extended_white;
        red = pq_to_linear(red) * pq_peak_nits / sdr_reference_white_nits;
        green = pq_to_linear(green) * pq_peak_nits / sdr_reference_white_nits;
        blue = pq_to_linear(blue) * pq_peak_nits / sdr_reference_white_nits;
        const double luminance = std::max(0.2627 * red + 0.6780 * green + 0.0593 * blue, 0.0);
        if (luminance > 1e-9) {
            const double mapped =
                luminance * (1.0 + luminance / extended_white_squared) /
                (1.0 + luminance);
            const double scale = mapped / luminance;
            red *= scale;
            green *= scale;
            blue *= scale;
        }
    } else {
        red = hlg_to_linear(red);
        green = hlg_to_linear(green);
        blue = hlg_to_linear(blue);
    }

    const double bt709_red = 1.6605 * red - 0.5876 * green - 0.0728 * blue;
    const double bt709_green = -0.1246 * red + 1.1329 * green - 0.0083 * blue;
    const double bt709_blue = -0.0182 * red - 0.1006 * green + 1.1187 * blue;
    return BgraPixel{
        linear_to_srgb_byte(bt709_blue),
        linear_to_srgb_byte(bt709_green),
        linear_to_srgb_byte(bt709_red),
        0xff,
    };
}

}  // namespace ycore_tone_map
