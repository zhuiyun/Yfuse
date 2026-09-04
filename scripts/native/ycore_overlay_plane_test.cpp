#include "ycore_overlay_plane.h"

#include <cassert>
#include <cstdint>
#include <vector>

namespace {

constexpr int kPlaneWidth = 1920;
constexpr int kPlaneHeight = 1080;

/** Mirrors the wipe path: clip, reject an empty rectangle, then fill the surviving rows. */
void wipe(std::vector<uint32_t>* plane, int x, int y, int width, int height) {
    const auto rect = ycore_overlay::clip_to_plane(kPlaneWidth, kPlaneHeight, x, y, width, height);
    if (rect.empty()) return;
    for (int row = rect.y0; row < rect.y1; ++row) {
        const auto begin = plane->begin() + static_cast<int64_t>(row) * kPlaneWidth;
        // A reversed range here would be undefined behaviour, so prove the clip ordered it.
        assert(rect.x0 <= rect.x1);
        std::fill(begin + rect.x0, begin + rect.x1, 0U);
    }
}

}  // namespace

int main() {
    const auto full = ycore_overlay::clip_to_plane(kPlaneWidth, kPlaneHeight, 0, 0, 1920, 1080);
    assert(!full.empty());
    assert(full.x0 == 0 && full.y0 == 0 && full.x1 == 1920 && full.y1 == 1080);

    // Partly outside: the overlapping part survives, clipped to the plane.
    const auto straddling = ycore_overlay::clip_to_plane(kPlaneWidth, kPlaneHeight, 1900, 1070, 64, 64);
    assert(!straddling.empty());
    assert(straddling.x0 == 1900 && straddling.x1 == 1920);
    assert(straddling.y0 == 1070 && straddling.y1 == 1080);

    // Entirely past the right edge: this is the case that used to invert the row span.
    assert(ycore_overlay::clip_to_plane(kPlaneWidth, kPlaneHeight, 2000, 0, 16, 16).empty());
    // Entirely below the plane.
    assert(ycore_overlay::clip_to_plane(kPlaneWidth, kPlaneHeight, 0, 2000, 16, 16).empty());
    // Touching the edge exactly covers nothing.
    assert(ycore_overlay::clip_to_plane(kPlaneWidth, kPlaneHeight, 1920, 0, 16, 16).empty());
    // A zero-sized rectangle covers nothing.
    assert(ycore_overlay::clip_to_plane(kPlaneWidth, kPlaneHeight, 10, 10, 0, 0).empty());

    std::vector<uint32_t> plane(static_cast<size_t>(kPlaneWidth) * kPlaneHeight, 0xffffffffU);
    wipe(&plane, 2000, 0, 16, 16);
    wipe(&plane, 0, 2000, 16, 16);
    wipe(&plane, 65535, 65535, 65535, 65535);
    // Nothing was in range, so the plane is untouched and no iterator ran off it.
    assert(plane.front() == 0xffffffffU && plane.back() == 0xffffffffU);

    wipe(&plane, 1900, 1070, 64, 64);
    assert(plane.back() == 0U);
    assert(plane.front() == 0xffffffffU);
    return 0;
}
