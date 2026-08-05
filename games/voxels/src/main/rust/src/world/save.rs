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
    let mut data = Vec::with_capacity(16*1024);
    data.extend_from_slice(b"VOX1");
    data.push(SECTIONS_PER_CHUNK as u8);
    for sec_opt in chunk.sections.iter() {
        if let Some(sec) = sec_opt {
            data.push(1);
            data.extend_from_slice(&sec.blocks);
        } else { data.push(0); }
    }
    fs::write(&tmp, &data)?;
    fs::rename(&tmp, &path)?;
    Ok(())
}
pub fn load_chunk(base: &str, _pos: ChunkPos, chunk: &mut Chunk) -> std::io::Result<()> {
    let path = chunk_file(base, chunk.pos);
    let bytes = fs::read(&path)?;
    if bytes.len() < 5 { return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "too small")); }
    if &bytes[0..4] != b"VOX1" { return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad magic")); }
    let sec_count = bytes[4] as usize;
    let mut off = 5usize;
    for sec_idx in 0..sec_count.min(SECTIONS_PER_CHUNK) {
        if off >= bytes.len() { break; }
        let flag = bytes[off]; off+=1;
        if flag==1 {
            if off + 4096 > bytes.len() { return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "section overflow")); }
            let mut blocks = [0u8; 4096];
            blocks.copy_from_slice(&bytes[off..off+4096]);
            off+=4096;
            let non_air = blocks.iter().filter(|&&b| b!=0).count();
            if non_air>0 { chunk.sections[sec_idx] = Some(super::chunk::BlockSection{ blocks, non_air }); }
        }
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
