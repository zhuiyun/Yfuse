"""Builds the five logo concepts as plain SVG.

Every concept is drawn on a 1024 x 1024 canvas that stands for the visible 72dp of an
adaptive icon. Only paths and linear or radial gradients are used, so each file can be
converted to an Android VectorDrawable as it is: no filters, masks, blend modes or bitmaps.
A blur is faked with stacked translucent copies for the same reason.

    pip install shapely
    python3 concepts.py            # writes ../NN-name-icon.svg and ../NN-name-mono.svg
    python3 concepts.py 02-jiji    # one concept only
"""
import os
import sys

from geo import *

OUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def mono(glyph):
    """The single-colour layer an Android 13+ themed icon is tinted from."""
    return svg(path(glyph, "#FFFFFF"))


def soft_shadow(g, color, layers):
    """Stacked offset copies stand in for a blur (vector drawables have no filters)."""
    out = []
    for dy, grow, a in layers:
        out.append(path(affinity.translate(g.buffer(grow, quad_segs=24), 0, dy), color, f'fill-opacity="{a}"'))
    return "".join(out)


# ---------------------------------------------------------------------------
# 01 汇光 Prism: three coloured beams enter a play-shaped prism and converge into white light.
def prism():
    cy = 512
    side = 440
    x0 = 452
    r = 52
    tri = rounded(play_triangle(x0, cy, side), r)
    cxt = tri.centroid.x
    tip = tri.bounds[2]
    hb = 58
    beams = [(cy - 102, x0 - 204, "#3FD8FF"), (cy, x0 - 266, "#FF5BB0"), (cy + 102, x0 - 178, "#FFC24A")]
    focus = (tip - 64, cy)
    defs = [
        rad("bg", 610, 440, 800, [(0, "#212D5C", None), (0.55, "#0E1430", None), (1, "#070A16", None)]),
        rad("glow", cxt + 20, cy, 430, [(0, "#A9BBFF", 0.42), (0.45, "#6F86F0", 0.12), (1, "#6F86F0", 0)]),
        lin("prism", x0, cy - side / 2, x0 + side * 0.8, cy + side / 2,
            [(0, "#FFFFFF", None), (0.6, "#F4F6FF", None), (1, "#D9E0FF", None)]),
    ]
    body = [path(FULL, "url(#bg)"), f'<circle cx="{fmt(cxt + 20)}" cy="{cy}" r="430" fill="url(#glow)"/>']
    for i, (y, xl, col) in enumerate(beams):
        defs.append(lin(f"tail{i}", xl, 0, x0, 0, [(0, col, 0.18), (0.32, col, None), (1, col, None)]))
        body.append(path(pill(xl, x0 + 40, y, hb), f"url(#tail{i})"))
    body.append(path(tri, "url(#prism)"))
    # Inside the prism each beam narrows toward one point near the tip and fades to white.
    for i, (y, xl, col) in enumerate(beams):
        defs.append(lin(f"ray{i}", x0, y, focus[0], focus[1], [(0, col, None), (0.7, col, 0.18), (1, col, 0)]))
        ray = Polygon([(x0 - 2, y - hb / 2), focus, (x0 - 2, y + hb / 2)]).intersection(tri)
        body.append(path(ray, f"url(#ray{i})"))
    icon = svg("".join(body), "".join(defs), title="Yfuse 汇光")
    glyph = unary_union([tri] + [pill(xl, x0 - 22, y, hb) for y, xl, _ in beams])
    return {"icon": icon, "mono": mono(glyph)}


