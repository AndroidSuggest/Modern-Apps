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
        let pct = |id: Id| counts.get(&id).copied().unwrap_or(0) as f64 * 100.0 / total as f64;
        for (id, name, lo, hi) in [
            (18 as Id, "coal", 0.5, 4.0),
            (19 as Id, "iron", 0.2, 2.5),
            (21 as Id, "redstone", 0.02, 0.6),
            (20 as Id, "diamond", 0.005, 0.3),
            (22 as Id, "emerald", 0.002, 0.3),
            (90 as Id, "silver", 0.02, 0.8),
            (97 as Id, "copper", 0.3, 3.0),
            (98 as Id, "gold", 0.01, 0.5),
        ] {
            let p = pct(id);
            println!("{name}: {p:.4}%");
            assert!(p >= lo && p <= hi, "{name} at {p:.4}% of stone, expected {lo}..{hi}%");
        }
        assert!(pct(57) > 5.0, "deepslate should fill the lower world, got {:.2}%", pct(57));
    }

    // The Nether must actually yield the alloy feedstock, or steel and adamant are unreachable.
    #[test]
    fn nether_ores_are_reachable() {
        let gen = TerrainGen::new_dim(999, 1);
        let (mut sulfur, mut cinnabar, mut total) = (0u32, 0u32, 0u32);
        for wx in (-192..192).step_by(3) {
            for wz in (-192..192).step_by(3) {
                for y in (5..118).step_by(4) {
                    total += 1;
                    match gen.stone_at(&NETHER_VEINS, wx as f64, y, wz as f64, 32) {
                        91 => sulfur += 1,
                        92 => cinnabar += 1,
                        _ => {}
                    }
                }
            }
        }
        let sp = sulfur as f64 * 100.0 / total as f64;
        let cp = cinnabar as f64 * 100.0 / total as f64;
        println!("sulfur: {sp:.4}%  cinnabar: {cp:.4}%");
        assert!((0.05..3.0).contains(&sp), "sulfur at {sp:.4}%");
        assert!((0.02..2.0).contains(&cp), "cinnabar at {cp:.4}%");
    }
}
