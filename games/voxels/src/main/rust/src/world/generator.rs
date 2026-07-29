use super::chunk::{Chunk, CHUNK_SIZE, CHUNK_HEIGHT};
use noise::NoiseFn;
use noise::Perlin;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Biome { Plains, Forest, Desert, Snowy, Mountain }

pub struct TerrainGen {
    perlin_height: Perlin,
    perlin_detail: Perlin,
    perlin_cave: Perlin,
    perlin_biome: Perlin,
    seed: u32,
}

impl TerrainGen {
    pub fn new(seed: u32) -> Self {
        Self {
            perlin_height: Perlin::new(seed),
            perlin_detail: Perlin::new(seed.wrapping_add(101)),
            perlin_cave: Perlin::new(seed.wrapping_add(202)),
            perlin_biome: Perlin::new(seed.wrapping_add(303)),
            seed,
        }
    }
    // Per-biome grass tint (multiplied over the grayscale grass_top / grass_side textures). Uses the
    // same low-frequency biome noise the terrain height uses, plus a humidity sample, so the ground
    // colour shifts smoothly between cool-lush, warm-lush and dry regions.
    pub fn grass_tint(&self, wx: f64, wz: f64) -> [f32; 3] {
        let temp = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        let humid = (self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        let lerp3 = |a: [f32; 3], b: [f32; 3], t: f32| [a[0] + (b[0]-a[0])*t, a[1] + (b[1]-a[1])*t, a[2] + (b[2]-a[2])*t];
        let cold = [0.46, 0.62, 0.42]; // cool blue-green
        let lush = [0.40, 0.68, 0.28]; // temperate plains
        let dry  = [0.74, 0.72, 0.36]; // warm/dry yellow-green
        let warm = lerp3(dry, lush, humid);
        lerp3(cold, warm, temp)
    }
    // Classify a column into a biome from temperature/humidity noise, with high elevation overriding
    // to Mountain. Same low-frequency biome noise as the grass tint so colour and terrain agree.
    pub fn biome_at(&self, wx: f64, wz: f64, h: i32) -> Biome {
        if h > 104 { return Biome::Mountain; }
        let temp = (self.perlin_biome.get([wx * 0.0015, wz * 0.0015]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        let humid = (self.perlin_biome.get([wx * 0.0015 + 137.0, wz * 0.0015 - 91.0]) as f32 * 0.5 + 0.5).clamp(0.0, 1.0);
        if temp > 0.68 && humid < 0.45 { Biome::Desert }
        else if temp < 0.30 { Biome::Snowy }
        else if humid > 0.60 { Biome::Forest }
        else { Biome::Plains }
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
        if y < 10.0 || y > 90.0 { return false; }
        let v = self.perlin_cave.get([x * 0.07, y * 0.08, z * 0.07]);
        let v2 = self.perlin_cave.get([x * 0.15 + 100.0, y * 0.15, z * 0.15]) * 0.5;
        (v + v2) > 0.58
    }
    pub fn fill_chunk(&self, chunk: &mut Chunk) {
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
                    chunk.set_block(dx, y, dz, 1);
                }
            }
            // Subsurface band just under the surface: sand in deserts, stone on mountains, else dirt.
            let sub: u8 = match biome { Biome::Desert => 6, Biome::Mountain => 1, _ => 2 };
            for y in stone_top..h.saturating_sub(1) {
                if !self.cave_at(wx, y as f64, wz) {
                    chunk.set_block(dx, y, dz, sub);
                }
            }
            if h > 0 && !self.cave_at(wx, h as f64, wz) {
                // Surface block by biome; underwater/beach columns are always sand.
                let surface: u8 = if h < 62 { 6 } else { match biome {
                    Biome::Desert => 6,                                   // sand
                    Biome::Snowy => 11,                                   // snow
                    Biome::Mountain => if h > 118 { 11 } else if h > 108 { 1 } else { 3 }, // snow cap / bare rock / grass
                    _ => 3,                                               // grass
                }};
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
            let th = 4 + (hasher.finish() % 4) as usize;
            match biome {
                Biome::Desert => {
                    // Sparse cacti-like columns of cactus... none available, so rare surface rocks.
                    if surf == 6 && r < 4 { let y = h + 1; if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, 8); } }
                }
                Biome::Snowy => {
                    if surf == 11 && r < 22 { self.plant_tree(chunk, dx, dz, h, 29, 31, th + 1); } // spruce
                }
                Biome::Forest => {
                    if surf == 3 && r < 65 {
                        if (hasher.finish() >> 8) % 2 == 0 { self.plant_tree(chunk, dx, dz, h, 26, 28, th); } // birch
                        else { self.plant_tree(chunk, dx, dz, h, 4, 5, th); }                                 // oak
                    }
                }
                Biome::Mountain => {
                    if surf == 3 && r < 8 { self.plant_tree(chunk, dx, dz, h, 29, 31, th); } // sparse spruce
                }
                Biome::Plains => {
                    if surf == 3 {
                        if r == 3 { let y = h + 1; if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, 8); } } // scattered rock
                        else if r < 12 { self.plant_tree(chunk, dx, dz, h, 4, 5, th); }                     // sparse oak
                    }
                }
            }
        }}
        chunk.generated = true;
        chunk.mesh_dirty = true;
    }

    pub const SEA_LEVEL: usize = 62;

    // Fill each column's air above the *intended terrain surface* up to sea level with water.
    // Idempotent, and applied to loaded chunks too so worlds saved before water existed still get
    // oceans/lakes. Using height_at (not the scanned solid top) means cave shafts carved into high
    // land are NOT flooded — only genuine lowland columns (surface below sea) hold water.
    pub fn ensure_water(&self, chunk: &mut Chunk) {
        let (ox, oz) = chunk.pos.world_origin();
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let h = self.height_at((ox + dx as i32) as f64, (oz + dz as i32) as f64) as usize;
            if h >= Self::SEA_LEVEL { continue; }
            for y in (h + 1)..=Self::SEA_LEVEL {
                if y < CHUNK_HEIGHT && chunk.get_block(dx, y, dz) == 0 { chunk.set_block(dx, y, dz, 12); }
            }
        }}
    }
}
