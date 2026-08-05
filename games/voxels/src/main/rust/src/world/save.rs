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
// VOX2: "VOX2", section count, then per section: present u8; if 1 { 4096 block bytes; has_meta u8;
// if 1 { 4096 meta bytes } }. The meta flag keeps cube-only sections exactly the size VOX1 wrote.
fn encode_chunk(chunk: &Chunk) -> Vec<u8> {
    let mut data = Vec::with_capacity(16*1024);
    data.extend_from_slice(b"VOX2");
    data.push(SECTIONS_PER_CHUNK as u8);
    for sec_opt in chunk.sections.iter() {
        let Some(sec) = sec_opt else { data.push(0); continue; };
        data.push(1);
        data.extend_from_slice(&sec.blocks);
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
// Reads VOX1 and VOX2. A VOX1 chunk upgrades losslessly: every block it can contain is a full cube,
// which is exactly what meta 0 means. Rejecting it instead would make `load_or_gen` treat the chunk
// as ungenerated and regenerate it from the seed, erasing the player's build.
fn decode_chunk(bytes: &[u8], chunk: &mut Chunk) -> std::io::Result<()> {
    let bad = |m: &'static str| std::io::Error::new(std::io::ErrorKind::InvalidData, m);
    if bytes.len() < 5 { return Err(bad("too small")); }
    let has_meta_flags = match &bytes[0..4] {
        b"VOX1" => false,
        b"VOX2" => true,
        _ => return Err(bad("bad magic")),
    };
    let sec_count = bytes[4] as usize;
    let mut off = 5usize;
    for sec_idx in 0..sec_count.min(SECTIONS_PER_CHUNK) {
        if off >= bytes.len() { break; }
        let flag = bytes[off]; off+=1;
        if flag != 1 { continue; }
        if off + 4096 > bytes.len() { return Err(bad("section overflow")); }
        let mut blocks = [0u8; 4096];
        blocks.copy_from_slice(&bytes[off..off+4096]);
        off+=4096;
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
        }
    }
}
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct InventorySave { pub selected: usize, pub slots: Vec<InvSlotSave> }
#[derive(serde::Serialize, serde::Deserialize, Default, Debug, Clone)]
pub struct InvSlotSave { pub id: u8, pub count: i32 }
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
    use super::super::chunk::{BlockSection, SECTIONS_PER_CHUNK};

    // Build a chunk file in the retired VOX1 layout: magic, section count, then per section a
    // present-flag followed by 4096 raw block bytes and nothing else.
    fn encode_vox1(chunk: &Chunk) -> Vec<u8> {
        let mut data = vec![];
        data.extend_from_slice(b"VOX1");
        data.push(SECTIONS_PER_CHUNK as u8);
        for sec in chunk.sections.iter() {
            match sec {
                Some(s) => { data.push(1); data.extend_from_slice(&s.blocks); }
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
        let one_section = 1 + 4096 + 1; // present flag, blocks, has-meta flag
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
        let back: PlayerSave = serde_json::from_str(&serde_json::to_string(&ps).unwrap()).unwrap();
        assert_eq!(back.progress.max_health, 34.0);
        assert_eq!(back.progress.dim, 1);
        assert_eq!(back.progress.armor[0].id, 175);
        assert_eq!(back.progress.dim_visited, vec![true, true, false]);
        assert_eq!(back.progress.respawn, Some([8.0, 70.0, 9.0]));
        assert!(back.progress.nether_wither_dead);
        assert_eq!(back.progress.world_secs, 372.5);
    }
}
