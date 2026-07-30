#!/usr/bin/env python3
"""Assemble mob skins into a single entity atlas (raw RGBA, 256x256, 4x4 grid of 64px cells).

Cell index = row*4 + col; the Rust mob renderer offsets each mob's skin-space UVs into its cell. Skins
that are 64x32 occupy the top half of their cell (their model UVs only touch that region).
"""
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from build_atlas import decode_png  # reuse the pure-Python PNG decoder

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ENT = os.path.join(ROOT, "tmp/Matcha_Flavoured/assets/minecraft/textures/entity")
OUT = os.path.join(ROOT, "games/voxels/src/main/rust/shaders/entity_atlas.bin")

CELL = 64
COLS = 4
W = H = 256

# Cell index -> skin path (must match the MobKind order in entity.rs).
SKINS = {
    0: "pig/pig_temperate.png",
    1: "cow/cow_temperate.png",
    2: "sheep/sheep.png",
    3: "chicken/chicken_temperate.png",
    4: "creeper/creeper.png",
    5: "zombie/zombie.png",
    6: "zombie_villager/zombie_villager.png",  # closest villager-shaped humanoid the pack ships
}


def main():
    atlas = bytearray(W * H * 4)
    for idx, rel in SKINS.items():
        p = os.path.join(ENT, rel)
        w, h, rgba = decode_png(p)
        cx = (idx % COLS) * CELL
        cy = (idx // COLS) * CELL
        for y in range(min(h, CELL)):
            for x in range(min(w, CELL)):
                s = (y * w + x) * 4
                d = ((cy + y) * W + (cx + x)) * 4
                atlas[d:d+4] = rgba[s:s+4]
        print("cell %d <- %s (%dx%d)" % (idx, rel, w, h))
    # Cell 7: procedural dark-purple Ender Dragon skin (uniform, sampled at the cell centre).
    dx7, dy7 = (7 % COLS) * CELL, (7 // COLS) * CELL
    for y in range(CELL):
        for x in range(CELL):
            n = ((x * 7 + y * 13) % 11) - 5
            d = ((dy7 + y) * W + (dx7 + x)) * 4
            atlas[d:d+4] = bytes([max(0, 34 + n), max(0, 12 + n), max(0, 48 + n), 255])
    # Cell 8: procedural dark Wither skin (charcoal grey).
    dx8, dy8 = (8 % COLS) * CELL, (8 // COLS) * CELL
    for y in range(CELL):
        for x in range(CELL):
            n = ((x * 5 + y * 11) % 9) - 4
            d = ((dy8 + y) * W + (dx8 + x)) * 4
            atlas[d:d+4] = bytes([max(0, 34 + n), max(0, 34 + n), max(0, 38 + n), 255])
    # Cell 9: procedural glowing Blaze skin (orange-yellow).
    dx9, dy9 = (9 % COLS) * CELL, (9 // COLS) * CELL
    for y in range(CELL):
        for x in range(CELL):
            n = ((x * 9 + y * 7) % 41) - 12
            d = ((dy9 + y) * W + (dx9 + x)) * 4
            atlas[d:d+4] = bytes([min(255, 236 + n // 3), max(0, 150 + n), max(0, 24 + n // 2), 255])
    # Cell 10: procedural Wither Skeleton skin (near-black charcoal).
    dx10, dy10 = (10 % COLS) * CELL, (10 // COLS) * CELL
    for y in range(CELL):
        for x in range(CELL):
            n = ((x * 3 + y * 13) % 7) - 3
            d = ((dy10 + y) * W + (dx10 + x)) * 4
            atlas[d:d+4] = bytes([max(0, 22 + n), max(0, 22 + n), max(0, 24 + n), 255])
    # Cell 11: procedural Shulker skin (muted purple, matching purpur).
    dx11, dy11 = (11 % COLS) * CELL, (11 // COLS) * CELL
    for y in range(CELL):
        for x in range(CELL):
            n = ((x * 7 + y * 5) % 21) - 10
            d = ((dy11 + y) * W + (dx11 + x)) * 4
            atlas[d:d+4] = bytes([max(0, 150 + n), max(0, 116 + n), max(0, 172 + n), 255])
    # Cell 12: procedural Ghast skin (pale off-white).
    dx12, dy12 = (12 % COLS) * CELL, (12 // COLS) * CELL
    for y in range(CELL):
        for x in range(CELL):
            n = ((x * 5 + y * 9) % 13) - 6
            d = ((dy12 + y) * W + (dx12 + x)) * 4
            atlas[d:d+4] = bytes([min(255, 224 + n), min(255, 224 + n), min(255, 218 + n), 255])
    # White swatch in the last cell (index 15) for tinted particle quads. Rust samples its centre.
    wx, wy = (15 % COLS) * CELL, (15 // COLS) * CELL
    for y in range(8):
        for x in range(8):
            d = ((wy + y) * W + (wx + x)) * 4
            atlas[d:d+4] = bytes([255, 255, 255, 255])
    print("white particle swatch at cell 15")
    open(OUT, "wb").write(atlas)
    print("wrote %s (%dx%d, %d bytes)" % (OUT, W, H, len(atlas)))


if __name__ == "__main__":
    main()
