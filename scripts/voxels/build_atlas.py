#!/usr/bin/env python3
"""Build the voxels block-texture atlas (raw RGBA) from a Minecraft-style texture pack.

The runtime loads `games/voxels/src/main/rust/shaders/atlas.bin` as a raw RGBA buffer of
ATLAS_W*ATLAS_H*4 bytes, laid out as COLS x ROWS tiles of TILE px each. Tile index i lives at
column i%COLS, row i//COLS (matching block.frag / block.rs tile_uv). No PIL: we decode PNGs with a
small pure-Python decoder (zlib is stdlib).

Existing tiles (0..63) are preserved by copying them out of the current atlas.bin so their pixels are
byte-identical; only the layout stride changes (8 cols -> 16 cols) with the tile INDEX kept the same.
New tiles are decoded from the pack. Referenced PNGs are also copied into assets/block/ for the
Compose hotbar icons.
"""
import os, struct, zlib, shutil

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PACK = os.path.join(ROOT, "tmp/Matcha_Flavoured/assets/minecraft/textures/block")
PACK_ITEM = os.path.join(ROOT, "tmp/Matcha_Flavoured/assets/minecraft/textures/item")
ATLAS_BIN = os.path.join(ROOT, "games/voxels/src/main/rust/shaders/atlas.bin")
ASSETS = os.path.join(ROOT, "games/voxels/src/main/assets/block")

TILE = 16
OLD_COLS = 8
NEW_COLS = 16
NEW_ROWS = 16
NEW_W = NEW_COLS * TILE
NEW_H = NEW_ROWS * TILE

# New tiles: atlas index -> source PNG filename in the pack. Indices 0..63 are copied from the old
# atlas untouched; new blocks start at 64.
NEW_TILES = {
    64: "red_sand.png",
    65: "red_sandstone.png",
    66: "red_sandstone_top.png",
    67: "sandstone.png",
    68: "sandstone_top.png",
    69: "sandstone_bottom.png",
    70: "podzol_top.png",
    71: "podzol_side.png",
    72: "coarse_dirt.png",
    73: "mycelium_side.png",
    74: "packed_ice.png",
    75: "ice.png",
    76: "blue_ice.png",
    77: "mud.png",
    78: "rooted_dirt.png",
    79: "dark_oak_log.png",
    80: "dark_oak_log_top.png",
    81: "dark_oak_leaves.png",
    82: "dark_oak_planks.png",
    83: "acacia_log.png",
    84: "acacia_log_top.png",
    85: "jungle_log.png",
    86: "jungle_log_top.png",
    87: "jungle_planks.png",
    88: "granite_bricks.png",
    89: "deepslate_bricks.png",
    90: "nether_bricks.png",
    91: "end_stone_bricks.png",
    92: "cobbled_deepslate.png",
    93: "hay_block_side.png",
    94: "hay_block_top.png",
    95: "farmland.png",
    96: "packed_dirt.png",
    116: "azalea_leaves.png",
    133: "netherite_block.png",
}

