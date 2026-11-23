#!/usr/bin/env python3
"""Draws the README's header, docs/design/readme/header-{light,dark}.svg: the name and the blackbird's
call in the app's typeface beside the icon's blackbird in a tree, on the app's stone. Dev tool: needs
`pip install fonttools uharfbuzz`.

GitHub shows a README's pictures without CSS or fonts, so the letters are outlines. The bird is read
from the app icon (res/drawable/ic_launcher_foreground.xml), so the two never differ.
"""
import math
import pathlib
import re
import sys

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = pathlib.Path(__file__).resolve().parents[2]
ICON = ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
FONTS = ROOT / "app/src/main/res/font"
OUT = ROOT / "docs/design/readme"

W, H = 1200, 450
MARGIN = 100  # left of the name, above the disc and right of it
CORNER, SMOOTHING = 72, 0.6  # continuous corners, as ui/theme/SmoothShape.kt draws them
# The blackbird's warning call, as NABU writes it.
NAME, CALL = "Amsel", "pli-pli-pli-pli-pli"
# Screen titles in the app: the title voice at 650, tracked -0.015 em. The call is body text.
NAME_FACE, NAME_SIZE, NAME_TRACKING = "commissioner_title_650.ttf", 127, -0.015
CALL_FACE = "commissioner_440.ttf"
NAME_TO_CALL = 42  # the name's baseline to the call's cap height
DISC = 150
BIRD_SCALE, BIRD_HEAD = 4.4, (46.5, 62.0)  # the head's middle in the icon's 108 dp

# Stone, ink and the secondary ink from ui/theme/Color.kt; the tree in tints of its moss.
THEMES = {
    "light": {"field": "#E9E8E1", "disc": "#F7F6F2", "ink": "#191A17", "soft": "#66665E",
              "tree": ("#D3DAC4", "#B8C5A2", "#C7D1B5")},
    "dark": {"field": "#1B1C19", "disc": "#E6E5DE", "ink": "#E6E5DE", "soft": "#9A998F",
             "tree": ("#2B3425", "#34402C", "#3B4832")},
}


def n(value):
    return f"{value:.2f}".rstrip("0").rstrip(".")


class Face:
    def __init__(self, name):
        path = FONTS / name
        font = TTFont(str(path))
        self.glyphs, self.order, self.upm = font.getGlyphSet(), font.getGlyphOrder(), font["head"].unitsPerEm
        self.cap = font["OS/2"].sCapHeight / self.upm
        self.shaper = hb.Font(hb.Face(path.read_bytes()))

    def _run(self, text, tracking):
        """Each glyph's name, where its origin stands and its ink's left and right, in font units."""
        buffer = hb.Buffer()
        buffer.add_str(text)
        buffer.guess_segment_properties()
        hb.shape(self.shaper, buffer, {"kern": True})
        x = 0.0
        for info, pos in zip(buffer.glyph_infos, buffer.glyph_positions):
            name = self.order[info.codepoint]
            bounds = BoundsPen(self.glyphs)
            self.glyphs[name].draw(bounds)
            yield name, x + pos.x_offset, bounds.bounds
            x += pos.x_advance + tracking * self.upm

    def ink(self, text, size, tracking=0.0):
        """How wide the letters' ink is, at [size]."""
        inked = [(x, b) for _, x, b in self._run(text, tracking) if b]
        return (inked[-1][0] + inked[-1][1][2] - inked[0][0] - inked[0][1][0]) * size / self.upm

    def outline(self, text, size, left, baseline, fill, tracking=0.0):
        """The text as one path, its first letter's ink on [left]."""
        k = size / self.upm
        run = list(self._run(text, tracking))
        start = next(x + b[0] for _, x, b in run if b)
        parts = []
        for name, x, _ in run:
            pen = SVGPathPen(self.glyphs, ntos=n)
            self.glyphs[name].draw(TransformPen(pen, (k, 0, 0, -k, left + (x - start) * k, baseline)))
            parts.append(pen.getCommands())
        return f'<path fill="{fill}" d="{"".join(parts)}"/>'


