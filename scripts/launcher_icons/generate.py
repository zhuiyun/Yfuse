#!/usr/bin/env python3
"""Draws Yfuse's five alternate launcher icons and writes them as Android vector drawables.

Each icon is designed on a 1024 x 1024 canvas that stands for the visible 72dp of an adaptive
icon, and is written as

  composeApp/src/androidMain/res/drawable/ic_<key>_background.xml   108dp background layer
  composeApp/src/androidMain/res/drawable/ic_<key>_foreground.xml   108dp foreground layer
  composeApp/src/androidMain/res/drawable/ic_<key>_mono.xml         Android 13+ themed layer
  composeApp/src/androidMain/res/drawable/ic_notification_<key>.xml 24dp status-bar icon
  docs/logo-concepts-20261004/NN-<key>-icon.svg, -mono.svg          the same artwork as SVG

A vector drawable has paths and linear or radial gradients and nothing else: no filters, masks
or blend modes, so a soft shadow here is a stack of translucent offset copies. Lint reports
vector paths longer than 800 characters as slow to draw, so shapes are written as integer,
relative path data split across paths that stay under that length.

    pip install shapely
    python3 scripts/launcher_icons/generate.py            # every icon
    python3 scripts/launcher_icons/generate.py prism      # one icon
"""
import math
import os
import sys
from dataclasses import dataclass, replace

from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import unary_union

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from geo import (  # noqa: E402
    bezier,
    fillet,
    pill,
    play_triangle,
    ribbon,
    rounded,
    stroke,
    svg_path_data,
    vector_path_data,
)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DRAWABLES = os.path.join(ROOT, "composeApp/src/androidMain/res/drawable")
MASTERS = os.path.join(ROOT, "docs/logo-concepts-20261004")

CANVAS = 1024
# A 108dp layer around the 72dp the launcher shows: the canvas sits 18dp (256 units) in.
VIEWPORT = 1536
OFFSET = (VIEWPORT - CANVAS) // 2
PATH_LIMIT = 800

FULL = "full"  # a shape that covers the whole layer


@dataclass(frozen=True)
class Gradient:
    kind: str  # "linear": (x1, y1, x2, y2); "radial": (cx, cy, r)
    coords: tuple
    stops: tuple  # (offset, "#RRGGBB", alpha)


@dataclass(frozen=True)
class Shape:
    geom: object  # shapely geometry, or FULL
    fill: object = None  # "#RRGGBB" or Gradient
    alpha: float = 1.0
    stroke: object = None
    width: float = 0.0
    tol: float = 1.0  # how far the vector drawable may stray from the curve, in canvas units


@dataclass(frozen=True)
class Icon:
    background: list
    foreground: list
    mono: object


def linear(x1, y1, x2, y2, *stops):
    return Gradient("linear", (x1, y1, x2, y2), stops)


def radial(cx, cy, r, *stops):
    return Gradient("radial", (cx, cy, r), stops)


def place(shapes, scale, origin, target):
    """Scales shapes about origin and moves origin onto target, their gradients with them.

    Each mark is grown until it reaches 410-460 units from the centre, near the current icon's 444
    and inside the 469 of the 66dp safe zone, which no launcher mask cuts into.
    """
    ox, oy = origin
    tx, ty = target
    matrix = [scale, 0, 0, scale, tx - ox * scale, ty - oy * scale]

    def point(x, y):
        return tx + (x - ox) * scale, ty + (y - oy) * scale

    def paint(p):
        if not isinstance(p, Gradient):
            return p
        if p.kind == "linear":
            x1, y1, x2, y2 = p.coords
            return replace(p, coords=(*point(x1, y1), *point(x2, y2)))
        cx, cy, r = p.coords
        return replace(p, coords=(*point(cx, cy), r * scale))

    def geom(g):
        return g if g is FULL else affinity.affine_transform(g, matrix)

    return [
        replace(s, geom=geom(s.geom), fill=paint(s.fill), stroke=paint(s.stroke), width=s.width * scale)
        for s in shapes
    ]


