use super::chunk::{Chunk, CHUNK_SIZE, CHUNK_HEIGHT};
use crate::world::perlin::NoiseFn;
use crate::world::perlin::Perlin;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Biome {
    Plains, SunflowerPlains, Meadow,
    Forest, BirchForest, DarkForest, FlowerForest,
    Taiga, SnowyTaiga,
    Savanna, Jungle, SparseJungle,
    Swamp, MangroveSwamp,
    Desert, Badlands,
    SnowyPlains, IceSpikes, MushroomFields,
    WindsweptHills, Grove, SnowySlopes,
    JaggedPeaks, FrozenPeaks, StonyPeaks,
}

// ---- Multi-chunk villages (deterministic region grid) ----
const VILLAGE_REGION: i32 = 6; // chunks per region side; at most one village per region

const B_HOUSE: u8 = 0;
const B_WELL: u8 = 1;
const B_LAMP: u8 = 2;
const B_BIGHOUSE: u8 = 3;
const B_FARM: u8 = 4;
const B_BLACKSMITH: u8 = 5;
const B_CHURCH: u8 = 6;

// Level a village column: dirt foundation down a few blocks, clear terrain above up to `clear_to`.
fn level_column(chunk: &mut Chunk, lx: usize, lz: usize, base: usize, clear_to: usize, floor: u8) {
    for dyb in 1..=3 { if base >= dyb { chunk.set_block(lx, base - dyb, lz, 2); } } // dirt foundation
    chunk.set_block(lx, base, lz, floor);
    for dy in 1..=clear_to { if base + dy < CHUNK_HEIGHT { chunk.set_block(lx, base + dy, lz, 0); } }
}

fn hash3(a: i32, b: i32, c: u32) -> u64 {
    let mut h = DefaultHasher::new();
    (a, b, c).hash(&mut h);
    h.finish()
}
fn in_chunk(wx: i32, wz: i32, ox: i32, oz: i32) -> bool { wx >= ox && wx < ox + 16 && wz >= oz && wz < oz + 16 }

// Render a single village building, writing only the columns that fall inside this chunk (so a
// building straddling a chunk border is completed by whichever chunks overlap it).
fn render_building(chunk: &mut Chunk, ox: i32, oz: i32, bx: i32, bz: i32, w: i32, d: i32, base: usize, kind: u8) {
    if base + 7 >= CHUNK_HEIGHT { return; }
    match kind {
        B_WELL => {
            for gx in 0..3 { for gz in 0..3 {
                let (wx, wz) = (bx + gx, bz + gz);
                if !in_chunk(wx, wz, ox, oz) { continue; }
                let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
                let id = if gx == 1 && gz == 1 { 12 } else { 8 };
                chunk.set_block(lx, base, lz, id);
                let corner = (gx == 0 || gx == 2) && (gz == 0 || gz == 2);
                if corner { for dy in 1..=2 { chunk.set_block(lx, base + dy, lz, 8); } }
            }}
        }
        B_LAMP => {
            if in_chunk(bx, bz, ox, oz) {
                let (lx, lz) = ((bx - ox) as usize, (bz - oz) as usize);
                for dy in 1..=3 { chunk.set_block(lx, base + dy, lz, 4); }
                chunk.set_block(lx, base + 4, lz, 77); // glowstone lamp
            }
        }
        B_FARM => {
            for gx in 0..w { for gz in 0..d {
                let (wx, wz) = (bx + gx, bz + gz);
                if !in_chunk(wx, wz, ox, oz) { continue; }
                let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
                let water = gx == w / 2; // central irrigation channel
                level_column(chunk, lx, lz, base, 3, if water { 12 } else { 59 }); // water / farmland
                if !water && (gx + gz) % 2 == 0 && base + 1 < CHUNK_HEIGHT {
                    chunk.set_block(lx, base + 1, lz, 58); // hay = ripe crop stand-in
                }
            }}
        }
        _ => {
            let height: usize = match kind { B_BIGHOUSE => 6, B_CHURCH => 9, _ => 4 };
            for gx in 0..w { for gz in 0..d {
                let (wx, wz) = (bx + gx, bz + gz);
                if !in_chunk(wx, wz, ox, oz) { continue; }
                let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
                level_column(chunk, lx, lz, base, height + 2, 8); // foundation + cobble floor + clear
                let edge = gx == 0 || gx == w - 1 || gz == 0 || gz == d - 1;
                if edge {
                    for dy in 1..height {
                        let door = gz == 0 && gx == w / 2 && dy <= 2;
                        if door { continue; }
                        let window = dy == 2 && (((gx == 0 || gx == w - 1) && gz == d / 2) || (gz == d - 1 && gx == w / 2));
                        chunk.set_block(lx, base + dy, lz, if window { 7 } else { 10 });
                    }
                }
                chunk.set_block(lx, base + height, lz, 10); // roof
            }}
            let (cxw, czw) = (bx + w / 2, bz + d / 2);
            if in_chunk(cxw, czw, ox, oz) {
                chunk.set_block((cxw - ox) as usize, base, (czw - oz) as usize, if kind == B_BIGHOUSE { 81 } else { 77 });
            }
            // Blacksmith forge: furnace + magma fire + iron-block anvil inside.
            if kind == B_BLACKSMITH {
                for &(dx, dz, dy, id) in &[(1i32, 1i32, 1usize, 35u8), (2, 1, 0, 76), (3, 1, 1, 23)] {
                    let (wx, wz) = (bx + dx, bz + dz);
                    if in_chunk(wx, wz, ox, oz) { chunk.set_block((wx - ox) as usize, base + dy, (wz - oz) as usize, id); }
                }
            }
            // Church spire: a sea-lantern beacon above the roof.
            if kind == B_CHURCH && in_chunk(cxw, czw, ox, oz) && base + height + 2 < CHUNK_HEIGHT {
                chunk.set_block((cxw - ox) as usize, base + height + 1, (czw - oz) as usize, 67);
            }
        }
    }
}

// A partial obsidian ruined-portal frame standing at the surface.
fn build_ruined_portal(chunk: &mut Chunk, cx: usize, cz: usize, base: usize) {
    if cx + 4 >= 16 || base + 6 >= CHUNK_HEIGHT { return; }
    for gx in 0..4i32 { for gy in 0..5i32 {
        let edge = gx == 0 || gx == 3 || gy == 0 || gy == 4;
        if !edge { continue; }
        // Broken: skip a few frame blocks pseudo-randomly.
        if (gx * 7 + gy * 13) % 5 == 0 { continue; }
        chunk.set_block(cx + gx as usize, base + gy as usize, cz, 78); // obsidian
    }}
    // A little rubble at the base, with scattered magma and glowstone.
    for gx in -1i32..=4 {
        let x = cx as i32 + gx;
        if x >= 0 && x < 16 {
            let rub = match (gx.rem_euclid(3), gx.rem_euclid(5)) { (0, _) => 76, (_, 0) => 77, _ => 78 }; // magma/glowstone/obsidian
            chunk.set_block(x as usize, base, cz, rub);
        }
    }
    // Loot chest beside the ruined frame.
    if cx + 4 < 16 { chunk.set_block(cx + 4, base + 1, cz, 83); }
}

