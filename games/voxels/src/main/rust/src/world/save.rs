use super::chunk::{Chunk, ChunkPos, SECTIONS_PER_CHUNK};
use std::fs;
use std::path::{Path, PathBuf};

fn region_dir(base: &str) -> PathBuf {
    Path::new(base).join("voxels").join("worlds").join("default").join("regions")
}
fn chunk_file(base: &str, pos: ChunkPos) -> PathBuf {
    region_dir(base).join(format!("c_{}_{}.dat", pos.0, pos.1))
}
pub fn save_chunk(base: &str, chunk: &Chunk) -> std::io::Result<()> {
    let dir = region_dir(base);
    fs::create_dir_all(&dir)?;
    let path = chunk_file(base, chunk.pos);
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, &encode_chunk(chunk))?;
    fs::rename(&tmp, &path)?;
    Ok(())
}
// VOX3: "VOX3", section count, then per section: present u8; if 1 { 4096 block ids as u16
// little-endian; has_meta u8; if 1 { 4096 meta bytes } }. Block ids are two bytes wide so ids above
// 255 have somewhere to live; meta stays one byte. The meta flag keeps cube-only sections from
// paying for an array they never filled.
fn encode_chunk(chunk: &Chunk) -> Vec<u8> {
    let mut data = Vec::with_capacity(32*1024);
    data.extend_from_slice(b"VOX3");
    data.push(SECTIONS_PER_CHUNK as u8);
    for sec_opt in chunk.sections.iter() {
        let Some(sec) = sec_opt else { data.push(0); continue; };
        data.push(1);
        for &id in sec.blocks.iter() { data.extend_from_slice(&id.to_le_bytes()); }
        match sec.meta.as_ref().filter(|m| m.iter().any(|&v| v != 0)) {
            Some(meta) => { data.push(1); data.extend_from_slice(&meta[..]); }
            None => data.push(0),
        }
    }
    data
}
pub fn load_chunk(base: &str, _pos: ChunkPos, chunk: &mut Chunk) -> std::io::Result<()> {
    let path = chunk_file(base, chunk.pos);
    decode_chunk(&fs::read(&path)?, chunk)
}
// Reads VOX1, VOX2 and VOX3. Older chunks upgrade losslessly: a VOX1 chunk holds only full cubes,
// which is exactly what meta 0 means, and narrow block bytes widen into the same numbers. Rejecting
// an old file instead would make `load_or_gen` treat the chunk as ungenerated and regenerate it from
// the seed, erasing the player's build.
fn decode_chunk(bytes: &[u8], chunk: &mut Chunk) -> std::io::Result<()> {
    let bad = |m: &'static str| std::io::Error::new(std::io::ErrorKind::InvalidData, m);
    if bytes.len() < 5 { return Err(bad("too small")); }
    let (wide_ids, has_meta_flags) = match &bytes[0..4] {
        b"VOX1" => (false, false),
        b"VOX2" => (false, true),
        b"VOX3" => (true, true),
        _ => return Err(bad("bad magic")),
    };
    let block_bytes = if wide_ids { 8192 } else { 4096 };
    let sec_count = bytes[4] as usize;
    let mut off = 5usize;
    for sec_idx in 0..sec_count.min(SECTIONS_PER_CHUNK) {
        if off >= bytes.len() { break; }
        let flag = bytes[off]; off+=1;
        if flag != 1 { continue; }
        if off + block_bytes > bytes.len() { return Err(bad("section overflow")); }
        let mut blocks = [0 as crate::world::block::Id; 4096];
        if wide_ids {
            for (i, cell) in blocks.iter_mut().enumerate() {
                *cell = u16::from_le_bytes([bytes[off + i*2], bytes[off + i*2 + 1]]);
            }
        } else {
            for (i, cell) in blocks.iter_mut().enumerate() { *cell = bytes[off + i] as u16; }
        }
        off+=block_bytes;
        let mut meta = None;
        if has_meta_flags {
            if off >= bytes.len() { return Err(bad("missing meta flag")); }
            let has_meta = bytes[off]; off+=1;
            if has_meta == 1 {
                if off + 4096 > bytes.len() { return Err(bad("meta overflow")); }
                let mut m = Box::new([0u8; 4096]);
                m.copy_from_slice(&bytes[off..off+4096]);
                off+=4096;
                meta = Some(m);
            }
        }
        let non_air = blocks.iter().filter(|&&b| b!=0).count();
        if non_air>0 { chunk.sections[sec_idx] = Some(super::chunk::BlockSection{ blocks, meta, non_air }); }
    }
    chunk.generated = true; chunk.dirty = false; chunk.mesh_dirty = true;
    Ok(())
}

