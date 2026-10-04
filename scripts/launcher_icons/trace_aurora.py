#!/usr/bin/env python3
"""Traces the 极光 Y into the status-bar icon shared by 极光深色 and 极光浅色.

The aurora icons are artwork (drawable-nodpi/yfuse_aurora_dark.webp), not geometry, and have no
themed layer to crop the way generate.py crops the others. A notification's small icon keeps
only its alpha, so what it needs is the Y's silhouette: the glyph's bright pixels, traced, with
the dark crease where the play arm tucks under the stem filled in, since at 24dp it reads as a
speck rather than a fold.

    pip install shapely pillow potracer
    python3 scripts/launcher_icons/trace_aurora.py
"""
import os
import sys

import numpy as np
import potrace
from PIL import Image
from shapely.geometry import Polygon

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate import DRAWABLES, ROOT, to_small_icon, write  # noqa: E402

ARTWORK = os.path.join(ROOT, "composeApp/src/androidMain/res/drawable-nodpi/yfuse_aurora_dark.webp")
# The glyph's deepest blue is brighter than this in its strongest channel; the glow is not.
THRESHOLD = 0.7
# Each Bézier segment is flattened into this many straight steps before simplifying.
STEPS = 16


def _bezier(p0, p1, p2, p3):
    for i in range(1, STEPS + 1):
        t = i / STEPS
        u = 1 - t
        yield (
            u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
            u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1],
        )


def _ring(curve):
    points = [(curve.start_point.x, curve.start_point.y)]
    for segment in curve.segments:
        end = (segment.end_point.x, segment.end_point.y)
        if segment.is_corner:
            points += [(segment.c.x, segment.c.y), end]
        else:
            c1 = (segment.c1.x, segment.c1.y)
            c2 = (segment.c2.x, segment.c2.y)
            points += list(_bezier(points[-1], c1, c2, end))
    return points


def silhouette():
    pixels = np.asarray(Image.open(ARTWORK).convert("RGB"), dtype=np.float32) / 255
    glyph = pixels.max(axis=2) > THRESHOLD
    # potracer fills the pixels that are false, and lists holes as outlines of their own. Only
    # the outlines no other one contains are kept, which leaves out the crease, the one hole.
    traced = potrace.Bitmap(~glyph).trace(turdsize=50, alphamax=1.0, opticurve=True, opttolerance=0.2)
    rings = [Polygon(_ring(curve)).buffer(0) for curve in traced.curves]
    outlines = [r for r in rings if not any(o is not r and o.contains(r) for o in rings)]
    if len(outlines) != 1:
        raise SystemExit(f"expected the Y as one outline, traced {len(outlines)}")
    return outlines[0]


def main():
    write(
        os.path.join(DRAWABLES, "ic_notification_aurora.xml"),
        to_small_icon(
            silhouette(),
            [
                "极光深色 and 极光浅色: status-bar icon of the notifications sent while either is chosen.",
                "Traced by scripts/launcher_icons/trace_aurora.py; change the script and run it again.",
            ],
        ),
    )
    print("wrote aurora")


if __name__ == "__main__":
    main()