// Carve one 3-wide mineshaft corridor arm from a centre along (dirx,dirz), with a plank floor,
// periodic wood support frames, and only the columns inside this chunk written.
fn carve_corridor(chunk: &mut Chunk, ox: i32, oz: i32, cx: i32, cz: i32, dirx: i32, dirz: i32, len: i32, fy: usize) {
    for i in 0..len {
        let (ax, az) = (cx + dirx * i, cz + dirz * i);
        for p in -1..=1 {
            let wx = ax + if dirx != 0 { 0 } else { p };
            let wz = az + if dirz != 0 { 0 } else { p };
            if !in_chunk(wx, wz, ox, oz) { continue; }
            let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
            chunk.set_block(lx, fy, lz, 10); // plank floor
            for dy in 1..=2 { if fy + dy < CHUNK_HEIGHT { chunk.set_block(lx, fy + dy, lz, 0); } }
        }
        if i % 6 == 0 {
            for &p in &[-1i32, 1] {
                let wx = ax + if dirx != 0 { 0 } else { p };
                let wz = az + if dirz != 0 { 0 } else { p };
                if in_chunk(wx, wz, ox, oz) { let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize); for dy in 1..=2 { if fy + dy < CHUNK_HEIGHT { chunk.set_block(lx, fy + dy, lz, 4); } } } // posts
            }
            for p in -1..=1 {
                let wx = ax + if dirx != 0 { 0 } else { p };
                let wz = az + if dirz != 0 { 0 } else { p };
                if in_chunk(wx, wz, ox, oz) && fy + 3 < CHUNK_HEIGHT { chunk.set_block((wx - ox) as usize, fy + 3, (wz - oz) as usize, 4); } // beam
            }
        }
    }
}

// A small underground dungeon room: mossy-cobble shell, a light, and a couple of loot blocks.
fn build_dungeon(chunk: &mut Chunk, cx: usize, cz: usize, y: usize, r: u64) {
    if cx + 4 >= 16 || cz + 4 >= 16 || y + 5 >= CHUNK_HEIGHT || y < 2 { return; }
    for gx in 0..5 { for gz in 0..5 { for gy in 0..5 {
        let edge = gx == 0 || gx == 4 || gz == 0 || gz == 4 || gy == 0 || gy == 4;
        let id = if edge { if (gx + gz + gy) % 3 == 0 { 15 } else { 8 } } else { 0 };
        chunk.set_block(cx + gx, y + gy, cz + gz, id);
    }}}
    chunk.set_block(cx + 2, y + 3, cz + 2, 77); // glowstone light
    let _ = r;
    // Loot chests in the corners.
    chunk.set_block(cx + 1, y + 1, cz + 1, 83);
    chunk.set_block(cx + 3, y + 1, cz + 3, 83);
}

// A stepped sandstone desert temple with a small hidden chamber of loot chests underneath.
fn build_temple(chunk: &mut Chunk, cx: usize, cz: usize, base: usize) {
    let (c, cz2) = (cx as i32, cz as i32);
    if c - 4 < 0 || c + 4 >= 16 || cz2 - 4 < 0 || cz2 + 4 >= 16 || base + 6 >= CHUNK_HEIGHT { return; }
    for gy in 0..6i32 {
        let r = 4 - gy;
        for gx in -r..=r { for gz in -r..=r {
            chunk.set_block((c + gx) as usize, base + gy as usize, (cz2 + gz) as usize, 38); // sandstone
        }}
    }
    for gy in 1..=3 { for gx in -1i32..=1 { for gz in -1i32..=1 {
        let y = base as i32 - gy;
        if y >= 1 { chunk.set_block((c + gx) as usize, y as usize, (cz2 + gz) as usize, 0); }
    }}}
    let fy = base as i32 - 3;
    if fy >= 1 {
        chunk.set_block((c - 1) as usize, fy as usize, (cz2 - 1) as usize, 83);
        chunk.set_block((c + 1) as usize, fy as usize, (cz2 + 1) as usize, 83);
        if base >= 1 { chunk.set_block(cx, base - 1, cz, 77); }
    }
}

// An underground stronghold room: brick/cobble shell, loot chests, and a decorative obsidian frame.
fn build_stronghold(chunk: &mut Chunk, cx: usize, cz: usize, y: usize) {
    if cx + 6 >= 16 || cz + 6 >= 16 || y + 5 >= CHUNK_HEIGHT || y < 2 { return; }
    for gx in 0..7 { for gz in 0..7 { for gy in 0..5 {
        let edge = gx == 0 || gx == 6 || gz == 0 || gz == 6 || gy == 0 || gy == 4;
        let id = if edge { if (gx + gz + gy) % 4 == 0 { 54 } else { 8 } } else { 0 };
        chunk.set_block(cx + gx, y + gy, cz + gz, id);
    }}}
    chunk.set_block(cx + 1, y + 1, cz + 1, 83);
    chunk.set_block(cx + 5, y + 1, cz + 5, 83);
    chunk.set_block(cx + 3, y + 3, cz + 3, 77); // glowstone
    for gx in 2..=4 { chunk.set_block(cx + gx, y + 1, cz + 3, 78); chunk.set_block(cx + gx, y + 3, cz + 3, 78); }
    for gy in 1..=3 { chunk.set_block(cx + 2, y + gy, cz + 3, 78); chunk.set_block(cx + 4, y + gy, cz + 3, 78); }
    // Active End portal: a 2x2 pool of end-portal blocks on the floor — walk in to reach the End.
    for gx in 2..=3 { for gz in 2..=3 {
        chunk.set_block(cx + gx, y, cz + gz, 85);       // end-stone rim under the portal
        chunk.set_block(cx + gx, y + 1, cz + gz, 87);   // end portal
    }}
}