#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct PlayerSave {
    pub x: f32, pub y: f32, pub z: f32, pub yaw: f32, pub pitch: f32,
    pub inventory: InventorySave, pub stats: StatsSave,
    // Everything below was added after the first save format; `default` keeps old saves loadable.
    #[serde(default)] pub progress: ProgressSave,
}
// Progression that used to reset on every quit: equipped armor, earned max health, which dimension
// the player is standing in (and where they were in the others), and one-time boss kills.
#[derive(serde::Serialize, serde::Deserialize, Debug, Clone)]
pub struct ProgressSave {
    #[serde(default)] pub armor: Vec<InvSlotSave>,
    #[serde(default = "default_max_health")] pub max_health: f32,
    #[serde(default)] pub dim: u8,
    #[serde(default)] pub dim_pos: Vec<[f32; 3]>,
    #[serde(default)] pub dim_visited: Vec<bool>,
    #[serde(default)] pub respawn: Option<[f32; 3]>,
    #[serde(default)] pub end_dragon_dead: bool,
    #[serde(default)] pub nether_wither_dead: bool,
    #[serde(default)] pub world_secs: f32,
    #[serde(default)] pub best_beacon: i32,
    #[serde(default = "default_deepest")] pub deepest_y: i32,
    #[serde(default)] pub blessings: crate::blessing::Attunement,
    // Completed trades per profession, indexed by `villager::ALL` order. Villager mobs aren't saved,
    // so levels are tracked against the profession rather than the individual.
    #[serde(default)] pub trades_done: Vec<u32>,
    // Weather state and the seconds left on it, so a save doesn't reopen to a clear sky mid-storm.
    #[serde(default)] pub weather: u8,
    #[serde(default)] pub weather_cd: f32,
    /// Bitmask over RECIPES of the recipes the player has been shown.
    #[serde(default)] pub known_recipes: Vec<u8>,
}
fn default_max_health() -> f32 { 20.0 }
fn default_deepest() -> i32 { 128 }
impl Default for ProgressSave {
    fn default() -> Self {
        Self {
            armor: Vec::new(), max_health: default_max_health(), dim: 0,
            dim_pos: Vec::new(), dim_visited: Vec::new(), respawn: None,
            end_dragon_dead: false, nether_wither_dead: false, world_secs: 0.0,
            best_beacon: 0, deepest_y: default_deepest(),
            blessings: crate::blessing::Attunement::default(),
            trades_done: Vec::new(),
            weather: 0, weather_cd: 0.0, known_recipes: Vec::new(),
        }
    }
}
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct InventorySave { pub selected: usize, pub slots: Vec<InvSlotSave> }
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct InvSlotSave { pub id: crate::world::block::Id, pub count: i32 }
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct StatsSave { pub placed: i32, pub broken: i32, pub walked: i32, pub night_seen: bool }