def smooth_rect(w, h, radius):
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
    out = [f"M{n(p)},0"]
    # Each corner: its arc's middle angle, the way along the edge it comes from and the one it leaves by.
    for angle, ux, uy, vx, vy, x, y in ((-45, 1, 0, 0, 1, w, 0), (45, 0, 1, -1, 0, w, h),
                                        (135, -1, 0, 0, -1, 0, h), (225, 0, -1, 1, 0, 0, 0)):
        sx, sy, ex, ey = x - ux * p, y - uy * p, x + vx * p, y + vy * p

        def along(t, m):
            return f"{n(sx + ux * t + vx * m)},{n(sy + uy * t + vy * m)}"

        def back(t):
            return f"{n(ex - vx * t)},{n(ey - vy * t)}"

        end = math.radians(angle + arc / 2)
        cx, cy = x - ux * r + vx * r, y - uy * r + vy * r
        out.append(f"L{n(sx)},{n(sy)}C{along(a, 0)} {along(a + b, 0)} {along(a + b + c, d)}")
        out.append(f"A{n(r)},{n(r)} 0 0 1 {n(cx + r * math.cos(end))},{n(cy + r * math.sin(end))}")
        out.append(f"C{back(a + b)} {back(a)} {n(ex)},{n(ey)}")
    return "".join(out) + "Z"


def bird(x, y):
    """The icon's bird, looking left, its head's middle on (x, y)."""
    layers = re.findall(r'fillColor="#FF([0-9A-F]{6})"\s+android:fillType="evenOdd"\s+android:pathData="([^"]+)"',
                        ICON.read_text())
    paths = "".join(f'<path fill="#{colour}" fill-rule="evenodd" d="{data}"/>' for colour, data in layers)
    return (f'<g transform="translate({n(x)} {n(y)}) scale({-BIRD_SCALE} {BIRD_SCALE}) '
            f'translate({-BIRD_HEAD[0]} {-BIRD_HEAD[1]})">{paths}</g>')


def header(theme, name_face, call_face):
    cx, cy = W - MARGIN - DISC, MARGIN + DISC
    back, behind, front = theme["tree"]
    art = (f'<circle cx="{cx}" cy="{cy}" r="{DISC}" fill="{theme["disc"]}"/>'
           f'<circle cx="{cx - 164}" cy="{cy + 290}" r="150" fill="{back}"/>'
           f'<circle cx="{cx + 161}" cy="{cy + 255}" r="175" fill="{behind}"/>'
           + bird(cx + 6, cy - 12)
           + f'<circle cx="{cx + 1}" cy="{cy + 270}" r="200" fill="{front}"/>')
    # The call's ink is as wide as the name's; the two stand in the middle of the height.
    call_size = 100 * name_face.ink(NAME, NAME_SIZE, NAME_TRACKING) / call_face.ink(CALL, 100)
    name_cap, call_cap = name_face.cap * NAME_SIZE, call_face.cap * call_size
    top = (H - name_cap - NAME_TO_CALL - call_cap) / 2
    words = (name_face.outline(NAME, NAME_SIZE, MARGIN, top + name_cap, theme["ink"], NAME_TRACKING)
             + call_face.outline(CALL, call_size, MARGIN, top + name_cap + NAME_TO_CALL + call_cap,
                                 theme["soft"]))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" '
            f'aria-label="{NAME}. {CALL}">'
            f'<defs><clipPath id="card"><path d="{smooth_rect(W, H, CORNER)}"/></clipPath></defs>'
            f'<g clip-path="url(#card)"><rect width="{W}" height="{H}" fill="{theme["field"]}"/>{art}{words}</g>'
            "</svg>\n")


def main() -> int:
    name_face, call_face = Face(NAME_FACE), Face(CALL_FACE)
    OUT.mkdir(parents=True, exist_ok=True)
    for name, theme in THEMES.items():
        (OUT / f"header-{name}.svg").write_text(header(theme, name_face, call_face))
    print("wrote the header, light and dark")
    return 0


if __name__ == "__main__":
    sys.exit(main())
