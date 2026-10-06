#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace ycore_deinterlace {

/** Spatial bob: keep one field and interpolate only between lines from that same instant. */
inline void plane(
    const uint8_t* source, ptrdiff_t source_stride,
    uint8_t* target, ptrdiff_t target_stride,
    int row_bytes, int height, int parity, bool words, bool big_endian) {
    if (row_bytes <= 0 || height <= 0) return;
    // A one-line chroma plane has only the top field.
    parity = std::min(parity, height - 1);
    const int last = height - 1 - ((height - 1 - parity) & 1);
    for (int y = 0; y < height; ++y) {
        uint8_t* out = target + static_cast<ptrdiff_t>(y) * target_stride;
        if ((y & 1) == parity) {
            std::memcpy(out, source + static_cast<ptrdiff_t>(y) * source_stride, row_bytes);
            continue;
        }
        const int before = std::clamp(y - 1, parity, last);
        const int after = std::clamp(y + 1, parity, last);
        const uint8_t* a = source + static_cast<ptrdiff_t>(before) * source_stride;
        const uint8_t* b = source + static_cast<ptrdiff_t>(after) * source_stride;
        for (int x = 0; x < row_bytes; x += words ? 2 : 1) {
            if (words && x + 1 < row_bytes) {
                const unsigned av = big_endian ? (unsigned(a[x]) << 8U) | a[x + 1] : (unsigned(a[x + 1]) << 8U) | a[x];
                const unsigned bv = big_endian ? (unsigned(b[x]) << 8U) | b[x + 1] : (unsigned(b[x + 1]) << 8U) | b[x];
                const unsigned mean = (av + bv + 1U) / 2U;
                out[x] = static_cast<uint8_t>(big_endian ? mean >> 8U : mean);
                out[x + 1] = static_cast<uint8_t>(big_endian ? mean : mean >> 8U);
            } else {
                out[x] = static_cast<uint8_t>((unsigned(a[x]) + b[x] + 1U) / 2U);
            }
        }
    }
}

inline int parity(bool top_first, int field_index) {
    return (top_first ? 0 : 1) ^ (field_index & 1);
}

inline int64_t field_pts(int64_t frame_pts, int64_t duration_us, int field_index) {
    if (frame_pts == std::numeric_limits<int64_t>::min() || field_index == 0) return frame_pts;
    const int64_t offset = std::max<int64_t>(duration_us / 2, 1);
    return frame_pts > std::numeric_limits<int64_t>::max() - offset
        ? std::numeric_limits<int64_t>::max() : frame_pts + offset;
}

} // namespace ycore_deinterlace