# ---------------------------------------------------------------------------
# 02 水火既济 Water over fire: a play triangle split by one wave, water above and fire below.
def jiji(scale=1.12):
    cy = 512
    side = 520
    r = 78
    tri0 = rounded(play_triangle(0, cy, side), r)
    tri = affinity.translate(tri0, 530 - tri0.centroid.x, 0)
    xl, _, xr, _ = tri.bounds
    seg1 = bezier((xl - 40, cy + 26), (xl + 30, cy + 140), (xl + 130, cy + 150), (xl + 196, cy + 34), 80)
    seg2 = bezier((xl + 196, cy + 34), (xl + 250, cy - 62), (xl + 330, cy - 70), (xr + 30, cy - 2), 80)
    curve = seg1 + seg2[1:]
    gap = ribbon(curve, lambda t: 34 * (1 - t) ** 0.9 + 3)
    above = Polygon([(curve[0][0], 0)] + curve + [(curve[-1][0], 0)]).buffer(0)
    # Drawn at the base size, then grown about the centroid so it holds its own beside the Y marks.
    grow = lambda g: affinity.scale(g, scale, scale, origin=(530, cy))
    tri, gap, above = grow(tri), grow(gap), grow(above)
    xl, yt, xr, yb = tri.bounds
    water = tri.intersection(above).difference(gap)
    fire = tri.difference(above).difference(gap)
    defs = [
        lin("bg", 0, 0, 0, 1024, [(0, "#FFFFFF", None), (1, "#F4F1EC", None)]),
        lin("water", xl, yt, xr, cy, [(0, "#3BD9F7", None), (0.55, "#2F8DF6", None), (1, "#3557E8", None)]),
        lin("fire", xl, yb, xr, cy, [(0, "#FFCB45", None), (0.5, "#FF8A2E", None), (1, "#F2492F", None)]),
    ]
    shadow_layers = tuple((6 + 4 * k, 1 + 2.5 * k, 0.022) for k in range(9))
    body = [path(FULL, "url(#bg)"), soft_shadow(tri, "#4A3A2A", shadow_layers),
            path(water, "url(#water)"), path(fire, "url(#fire)"), path(gap.intersection(tri), "#FFFFFF")]
    icon = svg("".join(body), "".join(defs), title="Yfuse 水火既济")
    return {"icon": icon, "mono": mono(tri.difference(gap))}


# ---------------------------------------------------------------------------
def y_strokes(w, tl, tr, j, sb, cap="round", stem_w=None):
    """The Y as two strokes, left arm + stem and right arm + stem, so they overlap in the stem."""
    if stem_w is None:
        return stroke([tl, j, sb], w, cap=cap), stroke([tr, j, sb], w, cap=cap)
    stem = stroke([j, sb], stem_w, cap=cap)
    return (unary_union([stroke([tl, j], w, cap=cap), stem]),
            unary_union([stroke([tr, j], w, cap=cap), stem]))


# 03 叠印 Overprint: two streams cross like two inks; where they share the stem, a third colour.
def overprint():
    w = 144
    tl, tr, j, sb = (318, 272), (706, 272), (512, 548), (512, 770)
    left, right = y_strokes(w, tl, tr, j, sb)
    both = left.intersection(right)
    defs = [
        lin("bg", 0, 0, 0, 1024, [(0, "#FFFFFF", None), (1, "#F1F3F8", None)]),
        lin("c", 0, 200, 0, 620, [(0, "#34D5FA", None), (1, "#0AB2EE", None)]),
        lin("m", 0, 200, 0, 620, [(0, "#FF72B4", None), (1, "#FF3F8E", None)]),
        lin("v", 0, 420, 0, 850, [(0, "#5B3CEB", None), (1, "#3020C4", None)]),
    ]
    body = [
        path(FULL, "url(#bg)"),
        path(left.difference(both), "url(#c)"),
        path(right.difference(both), "url(#m)"),
        path(both, "url(#v)"),
    ]
    icon = svg("".join(body), "".join(defs), title="Yfuse 叠印")
    return {"icon": icon, "mono": mono(unary_union([left, right]))}


