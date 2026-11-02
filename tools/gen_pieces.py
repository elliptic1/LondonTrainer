#!/usr/bin/env python3
"""Draws the app's original chess pieces into res/drawable-nodpi. Needs Pillow and numpy.

Coordinates are 0..100 with y down. Each piece is a stack of parts, painted bottom to top,
and every part gets its own outline, so the stack reads as turned, separate pieces.
"""
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = Path(__file__).parent.parent / "app/src/main/res/drawable-nodpi"
SIZE = 256
SS = 4  # supersampling
S = SIZE * SS
U = S / 100

LIGHT = (-0.55, -0.83)  # top-left
IVORY = dict(hi=(255, 252, 243), mid=(240, 229, 207), lo=(176, 151, 120), line=(52, 42, 34), ink=(92, 72, 56))
GRAPHITE = dict(hi=(112, 110, 110), mid=(62, 60, 62), lo=(26, 25, 28), line=(14, 13, 15), ink=(176, 170, 160))
BRASS = dict(hi=(255, 233, 160), mid=(222, 170, 72), lo=(136, 88, 34), line=None, ink=None)


def flatten(d):
    """Absolute M/L/Q/C path to points."""
    tokens = re.findall(r"[MLQCZ]|-?[\d.]+", d)
    pts, i, cmd = [], 0, None
    def num(n):
        nonlocal i
        vals = [float(t) for t in tokens[i:i + n]]
        i += n
        return vals
    while i < len(tokens):
        if tokens[i] in "MLQCZ":
            cmd = tokens[i]
            i += 1
            if cmd == "Z":
                continue
        if cmd in "ML":
            pts.append(tuple(num(2)))
        else:
            p0 = np.array(pts[-1])
            ctrl = np.array(num(4 if cmd == "Q" else 6)).reshape(-1, 2)
            for t in np.linspace(0, 1, 40)[1:]:
                if cmd == "Q":
                    p = (1 - t) ** 2 * p0 + 2 * (1 - t) * t * ctrl[0] + t ** 2 * ctrl[1]
                else:
                    p = (1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * ctrl[0] + 3 * (1 - t) * t ** 2 * ctrl[1] + t ** 3 * ctrl[2]
                pts.append(tuple(p))
    return pts


def scaled(pts):
    return [(x * U, y * U) for x, y in pts]


def blank():
    return Image.new("L", (S, S), 0)


def poly(d):
    m = blank()
    ImageDraw.Draw(m).polygon(scaled(flatten(d)), fill=255)
    return m


def lathe(d):
    """Half outline on the right of x=50, top to bottom; mirrored into a closed shape."""
    right = flatten(d)
    pts = right + [(100 - x, y) for x, y in reversed(right)]
    m = blank()
    ImageDraw.Draw(m).polygon(scaled(pts), fill=255)
    return m


def disc(x, y, r):
    m = blank()
    ImageDraw.Draw(m).ellipse([(x - r) * U, (y - r) * U, (x + r) * U, (y + r) * U], fill=255)
    return m


def bar(half, top, bottom, r=None, x=50):
    m = blank()
    r = (bottom - top) / 2 if r is None else r
    ImageDraw.Draw(m).rounded_rectangle([(x - half) * U, top * U, (x + half) * U, bottom * U], radius=r * U, fill=255)
    return m


def stroke(d, w):
    m = blank()
    pts = scaled(flatten(d))
    draw = ImageDraw.Draw(m)
    draw.line(pts, fill=255, width=round(w * U), joint="curve")
    r = w * U / 2
    for x, y in (pts[0], pts[-1]):
        draw.ellipse([x - r, y - r, x + r, y + r], fill=255)
    return m


def union(*masks):
    out = np.maximum.reduce([np.asarray(m) for m in masks])
    return Image.fromarray(out)


def arr(m):
    return np.asarray(m, dtype=np.float32) / 255


def grow(m, r):
    soft = np.asarray(m.filter(ImageFilter.GaussianBlur(r * U * 0.62)), dtype=np.float32) / 255
    return np.clip((soft - 0.06) * 14, 0, 1)


def shade(m, pal):
    """Pillowy lighting: the blurred mask is a height map, lit from the top left."""
    height = np.asarray(m.filter(ImageFilter.GaussianBlur(3.2 * U)), dtype=np.float32) / 255
    dy, dx = np.gradient(height)
    lit = -(dx * LIGHT[0] + dy * LIGHT[1]) * 3.2 * U
    # A gentle top-to-bottom fall-off keeps tall pieces from looking flat.
    fall = np.linspace(0.22, -0.22, S)[:, None]
    t = np.clip(lit * 0.9 + fall, -1, 1)[..., None]
    hi, mid, lo = (np.array(pal[k], dtype=np.float32) for k in ("hi", "mid", "lo"))
    return np.where(t > 0, mid + (hi - mid) * t, mid + (lo - mid) * -t)


class Canvas:
    def __init__(self, pal):
        self.pal = pal
        self.rgb = np.zeros((S, S, 3), dtype=np.float32)
        self.alpha = np.zeros((S, S, 1), dtype=np.float32)

    def over(self, color, a):
        a = a[..., None]
        self.rgb = color * a + self.rgb * (1 - a)
        self.alpha = a + self.alpha * (1 - a)

    def part(self, m, pal=None, outline=1.5):
        pal = pal or self.pal
        self.over(np.array(self.pal["line"], dtype=np.float32), grow(m, outline))
        self.over(shade(m, pal), arr(m))

    def ink(self, m, color=None):
        self.over(np.array(color or self.pal["ink"], dtype=np.float32), arr(m))

    def png(self):
        def down(x):
            return x.reshape(SIZE, SS, SIZE, SS, -1).mean(axis=(1, 3))
        alpha = down(self.alpha)
        rgb = down(self.rgb) / np.maximum(alpha, 1e-4)  # self.rgb is premultiplied by construction
        out = np.concatenate([np.clip(rgb, 0, 255), alpha * 255], axis=2)
        return Image.fromarray(np.round(out).astype(np.uint8), "RGBA")


def plinth(c, half=26, top=80):
    """The foot every piece stands on, with its brass ring. Painted last, to cover the body's raw edge."""
    c.part(bar(half, top + 3, 93, 4))
    c.part(bar(half - 2, top, top + 4.5), BRASS, 1)


def pawn(c):
    c.part(lathe("M 50 40 L 59 44 C 60 58 64 70 71 82 L 50 82"))
    c.part(bar(15, 42, 48))
    c.part(disc(50, 29, 15))
    plinth(c, 24)


def rook(c):
    c.part(lathe("M 50 36 L 68 36 C 67 52 68 68 72 82 L 50 82"))
    top = bar(25, 18, 40, 3)
    notch = blank()
    draw = ImageDraw.Draw(notch)
    for x in (37.5, 62.5):
        draw.rounded_rectangle([(x - 3.5) * U, 10 * U, (x + 3.5) * U, 27 * U], radius=1.5 * U, fill=255)
    c.part(Image.fromarray(np.where(np.asarray(notch) > 0, 0, np.asarray(top)).astype(np.uint8)))
    c.ink(bar(3, 51, 67), c.pal["line"])
    plinth(c)


def bishop(c):
    c.part(lathe("M 50 50 L 60 52 C 61 62 65 72 71 82 L 50 82"))
    c.part(bar(15, 50, 56))
    c.part(lathe("M 50 13 C 58 20 69 31 67 42 C 66 48 62 52 58 53 L 50 53"))
    cut = stroke("M 56 22 L 47 37", 3.2)
    c.ink(cut, c.pal["line"])
    c.part(disc(50, 12, 5.5), BRASS, 1)
    plinth(c, 24)


def knight(c):
    horse = poly(
        "M 53 8 C 57 11 59 14 60 18 C 74 22 84 38 82 58 C 81 68 77 75 73 82 L 27 82 "
        "C 28 72 37 63 47 56 C 50 53 51 50 51 46 C 46 49 42 52 37 53 "
        "C 32 57 25 58 20 55 C 16 52 14 47 17 42 C 22 34 28 28 35 23 "
        "C 39 19 43 16 47 15 C 48 13 50 10 53 8 Z"
    )
    c.part(horse)
    for d in ("M 63 24 C 68 29 71 34 72 41", "M 70 45 C 73 50 74 56 73 62", "M 69 66 C 69 70 68 74 66 78"):
        c.ink(stroke(d, 2), c.pal["ink"])
    c.ink(stroke("M 49 46 C 48 40 49 35 53 30", 1.8), c.pal["ink"])
    c.ink(stroke("M 21 49 C 25 50 29 50 33 48", 1.6), c.pal["ink"])
    c.ink(disc(25, 43.5, 1.7), c.pal["line"])
    c.part(disc(41.5, 29, 2.7), BRASS, 0.9)
    plinth(c)


def queen(c):
    c.part(lathe("M 50 42 L 60 46 C 61 60 65 72 72 82 L 50 82"))
    c.part(lathe(
        "M 50 22 L 53 22 L 57 34 L 66 18 L 67 18 L 69 35 L 82 22 L 83 23 "
        "L 66 50 C 60 52 55 52.5 50 52.5"
    ))
    for x, y, r in ((50, 15, 5), (32.5, 15, 4.4), (67.5, 15, 4.4), (17, 21, 4), (83, 21, 4)):
        c.part(disc(x, y, r), BRASS, 1)
    c.part(bar(16, 49, 55), BRASS, 1)
    plinth(c)


def king(c):
    c.part(lathe("M 50 44 L 60 48 C 61 60 65 72 72 82 L 50 82"))
    c.part(union(bar(3.2, 3, 24, 1.6), bar(9, 8, 14.2, 1.6)), BRASS, 1)
    c.part(lathe("M 50 21 C 60 18 76 21 75 32 C 74 40 68 46 65 52 C 60 54 55 54.5 50 54.5"))
    c.ink(stroke("M 37 27 C 45 33 55 33 63 27", 1.8), c.pal["ink"])
    c.part(bar(16, 50, 56), BRASS, 1)
    plinth(c)


PIECES = dict(p=pawn, r=rook, b=bishop, n=knight, q=queen, k=king)


def render():
    out = {}
    for side, pal in (("w", IVORY), ("b", GRAPHITE)):
        for name, draw in PIECES.items():
            canvas = Canvas(pal)
            draw(canvas)
            out[side + name] = canvas.png()
    return out


if __name__ == "__main__":
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else OUT
    target.mkdir(parents=True, exist_ok=True)
    for key, image in render().items():
        image.save(target / f"piece_{key}.png", optimize=True)
