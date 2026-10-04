"""Geometry and path-data helpers for the launcher icons (1024 x 1024 design canvas)."""
import math

from shapely.geometry import LineString, Point, Polygon

SQ3 = math.sqrt(3)


def polygons(g):
    """The polygons of any shapely geometry, empty pieces dropped."""
    if g is None or g.is_empty:
        return []
    if g.geom_type == "Polygon":
        return [g]
    return [p for p in getattr(g, "geoms", []) if p.geom_type == "Polygon" and not p.is_empty]


def _num(v, places):
    s = f"{v:.{places}f}".rstrip("0").rstrip(".") if places else str(int(round(v)))
    return "0" if s in ("-0", "") else s


def svg_path_data(g, tol=0.25):
    """Absolute path data at 0.1 precision, for the SVG masters."""
    out = []
    for p in polygons(g.simplify(tol, preserve_topology=True)):
        for ring in [p.exterior, *p.interiors]:
            pts = list(ring.coords)[:-1]
            out.append(
                f"M{_num(pts[0][0], 1)} {_num(pts[0][1], 1)}"
                + "".join(f"L{_num(x, 1)} {_num(y, 1)}" for x, y in pts[1:])
                + "Z"
            )
    return "".join(out)


def _relative_ring(coords, offset):
    """One ring as integer, relative path data; rounding happens before the deltas are taken."""
    pts = []
    for x, y in list(coords)[:-1]:
        p = (round(x + offset), round(y + offset))
        if not pts or p != pts[-1]:
            pts.append(p)
    while len(pts) > 1 and pts[-1] == pts[0]:
        pts.pop()
    if len(pts) < 3:
        return ""
    steps = " ".join(f"{b[0] - a[0]},{b[1] - a[1]}" for a, b in zip(pts, pts[1:]))
    return f"M{pts[0][0]},{pts[0][1]}l{steps}z"


def vector_path_data(g, offset, tol, limit):
    """Compact path data for a vector drawable, split so that no string exceeds limit characters.

    A polygon that is too long on its own is simplified further until it fits.
    """
    pieces = []
    for poly in polygons(g):
        t = tol
        while True:
            simple = poly.simplify(t, preserve_topology=True)
            text = "".join(_relative_ring(r.coords, offset) for r in [simple.exterior, *simple.interiors])
            if len(text) <= limit or t > 8:
                break
            t *= 1.4
        if len(text) > limit:
            raise ValueError(f"a single shape needs {len(text)} characters of path data")
        if text:
            pieces.append(text)
    chunks = []
    for text in pieces:
        if chunks and len(chunks[-1]) + len(text) <= limit:
            chunks[-1] += text
        else:
            chunks.append(text)
    return chunks


def rounded(poly, r, segs=48):
    """Round every convex corner of a polygon by r (shrink, then grow)."""
    return poly.buffer(-r, join_style="mitre").buffer(r, quad_segs=segs)


def fillet(g, r, segs=48):
    """Round concave corners (grow, then shrink)."""
    return g.buffer(r, quad_segs=segs).buffer(-r, quad_segs=segs)


def stroke(points, w, cap="round", join="round", segs=48):
    return LineString(points).buffer(w / 2, cap_style=cap, join_style=join, quad_segs=segs)


def pill(x1, x2, yc, h, segs=32):
    r = h / 2
    if x2 - x1 <= h:
        return Point((x1 + x2) / 2, yc).buffer(r, quad_segs=segs)
    return LineString([(x1 + r, yc), (x2 - r, yc)]).buffer(r, quad_segs=segs)


def play_triangle(x0, cy, side):
    """Equilateral triangle pointing right, flat left side at x0."""
    h = side * SQ3 / 2
    return Polygon([(x0, cy - side / 2), (x0 + h, cy), (x0, cy + side / 2)])


def bezier(p0, p1, p2, p3, n=120):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def ribbon(pts, width_fn):
    """Variable-width band along a polyline; width_fn(t) with t in [0, 1]."""
    n = len(pts)
    left, right = [], []
    for i, (x, y) in enumerate(pts):
        a = pts[max(i - 1, 0)]
        b = pts[min(i + 1, n - 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        length = math.hypot(dx, dy) or 1
        nx, ny = -dy / length, dx / length
        w = width_fn(i / (n - 1)) / 2
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    return Polygon(left + right[::-1]).buffer(0)