pub fn player_file(base: &str) -> PathBuf {
    Path::new(base).join("voxels").join("worlds").join("default").join("player.json")
}
pub fn save_player(base: &str, save: &PlayerSave) -> std::io::Result<()> {
    let path = player_file(base);
    if let Some(p) = path.parent() { fs::create_dir_all(p)?; }
    let json = serde_json::to_string(save).unwrap_or_default();
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, json.as_bytes())?;
    fs::rename(tmp, path)?;
    Ok(())
}
pub fn load_player(base: &str) -> Option<PlayerSave> {
    let path = player_file(base);
    let s = fs::read_to_string(&path).ok()?;
    serde_json::from_str(&s).ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use super::super::chunk::{BlockSection, CHUNK_HEIGHT, SECTIONS_PER_CHUNK};

    // Build a chunk file in the retired VOX1 layout: magic, section count, then per section a
    // present-flag followed by 4096 raw block bytes and nothing else.
    fn encode_vox1(chunk: &Chunk) -> Vec<u8> {
        let mut data = vec![];
        data.extend_from_slice(b"VOX1");
        data.push(SECTIONS_PER_CHUNK as u8);
        for sec in chunk.sections.iter() {
            match sec {
                Some(s) => { data.push(1); data.extend(s.blocks.iter().map(|&b| b as u8)); }
                None => data.push(0),
            }
        }
        data
    }

    // Build a chunk file in the retired VOX2 layout: VOX1 plus a has-meta flag and optional meta
    // array per section.
    fn encode_vox2(chunk: &Chunk) -> Vec<u8> {
        let mut data = vec![];
        data.extend_from_slice(b"VOX2");
        data.push(SECTIONS_PER_CHUNK as u8);
        for sec in chunk.sections.iter() {
            match sec {
                Some(s) => {
                    data.push(1);
                    data.extend(s.blocks.iter().map(|&b| b as u8));
                    match s.meta.as_ref() {
                        Some(m) => { data.push(1); data.extend_from_slice(&m[..]); }
                        None => data.push(0),
                    }
                }
                None => data.push(0),
            }
        }
        data
    }

    // The load path treats a decode failure as "not generated", which makes ChunkMap regenerate the
    // chunk from the seed and silently destroy whatever the player built there. A VOX1 file must
    // therefore keep loading, byte-for-byte, with meta defaulting to plain cubes.
    #[test]
    fn legacy_vox1_chunks_still_load() {
        let mut original = Chunk::new(ChunkPos(3, -4));
        original.set_block(1, 5, 2, 42);
        original.set_block(15, 200, 15, 7);
        let bytes = encode_vox1(&original);

        let mut loaded = Chunk::new(ChunkPos(3, -4));
        decode_chunk(&bytes, &mut loaded).expect("a VOX1 chunk must still decode");

        assert!(loaded.generated, "a decoded chunk must count as generated or it gets regenerated");
        assert_eq!(loaded.get_block(1, 5, 2), 42);
        assert_eq!(loaded.get_block(15, 200, 15), 7);
        assert_eq!(loaded.get_meta(1, 5, 2), 0, "legacy blocks are all full cubes");
        for (a, b) in original.sections.iter().zip(loaded.sections.iter()) {
            match (a, b) {
                (Some(a), Some(b)) => assert_eq!(a.blocks, b.blocks, "block bytes must survive verbatim"),
                (None, None) => {}
                _ => panic!("section presence changed across a VOX1 load"),
            }
        }
    }

    #[test]
    fn legacy_vox2_chunks_still_load() {
        let mut original = Chunk::new(ChunkPos(-9, 12));
        original.set_block(2, 30, 4, 55);
        original.set_block_meta(3, 30, 4, 103, 0b110);
        original.set_block(15, 250, 15, 1);
        let bytes = encode_vox2(&original);

        let mut loaded = Chunk::new(ChunkPos(-9, 12));
        decode_chunk(&bytes, &mut loaded).expect("a VOX2 chunk must still decode");

        assert!(loaded.generated);
        assert_eq!(loaded.get_block(2, 30, 4), 55);
        assert_eq!(loaded.get_block(3, 30, 4), 103);
        assert_eq!(loaded.get_meta(3, 30, 4), 0b110, "stair facing must survive the upgrade");
        assert_eq!(loaded.get_block(15, 250, 15), 1);
    }

    #[test]
    fn vox3_round_trips_blocks_and_meta() {
        let mut original = Chunk::new(ChunkPos(7, 7));
        original.set_block(0, 0, 0, 1);
        original.set_block(15, 255, 15, 123);
        original.set_block_meta(8, 64, 8, 102, 0b100);
        original.set_block(0, 255, 0, 123);
        // An id from the high block window, which only VOX3 is wide enough to carry.
        original.set_block(1, 255, 0, crate::world::block::BLOCK_HIGH_BASE);

        let encoded = encode_chunk(&original);
        assert_eq!(&encoded[0..4], b"VOX3");

        let mut loaded = Chunk::new(ChunkPos(7, 7));
        decode_chunk(&encoded, &mut loaded).expect("VOX3 must decode");
        assert_eq!(loaded.get_block(1, 255, 0), crate::world::block::BLOCK_HIGH_BASE,
            "a wide id must survive the round trip intact");
        for (a, b) in original.sections.iter().zip(loaded.sections.iter()) {
            match (a, b) {
                (Some(a), Some(b)) => {
                    assert_eq!(a.blocks, b.blocks, "every block id must survive");
                    assert_eq!(a.meta.is_some(), b.meta.is_some());
                    if let (Some(am), Some(bm)) = (&a.meta, &b.meta) { assert_eq!(&am[..], &bm[..]); }
                }
                (None, None) => {}
                _ => panic!("section presence changed across a VOX3 round trip"),
            }
        }
    }

    // The upgrade is the dangerous moment: a world saved by the old build gets read as VOX2 and
    // rewritten as VOX3 the first time it's touched. Not one cell may shift in the process.
    #[test]
    fn a_vox2_chunk_upgrades_without_losing_a_block() {
        let mut original = Chunk::new(ChunkPos(1, -1));
        // A spread of ids and metas across several sections, including the extremes.
        for i in 0..64 {
            let x = i % 16; let z = (i / 16) % 16;
            original.set_block(x, i, z, (i as crate::world::block::Id % 123) + 1);
        }
        original.set_block_meta(4, 100, 4, 103, 2);
        original.set_block_meta(5, 100, 4, 102, 0b100);
        original.set_block(0, 255, 0, 123);

        let mut once = Chunk::new(ChunkPos(1, -1));
        decode_chunk(&encode_vox2(&original), &mut once).expect("VOX2 decodes");
        let mut twice = Chunk::new(ChunkPos(1, -1));
        decode_chunk(&encode_chunk(&once), &mut twice).expect("the VOX3 rewrite decodes");

        for y in 0..CHUNK_HEIGHT { for x in 0..16 { for z in 0..16 {
            assert_eq!(original.get_block(x, y, z), twice.get_block(x, y, z), "block at {x},{y},{z} changed");
            assert_eq!(original.get_meta(x, y, z), twice.get_meta(x, y, z), "meta at {x},{y},{z} changed");
        }}}
    }

    // A decode error makes ChunkMap regenerate the chunk from the seed, so `Err` has to mean "this
    // really is not a chunk file" and nothing weaker.
    #[test]
    fn decode_errors_only_on_genuinely_corrupt_input() {
        let mut chunk = Chunk::new(ChunkPos(0, 0));
        chunk.set_block(1, 1, 1, 5);
        let good = encode_chunk(&chunk);

        let mut out = Chunk::new(ChunkPos(0, 0));
        assert!(decode_chunk(&good, &mut out).is_ok());
        assert!(decode_chunk(&[], &mut out).is_err(), "an empty file is not a chunk");
        assert!(decode_chunk(b"NOPE\x10", &mut out).is_err(), "a foreign magic is not a chunk");
        assert!(decode_chunk(&good[..good.len()/2], &mut out).is_err(), "a truncated section is corrupt");

        // A file whose header promises more sections than it carries stops early rather than
        // failing: the sections it did carry are real data worth keeping.
        let mut short = good.clone();
        short[4] = SECTIONS_PER_CHUNK as u8;
        let mut partial = Chunk::new(ChunkPos(0, 0));
        assert!(decode_chunk(&short[..6 + 8192 + 1], &mut partial).is_ok());
        assert_eq!(partial.get_block(1, 1, 1), 5);
    }

    #[test]
    fn slabs_and_stairs_survive_a_round_trip() {
        let mut original = Chunk::new(ChunkPos(0, 0));
        // A slab, and stairs facing each of the four compass directions.
        original.set_block_meta(0, 64, 0, 102, 0b100);       // top-half slab
        original.set_block_meta(1, 64, 0, 103, 0);           // stairs facing north, bottom
        original.set_block_meta(2, 64, 0, 103, 1);           // east
        original.set_block_meta(3, 64, 0, 103, 0b110);       // south, top-half
        original.set_block_meta(4, 64, 0, 103, 3);           // west
        original.set_block(5, 64, 0, 1);                     // a plain cube alongside

        let mut loaded = Chunk::new(ChunkPos(0, 0));
        decode_chunk(&encode_chunk(&original), &mut loaded).expect("VOX2 must decode");

        assert_eq!(loaded.get_meta(0, 64, 0), 0b100);
        assert_eq!(loaded.get_meta(1, 64, 0), 0);
        assert_eq!(loaded.get_meta(2, 64, 0), 1);
        assert_eq!(loaded.get_meta(3, 64, 0), 0b110);
        assert_eq!(loaded.get_meta(4, 64, 0), 3);
        assert_eq!(loaded.get_block(3, 64, 0), 103);
        assert_eq!(loaded.get_block(5, 64, 0), 1);
        assert_eq!(loaded.get_meta(5, 64, 0), 0);
    }

    // Terrain is all cubes, so a chunk that never stored a meta value must not pay for one — on
    // disk or in memory.
    #[test]
    fn cube_only_chunks_carry_no_meta() {
        let mut chunk = Chunk::new(ChunkPos(0, 0));
        for x in 0..16 { for z in 0..16 { chunk.set_block(x, 10, z, 1); } }
        let sec = chunk.sections[0].as_ref().expect("section 0 holds the stone");
        assert!(sec.meta.is_none(), "a cube-only section must not allocate a meta array");

        let encoded = encode_chunk(&chunk);
        let one_section = 1 + 8192 + 1; // present flag, blocks as u16, has-meta flag
        assert_eq!(encoded.len(), 5 + one_section + (SECTIONS_PER_CHUNK - 1), "no meta bytes should be written");
    }

    // Clearing a block must clear its meta too, or a later slab placed in the same cell would
    // inherit a stale facing.
    #[test]
    fn replacing_a_block_clears_its_meta() {
        let mut s = BlockSection::empty();
        s.set(1, 2, 3, 103);
        s.set_meta(1, 2, 3, 0b110);
        assert_eq!(s.get_meta(1, 2, 3), 0b110);
        s.set(1, 2, 3, 1);
        assert_eq!(s.get_meta(1, 2, 3), 0, "a fresh block starts with default meta");
    }

    // A player.json written before ProgressSave existed must still load, defaulting to a fresh
    // progression rather than failing and wiping the world.
    #[test]
    fn legacy_player_json_still_loads() {
        let legacy = r#"{"x":1.0,"y":80.0,"z":-3.0,"yaw":0.5,"pitch":0.0,
            "inventory":{"selected":2,"slots":[{"id":3,"count":64}]},
            "stats":{"placed":7,"broken":9,"walked":42,"night_seen":true}}"#;
        let ps: PlayerSave = serde_json::from_str(legacy).expect("legacy save must parse");
        assert_eq!(ps.inventory.selected, 2);
        assert_eq!(ps.stats.placed, 7);
        assert_eq!(ps.progress.max_health, 20.0);
        assert_eq!(ps.progress.dim, 0);
        assert!(!ps.progress.end_dragon_dead);
    }

    #[test]
    fn progress_round_trips() {
        let mut ps = PlayerSave::default();
        ps.progress.max_health = 34.0;
        ps.progress.dim = 1;
        ps.progress.armor = vec![InvSlotSave { id: 175, count: 400 }];
        ps.progress.dim_pos = vec![[1.0, 2.0, 3.0], [4.0, 5.0, 6.0], [0.0; 3]];
        ps.progress.dim_visited = vec![true, true, false];
        ps.progress.respawn = Some([8.0, 70.0, 9.0]);
        ps.progress.nether_wither_dead = true;
        ps.progress.world_secs = 372.5;
        ps.progress.trades_done = vec![0, 7, 0, 0, 0, 0, 14, 0, 0, 0, 0];
        ps.progress.weather = 2;
        ps.progress.weather_cd = 88.0;
        ps.progress.known_recipes = vec![0b0000_0101, 0b1000_0000];
        let back: PlayerSave = serde_json::from_str(&serde_json::to_string(&ps).unwrap()).unwrap();
        assert_eq!(back.progress.max_health, 34.0);
        assert_eq!(back.progress.dim, 1);
        assert_eq!(back.progress.armor[0].id, 175);
        assert_eq!(back.progress.dim_visited, vec![true, true, false]);
        assert_eq!(back.progress.respawn, Some([8.0, 70.0, 9.0]));
        assert!(back.progress.nether_wither_dead);
        assert_eq!(back.progress.world_secs, 372.5);
        assert_eq!(back.progress.trades_done[1], 7, "villager levels have to survive a quit");
        assert_eq!(back.progress.trades_done[6], 14);
        assert_eq!(back.progress.weather, 2, "a storm has to still be raging after a reload");
        assert_eq!(back.progress.weather_cd, 88.0);
        assert_eq!(back.progress.known_recipes, vec![0b0000_0101, 0b1000_0000], "discovered recipes must persist");
    }
}