# Procedurally generated tiles (index -> base RGB) for blocks the pack lacks textures for (coral,
# cave, and ocean specials). Each is a solid colour with light deterministic speckle so it reads as a
# textured block rather than a flat swatch.
PROC_TILES = {
    143: (236, 238, 234),  # wool (soft off-white; the pack never retextures it)
    97:  (52, 90, 235),    # tube coral (blue)
    98:  (240, 116, 168),  # brain coral (pink)
    99:  (178, 52, 216),   # bubble coral (purple)
    100: (228, 54, 62),    # fire coral (red)
    101: (240, 214, 66),   # horn coral (yellow)
    102: (46, 112, 48),    # kelp (green)
    103: (204, 240, 228),  # sea lantern (bright cyan-white)
    104: (92, 158, 152),   # prismarine (teal)
    105: (44, 82, 76),     # dark prismarine
    106: (140, 108, 92),   # dripstone (tan-brown)
    107: (82, 128, 52),    # moss block (green)
    108: (18, 34, 44),     # sculk (dark blue-black)
    109: (154, 116, 216),  # amethyst (purple)
    110: (230, 230, 222),  # calcite (near white)
    111: (108, 112, 102),  # tuff (gray)
    112: (140, 46, 20),    # magma (dark orange-red)
    113: (242, 204, 90),   # glowstone (yellow)
    114: (26, 16, 40),     # obsidian (near-black purple)
    115: (158, 166, 178),  # clay (light gray-blue)
    117: (86, 214, 190),   # warding stone (glowing teal checkpoint)
    118: (74, 50, 30),     # jukebox (dark wood box)
    119: (139, 94, 52),    # chest (brown wood)
    120: (214, 92, 24),    # lava (glowing orange)
    121: (222, 226, 165),  # end stone (pale)
    122: (146, 52, 214),   # nether portal (purple)
    123: (36, 18, 66),     # end portal (dark starry)
    124: (150, 236, 240),  # beacon (bright glowing cyan)
    125: (170, 116, 172),  # purpur (muted purple)
    126: (214, 66, 66),    # firework rocket icon (red) — item only, tile unused by any block
    127: (226, 238, 250),  # snowball icon (near-white) — item only
    131: (198, 202, 214),  # silver block (pale metal)
    132: (86, 92, 104),    # steel block (blue-gray)
    134: (58, 54, 62),     # blast furnace top (dark iron)
    135: (44, 42, 48),     # blast furnace side
    138: (204, 118, 66),   # copper block
    139: (246, 208, 62),   # gold block
    140: (176, 141, 87),   # bronze block
    141: (188, 192, 198),  # stonecutter top (saw blade steel)
    142: (112, 110, 106),  # stonecutter side (stone body)
}

# Ore tiles: a stone/netherrack base with deterministic mineral blobs speckled over it, so new ores
# read like the pack's real ore textures rather than a flat colour.
# index -> (base tile index, mineral rgb, blob seed)
ORE_TILES = {
    128: (0, (214, 220, 232), 0x511E),   # silver ore in stone
    129: (37, (238, 214, 62), 0x5F1F),   # sulfur ore in netherrack
    130: (37, (196, 40, 44), 0xC14B),    # cinnabar ore in netherrack
    136: (0, (216, 130, 78), 0xC099),    # copper ore in stone
    137: (0, (250, 214, 76), 0x901D),    # gold ore in stone
}

# Blocks and items whose icon comes straight from a pack PNG. dest filename -> (subdir, source).
COPY_ICONS = {
    "adamant_block.png": ("block", "netherite_block.png"),
    "adamant_ingot.png": ("item", "netherite_ingot.png"),
    "adamant_pickaxe.png": ("item", "adamant_mattock.png"),
    "adamant_sword.png": ("item", "adamant_claymore.png"),
    "adamant_helmet.png": ("item", "netherite_helmet.png"),
    "adamant_chestplate.png": ("item", "netherite_chestplate.png"),
    "adamant_leggings.png": ("item", "netherite_leggings.png"),
    "adamant_boots.png": ("item", "netherite_boots.png"),
    "silver_ingot.png": ("item", "prismarine_crystals.png"),
    "shears.png": ("item", "shears.png"),
    "raw_fish.png": ("item", "catfish.png"),
    "quicksilver.png": ("item", "prismarine_shard.png"),
    # The blessing pantheon. Items only: a blessing is never placed as a block, so no atlas tile.
    **{f"blessing_{n}.png": ("item", f"blessing_{n}.png") for n in (
        "aeolus", "apollo", "arachnae", "ares", "artemis", "clement", "cronus", "daedalus",
        "demeter", "eros", "glaucus", "god_king", "hyacinthus", "icarus", "lu_ban", "paris",
        "prometheus", "talos", "warding", "will", "yama", "yamm",
    )},
    # Matcha's bronze tier, between iron and diamond.
    "copper_ingot.png": ("item", "copper_ingot.png"),
    "gold_ingot.png": ("item", "gold_ingot.png"),
    **{f"bronze_{n}.png": ("item", f"bronze_{n}.png") for n in (
        "pickaxe", "sword", "helmet", "chestplate", "leggings", "boots",
    )},
    # Matcha's cooked dishes.
    "cooked_meat.png": ("item", "porkchop_classic.png"),
    **{f"{n}.png": ("item", f"{n}.png") for n in (
        "ramen", "japanese_curry", "green_curry", "gnocchi", "naan", "pupusa", "latke",
        "bruschetta", "french_toast", "sweet_berry_danish", "melon_sorbet", "stroganoff",
    )},
}