def placed_geometry(g, scale, origin, target):
    return place([Shape(g)], scale, origin, target)[0].geom


def soft_shadow(g, color, layers):
    """Stacked translucent copies, each offset down and grown, in place of a blur."""
    return [
        Shape(affinity.translate(g.buffer(grow, quad_segs=24), 0, dy), color, alpha, tol=2.0)
        for dy, grow, alpha in layers
    ]


# ---------------------------------------------------------------------------------------------
# 01 汇光 Prism: three coloured beams enter a play-shaped prism and converge into white light.
def prism():
    cy, side, x0, r, hb = 512, 440, 452, 52, 58
    tri = rounded(play_triangle(x0, cy, side), r)
    cxt, tip = tri.centroid.x, tri.bounds[2]
    beams = [(cy - 102, x0 - 204, "#3FD8FF"), (cy, x0 - 266, "#FF5BB0"), (cy + 102, x0 - 178, "#FFC24A")]
    focus = (tip - 64, cy)
    glow = Shape(FULL, radial(cxt + 20, cy, 430, (0, "#A9BBFF", 0.42), (0.45, "#6F86F0", 0.12), (1, "#6F86F0", 0)))
    mark = [
        Shape(pill(xl, x0 + 40, y, hb), linear(xl, 0, x0, 0, (0, col, 0.18), (0.32, col, 1), (1, col, 1)))
        for y, xl, col in beams
    ]
    mark.append(
        Shape(
            tri,
            linear(x0, cy - side / 2, x0 + side * 0.8, cy + side / 2,
                   (0, "#FFFFFF", 1), (0.6, "#F4F6FF", 1), (1, "#D9E0FF", 1)),
        ),
    )
    # Inside the prism each beam narrows toward one point near the tip and fades into the white.
    for y, _, col in beams:
        ray = Polygon([(x0 - 2, y - hb / 2), focus, (x0 - 2, y + hb / 2)]).intersection(tri)
        mark.append(Shape(ray, linear(x0, y, *focus, (0, col, 1), (0.7, col, 0.18), (1, col, 0))))
    glyph = unary_union([tri] + [pill(xl, x0 - 22, y, hb) for y, xl, _ in beams])
    # 1.36x the first draft; the tails of the beams are what come nearest the edge.
    fit = dict(scale=1.36, origin=((glyph.bounds[0] + glyph.bounds[2]) / 2, cy), target=(506, 512))
    ground = Shape(FULL, radial(610, 440, 800, (0, "#212D5C", 1), (0.55, "#0E1430", 1), (1, "#070A16", 1)))
    return Icon([ground, *place([glow], **fit)], place(mark, **fit), placed_geometry(glyph, **fit))