// A nether-brick fortress bridge section carved into the netherrack: a walkway with railings,
// arched pillars, glowstone lamps and loot chests. Built above the lava line in the Nether.
fn build_nether_fortress(chunk: &mut Chunk, cx: usize, cz: usize, base: usize, r: u64) {
    if cx + 8 >= 16 || cz + 8 >= 16 || base + 8 >= CHUNK_HEIGHT || base < 2 { return; }
    // Hollow out a hall.
    for gx in 0..9 { for gz in 0..9 { for gy in 1..7 { chunk.set_block(cx + gx, base + gy, cz + gz, 0); }}}
    // Nether-brick floor + surrounding railing.
    for gx in 0..9 { for gz in 0..9 {
        chunk.set_block(cx + gx, base, cz + gz, 55);
        let edge = gx == 0 || gx == 8 || gz == 0 || gz == 8;
        if edge && (gx + gz) % 2 == 0 { chunk.set_block(cx + gx, base + 1, cz + gz, 55); } // low railing
    }}
    // Four arched corner pillars up to the ceiling beam.
    for &(px, pz) in &[(0usize, 0usize), (8, 0), (0, 8), (8, 8)] {
        for gy in 1..=6 { chunk.set_block(cx + px, base + gy, cz + pz, 55); }
    }
    // Ceiling beams across the top.
    for gx in 0..9 { chunk.set_block(cx + gx, base + 6, cz, 55); chunk.set_block(cx + gx, base + 6, cz + 8, 55); }
    for gz in 0..9 { chunk.set_block(cx, base + 6, cz + gz, 55); chunk.set_block(cx + 8, base + 6, cz + gz, 55); }
    // Glowstone lamps hung under the beams + loot chests along the walkway.
    chunk.set_block(cx + 4, base + 6, cz + 4, 77);
    chunk.set_block(cx + 2, base + 5, cz + 2, 77);
    chunk.set_block(cx + 6, base + 5, cz + 6, 77);
    chunk.set_block(cx + 1, base + 1, cz + 4, 83);
    chunk.set_block(cx + 7, base + 1, cz + 4, 83);
    let _ = r;
}

// An End City: a tall purpur tower on an end-stone island, capped with a loot chest. Simple and
// deterministic (single chunk), rising from the island surface.
fn build_end_city(chunk: &mut Chunk, cx: usize, cz: usize, base: usize, r: u64) {
    if cx + 4 >= 16 || cz + 4 >= 16 || base + 18 >= CHUNK_HEIGHT { return; }
    let height = 12 + (r % 6) as usize;
    // Hollow purpur tower shell (5x5) with a room every few floors.
    for gy in 0..height { for gx in 0..5 { for gz in 0..5 {
        let edge = gx == 0 || gx == 4 || gz == 0 || gz == 4;
        let floor = gy % 5 == 0;
        chunk.set_block(cx + gx, base + gy, cz + gz, if edge || floor { 89 } else { 0 });
    }}}
    // Battlement crown of end-stone bricks.
    for gx in 0..5 { for gz in 0..5 {
        if (gx == 0 || gx == 4 || gz == 0 || gz == 4) && (gx + gz) % 2 == 0 {
            chunk.set_block(cx + gx, base + height, cz + gz, 56);
        }
    }}
    // Glowstone lantern + loot chest in the top room.
    let top = base + height - 4;
    chunk.set_block(cx + 2, base + height - 1, cz + 2, 77);
    chunk.set_block(cx + 1, top, cz + 1, 83);
    chunk.set_block(cx + 3, top, cz + 3, 83);
}

pub struct TerrainGen {
    perlin_height: Perlin,
    perlin_detail: Perlin,
    perlin_cave: Perlin,
    perlin_biome: Perlin,
    perlin_ore: Perlin,
    seed: u32,
    pub dim: u8, // 0 overworld, 1 nether, 2 end
}

// Ore/stone-variant veins, richest first so a rare vein wins where two overlap.
// (block id, noise offset, noise scale, threshold, min y, max y)
type Vein = (u8, f64, f64, f64, i32, i32);
const OVERWORLD_VEINS: [Vein; 8] = [
    (22, 947.0, 0.115, 0.80, 62, 118), // emerald — mountains only, the rarest surface find
    (20, 823.0, 0.105, 0.78,  5,  20), // diamond — deepest
    (21, 601.0, 0.100, 0.72,  5,  30), // redstone
    (19, 137.0, 0.090, 0.68,  5,  74), // iron
    (18,   7.0, 0.080, 0.62,  5, 112), // coal — large shallow seams
    (14, 311.0, 0.060, 0.70,  5, 100), // gravel pockets
    (16, 419.0, 0.055, 0.66,  5,  96), // diorite blobs
    (75, 733.0, 0.050, 0.66,  5,  34), // tuff blobs, deep
];
// Below this depth plain stone becomes deepslate (with a noisy transition band above it).
const DEEPSLATE_Y: i32 = 16;

