#!/usr/bin/env python3
"""Measure a screenshot the way a designer would: ink boxes in dp, alignment near-misses, gaps.

  measure.py boxes   SHOT.png            boxes, edge clusters, near-misses, vertical gaps
  measure.py overlay SHOT.png OUT.png    boxes, keylines and an 8 dp grid drawn over the shot
  measure.py zoom    SHOT.png OUT.png X Y W H [SCALE]   a region in dp, enlarged

Screenshots are xxhdpi (3 px per dp). "Ink" is anything that differs from the row's background,
sampled at the screen's outer columns, so tinted and gradient backgrounds work too.
"""
import sys
from collections import deque

import numpy as np
from PIL import Image, ImageDraw

DENSITY = 3
KEYLINES = (24, 387)
NEAR_MISS = (1.5, 6.0)


def ink_mask(img):
    a = np.asarray(img.convert("RGB")).astype(np.int16)
    edge = np.concatenate([a[:, :3], a[:, -3:]], axis=1)
    bg = np.median(edge, axis=1)[:, None, :]
    return (np.abs(a - bg).sum(axis=2) > 36)


def to_dp_grid(mask):
    h, w = mask.shape
    h, w = h - h % DENSITY, w - w % DENSITY
    m = mask[:h, :w].reshape(h // DENSITY, DENSITY, w // DENSITY, DENSITY)
    return m.sum(axis=(1, 3)) >= 2


def dilate(grid, dx, dy):
    out = grid.copy()
    for s in range(1, dx + 1):
        out[:, s:] |= grid[:, :-s]
        out[:, :-s] |= grid[:, s:]
    g = out.copy()
    for s in range(1, dy + 1):
        out[s:, :] |= g[:-s, :]
        out[:-s, :] |= g[s:, :]
    return out


def components(grid, ink):
    h, w = grid.shape
    seen = np.zeros_like(grid)
    boxes = []
    for y in range(h):
        for x in range(w):
            if not grid[y, x] or seen[y, x]:
                continue
            q = deque([(y, x)])
            seen[y, x] = True
            cells = []
            while q:
                cy, cx = q.popleft()
                cells.append((cy, cx))
                for ny, nx in ((cy + 1, cx), (cy - 1, cx), (cy, cx + 1), (cy, cx - 1)):
                    if 0 <= ny < h and 0 <= nx < w and grid[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
            ys = [c[0] for c in cells]
            xs = [c[1] for c in cells]
            y0, y1, x0, x1 = min(ys), max(ys) + 1, min(xs), max(xs) + 1
            sub = ink[y0 * DENSITY:y1 * DENSITY, x0 * DENSITY:x1 * DENSITY]
            if not sub.any():
                continue
            rows = np.where(sub.any(axis=1))[0]
            cols = np.where(sub.any(axis=0))[0]
            box = (
                (x0 * DENSITY + cols[0]) / DENSITY,
                (y0 * DENSITY + rows[0]) / DENSITY,
                (x0 * DENSITY + cols[-1] + 1) / DENSITY,
                (y0 * DENSITY + rows[-1] + 1) / DENSITY,
            )
            if (box[2] - box[0]) * (box[3] - box[1]) >= 4:
                boxes.append(box)
    return sorted(boxes, key=lambda b: (round(b[1]), b[0]))


def measure(path):
    img = Image.open(path)
    ink = ink_mask(img)
    grid = dilate(to_dp_grid(ink), 3, 2)
    return img, components(grid, ink)


def fmt(v):
    return f"{v:.1f}".rstrip("0").rstrip(".")


def near_misses(boxes, key, label):
    out = []
    for i, a in enumerate(boxes):
        for b in boxes[i + 1:]:
            d = abs(key(a) - key(b))
            if NEAR_MISS[0] <= d <= NEAR_MISS[1]:
                out.append(f"  {label}: {fmt(key(a))} vs {fmt(key(b))} (off {fmt(d)} dp)  {short(a)}  {short(b)}")
    return out


def short(b):
    return f"[{fmt(b[0])},{fmt(b[1])} {fmt(b[2] - b[0])}x{fmt(b[3] - b[1])}]"


def cluster(values):
    groups = {}
    for v in values:
        groups.setdefault(round(v * 2) / 2, []).append(v)
    return sorted(((k, len(v)) for k, v in groups.items()), key=lambda kv: -kv[1])


def report(path):
    img, boxes = measure(path)
    print(f"{path}: {img.width // DENSITY}x{img.height // DENSITY} dp, {len(boxes)} ink boxes (x,y w x h in dp)")
    for b in boxes:
        cy = (b[1] + b[3]) / 2
        print(f"  {short(b)}  right {fmt(b[2])}  centre-y {fmt(cy)}")
    print("Left edges (dp: count):", ", ".join(f"{fmt(k)}:{n}" for k, n in cluster([b[0] for b in boxes])[:12]))
    print("Right edges (dp: count):", ", ".join(f"{fmt(k)}:{n}" for k, n in cluster([b[2] for b in boxes])[:12]))
    print("Near-misses (edges 1.5-6 dp apart: misaligned unless intended):")
    width = img.width / DENSITY
    trailing = [b for b in boxes if b[2] > width - 80]
    lines = near_misses(boxes, lambda b: b[0], "left") + near_misses(trailing, lambda b: b[2], "right")
    rows = []
    for i, a in enumerate(boxes):
        for b in boxes[i + 1:]:
            overlap = min(a[3], b[3]) - max(a[1], b[1])
            if overlap > 0.5 * min(a[3] - a[1], b[3] - b[1]) and (a[2] <= b[0] or b[2] <= a[0]):
                ca, cb = (a[1] + a[3]) / 2, (b[1] + b[3]) / 2
                if NEAR_MISS[0] <= abs(ca - cb) <= NEAR_MISS[1]:
                    rows.append(f"  centre-y in a row: {fmt(ca)} vs {fmt(cb)}  {short(a)}  {short(b)}")
    for line in (lines + rows) or ["  none"]:
        print(line)
    print("Vertical gaps between stacked boxes that share a left edge (dp):")
    by_left = {}
    for b in boxes:
        by_left.setdefault(round(b[0]), []).append(b)
    for left, col in sorted(by_left.items()):
        col.sort(key=lambda b: b[1])
        gaps = [fmt(col[i + 1][1] - col[i][3]) for i in range(len(col) - 1)]
        if gaps:
            print(f"  x={left}: {' '.join(gaps)}")


def overlay(path, out):
    img, boxes = measure(path)
    img = img.convert("RGB")
    draw = ImageDraw.Draw(img, "RGBA")
    w, h = img.size
    for y in range(0, h, 8 * DENSITY):
        draw.line([(0, y), (w, y)], fill=(0, 120, 255, 28))
    for x in range(0, w, 8 * DENSITY):
        draw.line([(x, 0), (x, h)], fill=(0, 120, 255, 28))
    for k in KEYLINES:
        draw.line([(k * DENSITY, 0), (k * DENSITY, h)], fill=(255, 0, 160, 140), width=2)
    for b in boxes:
        draw.rectangle([b[0] * DENSITY, b[1] * DENSITY, b[2] * DENSITY - 1, b[3] * DENSITY - 1],
                       outline=(255, 60, 0, 200), width=2)
    img.save(out)


def zoom(path, out, x, y, w, h, scale=2):
    img = Image.open(path).convert("RGB")
    crop = img.crop((int(x * DENSITY), int(y * DENSITY), int((x + w) * DENSITY), int((y + h) * DENSITY)))
    crop = crop.resize((crop.width * scale, crop.height * scale), Image.NEAREST)
    draw = ImageDraw.Draw(crop, "RGBA")
    step = 4 * DENSITY * scale
    for gx in range(0, crop.width, step):
        draw.line([(gx, 0), (gx, crop.height)], fill=(0, 120, 255, 40))
    for gy in range(0, crop.height, step):
        draw.line([(0, gy), (crop.width, gy)], fill=(0, 120, 255, 40))
    crop.save(out)


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "boxes":
        report(args[0])
    elif cmd == "overlay":
        overlay(args[0], args[1])
    elif cmd == "zoom":
        zoom(args[0], args[1], *map(float, args[2:6]), *(int(a) for a in args[6:7]))
    else:
        sys.exit(__doc__)