# ---------------------------------------------------------------------------------------------
# 02 水火既济 Water over fire: a play triangle split by one wave, water above and fire below.
def water_over_fire():
    cy, side, r = 512, 520, 78
    tri0 = rounded(play_triangle(0, cy, side), r)
    tri = affinity.translate(tri0, 530 - tri0.centroid.x, 0)
    xl, yt, xr, yb = tri.bounds
    seg1 = bezier((xl - 40, cy + 26), (xl + 30, cy + 140), (xl + 130, cy + 150), (xl + 196, cy + 34), 80)
    seg2 = bezier((xl + 196, cy + 34), (xl + 250, cy - 62), (xl + 330, cy - 70), (xr + 30, cy - 2), 80)
    curve = seg1 + seg2[1:]
    gap = ribbon(curve, lambda t: 34 * (1 - t) ** 0.9 + 3).intersection(tri)
    above = Polygon([(curve[0][0], 0)] + curve + [(curve[-1][0], 0)]).buffer(0)
    mark = soft_shadow(tri, "#4A3A2A", [(6 + 4 * k, 1 + 2.5 * k, 0.022) for k in range(9)])
    mark += [
        Shape(tri.intersection(above),
              linear(xl, yt, xr, cy, (0, "#3BD9F7", 1), (0.55, "#2F8DF6", 1), (1, "#3557E8", 1))),
        Shape(tri.difference(above),
              linear(xl, yb, xr, cy, (0, "#FFCB45", 1), (0.5, "#FF8A2E", 1), (1, "#F2492F", 1))),
        # Drawn over the seam between the two halves, so neither edge shows through.
        Shape(gap, "#FFFFFF"),
    ]
    glyph = tri.difference(gap)
    # A play triangle's weight sits toward its flat side and its point reaches far past it: centred
    # by its box it leans left, and centred by its centroid its point comes near the edge. The eye
    # settles halfway between, so that goes on the centre.
    # The first draft drew this at 1.12x, the smallest of the five; 1.85x reaches 429 from the centre.
    gx0, _, gx1, _ = glyph.bounds
    middle = ((gx0 + gx1) / 2 + glyph.centroid.x) / 2
    fit = dict(scale=1.85, origin=(middle, cy), target=(512, 512))
    ground = Shape(FULL, linear(0, 0, 0, 1024, (0, "#FFFFFF", 1), (1, "#F4F1EC", 1)))
    return Icon([ground], place(mark, **fit), placed_geometry(glyph, **fit))


# ---------------------------------------------------------------------------------------------
def y_strokes(w, tl, tr, j, sb, cap="round", stem_w=None):
    """The Y as two strokes, left arm + stem and right arm + stem, so they overlap in the stem."""
    if stem_w is None:
        return stroke([tl, j, sb], w, cap=cap), stroke([tr, j, sb], w, cap=cap)
    stem = stroke([j, sb], stem_w, cap=cap)
    return (
        unary_union([stroke([tl, j], w, cap=cap), stem]),
        unary_union([stroke([tr, j], w, cap=cap), stem]),
    )


# 03 叠印 Overprint: two streams cross like two inks; where they share the stem, a third colour.
def overprint():
    left, right = y_strokes(144, (318, 272), (706, 272), (512, 548), (512, 770))
    # Each stroke whole, the shared part on top: no two colours meet along a seam of background.
    mark = [
        Shape(left, linear(0, 200, 0, 620, (0, "#34D5FA", 1), (1, "#0AB2EE", 1))),
        Shape(right, linear(0, 200, 0, 620, (0, "#FF72B4", 1), (1, "#FF3F8E", 1))),
        Shape(left.intersection(right), linear(0, 420, 0, 850, (0, "#5B3CEB", 1), (1, "#3020C4", 1))),
    ]
    fit = dict(scale=1.155, origin=(512, 521), target=(512, 512))
    ground = Shape(FULL, linear(0, 0, 0, 1024, (0, "#FFFFFF", 1), (1, "#F1F3F8", 1)))
    return Icon([ground], place(mark, **fit), placed_geometry(unary_union([left, right]), **fit))