# ---------------------------------------------------------------------------
# 04 弹幕 Danmaku: the Y written in bars of flying comments.
def scanline():
    top, bot = 236, 812
    tl, tr, j, sb = (282, top - 20), (742, top - 20), (512, 586), (512, bot + 20)
    left, right = y_strokes(140, tl, tr, j, sb, cap="flat", stem_w=158)
    ysil = unary_union([left, right]).intersection(box(0, top, 1024, bot))
    rows = 10
    pitch = (bot - top) / rows
    h = pitch * 0.62
    bars = []
    for i in range(rows):
        yc = top + pitch * (i + 0.5)
        line = LineString([(0, yc), (1024, yc)]).intersection(ysil)
        segs = [line] if line.geom_type == "LineString" else list(getattr(line, "geoms", []))
        for sgm in segs:
            if not sgm.is_empty:
                xs = [p[0] for p in sgm.coords]
                bars.append(pill(min(xs), max(xs), yc, h))
    glyph = unary_union(bars)
    # Detached comments flying past; left out of the themed icon, where they would read as noise.
    fly = [(1, 812, 868), (5, 300, 382), (8, 640, 700)]
    flies = [pill(a, b, top + pitch * (i + 0.5), h) for i, a, b in fly]
    defs = [
        rad("bg", 512, 470, 740, [(0, "#1C2246", None), (0.6, "#0E1226", None), (1, "#080A16", None)]),
        lin("ink", 0, top, 0, bot,
            [(0, "#38E1FF", None), (0.38, "#4C83FF", None), (0.7, "#8E5CFF", None), (1, "#FF5FB4", None)]),
    ]
    body = [path(FULL, "url(#bg)"), path(unary_union(flies), "url(#ink)", 'fill-opacity="0.38"'),
            path(glyph, "url(#ink)")]
    icon = svg("".join(body), "".join(defs), title="Yfuse 弹幕")
    return {"icon": icon, "mono": mono(glyph)}


# ---------------------------------------------------------------------------
# 05 液态 Liquid glass: a frosted Y whose arms merge like two drops, over an aurora.
def drop_y():
    """Arms that swell toward their tops, like two drops running together."""
    tl, tr, j, sb = (330, 300), (694, 300), (512, 540), (512, 748)

    def hull(a, ra, b, rb):
        return unary_union([Point(a).buffer(ra, quad_segs=48), Point(b).buffer(rb, quad_segs=48)]).convex_hull

    return fillet(unary_union([hull(tl, 84, j, 60), hull(tr, 84, j, 60), hull(j, 64, sb, 70)]), 46)


def glass():
    y = drop_y()
    lit = y.difference(affinity.translate(y, 9, 11))  # specular rim, upper left
    caustic = y.difference(affinity.translate(y, -7, -9))  # light gathered at the lower right edge
    defs = [
        lin("bg", 0, 0, 1024, 1024, [(0, "#61E4F7", None), (0.5, "#4C7BFA", None), (1, "#7A4EF2", None)]),
        rad("blobA", 150, 920, 600, [(0, "#FF7FD0", 0.95), (1, "#FF7FD0", 0)]),
        rad("blobB", 940, 110, 540, [(0, "#9AF3FF", 0.85), (1, "#9AF3FF", 0)]),
        lin("glass", 0, 220, 0, 830, [(0, "#FFFFFF", 0.66), (1, "#FFFFFF", 0.26)]),
        lin("lit", 0, 220, 0, 820, [(0, "#FFFFFF", 1), (1, "#FFFFFF", 0.5)]),
        lin("rim", 260, 220, 760, 840, [(0, "#FFFFFF", 0.95), (1, "#FFFFFF", 0.35)]),
    ]
    body = [
        path(FULL, "url(#bg)"),
        path(FULL, "url(#blobA)"),
        path(FULL, "url(#blobB)"),
        soft_shadow(y, "#22156A", tuple((6 + 5 * k, 2 + 3 * k, 0.03) for k in range(9))),
        path(y, "url(#glass)"),
        path(caustic, "#FFFFFF", 'fill-opacity="0.42"'),
        path(lit, "url(#lit)"),
        f'<path d="{d(y)}" fill="none" stroke="url(#rim)" stroke-width="4"/>',
    ]
    icon = svg("".join(body), "".join(defs), title="Yfuse 液态")
    return {"icon": icon, "mono": mono(y)}


CONCEPTS = {"01-prism": prism, "02-jiji": jiji, "03-overprint": overprint, "04-scanline": scanline, "05-glass": glass}

if __name__ == "__main__":
    only = sys.argv[1:]
    for key, fn in CONCEPTS.items():
        if only and key not in only:
            continue
        for variant, text in fn().items():
            with open(os.path.join(OUT, f"{key}-{variant}.svg"), "w") as f:
                f.write(text)
        print("wrote", key)
