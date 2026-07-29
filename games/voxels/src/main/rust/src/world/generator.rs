use super::chunk::{Chunk, CHUNK_SIZE, CHUNK_HEIGHT};
use noise::NoiseFn;
use noise::Perlin;
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
                if here == 1 && above == 0 {
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
                } else if here == 0 && above == 1 {
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