# ---------------------------------------------------------------------------------------------
# 04 弹幕 Danmaku: the Y written in bars of flying comments.
def danmaku():
    top, bot = 236, 812
    tl, tr, j, sb = (282, top - 20), (742, top - 20), (512, 586), (512, bot + 20)
    left, right = y_strokes(140, tl, tr, j, sb, cap="flat", stem_w=158)
    silhouette = unary_union([left, right]).intersection(box(0, top, 1024, bot))
    rows = 10
    pitch = (bot - top) / rows
    h = pitch * 0.62
    bars = []
    for i in range(rows):
        yc = top + pitch * (i + 0.5)
        line = LineString([(0, yc), (1024, yc)]).intersection(silhouette)
        for segment in [line] if line.geom_type == "LineString" else list(getattr(line, "geoms", [])):
            if not segment.is_empty:
                xs = [p[0] for p in segment.coords]
                bars.append(pill(min(xs), max(xs), yc, h))
    glyph = unary_union(bars)
    ink = linear(0, top, 0, bot, (0, "#38E1FF", 1), (0.38, "#4C83FF", 1), (0.7, "#8E5CFF", 1), (1, "#FF5FB4", 1))
    # Comments flying past. They stay out of the themed icon, where they would read as noise.
    flies = unary_union([pill(a, b, top + pitch * (i + 0.5), h) for i, a, b in [(1, 812, 868), (5, 300, 382), (8, 640, 700)]])
    mark = [Shape(flies, ink, 0.38), Shape(glyph, ink)]
    fit = dict(scale=1.12, origin=(512, 524), target=(512, 512))
    ground = Shape(FULL, radial(512, 470, 740, (0, "#1C2246", 1), (0.6, "#0E1226", 1), (1, "#080A16", 1)))
    return Icon([ground], place(mark, **fit), placed_geometry(glyph, **fit))


# ---------------------------------------------------------------------------------------------
# 05 液态 Liquid glass: a frosted Y whose arms merge like two drops, over an aurora.
def drop_y():
    """Arms that swell toward their tops, like two drops running together."""
    tl, tr, j, sb = (330, 300), (694, 300), (512, 540), (512, 748)

    def hull(a, ra, b, rb):
        return unary_union([Point(a).buffer(ra, quad_segs=48), Point(b).buffer(rb, quad_segs=48)]).convex_hull

    return fillet(unary_union([hull(tl, 84, j, 60), hull(tr, 84, j, 60), hull(j, 64, sb, 70)]), 46)


def liquid_glass():
    y = drop_y()
    lit = y.difference(affinity.translate(y, 9, 11))  # specular rim, upper left
    caustic = y.difference(affinity.translate(y, -7, -9))  # light gathered at the lower right edge
    mark = soft_shadow(y, "#22156A", [(6 + 5 * k, 2 + 3 * k, 0.03) for k in range(9)])
    mark += [
        Shape(y, linear(0, 220, 0, 830, (0, "#FFFFFF", 0.66), (1, "#FFFFFF", 0.26))),
        Shape(caustic, "#FFFFFF", 0.42),
        Shape(lit, linear(0, 220, 0, 820, (0, "#FFFFFF", 1), (1, "#FFFFFF", 0.5))),
        Shape(y, stroke=linear(260, 220, 760, 840, (0, "#FFFFFF", 0.95), (1, "#FFFFFF", 0.35)), width=4),
    ]
    fit = dict(scale=1.21, origin=(512, 517), target=(512, 512))
    ground = [
        Shape(FULL, linear(0, 0, 1024, 1024, (0, "#61E4F7", 1), (0.5, "#4C7BFA", 1), (1, "#7A4EF2", 1))),
        Shape(FULL, radial(150, 920, 600, (0, "#FF7FD0", 0.95), (1, "#FF7FD0", 0))),
        Shape(FULL, radial(940, 110, 540, (0, "#9AF3FF", 0.85), (1, "#9AF3FF", 0))),
    ]
    return Icon(ground, place(mark, **fit), placed_geometry(y, **fit))


ICONS = {
    "prism": (1, "汇光", prism),
    "water_over_fire": (2, "水火既济", water_over_fire),
    "overprint": (3, "叠印", overprint),
    "danmaku": (4, "弹幕", danmaku),
    "liquid_glass": (5, "液态", liquid_glass),
}


# ---------------------------------------------------------------------------------------------
# SVG masters
def _svg_number(v):
    return f"{v:.3f}".rstrip("0").rstrip(".")


