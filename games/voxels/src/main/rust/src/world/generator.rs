use super::chunk::{Chunk, CHUNK_SIZE, CHUNK_HEIGHT};
use noise::NoiseFn;
use noise::Perlin;
use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

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
            let h = heightmap[dz][dx] as usize;
            let h = h.min(CHUNK_HEIGHT - 2);
            for y in 0..5 {
                let id = if y == 0 { 13 } else if y < 3 && self.perlin_detail.get([(ox+dx as i32) as f64 * 0.1, y as f64 * 0.2, (oz+dz as i32) as f64 *0.1]) > 0.2 { 0 } else { 1 };
                if id != 0 { chunk.set_block(dx, y, dz, id); }
            }
            let stone_top = h.saturating_sub(4);
            for y in 5..stone_top {
                if !self.cave_at((ox+dx as i32) as f64, y as f64, (oz+dz as i32) as f64) {
                    chunk.set_block(dx, y, dz, 1);
                }
            }
            for y in stone_top..h.saturating_sub(1) {
                if !self.cave_at((ox+dx as i32) as f64, y as f64, (oz+dz as i32) as f64) {
                    chunk.set_block(dx, y, dz, 2);
                }
            }
            if h > 0 {
                if !self.cave_at((ox+dx as i32) as f64, h as f64, (oz+dz as i32) as f64) {
                    let top_id = if h < 62 { 6 } else { 3 };
                    chunk.set_block(dx, h, dz, top_id);
                    if h > 1 && top_id == 3 { chunk.set_block(dx, h-1, dz, 2); }
                }
            }
        }}
        for dz in 0..CHUNK_SIZE { for dx in 0..CHUNK_SIZE {
            let h = heightmap[dz][dx] as usize;
            if h < 63 || h > 120 { continue; }
            if chunk.get_block(dx, h, dz) != 3 { continue; }
            let mut hasher = DefaultHasher::new();
            (ox+dx as i32, oz+dz as i32, self.seed).hash(&mut hasher);
            let r = hasher.finish() % 100;
            if r < 1 {
                let th = 4 + (hasher.finish() % 2) as usize;
                for ty in 1..=th {
                    let y = h + ty;
                    if y < CHUNK_HEIGHT { chunk.set_block(dx, y, dz, 4); }
                }
                let top_y = h + th;
                for ly in 0..2 {
                    let y = top_y + ly;
                    if y >= CHUNK_HEIGHT { continue; }
                    for lx in -2i32..=2 { for lz in -2i32..=2 {
                        if lx.abs() == 2 && lz.abs() == 2 { continue; }
                        let x = dx as i32 + lx;
                        let z = dz as i32 + lz;
                        if x < 0 || x >= 16 || z < 0 || z >= 16 { continue; }
                        if ly == 0 && lx ==0 && lz==0 { continue; }
                        if chunk.get_block(x as usize, y, z as usize) == 0 {
                            chunk.set_block(x as usize, y, z as usize, 5);
                        }
                    }}
                }
                let y = top_y + 2;
                if y < CHUNK_HEIGHT && chunk.get_block(dx, y, dz) ==0 {
                    chunk.set_block(dx, y, dz, 5);
                }
            }
        }}
        chunk.generated = true;
        chunk.mesh_dirty = true;
    }
}
