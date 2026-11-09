#!/usr/bin/env python3
"""Generate the checked-in 10×10 vocation sprites using only the Python standard library."""

import argparse
import json
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parents[2]


def rgba(value):
    value = bytes.fromhex(value.removeprefix("#"))
    return tuple(value if len(value) == 4 else value + b"\xff")


def png(path, rows):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    height, width = len(rows), len(rows[0])
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel) for row in rows)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n"
                    + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
                    + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def dark(rows, rim):
    # Keep the same 10×10 canvas and silhouette; a light rim occupies adjacent transparent pixels.
    size = len(rows)
    return [[rim if not rows[y][x][3] and any(
        0 <= x + dx < size and 0 <= y + dy < size and rows[y + dy][x + dx][3]
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1))) else rows[y][x]
        for x in range(size)] for y in range(size)]


def sheet(path, sprites):
    # Rows follow icons.json; the two halves compare paper and dark-card backgrounds at 1×, 2× and 8×.
    width, height = 360, 15 * 92 + 12
    paper, card = rgba("#e1cda0"), rgba("#25221f")
    rows = [[paper if x < width // 2 else card for x in range(width)] for _ in range(height)]
    for index, (light, darkened) in enumerate(sprites):
        for offset, pixels in ((0, light), (180, darkened)):
            for left, scale in ((12, 1), (36, 2), (76, 8)):
                for y, line in enumerate(pixels):
                    for x, color in enumerate(line):
                        if color[3]:
                            for dy in range(scale):
                                for dx in range(scale):
                                    rows[12 + index * 92 + y * scale + dy][offset + left + x * scale + dx] = color
    png(path, rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sheet", type=Path, help="also write a paper/dark 1×/2×/8× comparison sheet")
    args = parser.parse_args()
    source = json.loads(Path(__file__).with_name("icons.json").read_text())
    sprites = []
    for icon in source["icons"]:
        pixels = icon["pixels"]
        palette = {key: rgba(value) for key, value in icon["palette"].items()}
        if len(pixels) != 10 or any(len(row) != 10 for row in pixels):
            raise ValueError(f"{icon['sprite']}: expected a 10×10 grid")
        if len(palette.keys() - {".", "#"}) > 2 or palette["#"] != rgba("#493526"):
            raise ValueError(f"{icon['sprite']}: expected the brown outline and at most two accents")
        rows = [[palette[key] for key in line] for line in pixels]
        darkened = dark(rows, rgba(source["dark_rim"]))
        namespace, sprite = icon["sprite"].split(":")
        mod = namespace.removeprefix("folkways_")
        root = ROOT / f"mods/{mod}/src/main/resources/assets/{namespace}/textures/gui/sprites"
        png(root / f"{sprite}.png", rows)
        png(root / f"dark/{sprite}.png", darkened)
        sprites.append((rows, darkened))
    # Tiny, font-independent disclosure marks keep the group headings legible in every locale.
    root = ROOT / "mods/core/src/main/resources/assets/folkways_core/textures/gui/sprites/glyph"
    for name, pixels in {"folded": ["#..", "##.", "###", "##.", "#.."],
                         "unfolded": ["#####", ".###.", "..#.."]}.items():
        png(root / f"{name}.png", [[rgba("#493526") if key == "#" else (0, 0, 0, 0)
                                  for key in row] for row in pixels])
    if args.sheet:
        sheet(args.sheet, sprites)


if __name__ == "__main__":
    main()