def _svg_paint(p, defs):
    if not isinstance(p, Gradient):
        return p
    gid = f"g{len(defs)}"
    stops = "".join(
        f'<stop offset="{_svg_number(o)}" stop-color="{c}"'
        + (f' stop-opacity="{_svg_number(a)}"' if a < 1 else "")
        + "/>"
        for o, c, a in p.stops
    )
    if p.kind == "linear":
        x1, y1, x2, y2 = (_svg_number(v) for v in p.coords)
        defs.append(
            f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" '
            f'x2="{x2}" y2="{y2}">{stops}</linearGradient>'
        )
    else:
        cx, cy, r = (_svg_number(v) for v in p.coords)
        defs.append(
            f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" '
            f'r="{r}">{stops}</radialGradient>'
        )
    return f"url(#{gid})"


def to_svg(shapes, title=None):
    defs, body = [], []
    for s in shapes:
        data = f"M0 0H{CANVAS}V{CANVAS}H0Z" if s.geom is FULL else svg_path_data(s.geom)
        attrs = [f'd="{data}"', f'fill="{_svg_paint(s.fill, defs)}"' if s.fill else 'fill="none"']
        if s.alpha < 1:
            attrs.append(f'fill-opacity="{_svg_number(s.alpha)}"')
        if s.stroke:
            attrs.append(f'stroke="{_svg_paint(s.stroke, defs)}" stroke-width="{_svg_number(s.width)}"')
        body.append(f"<path {' '.join(attrs)}/>")
    head = f"<title>{title}</title>" if title else ""
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{CANVAS}" height="{CANVAS}" '
        f'viewBox="0 0 {CANVAS} {CANVAS}">{head}<defs>{"".join(defs)}</defs>{"".join(body)}</svg>\n'
    )


# ---------------------------------------------------------------------------------------------
# Vector drawables
def _vd_number(v):
    return f"{v:.1f}".rstrip("0").rstrip(".")


def _vd_color(rgb, alpha=1.0):
    return f"#{round(alpha * 255):02X}{rgb.lstrip('#').upper()}"


def _vd_gradient(attr, g, indent):
    pad = " " * indent
    if g.kind == "linear":
        x1, y1, x2, y2 = (_vd_number(v + OFFSET) for v in g.coords)
        geometry = [
            'android:type="linear"',
            f'android:startX="{x1}"',
            f'android:startY="{y1}"',
            f'android:endX="{x2}"',
            f'android:endY="{y2}"',
        ]
    else:
        cx, cy, r = g.coords
        geometry = [
            'android:type="radial"',
            f'android:centerX="{_vd_number(cx + OFFSET)}"',
            f'android:centerY="{_vd_number(cy + OFFSET)}"',
            f'android:gradientRadius="{_vd_number(r)}"',
        ]
    lines = [f'{pad}<aapt:attr name="{attr}">', f"{pad}    <gradient"]
    lines += [f"{pad}        {a}" for a in geometry[:-1]]
    lines.append(f"{pad}        {geometry[-1]}>")
    for o, c, a in g.stops:
        lines.append(f'{pad}        <item android:offset="{_svg_number(o)}" android:color="{_vd_color(c, a)}" />')
    lines += [f"{pad}    </gradient>", f"{pad}</aapt:attr>"]
    return lines


def _vd_paths(s):
    if s.geom is FULL:
        return [f"M0,0h{VIEWPORT}v{VIEWPORT}h-{VIEWPORT}z"]
    return vector_path_data(s.geom, OFFSET, s.tol, PATH_LIMIT)


