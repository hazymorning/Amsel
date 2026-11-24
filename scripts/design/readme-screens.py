#!/usr/bin/env python3
"""Lays the app's screens out for the README, docs/design/readme/screens-{light,dark}.webp: four
screenshots of each theme in a row on that theme's stone, with the header's corners. GitHub shows the
one that matches its own theme. Dev tool: needs `pip install pillow`.

    scripts/design/readme-screens.py LIGHT1 LIGHT2 LIGHT3 LIGHT4 DARK1 DARK2 DARK3 DARK4

The screenshots are a phone's whole screen, all of one size.
"""
import math
import pathlib
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/design/readme"

W, MARGIN, GAP = 1200, 60, 30  # the header's width; the screens' room from the card's edge and between them
SCALE, SUPERSAMPLE = 2, 4
CORNER, SMOOTHING = 72, 0.6  # the header's continuous corners
SCREEN_CORNER = 0.095  # of a screen's width, as a phone's display rounds
# Stone and ink from ui/theme/Color.kt; a screen's edge is a cover's hairline, and by day it stands on a
# large cover's shade (Elevation.kt: drop, blur, spread, alpha), at night on its own.
THEMES = {
    "light": {"field": "#E9E8E1", "ink": "#191A17", "shades": ((1, 2, 0, 0.05), (24, 48, -24, 0.38))},
    "dark": {"field": "#1B1C19", "ink": "#E6E5DE", "shades": ()},
}
HAIRLINE, UMBER = 0.08, "#3C3018"


def rgb(colour):
    return tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))


def cubic(p0, p1, p2, p3, steps=40):
    return [tuple((1 - t) ** 3 * a + 3 * (1 - t) ** 2 * t * b + 3 * (1 - t) * t * t * c + t ** 3 * d
                  for a, b, c, d in zip(p0, p1, p2, p3)) for t in (i / steps for i in range(steps + 1))]


def outline(w, h, radius, steps=40):
    """readme-header.py's smooth_rect as points."""
    budget = min(w, h) / 2
    r = min(radius, budget)
    s = max(0.0, min(SMOOTHING, budget / r - 1))
    p = min((1 + s) * r, budget)
    arc = 90 * (1 - s)
    chord = math.sin(math.radians(arc / 2)) * r * math.sqrt(2)
    c = r * math.tan(math.radians((90 - arc) / 4)) * math.cos(math.radians(45 * s))
    d = c * math.tan(math.radians(45 * s))
    b = (p - chord - c - d) / 3
    a = 2 * b
    points = []
    for angle, ux, uy, vx, vy, x, y in ((-45, 1, 0, 0, 1, w, 0), (45, 0, 1, -1, 0, w, h),
                                        (135, -1, 0, 0, -1, 0, h), (225, 0, -1, 1, 0, 0, 0)):
        sx, sy, ex, ey = x - ux * p, y - uy * p, x + vx * p, y + vy * p
        cx, cy = x - ux * r + vx * r, y - uy * r + vy * r
        start, end = math.radians(angle - arc / 2), math.radians(angle + arc / 2)
        points += cubic((sx, sy), (sx + ux * a, sy + uy * a), (sx + ux * (a + b), sy + uy * (a + b)),
                        (sx + ux * (a + b + c) + vx * d, sy + uy * (a + b + c) + vy * d))
        turn = [start + (end - start) * i / steps for i in range(steps + 1)]
        points += [(cx + r * math.cos(t), cy + r * math.sin(t)) for t in turn]
        points += cubic((cx + r * math.cos(end), cy + r * math.sin(end)), (ex - vx * (a + b), ey - vy * (a + b)),
                        (ex - vx * a, ey - vy * a), (ex, ey))
    return points


def mask(size, box, radius):
    x, y, w, h = box
    big = Image.new("L", (size[0] * SUPERSAMPLE, size[1] * SUPERSAMPLE), 0)
    ImageDraw.Draw(big).polygon([((x + px) * SUPERSAMPLE, (y + py) * SUPERSAMPLE) for px, py in outline(w, h, radius)],
                                fill=255)
    return big.reduce(SUPERSAMPLE)


def layer(size, colour, alpha):
    out = Image.new("RGBA", size, rgb(colour) + (0,))
    out.putalpha(alpha)
    return out


def screens(theme, shots):
    width, height = Image.open(shots[0]).size
    s = (W - 2 * MARGIN - (len(shots) - 1) * GAP) / len(shots)
    size = (W * SCALE, round((s * height / width + 2 * MARGIN) * SCALE))
    canvas = layer(size, theme["field"], mask(size, (0, 0, *size), CORNER * SCALE))
    for i, shot in enumerate(shots):
        x, y = round((MARGIN + i * (s + GAP)) * SCALE), MARGIN * SCALE
        w, h = round(s * SCALE), round(s * height / width * SCALE)
        box = (x, y, w, h)
        radius = SCREEN_CORNER * w
        for drop, blur, spread, alpha in theme["shades"]:
            drop, blur, spread = drop * SCALE, blur * SCALE, spread * SCALE
            shade = mask(size, (x - spread, y - spread + drop, w + 2 * spread, h + 2 * spread), radius + spread)
            canvas.alpha_composite(layer(size, UMBER, shade.filter(ImageFilter.GaussianBlur(blur / 2)).point(
                lambda v, k=alpha: round(v * k))))
        picture = Image.new("RGBA", size, (0, 0, 0, 0))
        picture.paste(Image.open(shot).convert("RGBA").resize((w, h), Image.LANCZOS), (x, y))
        edge = mask(size, box, radius)
        picture.putalpha(ImageChops.multiply(picture.getchannel("A"), edge))
        canvas.alpha_composite(picture)
        t = 0.75 * SCALE
        inside = mask(size, (x + t, y + t, w - 2 * t, h - 2 * t), radius - t)
        canvas.alpha_composite(layer(size, theme["ink"], ImageChops.subtract(edge, inside).point(
            lambda v: round(v * HAIRLINE))))
    return canvas


def main(args) -> int:
    if len(args) != 8:
        print(__doc__)
        return 2
    for (name, theme), shots in zip(THEMES.items(), (args[:4], args[4:])):
        screens(theme, shots).save(OUT / f"screens-{name}.webp", quality=90, method=6)
    print("wrote the screens, light and dark")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
