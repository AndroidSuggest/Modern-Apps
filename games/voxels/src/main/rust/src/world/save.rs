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
pub struct PlayerSave { pub x: f32, pub y: f32, pub z: f32, pub yaw: f32, pub pitch: f32, pub inventory: InventorySave, pub stats: StatsSave }
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
