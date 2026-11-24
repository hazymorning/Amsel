#!/usr/bin/env python3
"""Builds the static fonts of docs/DESIGN.md, "Type"; needs fonttools.

The source is upstream Commissioner 1.012, pinned by hash, for its tabular figures.
"""
import hashlib
import io
import pathlib
import sys
import urllib.request

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = pathlib.Path(__file__).resolve().parents[2]
FONTS = ROOT / "app/src/main/res/font"
LICENCES = ROOT / "app/src/main/assets/licences"
COMMISSIONER = "https://raw.githubusercontent.com/kosbarts/Commissioner/master"

SOURCES = {
    "commissioner": (
        f"{COMMISSIONER}/fonts/variable/Commissioner%5BFLAR,VOLM,slnt,wght%5D.ttf",
        "866b403dd8ab4168858e1e8fca6485eea5c3ea7b99f7342039d083f28d561912",
        f"{COMMISSIONER}/OFL.txt",
        "OFL-Commissioner.txt",
    ),
}

TEXT = (400, 440, 500, 520, 560, 600, 700)
TITLES = (650, 700)

OUTPUTS = (
    [(f"commissioner_{w}.ttf", "commissioner", {"wght": w, "FLAR": 0, "VOLM": 0, "slnt": 0}) for w in TEXT]
    + [(f"commissioner_title_{w}.ttf", "commissioner", {"wght": w, "FLAR": 25, "VOLM": 0, "slnt": 0}) for w in TITLES]
)

# Google Fonts' "latin" and "latin-ext" ranges.
UNICODES = (
    "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,"
    "U+2000-206F,U+20AC,U+2122,U+2190-2193,U+2212,U+2215,U+FEFF,U+FFFD,"
    "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,"
    "U+20A0-20AB,U+20AD-20C0,U+2113"
)

# German and the typography Amsel's strings use.
REQUIRED = "ÄÖÜäöüß„“”‚‘’–—…·×−↓↑ 0123456789"


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def build(data: bytes, axes: dict, out: pathlib.Path) -> None:
    font = TTFont(io.BytesIO(data))
    present = {a.axisTag for a in font["fvar"].axes}
    font = instancer.instantiateVariableFont(font, {k: v for k, v in axes.items() if k in present})
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    options.hinting = False
    sub = subset.Subsetter(options)
    sub.populate(unicodes=subset.parse_unicodes(UNICODES))
    sub.subset(font)
    cmap = font.getBestCmap()
    missing = [c for c in REQUIRED if ord(c) not in cmap]
    if missing:
        sys.exit(f"{out.name} lacks {''.join(missing)}")
    font.save(out)
    print(f"wrote {out.relative_to(ROOT)} ({out.stat().st_size // 1024} KB)")


def main() -> int:
    sources = {}
    for name, (url, digest, _, _) in SOURCES.items():
        data = fetch(url)
        if digest is not None and hashlib.sha256(data).hexdigest() != digest:
            sys.exit(f"{name}: unexpected hash")
        sources[name] = data
    FONTS.mkdir(parents=True, exist_ok=True)
    for old in FONTS.glob("*.ttf"):
        old.unlink()
    for file, source, axes in OUTPUTS:
        build(sources[source], axes, FONTS / file)
    LICENCES.mkdir(parents=True, exist_ok=True)
    for old in LICENCES.glob("OFL-*.txt"):
        old.unlink()
    for _, (_, _, licence_url, licence_name) in SOURCES.items():
        licence = LICENCES / licence_name
        licence.write_bytes(fetch(licence_url))
        print(f"wrote {licence.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