def to_vector(shapes, comment):
    uses_gradients = any(isinstance(p, Gradient) for s in shapes for p in (s.fill, s.stroke))
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!--", *[f"  {line}" for line in comment], "-->"]
    out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    if uses_gradients:
        out.append('    xmlns:aapt="http://schemas.android.com/aapt"')
    out += [
        '    android:width="108dp"',
        '    android:height="108dp"',
        f'    android:viewportWidth="{VIEWPORT}"',
        f'    android:viewportHeight="{VIEWPORT}">',
    ]
    for s in shapes:
        for data in _vd_paths(s):
            attrs = []
            if s.fill and not isinstance(s.fill, Gradient):
                attrs.append(f'android:fillColor="{_vd_color(s.fill)}"')
            if s.alpha < 1:
                attrs.append(f'android:fillAlpha="{_svg_number(s.alpha)}"')
            if s.stroke:
                attrs.append(f'android:strokeWidth="{_vd_number(s.width)}"')
                if not isinstance(s.stroke, Gradient):
                    attrs.append(f'android:strokeColor="{_vd_color(s.stroke)}"')
            attrs.append(f'android:pathData="{data}"')
            children = []
            if isinstance(s.fill, Gradient):
                children += _vd_gradient("android:fillColor", s.fill, 8)
            if isinstance(s.stroke, Gradient):
                children += _vd_gradient("android:strokeColor", s.stroke, 8)
            out.append("    <path")
            out += [f"        {a}" for a in attrs[:-1]]
            out.append(f"        {attrs[-1]}" + (">" if children else " />"))
            if children:
                out += children
                out.append("    </path>")
    out.append("</vector>")
    return "\n".join(out) + "\n"


# A status-bar icon is drawn 24dp square; the mark fills the middle 22dp, the 1dp margin
# Android's own notification icons keep.
SMALL_ICON_DP = 24
SMALL_ICON_LIVE_DP = 22


def to_small_icon(geom, comment):
    """The themed layer's shape as a 24dp status-bar icon, cropped to its own bounds.

    Android draws only the alpha of a notification's small icon, tinted, so the one-colour
    stencil of the themed layer is already the right artwork; at 108dp it would leave the mark
    a third of the icon, so the viewport is the mark's bounds plus the margin.
    """
    x0, y0, x1, y1 = geom.bounds
    side = math.ceil(max(x1 - x0, y1 - y0) * SMALL_ICON_DP / SMALL_ICON_LIVE_DP)
    placed = affinity.translate(geom, side / 2 - (x0 + x1) / 2, side / 2 - (y0 + y1) / 2)
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!--", *[f"  {line}" for line in comment], "-->"]
    out += [
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{SMALL_ICON_DP}dp"',
        f'    android:height="{SMALL_ICON_DP}dp"',
        f'    android:viewportWidth="{side}"',
        f'    android:viewportHeight="{side}">',
    ]
    for data in vector_path_data(placed, 0, 1.0, PATH_LIMIT):
        out += ["    <path", '        android:fillColor="#FFFFFFFF"', f'        android:pathData="{data}" />']
    out.append("</vector>")
    return "\n".join(out) + "\n"


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main(keys):
    for key in keys or ICONS:
        number, name, build = ICONS[key]
        icon = build()
        source = "Generated by scripts/launcher_icons/generate.py; change the script and run it again."
        write(
            os.path.join(DRAWABLES, f"ic_{key}_background.xml"),
            to_vector(icon.background, [f"{name}: launcher icon, background layer.", source]),
        )
        write(
            os.path.join(DRAWABLES, f"ic_{key}_foreground.xml"),
            to_vector(icon.foreground, [f"{name}: launcher icon, foreground layer.", source]),
        )
        write(
            os.path.join(DRAWABLES, f"ic_{key}_mono.xml"),
            to_vector([Shape(icon.mono, "#FFFFFF")], [f"{name}: launcher icon, Android 13+ themed layer.", source]),
        )
        write(
            os.path.join(DRAWABLES, f"ic_notification_{key}.xml"),
            to_small_icon(icon.mono, [f"{name}: status-bar icon of the notifications sent while it is chosen.", source]),
        )
        stem = f"{number:02d}-{key.replace('_', '-')}"
        write(os.path.join(MASTERS, f"{stem}-icon.svg"), to_svg(icon.background + icon.foreground, f"Yfuse {name}"))
        write(os.path.join(MASTERS, f"{stem}-mono.svg"), to_svg([Shape(icon.mono, "#FFFFFF")]))
        print("wrote", key)


if __name__ == "__main__":
    main(sys.argv[1:])