# New blessings have no Matcha artwork, so they borrow a charm silhouette from an existing one and
# recolour it. dest -> (source blessing png, rgb multiplier).
TINT_ICONS = {
    "blessing_athena.png": ("blessing_warding.png", (0.72, 0.86, 1.25)),
    "blessing_sekhmet.png": ("blessing_ares.png", (1.30, 0.52, 0.48)),
    "blessing_camazotz.png": ("blessing_glaucus.png", (1.25, 0.45, 0.62)),
    "blessing_tangaroa.png": ("blessing_yamm.png", (0.55, 1.15, 1.10)),
    "blessing_anubis.png": ("blessing_prometheus.png", (1.20, 0.98, 0.42)),
    # No fishing rod in the pack; a blaze rod browned down reads as a wooden one.
    "fishing_rod.png": ("blaze_rod.png", (0.62, 0.46, 0.30)),
}

# Item-only icons with no atlas tile (items are never placed as blocks). filename -> base rgb.
PROC_ITEM_ICON = {
    "sulfur.png": (238, 214, 62),
    "steel_ingot.png": (118, 126, 140),
    "raw_meat.png": (214, 108, 116),  # the pack leaves raw meat vanilla, so make our own
    "bronze_ingot.png": (176, 141, 87),
}


# Asset filenames for the procedural tiles so the Compose hotbar has matching icons.
PROC_ICON = {
    97: "tube_coral.png", 98: "brain_coral.png", 99: "bubble_coral.png", 100: "fire_coral.png",
    101: "horn_coral.png", 102: "kelp.png", 103: "sea_lantern.png", 104: "prismarine.png",
    105: "dark_prismarine.png", 106: "dripstone.png", 107: "moss_block.png", 108: "sculk.png",
    109: "amethyst.png", 110: "calcite.png", 111: "tuff.png", 112: "magma.png", 113: "glowstone.png",
    114: "obsidian.png", 115: "clay.png", 117: "warding_stone.png", 118: "jukebox.png", 119: "chest.png",
    120: "lava.png", 121: "end_stone.png", 124: "beacon.png", 125: "purpur_block.png", 126: "firework_rocket.png", 127: "snowball.png",
    128: "silver_ore.png", 129: "sulfur_ore.png", 130: "cinnabar_ore.png",
    131: "silver_block.png", 132: "steel_block.png", 134: "blast_furnace.png",
    136: "copper_ore.png", 137: "gold_ore.png",
    138: "copper_block.png", 139: "gold_block.png", 140: "bronze_block.png",
    141: "stonecutter.png",
    143: "wool.png",
}


def write_png(path, w, h, rgba):
    def chunk(typ, data):
        c = typ + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter: none
        raw += rgba[y*w*4:(y+1)*w*4]
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    out = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b"")
    open(path, "wb").write(out)


def make_proc_tile(rgb):
    """A 16x16 RGBA solid tile with deterministic per-pixel brightness speckle for texture."""
    r, g, b = rgb
    out = bytearray(TILE * TILE * 4)
    for y in range(TILE):
        for x in range(TILE):
            # cheap hash noise in [-18, 18]
            hh = (x * 374761393 + y * 668265263) & 0xFFFFFFFF
            hh = (hh ^ (hh >> 13)) * 1274126177 & 0xFFFFFFFF
            n = (hh % 37) - 18
            o = (y * TILE + x) * 4
            out[o]   = max(0, min(255, r + n))
            out[o+1] = max(0, min(255, g + n))
            out[o+2] = max(0, min(255, b + n))
            out[o+3] = 255
    return out


def read_tile(atlas, tile_index):
    """Copy a 16x16 RGBA tile out of the packed atlas."""
    col, row = tile_index % NEW_COLS, tile_index // NEW_COLS
    out = bytearray(TILE * TILE * 4)
    for ty in range(TILE):
        s = ((row * TILE + ty) * NEW_W + col * TILE) * 4
        out[ty*TILE*4:(ty+1)*TILE*4] = atlas[s:s + TILE*4]
    return out