impl TerrainGen {
    pub fn new(seed: u32) -> Self { Self::new_dim(seed, 0) }
    pub fn new_dim(seed: u32, dim: u8) -> Self {
        Self {
            perlin_height: Perlin::new(seed),
            perlin_detail: Perlin::new(seed.wrapping_add(101)),
            perlin_cave: Perlin::new(seed.wrapping_add(202)),
            perlin_biome: Perlin::new(seed.wrapping_add(303)),
            perlin_ore: Perlin::new(seed.wrapping_add(404)),
            seed,
            dim,
        }
    }
    // Per-biome grass tint (multiplied over the grayscale grass_top / grass_side textures). Uses the
    // same low-frequency biome noise the terrain height uses, plus a humidity sample, so the ground
    // colour shifts smoothly between cool-lush, warm-lush and dry regions.
    pub fn grass_tint(&self, wx: f64, wz: f64) -> [f32; 3] {
        use Biome::*;
        match self.biome_at(wx, wz, self.height_at(wx, wz)) {
            Savanna => [0.74, 0.68, 0.32],                              // dry gold-green
            Jungle | SparseJungle | MangroveSwamp => [0.30, 0.66, 0.18], // vivid tropical
            Swamp => [0.42, 0.48, 0.27],                                // murky
            DarkForest => [0.26, 0.48, 0.20],                           // deep shade
            Desert | Badlands => [0.66, 0.64, 0.34],
            Taiga | SnowyTaiga | SnowyPlains | SnowySlopes | Grove | FrozenPeaks => [0.50, 0.62, 0.48],
            _ => {
                // Temperate default: smooth temperature x humidity blend.
                let temp = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
                let humid = (self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
                let lerp3 = |a: [f32; 3], b: [f32; 3], t: f32| [a[0] + (b[0]-a[0])*t, a[1] + (b[1]-a[1])*t, a[2] + (b[2]-a[2])*t];
                let cold = [0.46, 0.62, 0.42];
                let lush = [0.40, 0.68, 0.28];
                let dry  = [0.74, 0.72, 0.36];
                let warm = lerp3(dry, lush, humid);
                lerp3(cold, warm, temp)
            }
        }
    }
    // Classify a column into a biome from temperature/humidity/variant noise plus elevation. Mountain
    // bands take over at height; lowlands split by a Whittaker-style temp x humidity grid, with a
    // low-frequency "variant" noise selecting rarer sub-biomes (ice spikes, flower forest, etc.).
    pub fn biome_at(&self, wx: f64, wz: f64, h: i32) -> Biome {
        let temp = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        let humid = (self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        let variant = (self.perlin_detail.get([wx * 0.004 + 53.0, wz * 0.004 - 17.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        // High mountains.
        if h > 118 {
            if temp < 0.35 { return Biome::FrozenPeaks; }
            if temp > 0.66 { return Biome::StonyPeaks; }
            return Biome::JaggedPeaks;
        }
        if h > 98 {
            if temp < 0.34 { return Biome::SnowySlopes; }
            if humid > 0.5 { return Biome::Grove; }
            return Biome::WindsweptHills;
        }
        // Lowlands by temperature band.
        if temp < 0.28 {
            if variant > 0.86 { return Biome::IceSpikes; }
            if humid > 0.55 { return Biome::SnowyTaiga; }
            return Biome::SnowyPlains;
        } else if temp > 0.72 {
            if humid < 0.28 { return if variant > 0.72 { Biome::Badlands } else { Biome::Desert }; }
            if humid > 0.60 { return if variant > 0.5 { Biome::Jungle } else { Biome::SparseJungle }; }
            return Biome::Savanna;
        } else {
            if humid < 0.30 { return Biome::Savanna; }
            if humid > 0.78 { return if variant > 0.7 { Biome::MushroomFields } else if temp > 0.5 { Biome::MangroveSwamp } else { Biome::Swamp }; }
            if humid > 0.55 { return if variant > 0.78 { Biome::DarkForest } else if variant < 0.25 { Biome::Taiga } else { Biome::Forest }; }
            // Plains band with variants.
            if variant > 0.88 { return Biome::SunflowerPlains; }
            if variant > 0.72 { return Biome::FlowerForest; }
            if variant < 0.14 { return Biome::BirchForest; }
            if variant < 0.30 { return Biome::Meadow; }
            return Biome::Plains;
        }
    }
    // Surface + subsurface block for a biome at surface height `h` (before the sea/beach override).
    fn surface_blocks(&self, biome: Biome, h: usize, wx: f64, wz: f64) -> (u8, u8) {
        use Biome::*;
        match biome {
            Desert => (6, 38),                       // sand / sandstone
            Badlands => (36, 37),                    // red sand / red sandstone
            SnowyPlains | SnowySlopes | Grove => (11, 2), // snow / dirt
            SnowyTaiga => (11, 2),
            IceSpikes => (11, 42),                   // snow / packed ice
            FrozenPeaks => (42, 42),                 // packed ice
            JaggedPeaks => if h > 124 { (11, 1) } else { (1, 1) }, // snow cap / bare rock
            StonyPeaks => (1, 1),
            WindsweptHills => (3, 1),                // grass over stone
            MushroomFields => (41, 2),               // mycelium / dirt
            Swamp | MangroveSwamp => (45, 45),       // mud
            Taiga => {
                // Patches of podzol/coarse dirt among the grass.
                let n = self.perlin_detail.get([wx * 0.09, wz * 0.09]);
                if n > 0.35 { (39, 2) } else if n < -0.5 { (40, 40) } else { (3, 2) }
            }
            _ => (3, 2),                             // grass / dirt (plains, forests, savanna, jungle)
        }
    }
    // Plant a simple tree: a trunk of `log` topped with a `leaf` canopy. Shared by all wooded biomes.
    fn plant_tree(&self, chunk: &mut Chunk, dx: usize, dz: usize, h: usize, log: u8, leaf: u8, trunk_h: usize) {
        for ty in 1..=trunk_h {
            let y = h + ty;
            if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, log); }
        }
        let top_y = h + trunk_h;
        for ly in 0..2 {
            let y = top_y + ly;
            if y >= CHUNK_HEIGHT { continue; }
            for lx in -2i32..=2 { for lz in -2i32..=2 {
                if lx.abs() == 2 && lz.abs() == 2 { continue; }
                let x = dx as i32 + lx;
                let z = dz as i32 + lz;
                if x < 0 || x >= 16 || z < 0 || z >= 16 { continue; }
                if ly == 0 && lx == 0 && lz == 0 { continue; }
                if chunk.get_block(x as usize, y, z as usize) == 0 {
                    chunk.set_block(x as usize, y, z as usize, leaf);
                }
            }}
        }
        let y = top_y + 2;
        if y < CHUNK_HEIGHT && chunk.get_block(dx, y, dz) == 0 { chunk.set_block(dx, y, dz, leaf); }
    }
    pub fn height_at(&self, wx: f64, wz: f64) -> i32 {
        let h1 = self.perlin_height.get([wx * 0.007, wz * 0.007]) * 28.0;
        let h2 = self.perlin_height.get([wx * 0.015, wz * 0.015]) * 10.0;
        let detail = self.perlin_detail.get([wx * 0.05, wz * 0.05]) * 3.0;
        let biome = self.perlin_biome.get([wx * 0.002, wz * 0.002]);
        let hill_factor = (biome * 0.5 + 0.5).powf(1.8) * 34.0;
        let base = 62.0 + h1 + h2 + detail + hill_factor;
        base.clamp(12.0, 128.0) as i32
    }
    pub fn cave_at(&self, x: f64, y: f64, z: f64) -> bool {
        if y < 6.0 || y > 112.0 { return false; }
        // Spaghetti tunnels: two independent noise fields both near zero carve winding 1D tubes
        // (Perlin-worm approximation), giving long connected passages instead of isolated blobs.
        let n1 = self.perlin_cave.get([x * 0.021 + 11.0, y * 0.030, z * 0.021]).abs();
        let n2 = self.perlin_cave.get([x * 0.021 - 7.0, y * 0.030 + 50.0, z * 0.021]).abs();
        let width = 0.055 + 0.03 * (self.perlin_detail.get([x * 0.01, z * 0.01]) * 0.5 + 0.5);
        if n1 < width && n2 < width { return true; }
        // Cheese caverns: larger open rooms, concentrated in the lower half of the world.
        let cheese = self.perlin_cave.get([x * 0.045, y * 0.055, z * 0.045]);
        let depth_bias = ((60.0 - y) / 55.0).clamp(0.0, 1.0) as f64; // more/bigger caverns deeper
        cheese > (0.70 - 0.16 * depth_bias)
    }
    // The block that fills a solid underground cell: an ore/variant vein if one passes here,
    // otherwise deepslate at depth or plain stone.
    fn stone_at(&self, veins: &[Vein], wx: f64, y: i32, wz: f64, base: u8) -> u8 {
        for &(id, off, scale, thresh, y0, y1) in veins {
            if y < y0 || y > y1 { continue; }
            if self.perlin_ore.get([wx * scale + off, y as f64 * scale, wz * scale - off]) > thresh { return id; }
        }
        base
    }
    // Deepslate replaces stone below DEEPSLATE_Y, with a noisy band so the boundary isn't a flat line.
    fn deep_base(&self, wx: f64, y: i32, wz: f64) -> u8 {
        let jitter = self.perlin_detail.get([wx * 0.06, wz * 0.06]) * 5.0;
        if (y as f64) < DEEPSLATE_Y as f64 + jitter { 57 } else { 1 }
    }
    // Underground biome for cave decoration, from depth + humidity.
    fn cave_kind(&self, wx: f64, wz: f64, y: i32) -> u8 {
        if y < 20 { return 0; } // deep dark
        let humid = self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]);
        if humid > 0.15 { 1 } else { 2 } // 1 = lush, 2 = dripstone
    }
    // Ocean temperature class: 0 frozen, 1 cold, 2 lukewarm, 3 warm.
    fn ocean_kind(&self, wx: f64, wz: f64) -> u8 {
        let t = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        if t < 0.28 { 0 } else if t < 0.5 { 1 } else if t < 0.72 { 2 } else { 3 }
    }
    pub fn fill_chunk(&self, chunk: &mut Chunk) {
        match self.dim {
            1 => self.fill_nether(chunk),
            2 => self.fill_end(chunk),
            _ => self.fill_overworld(chunk),
        }
        chunk.generated = true;
        chunk.mesh_dirty = true;
    }

    // Nether: netherrack shell riddled with large 3D-noise caverns, a lava sea in the low y range,
    // glowstone clusters on ceilings, and magma patches. No sky (solid roof + floor).
    fn fill_nether(&self, chunk: &mut Chunk) {
        let (ox, oz) = chunk.pos.world_origin();
        const ROOF: i32 = 120;
        const LAVA: i32 = 31;
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            for y in 0..=ROOF {
                let yy = y as usize;
                // Solid bedrock cap at floor/roof.
                if y <= 2 || y >= ROOF - 2 { chunk.set_block(dx, yy, dz, 13); continue; }
                // Carve big caverns with 3D noise; solid netherrack elsewhere.
                let n = self.perlin_cave.get([wx * 0.035, y as f64 * 0.045, wz * 0.035])
                    + 0.5 * self.perlin_detail.get([wx * 0.07, y as f64 * 0.09, wz * 0.07]);
                if n > 0.15 {
                    if y <= LAVA { chunk.set_block(dx, yy, dz, 84); } // lava sea in the open low area
                } else {
                    // Magma near the lava line, netherrack elsewhere.
                    let id = if y <= LAVA + 1 && n > 0.02 { 76 } else { 32 };
                    chunk.set_block(dx, yy, dz, id);
                }
            }
            // Glowstone clusters hanging from the ceiling.
            let g = self.perlin_biome.get([wx * 0.08, wz * 0.08]);
            if g > 0.72 {
                for k in 0..3 { let y = (ROOF - 3 - k) as usize; if chunk.get_block(dx, y, dz) == 32 { chunk.set_block(dx, y, dz, 77); } }
            }
        }}
        // Nether-brick fortress section (~1/60 chunks), sitting on a platform above the lava sea.
        let fh = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x0F02417E);
        if fh % 60 == 0 {
            let base = LAVA as usize + 6 + (fh % 20) as usize;
            build_nether_fortress(chunk, 4, 4, base, fh);
        }
    }

    // End: floating end-stone islands in a void (no floor). A guaranteed central island near origin.
    fn fill_end(&self, chunk: &mut Chunk) {
        let (ox, oz) = chunk.pos.world_origin();
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            let dist = (wx * wx + wz * wz).sqrt();
            for y in 40..90 {
                // Island mass: 3D noise thresholded, biased to a slab around y=64; central island guaranteed.
                let slab = 1.0 - ((y as f64 - 64.0).abs() / 22.0);
                let n = self.perlin_cave.get([wx * 0.02, y as f64 * 0.03, wz * 0.02]) + slab * 0.6;
                let central = dist < 34.0 && (y as f64 - 62.0).abs() < 6.0;
                if n > 0.55 || central { chunk.set_block(dx, y as usize, dz, 85); }
            }
        }}
        // Obsidian pillars on the central island.
        if ox.abs() < 16 && oz.abs() < 16 {
            for &(px, pz) in &[(2i32, 2i32), (12, 3), (4, 12), (11, 11)] {
                if px >= 0 && px < 16 && pz >= 0 && pz < 16 {
                    for y in 63..70 { chunk.set_block(px as usize, y, pz as usize, 78); }
                }
            }
        }
        // End City (~1/40 outer chunks): a purpur tower rising from the island top, away from origin
        // so it never clashes with the dragon-fight central island.
        let dist0 = ((ox + 8) as f64).hypot((oz + 8) as f64);
        let ch = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x0E7DC17E);
        if dist0 > 80.0 && ch % 40 == 0 {
            // Find the island surface at local (6,6): topmost end-stone in the column.
            let mut top = None;
            for y in (40..90).rev() { if chunk.get_block(6, y, 6) == 85 { top = Some(y); break; } }
            if let Some(sy) = top { build_end_city(chunk, 6, 6, sy + 1, ch); }
        }
    }

    fn fill_overworld(&self, chunk: &mut Chunk) {
        let (ox, oz) = chunk.pos.world_origin();
        let mut heightmap = [[0i32; CHUNK_SIZE]; CHUNK_SIZE];
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            heightmap[dz][dx] = self.height_at((ox + dx as i32) as f64, (oz + dz as i32) as f64);
        }}
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            let h = (heightmap[dz][dx] as usize).min(CHUNK_HEIGHT - 2);
            let biome = self.biome_at(wx, wz, heightmap[dz][dx]);
            for y in 0..5 {
                let id = if y == 0 { 13 } else if y < 3 && self.perlin_detail.get([wx * 0.1, y as f64 * 0.2, wz *0.1]) > 0.2 { 0 } else { 1 };
                if id != 0 { chunk.set_block(dx, y, dz, id); }
            }
            let stone_top = h.saturating_sub(4);
            for y in 5..stone_top {
                if !self.cave_at(wx, y as f64, wz) {
                    let base = self.deep_base(wx, y as i32, wz);
                    chunk.set_block(dx, y, dz, self.stone_at(&OVERWORLD_VEINS, wx, y as i32, wz, base));
                }
            }
            let (surface_top, sub) = self.surface_blocks(biome, h, wx, wz);
            // Subsurface band just under the surface.
            for y in stone_top..h.saturating_sub(1) {
                if !self.cave_at(wx, y as f64, wz) {
                    chunk.set_block(dx, y, dz, sub);
                }
            }
            if h > 0 && !self.cave_at(wx, h as f64, wz) {
                // Beaches: sand where the ground meets the sea (snowy biomes get snowy beaches).
                let surface: u8 = if h < 62 {
                    match biome { Biome::Badlands => 36, Biome::SnowyPlains | Biome::SnowyTaiga | Biome::IceSpikes | Biome::FrozenPeaks | Biome::SnowySlopes => 11, _ => 6 }
                } else { surface_top };
                chunk.set_block(dx, h, dz, surface);
                if h > 1 && !self.cave_at(wx, (h-1) as f64, wz) { chunk.set_block(dx, h - 1, dz, sub); }
            }
        }}
        // Vegetation / surface decoration, per biome.
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let h = heightmap[dz][dx] as usize;
            if h < 63 || h > 122 { continue; }
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            let surf = chunk.get_block(dx, h, dz);
            let biome = self.biome_at(wx, wz, heightmap[dz][dx]);
            let mut hasher = DefaultHasher::new();
            (ox+dx as i32, oz+dz as i32, self.seed).hash(&mut hasher);
            let r = hasher.finish() % 1000;
            let alt = (hasher.finish() >> 8) % 2 == 0;
            let th = 4 + (hasher.finish() % 4) as usize;
            use Biome::*;
            let grassy = surf == 3;
            match biome {
                Desert => { if surf == 6 && r < 4 { let y = h + 1; if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, 8); } } }
                Badlands => { /* barren red terrain */ }
                SnowyPlains => { if surf == 11 && r < 4 { self.plant_tree(chunk, dx, dz, h, 29, 31, th); } }
                SnowyTaiga => { if surf == 11 && r < 40 { self.plant_tree(chunk, dx, dz, h, 29, 31, th + 1); } }
                IceSpikes => {
                    // Tall spikes of packed ice.
                    if surf == 11 && r < 12 {
                        let sh = 4 + (hasher.finish() % 8) as usize;
                        for ty in 1..=sh { let y = h + ty; if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, 42); } }
                    }
                }
                Taiga => { if (surf == 3 || surf == 39 || surf == 40) && r < 55 { self.plant_tree(chunk, dx, dz, h, 29, 31, th + 1); } }
                Forest => { if grassy && r < 65 { if alt { self.plant_tree(chunk, dx, dz, h, 26, 28, th); } else { self.plant_tree(chunk, dx, dz, h, 4, 5, th); } } }
                BirchForest => { if grassy && r < 70 { self.plant_tree(chunk, dx, dz, h, 26, 28, th + 1); } }
                DarkForest => { if grassy && r < 90 { self.plant_tree(chunk, dx, dz, h, 47, 48, th); } } // dense dark oak
                FlowerForest => { if grassy && r < 18 { self.plant_tree(chunk, dx, dz, h, 4, 5, th); } }
                Savanna => { if grassy && r < 12 { self.plant_tree(chunk, dx, dz, h, 50, 5, th.min(5)); } } // acacia (oak leaves)
                Jungle => { if grassy && r < 85 { self.plant_tree(chunk, dx, dz, h, 51, 5, th + 3); } }     // tall jungle
                SparseJungle => { if grassy && r < 22 { self.plant_tree(chunk, dx, dz, h, 51, 5, th + 2); } }
                Swamp => { if grassy && r < 8 { self.plant_tree(chunk, dx, dz, h, 4, 5, th); } }
                MangroveSwamp => { if surf == 45 && r < 20 { self.plant_tree(chunk, dx, dz, h, 51, 5, th); } }
                MushroomFields => { if surf == 41 && r < 3 { let y = h + 1; if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, 5); } } }
                Meadow => { if grassy && r == 3 { self.plant_tree(chunk, dx, dz, h, 4, 5, th); } }
                Grove => { if surf == 11 && r < 30 { self.plant_tree(chunk, dx, dz, h, 29, 31, th); } }
                WindsweptHills => { if grassy && r < 6 { self.plant_tree(chunk, dx, dz, h, 29, 31, th); } }
                SnowySlopes | JaggedPeaks | FrozenPeaks | StonyPeaks => { /* bare peaks */ }
                Plains | SunflowerPlains => {
                    if grassy {
                        if r == 3 { let y = h + 1; if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, 8); } }
                        else if r < 10 { self.plant_tree(chunk, dx, dz, h, 4, 5, th); }
                    }
                }
            }
        }}
        // --- Cave decoration: dress cave floors/ceilings by underground biome. ---
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            let top = (heightmap[dz][dx] as usize).min(CHUNK_HEIGHT - 1);
            let mut y = 7usize;
            while y + 1 < top {
                let here = chunk.get_block(dx, y, dz);
                let above = chunk.get_block(dx, y + 1, dz);
                if matches!(here, 1 | 57) && above == 0 {
                    // Cave floor.
                    let kind = self.cave_kind(wx, wz, y as i32);
                    let mut hasher = DefaultHasher::new();
                    (ox + dx as i32, y as i32, oz + dz as i32, self.seed ^ 0x5EED).hash(&mut hasher);
                    let r = hasher.finish() % 100;
                    match kind {
                        0 => { if r < 22 { chunk.set_block(dx, y, dz, 72); } if r == 5 { chunk.set_block(dx, y + 1, dz, 77); } } // sculk / rare glowstone
                        1 => { if r < 40 { chunk.set_block(dx, y, dz, 71); } if r < 6 { chunk.set_block(dx, y + 1, dz, 80); } if r == 7 { chunk.set_block(dx, y + 1, dz, 77); } } // moss / azalea / glow
                        _ => { if r < 25 { chunk.set_block(dx, y, dz, 70); } } // dripstone
                    }
                } else if here == 0 && matches!(above, 1 | 57) {
                    // Cave ceiling: hanging dripstone in dry caves.
                    if self.cave_kind(wx, wz, y as i32) == 2 {
                        let mut hasher = DefaultHasher::new();
                        (ox + dx as i32, y as i32, oz + dz as i32, self.seed ^ 0xBEEF).hash(&mut hasher);
                        if hasher.finish() % 100 < 12 { chunk.set_block(dx, y, dz, 70); }
                    }
                }
                y += 1;
            }
        }}
        // --- Ocean floor: coral reefs / kelp by water temperature (survives the later water fill). ---
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let h = heightmap[dz][dx] as usize;
            if h + 1 >= Self::SEA_LEVEL { continue; }
            let floor = chunk.get_block(dx, h, dz);
            if floor != 6 && floor != 2 && floor != 1 { continue; }
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            let kind = self.ocean_kind(wx, wz);
            let mut hasher = DefaultHasher::new();
            (ox + dx as i32, oz + dz as i32, self.seed ^ 0x0CEA).hash(&mut hasher);
            let r = hasher.finish() % 1000;
            let y = h + 1;
            match kind {
                3 => { // warm: coral reef
                    if r < 90 {
                        let coral = 61 + ((hasher.finish() >> 8) % 5) as u8;
                        chunk.set_block(dx, y, dz, coral);
                        if r < 20 && y + 1 < Self::SEA_LEVEL { chunk.set_block(dx, y + 1, dz, coral); }
                    } else if r < 96 { chunk.set_block(dx, y, dz, 67); } // sea lantern
                }
                1 | 2 => { // kelp forests
                    if r < 120 {
                        let kh = 2 + (hasher.finish() % 5) as usize;
                        for k in 0..kh { let yy = y + k; if yy < Self::SEA_LEVEL { chunk.set_block(dx, yy, dz, 66); } }
                    }
                }
                _ => {}
            }
        }}
        // --- Small single-chunk surface structures. ---
        {
            let ax = ox + 8; let az = oz + 8;
            let sh_top = self.height_at(ax as f64, az as f64) as usize;
            let biome = self.biome_at(ax as f64, az as f64, sh_top as i32);
            let mut hasher = DefaultHasher::new();
            (chunk.pos.0, chunk.pos.1, self.seed ^ 0x57AC).hash(&mut hasher);
            let sr = hasher.finish() % 100;
            let (bx, bz) = (8usize, 8usize);
            match biome {
                Biome::Taiga | Biome::SnowyTaiga | Biome::Grove | Biome::WindsweptHills if sr < 8 => {
                    // Mossy boulder.
                    let cy = sh_top + 1;
                    for ddx in -1i32..=1 { for ddy in 0i32..=2 { for ddz in -1i32..=1 {
                        if ddx.abs() + ddy + ddz.abs() > 3 { continue; }
                        let (x, y, z) = (bx as i32 + ddx, cy as i32 + ddy, bz as i32 + ddz);
                        if x < 0 || x >= 16 || z < 0 || z >= 16 || y < 0 || y as usize >= CHUNK_HEIGHT { continue; }
                        chunk.set_block(x as usize, y as usize, z as usize, 15);
                    }}}
                }
                Biome::Desert if sr < 10 => {
                    // Desert well: 3x3 sandstone rim with a water centre.
                    let cy = sh_top;
                    for ddx in -1i32..=1 { for ddz in -1i32..=1 {
                        let (x, z) = (bx as i32 + ddx, bz as i32 + ddz);
                        if x < 0 || x >= 16 || z < 0 || z >= 16 { continue; }
                        let id = if ddx == 0 && ddz == 0 { 12 } else { 38 };
                        chunk.set_block(x as usize, cy, z as usize, id);
                    }}
                }
                Biome::Plains | Biome::Meadow | Biome::Forest | Biome::Savanna | Biome::FlowerForest if sr < 6 => {
                    // Shrine: a small cobble platform topped with a Warding Stone (natural checkpoint).
                    let cy = sh_top;
                    for ddx in -1i32..=1 { for ddz in -1i32..=1 {
                        let (x, z) = (bx as i32 + ddx, bz as i32 + ddz);
                        if x < 0 || x >= 16 || z < 0 || z >= 16 { continue; }
                        chunk.set_block(x as usize, cy, z as usize, 8); // cobble platform
                    }}
                    // Corner pillars + warding stone centre.
                    for &(px, pz) in &[(-1i32, -1i32), (1, -1), (-1, 1), (1, 1)] {
                        let (x, z) = (bx as i32 + px, bz as i32 + pz);
                        if x >= 0 && x < 16 && z >= 0 && z < 16 && cy + 1 < CHUNK_HEIGHT { chunk.set_block(x as usize, cy + 1, z as usize, 1); }
                    }
                    if cy + 1 < CHUNK_HEIGHT { chunk.set_block(bx, cy + 1, bz, 81); } // warding stone
                }
                _ => {}
            }
        }
        // Multi-chunk villages + mineshafts (deterministic across their region grids).
        self.place_villages(chunk);
        self.place_mineshafts(chunk);
        // Ruined portal (surface) + dungeon (underground) — independent rare rolls.
        let ph = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x0B51D1A4);
        if ph % 130 == 0 {
            let (bx, bz) = (ox + 6, oz + 8);
            let sh = self.height_at(bx as f64, bz as f64);
            if sh >= Self::SEA_LEVEL as i32 { build_ruined_portal(chunk, 6, 8, sh as usize); }
        }
        let dh = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x0D0465E0);
        if dh % 80 == 0 {
            let (cxw, czw) = (ox + 5, oz + 5);
            let surf = self.height_at(cxw as f64, czw as f64);
            let y = 16 + (dh % 22) as i32;
            if y + 6 < surf { build_dungeon(chunk, 5, 5, y as usize, dh); }
        }
        // Desert temple (surface, desert/badlands).
        let te = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x7E0917E5);
        if te % 150 == 0 {
            let (bx, bz) = (ox + 8, oz + 8);
            let sh = self.height_at(bx as f64, bz as f64);
            if sh >= Self::SEA_LEVEL as i32 && matches!(self.biome_at(bx as f64, bz as f64, sh), Biome::Desert | Biome::Badlands) {
                build_temple(chunk, 8, 8, sh as usize);
            }
        }
        // Stronghold (deep underground).
        let st = hash3(chunk.pos.0, chunk.pos.1, self.seed ^ 0x0517A011);
        if st % 240 == 0 {
            let surf = self.height_at((ox + 7) as f64, (oz + 7) as f64);
            let y = 10 + (st % 12) as i32;
            if y + 6 < surf { build_stronghold(chunk, 4, 4, y as usize); }
        }
        chunk.generated = true;
        chunk.mesh_dirty = true;
    }

    // Deterministic building list for a village centred at world (cx, cz): (bx, bz, w, d, kind).
    fn village_layout(&self, cx: i32, cz: i32) -> Vec<(i32, i32, i32, i32, u8)> {
        let mut list = vec![(cx - 1, cz - 1, 3, 3, B_WELL)];
        let plots = [(-22, -18), (6, -22), (20, -6), (-20, 8), (8, 18), (-8, -20), (22, 14), (-24, -4), (4, 8)];
        for (i, (dx, dz)) in plots.iter().enumerate() {
            let hh = hash3(cx + dx, cz + dz, self.seed ^ 0x8171A6E);
            match i {
                0 => list.push((cx + dx, cz + dz, 7, 7, B_BIGHOUSE)),
                1 => list.push((cx + dx, cz + dz, 6, 6, B_BLACKSMITH)),
                4 => list.push((cx + dx, cz + dz, 5, 5, B_CHURCH)),
                3 | 6 => list.push((cx + dx, cz + dz, 7, 5, B_FARM)),
                _ => {
                    if hh % 10 < 7 {
                        let (w, d) = (5 + (hh % 3) as i32, 5 + ((hh / 3) % 3) as i32);
                        list.push((cx + dx, cz + dz, w, d, B_HOUSE));
                    } else if hh % 10 == 8 {
                        list.push((cx + dx, cz + dz, 1, 1, B_LAMP));
                    }
                }
            }
        }
        list
    }

    // Render any village buildings/roads that overlap this chunk (checking the 3x3 nearby regions).
    fn place_villages(&self, chunk: &mut Chunk) {
        let (ox, oz) = chunk.pos.world_origin();
        let crx = chunk.pos.0.div_euclid(VILLAGE_REGION);
        let crz = chunk.pos.1.div_euclid(VILLAGE_REGION);
        for rrx in (crx - 1)..=(crx + 1) { for rrz in (crz - 1)..=(crz + 1) {
            let hv = hash3(rrx, rrz, self.seed ^ 0x5A11A6E);
            if hv % 100 >= 30 { continue; } // 30% of regions hold a village
            let ccx = rrx * VILLAGE_REGION + (hv % VILLAGE_REGION as u64) as i32;
            let ccz = rrz * VILLAGE_REGION + ((hv / 7) % VILLAGE_REGION as u64) as i32;
            let (cx, cz) = (ccx * 16 + 8, ccz * 16 + 8);
            let base_i = self.height_at(cx as f64, cz as f64);
            if base_i < Self::SEA_LEVEL as i32 + 1 { continue; }
            if !matches!(self.biome_at(cx as f64, cz as f64, base_i), Biome::Plains | Biome::Meadow | Biome::Savanna) { continue; }
            let base = base_i as usize;
            if base + 7 >= CHUNK_HEIGHT { continue; }
            // Crossroads (packed-dirt), bounded to the village footprint.
            for wz in oz..oz + 16 {
                if (wz - cz).abs() > 28 { continue; }
                for w in -1..=1 { let wx = cx + w; if in_chunk(wx, wz, ox, oz) {
                    level_column(chunk, (wx - ox) as usize, (wz - oz) as usize, base, 3, 60);
                }}
            }
            for wx in ox..ox + 16 {
                if (wx - cx).abs() > 28 { continue; }
                for w in -1..=1 { let wz = cz + w; if in_chunk(wx, wz, ox, oz) {
                    level_column(chunk, (wx - ox) as usize, (wz - oz) as usize, base, 3, 60);
                }}
            }
            for (bx, bz, w, d, kind) in self.village_layout(cx, cz) {
                render_building(chunk, ox, oz, bx, bz, w, d, base, kind);
            }
        }}
    }

    // Render mineshaft corridors (deterministic region grid, ~7 chunks) overlapping this chunk.
    fn place_mineshafts(&self, chunk: &mut Chunk) {
        const MINE_REGION: i32 = 7;
        let (ox, oz) = chunk.pos.world_origin();
        let crx = chunk.pos.0.div_euclid(MINE_REGION);
        let crz = chunk.pos.1.div_euclid(MINE_REGION);
        for rrx in (crx - 1)..=(crx + 1) { for rrz in (crz - 1)..=(crz + 1) {
            let hv = hash3(rrx, rrz, self.seed ^ 0x319E5417);
            if hv % 100 >= 25 { continue; } // 25% of regions
            let ccx = rrx * MINE_REGION + (hv % MINE_REGION as u64) as i32;
            let ccz = rrz * MINE_REGION + ((hv / 5) % MINE_REGION as u64) as i32;
            let (cx, cz) = (ccx * 16 + 8, ccz * 16 + 8);
            let fy = 16 + (hv % 14) as usize; // depth 16..29
            let surf = self.height_at(cx as f64, cz as f64);
            if (surf as usize) < fy + 6 { continue; } // must be underground
            // Central junction room (5x5).
            for gx in -2..=2 { for gz in -2..=2 {
                let (wx, wz) = (cx + gx, cz + gz);
                if !in_chunk(wx, wz, ox, oz) { continue; }
                let (lx, lz) = ((wx - ox) as usize, (wz - oz) as usize);
                chunk.set_block(lx, fy, lz, 10);
                for dy in 1..=2 { if fy + dy < CHUNK_HEIGHT { chunk.set_block(lx, fy + dy, lz, 0); } }
            }}
            for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
                carve_corridor(chunk, ox, oz, cx, cz, dx, dz, 44, fy);
            }
            // A bit of loot + light in the junction.
            if in_chunk(cx, cz, ox, oz) {
                let (lx, lz) = ((cx - ox) as usize, (cz - oz) as usize);
                if fy + 3 < CHUNK_HEIGHT { chunk.set_block(lx, fy + 3, lz, 77); } // glowstone
            }
            if in_chunk(cx + 2, cz + 2, ox, oz) {
                chunk.set_block((cx + 2 - ox) as usize, fy + 1, (cz + 2 - oz) as usize, 83); // loot chest
            }
        }}
    }

    pub const SEA_LEVEL: usize = 62;

    // Fill each column's air above the *intended terrain surface* up to sea level with water.
    // Idempotent, and applied to loaded chunks too so worlds saved before water existed still get
    // oceans/lakes. Using height_at (not the scanned solid top) means cave shafts carved into high
    // land are NOT flooded — only genuine lowland columns (surface below sea) hold water.
    pub fn ensure_water(&self, chunk: &mut Chunk) {
        if self.dim != 0 { return; }
        let (ox, oz) = chunk.pos.world_origin();
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let wx = (ox + dx as i32) as f64;
            let wz = (oz + dz as i32) as f64;
            let h = self.height_at(wx, wz) as usize;
            if h >= Self::SEA_LEVEL { continue; }
            // Frozen oceans get a solid ice cap at the surface instead of open water.
            let frozen = self.ocean_kind(wx, wz) == 0;
            for y in (h + 1)..=Self::SEA_LEVEL {
                if y < CHUNK_HEIGHT && chunk.get_block(dx, y, dz) == 0 {
                    let id = if frozen && y == Self::SEA_LEVEL { 43 } else { 12 };
                    chunk.set_block(dx, y, dz, id);
                }
            }
        }}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Sanity-check ore rarity: sample the vein fields over a large volume and assert each ore lands
    // in a plausible band. Guards against a threshold tweak silently flooding or emptying the world.
    #[test]
    fn ore_density_is_plausible() {
        let gen = TerrainGen::new(12345);
        let mut counts = std::collections::HashMap::new();
        let mut total = 0u32;
        for wx in (-256..256).step_by(2) {
            for wz in (-256..256).step_by(2) {
                for y in (5..120).step_by(3) {
                    let base = gen.deep_base(wx as f64, y, wz as f64);
                    let id = gen.stone_at(&OVERWORLD_VEINS, wx as f64, y, wz as f64, base);
                    total += 1;
                    *counts.entry(id).or_insert(0u32) += 1;
                }
            }
        }
        let pct = |id: u8| counts.get(&id).copied().unwrap_or(0) as f64 * 100.0 / total as f64;
        for (id, name, lo, hi) in [
            (18u8, "coal", 0.5, 4.0),
            (19u8, "iron", 0.2, 2.5),
            (21u8, "redstone", 0.02, 0.6),
            (20u8, "diamond", 0.005, 0.3),
            (22u8, "emerald", 0.002, 0.3),
        ] {
            let p = pct(id);
            println!("{name}: {p:.4}%");
            assert!(p >= lo && p <= hi, "{name} at {p:.4}% of stone, expected {lo}..{hi}%");
        }
        assert!(pct(57) > 5.0, "deepslate should fill the lower world, got {:.2}%", pct(57));
    }
}
