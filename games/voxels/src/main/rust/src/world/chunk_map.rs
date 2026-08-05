use super::chunk::{Chunk, ChunkPos, CHUNK_SIZE, CHUNK_HEIGHT};
use super::generator::TerrainGen;
use super::save;
use hashbrown::HashMap;

pub struct ChunkMap {
    chunks: HashMap<ChunkPos, Chunk>,
    gen: TerrainGen,
    save_dir: String,
    pub render_distance: i32,
}

impl ChunkMap {
    pub fn new(seed: u32, save_dir: String) -> Self { Self::new_dim(seed, save_dir, 0) }
    pub fn new_dim(seed: u32, save_dir: String, dim: u8) -> Self {
        Self { chunks: HashMap::new(), gen: TerrainGen::new_dim(seed, dim), save_dir, render_distance: 12 }
    }
    pub fn dim(&self) -> u8 { self.gen.dim }
    pub fn get_block_world(&self, wx: i32, wy: i32, wz: i32) -> u8 {
        if wy < 0 || wy >= CHUNK_HEIGHT as i32 { return 0; }
        let cp = ChunkPos::from_world(wx, wz);
        if let Some(chunk) = self.chunks.get(&cp) {
            let lx = wx.rem_euclid(CHUNK_SIZE as i32) as usize;
            let lz = wz.rem_euclid(CHUNK_SIZE as i32) as usize;
            chunk.get_block(lx, wy as usize, lz)
        } else { 0 }
    }
    pub fn get_meta_world(&self, wx: i32, wy: i32, wz: i32) -> u8 {
        if wy < 0 || wy >= CHUNK_HEIGHT as i32 { return 0; }
        let cp = ChunkPos::from_world(wx, wz);
        if let Some(chunk) = self.chunks.get(&cp) {
            let lx = wx.rem_euclid(CHUNK_SIZE as i32) as usize;
            let lz = wz.rem_euclid(CHUNK_SIZE as i32) as usize;
            chunk.get_meta(lx, wy as usize, lz)
        } else { 0 }
    }
    pub fn set_block_world(&mut self, wx: i32, wy: i32, wz: i32, id: u8) -> bool {
        self.set_block_meta_world(wx, wy, wz, id, 0)
    }
    pub fn set_block_meta_world(&mut self, wx: i32, wy: i32, wz: i32, id: u8, meta: u8) -> bool {
        if wy < 0 || wy >= CHUNK_HEIGHT as i32 { return false; }
        let cp = ChunkPos::from_world(wx, wz);
        if !self.chunks.contains_key(&cp) { self.load_or_gen(cp); }
        if let Some(chunk) = self.chunks.get_mut(&cp) {
            let lx = wx.rem_euclid(CHUNK_SIZE as i32) as usize;
            let lz = wz.rem_euclid(CHUNK_SIZE as i32) as usize;
            chunk.set_block_meta(lx, wy as usize, lz, id, meta);
            true
        } else { false }
    }
    pub fn load_or_gen(&mut self, pos: ChunkPos) {
        if self.chunks.contains_key(&pos) { return; }
        let mut chunk = Chunk::new(pos);
        let loaded = save::load_chunk(&self.save_dir, pos, &mut chunk).is_ok() && chunk.generated;
        if !loaded {
            self.gen.fill_chunk(&mut chunk);
        }
        // Water below sea level, for freshly generated AND older saved chunks (idempotent).
        self.gen.ensure_water(&mut chunk);
        self.chunks.insert(pos, chunk);
    }
    pub fn ensure_radius(&mut self, center_x: i32, center_z: i32) {
        let cpos = ChunkPos::from_world(center_x, center_z);
        let rd = self.render_distance;
        for dz in -rd..=rd { for dx in -rd..=rd {
            if dx*dx + dz*dz > rd*rd + 2 { continue; }
            self.load_or_gen(ChunkPos(cpos.0 + dx, cpos.1 + dz));
        }}
        let unload_dist = rd + 2;
        let to_unload: Vec<ChunkPos> = self.chunks.keys().copied()
            .filter(|p| (p.0 - cpos.0).abs() > unload_dist || (p.1 - cpos.1).abs() > unload_dist)
            .collect();
        for pos in to_unload {
            if let Some(ch) = self.chunks.remove(&pos) {
                if ch.dirty { let _ = save::save_chunk(&self.save_dir, &ch); }
            }
        }
    }
    pub fn chunks_iter(&self) -> impl Iterator<Item=(&ChunkPos, &Chunk)> { self.chunks.iter() }
    pub fn get(&self, pos: ChunkPos) -> Option<&Chunk> { self.chunks.get(&pos) }
    pub fn get_mut(&mut self, pos: ChunkPos) -> Option<&mut Chunk> { self.chunks.get_mut(&pos) }
    pub fn save_all(&self) {
        for (_, ch) in self.chunks.iter() { if ch.dirty { let _ = save::save_chunk(&self.save_dir, ch); } }
    }
    pub fn len(&self) -> usize { self.chunks.len() }
    pub fn grass_tint(&self, wx: i32, wz: i32) -> [f32; 3] { self.gen.grass_tint(wx as f64, wz as f64) }
}