def make_ore_tile(base, rgb, seed):
    """Speckle mineral blobs over a base stone tile so it reads as an ore vein."""
    out = bytearray(base)
    r, g, b = rgb
    h = seed & 0xFFFFFFFF
    def rnd(limit):
        nonlocal h
        h = (h * 1103515245 + 12345) & 0x7FFFFFFF
        return h % limit
    for _ in range(8):
        cx, cy, rad = rnd(TILE), rnd(TILE), 1 + rnd(2)
        for y in range(cy - rad, cy + rad + 1):
            for x in range(cx - rad, cx + rad + 1):
                if not (0 <= x < TILE and 0 <= y < TILE): continue
                if (x - cx) ** 2 + (y - cy) ** 2 > rad * rad: continue
                # Keep the base tile's own luminance variation so the ore isn't a flat blob.
                o = (y * TILE + x) * 4
                shade = (base[o] + base[o+1] + base[o+2]) / 765.0 * 0.4 + 0.8
                out[o]   = max(0, min(255, int(r * shade)))
                out[o+1] = max(0, min(255, int(g * shade)))
                out[o+2] = max(0, min(255, int(b * shade)))
                out[o+3] = 255
    return out


def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc: return a
    if pb <= pc: return b
    return c


def decode_png(path):
    """Return (w, h, rgba_bytes). Supports 8-bit gray/RGB/RGBA and 4/8-bit palette, with tRNS."""
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a png: " + path
    pos = 8
    w = h = bd = ct = None
    idat = bytearray()
    plte = None
    trns = None
    while pos < len(data):
        (ln,) = struct.unpack(">I", data[pos:pos+4])
        typ = data[pos+4:pos+8]
        chunk = data[pos+8:pos+8+ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", chunk[:10])
        elif typ == b"PLTE":
            plte = chunk
        elif typ == b"tRNS":
            trns = chunk
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
        pos += 12 + ln

    raw = zlib.decompress(bytes(idat))
    if ct == 0: channels = 1
    elif ct == 2: channels = 3
    elif ct == 3: channels = 1
    elif ct == 4: channels = 2
    elif ct == 6: channels = 4
    else: raise ValueError("unsupported color type %d in %s" % (ct, path))

    bits_per_pixel = channels * bd
    bpp = max(1, (bits_per_pixel + 7) // 8)          # filter byte offset
    stride = (w * bits_per_pixel + 7) // 8           # bytes per scanline (no filter byte)

    # Unfilter.
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(bpp, stride): line[i] = (line[i] + line[i-bpp]) & 255
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0
                c = prev[i-bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(a, prev[i], c)) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line

    # Expand to RGBA.
    rgba = bytearray(w * h * 4)
    def pal(idx):
        r, g, b = plte[idx*3], plte[idx*3+1], plte[idx*3+2]
        a = trns[idx] if (trns and idx < len(trns)) else 255
        return r, g, b, a
    for y in range(h):
        for x in range(w):
            o = (y*w + x) * 4
            if ct == 6:
                s = y*stride + x*4
                rgba[o:o+4] = out[s:s+4]
            elif ct == 2:
                s = y*stride + x*3
                rgba[o] = out[s]; rgba[o+1] = out[s+1]; rgba[o+2] = out[s+2]; rgba[o+3] = 255
            elif ct == 0:
                g = out[y*stride + x]
                rgba[o] = g; rgba[o+1] = g; rgba[o+2] = g; rgba[o+3] = 255
            elif ct == 4:
                s = y*stride + x*2
                g, a = out[s], out[s+1]
                rgba[o] = g; rgba[o+1] = g; rgba[o+2] = g; rgba[o+3] = a
            elif ct == 3:
                if bd == 8:
                    idx = out[y*stride + x]
                elif bd == 4:
                    byte = out[y*stride + (x >> 1)]
                    idx = (byte >> 4) if (x & 1) == 0 else (byte & 0xF)
                else:
                    raise ValueError("palette bd %d unsupported" % bd)
                r, g, b, a = pal(idx)
                rgba[o] = r; rgba[o+1] = g; rgba[o+2] = b; rgba[o+3] = a
    return w, h, rgba


def blit(dst, dst_w, tile_index, cols, src_rgba, src_w):
    col = tile_index % cols
    row = tile_index // cols
    x0 = col * TILE
    y0 = row * TILE
    for ty in range(TILE):
        for tx in range(TILE):
            s = (ty * src_w + tx) * 4  # src is at least TILE wide; use first 16x16 of animated strips
            d = ((y0 + ty) * dst_w + (x0 + tx)) * 4
            dst[d:d+4] = src_rgba[s:s+4]


def main():
    raw = open(ATLAS_BIN, "rb").read()
    if len(raw) == NEW_W * NEW_H * 4:
        # Already expanded: keep every existing tile, only (re)write the new ones below.
        new = bytearray(raw)
    elif len(raw) == (OLD_COLS * TILE) * (OLD_COLS * TILE) * 4:
        # Migrate the original 8x8 atlas into the 16x16 layout, preserving tile INDEX.
        old_w = OLD_COLS * TILE
        new = bytearray(NEW_W * NEW_H * 4)
        for i in range(OLD_COLS * OLD_COLS):
            oc, orow = i % OLD_COLS, i // OLD_COLS
            tile = bytearray(TILE * TILE * 4)
            for ty in range(TILE):
                for tx in range(TILE):
                    s = ((orow*TILE + ty) * old_w + (oc*TILE + tx)) * 4
                    tile[(ty*TILE + tx)*4:(ty*TILE + tx)*4+4] = old[s:s+4]
            blit(new, NEW_W, i, NEW_COLS, tile, TILE)
    else:
        raise SystemExit("unexpected atlas.bin size %d" % len(raw))

    os.makedirs(ASSETS, exist_ok=True)
    # Icons that COPY_ICONS renames are skipped here so the pack filename isn't also left behind.
    renamed = {src for _, src in COPY_ICONS.values()}
    for idx, fname in sorted(NEW_TILES.items()):
        src = os.path.join(PACK, fname)
        w, h, rgba = decode_png(src)
        blit(new, NEW_W, idx, NEW_COLS, rgba, w)
        if fname not in renamed:
            shutil.copyfile(src, os.path.join(ASSETS, fname))
        print("tile %3d <- %s (%dx%d)" % (idx, fname, w, h))

    for idx, rgb in sorted(PROC_TILES.items()):
        tile = make_proc_tile(rgb)
        blit(new, NEW_W, idx, NEW_COLS, tile, TILE)
        if idx in PROC_ICON:
            write_png(os.path.join(ASSETS, PROC_ICON[idx]), TILE, TILE, tile)
        print("tile %3d <- proc %s" % (idx, rgb))

    for idx, (base_idx, rgb, seed) in sorted(ORE_TILES.items()):
        tile = make_ore_tile(read_tile(new, base_idx), rgb, seed)
        blit(new, NEW_W, idx, NEW_COLS, tile, TILE)
        if idx in PROC_ICON:
            write_png(os.path.join(ASSETS, PROC_ICON[idx]), TILE, TILE, tile)
        print("tile %3d <- ore over tile %d %s" % (idx, base_idx, rgb))

    for name, rgb in sorted(PROC_ITEM_ICON.items()):
        write_png(os.path.join(ASSETS, name), TILE, TILE, make_proc_tile(rgb))
        print("icon %s <- proc %s" % (name, rgb))

    for dest, (subdir, src_name) in sorted(COPY_ICONS.items()):
        src = os.path.join(PACK if subdir == "block" else PACK_ITEM, src_name)
        shutil.copyfile(src, os.path.join(ASSETS, dest))
        print("icon %s <- %s/%s" % (dest, subdir, src_name))

    for dest, (src_name, mul) in sorted(TINT_ICONS.items()):
        w, h, rgba = decode_png(os.path.join(PACK_ITEM, src_name))
        out = bytearray(rgba)
        for i in range(0, len(out), 4):
            for c in range(3):
                out[i + c] = min(255, int(out[i + c] * mul[c]))
        write_png(os.path.join(ASSETS, dest), w, h, bytes(out))
        print("icon %s <- %s tinted %s" % (dest, src_name, mul))

    open(ATLAS_BIN, "wb").write(new)
    print("wrote %s (%dx%d, %d bytes)" % (ATLAS_BIN, NEW_W, NEW_H, len(new)))


if __name__ == "__main__":
    main()
