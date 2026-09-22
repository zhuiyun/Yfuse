#pragma once

#include <algorithm>

namespace ycore_overlay {

/**
 * A rectangle already clipped to the overlay plane.
 *
 * `empty()` is the only safe way to ask whether the rectangle covers anything: an unclipped
 * rectangle can invert either span, and a caller that turns an inverted span into iterators has
 * undefined behaviour rather than a no-op.
 */
struct PlaneRect {
    int x0 = 0;
    int y0 = 0;
    int x1 = 0;
    int y1 = 0;

    bool empty() const { return x1 <= x0 || y1 <= y0; }
};

/**
 * Clips a disc-supplied rectangle to a plane of `plane_width` x `plane_height`.
 *
 * Wipe and draw rectangles come straight from the disc's interactive-graphics stream, and
 * libbluray passes them through without clipping them against the plane it announced. A rectangle
 * starting past the right or bottom edge therefore reaches this code with a start beyond the
 * clipped end, which is why the result must be tested with [PlaneRect::empty] before use.
 */
inline PlaneRect clip_to_plane(
    int plane_width,
    int plane_height,
    int x,
    int y,
    int width,
    int height) {
    PlaneRect rect;
    rect.x0 = std::max(0, x);
    rect.y0 = std::max(0, y);
    rect.x1 = std::min(plane_width, x + width);
    rect.y1 = std::min(plane_height, y + height);
    return rect;
}

}  // namespace ycore_overlay
