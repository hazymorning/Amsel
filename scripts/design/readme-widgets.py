#!/usr/bin/env python3
"""Lays home screen widgets out for the README, docs/design/readme/widgets-{light,dark}.webp: each
cut from a screenshot along its own corners and set on the launcher's grid, a piece of a home screen
on the theme's stone, as the screens are (readme-screens.py). GitHub shows the one that matches its
own theme. Dev tool: needs `pip install pillow`.

    scripts/design/readme-widgets.py PATH@LEFT,TOP,RIGHT,BOTTOM@COLUMN,ROW ...

Each is a screenshot, the widget's box in it in pixels, and the cell its top left takes on the grid;
together they fill a rectangle of cells. The screenshots are of one phone, all at its scale.
"""
import importlib.util
import pathlib
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("readme_screens", HERE / "readme-screens.py")
screens = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(screens)

# The phone's launcher, in its screenshots' pixels: a cell's pitch across and down, the gap between
# widgets, and a widget's corner (22 dp at 2.625 px a dp). A cut one pixel inside the corner leaves
# the wallpaper's blend with the edge behind; each widget then takes its cells' exact size.
PITCH, GAP, CORNER, INSET = (251.5, 248.0), 76, 58, 1


def cut(arg):
    path, box, cell = arg.split("@")
    left, top, right, bottom = (int(v) for v in box.split(","))
    column, row = (int(v) for v in cell.split(","))
    shot = Image.open(path).convert("RGBA").crop((left + INSET, top + INSET, right - INSET, bottom - INSET))
    columns = round((right - left + GAP) / PITCH[0])
    rows = round((bottom - top + GAP) / PITCH[1])
    return shot, (column, row, columns, rows)


def rounded(size, box, radius):
    """A rounded box's mask, as the launcher rounds a widget."""
    x, y, w, h = box
    s = screens.SUPERSAMPLE
    big = Image.new("L", (size[0] * s, size[1] * s), 0)
    ImageDraw.Draw(big).rounded_rectangle([x * s, y * s, (x + w) * s, (y + h) * s], radius=max(radius, 0) * s,
                                          fill=255)
    return big.reduce(s)


def card(theme, widgets):
    scale, margin = screens.SCALE, screens.MARGIN
    columns = max(c + w for _, (c, _, w, _) in widgets)
    rows = max(r + h for _, (_, r, _, h) in widgets)
    k = (screens.W - 2 * margin) / (columns * PITCH[0] - GAP)
    size = (screens.W * scale, round(((rows * PITCH[1] - GAP) * k + 2 * margin) * scale))
    canvas = screens.layer(size, theme["field"], screens.mask(size, (0, 0, *size), screens.CORNER * scale))
    radius = (CORNER - INSET) * k * scale
    for shot, (c, r, w, h) in widgets:
        box = (round((margin + c * PITCH[0] * k) * scale), round((margin + r * PITCH[1] * k) * scale),
               round((w * PITCH[0] - GAP) * k * scale), round((h * PITCH[1] - GAP) * k * scale))
        place(canvas, theme, shot, box, radius)
    return canvas


def place(canvas, theme, shot, box, radius):
    size, scale = canvas.size, screens.SCALE
    x, y, w, h = box
    for drop, blur, spread, alpha in theme["shades"]:
        drop, blur, spread = drop * scale, blur * scale, spread * scale
        shade = rounded(size, (x - spread, y - spread + drop, w + 2 * spread, h + 2 * spread), radius + spread)
        canvas.alpha_composite(screens.layer(size, screens.UMBER, shade.filter(ImageFilter.GaussianBlur(blur / 2)).point(
            lambda v, k=alpha: round(v * k))))
    picture = Image.new("RGBA", size, (0, 0, 0, 0))
    picture.paste(shot.resize((w, h), Image.LANCZOS), (x, y))
    edge = rounded(size, box, radius)
    picture.putalpha(ImageChops.multiply(picture.getchannel("A"), edge))
    canvas.alpha_composite(picture)
    t = 0.75 * scale
    inside = rounded(size, (x + t, y + t, w - 2 * t, h - 2 * t), radius - t)
    canvas.alpha_composite(screens.layer(size, theme["ink"], ImageChops.subtract(edge, inside).point(
        lambda v: round(v * screens.HAIRLINE))))


def main(args) -> int:
    if not args or any(a.count("@") != 2 for a in args):
        print(__doc__)
        return 2
    widgets = [cut(a) for a in args]
    for name, theme in screens.THEMES.items():
        card(theme, widgets).save(screens.OUT / f"widgets-{name}.webp", quality=90, method=6)
    print("wrote the widgets, light and dark")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
