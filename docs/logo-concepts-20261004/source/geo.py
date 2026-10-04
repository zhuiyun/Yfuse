"""Small geometry + SVG helpers for the logo concepts (1024 x 1024 icon canvas)."""
import math
from shapely.geometry import Polygon, LineString, Point, box
from shapely.ops import unary_union
from shapely import affinity

SQ3 = math.sqrt(3)


def fmt(v):
    s = f"{v:.1f}"
    if s.endswith(".0"):
        s = s[:-2]
    return "0" if s == "-0" else s


def ring_d(coords):
    pts = list(coords)
    if pts[0] == pts[-1]:
        pts = pts[:-1]
    out = [f"M{fmt(pts[0][0])} {fmt(pts[0][1])}"]
    for x, y in pts[1:]:
        out.append(f"L{fmt(x)} {fmt(y)}")
    return "".join(out) + "Z"


def d(g, tol=0.25):
    """Shapely geometry -> SVG path data (even-odd safe: holes are separate rings)."""
    if g is None or g.is_empty:
        return ""
    g = g.simplify(tol, preserve_topology=True)
    polys = []
    if g.geom_type == "Polygon":
        polys = [g]
    elif g.geom_type in ("MultiPolygon", "GeometryCollection"):
        polys = [p for p in g.geoms if p.geom_type == "Polygon"]
    parts = []
    for p in polys:
        parts.append(ring_d(p.exterior.coords))
        for i in p.interiors:
            parts.append(ring_d(i.coords))
    return "".join(parts)


def rounded(poly, r, segs=48):
    """Round every convex corner of a polygon by r (shrink then grow)."""
    return poly.buffer(-r, join_style="mitre").buffer(r, quad_segs=segs)


def fillet(g, r, segs=48):
    """Round concave corners (closing): grow then shrink."""
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
        L = math.hypot(dx, dy) or 1
        nx, ny = -dy / L, dx / L
        w = width_fn(i / (n - 1)) / 2
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
    return Polygon(left + right[::-1]).buffer(0)


def svg(body, defs="", size=1024, title=None):
    t = f"<title>{title}</title>" if title else ""
    df = f"<defs>{defs}</defs>" if defs else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 1024 1024">{t}{df}{body}</svg>\n'
    )


def path(g, fill, extra=""):
    dd = d(g) if not isinstance(g, str) else g
    return f'<path d="{dd}" fill="{fill}"{(" " + extra) if extra else ""}/>'


def lin(id_, x1, y1, x2, y2, stops):
    s = "".join(
        f'<stop offset="{o}" stop-color="{c}"' + (f' stop-opacity="{a}"' if a is not None else "") + "/>"
        for o, c, a in stops
    )
    return (
        f'<linearGradient id="{id_}" gradientUnits="userSpaceOnUse" x1="{fmt(x1)}" y1="{fmt(y1)}" '
        f'x2="{fmt(x2)}" y2="{fmt(y2)}">{s}</linearGradient>'
    )


def rad(id_, cx, cy, r, stops, fx=None, fy=None):
    s = "".join(
        f'<stop offset="{o}" stop-color="{c}"' + (f' stop-opacity="{a}"' if a is not None else "") + "/>"
        for o, c, a in stops
    )
    f = f' fx="{fmt(fx)}" fy="{fmt(fy)}"' if fx is not None else ""
    return (
        f'<radialGradient id="{id_}" gradientUnits="userSpaceOnUse" cx="{fmt(cx)}" cy="{fmt(cy)}" '
        f'r="{fmt(r)}"{f}>{s}</radialGradient>'
    )


FULL = "M0 0H1024V1024H0Z"
