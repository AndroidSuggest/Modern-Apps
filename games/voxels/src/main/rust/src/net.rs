// Networking layer for online multiplayer. This module is deliberately transport-agnostic: it never
// touches sockets or crypto. It owns a lock-free command queue (inbox/outbox of JSON `NetMsg`s) that
// the Kotlin side drains/feeds — Kotlin does all the encryption + relay I/O (see VoxelsSync.kt), so
// no networking crate is needed here and `panic = "abort"` stays safe (every handler is total).
//
// Authority model (host-authoritative over the office relay):
//  - The world owner runs the authoritative `tick_and_render`; it broadcasts snapshots (mobs, clock,
//    inventory) and edited chunks, and applies validated intents from clients.
//  - Clients apply host snapshots over their own locally-simulated world and send their edits/intents
//    upstream. Player transforms ride ephemeral PlayerTransform frames (relayed as presence).
//
// Role gating (owner/editor/viewer) and signature verification happen in Kotlin *before* a message is
// ever pushed here, so this layer trusts its inbox.

use crate::engine::EngineState;
use crate::entity::{Mob, MobKind, Projectile, ProjKind};
use crate::inventory::InvSlot;
use crate::player::Player;
use crate::world::block::Id;
use crate::world::chunk::ChunkPos;
use glam::Vec3;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU8, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::Instant;

pub const ROLE_OFFLINE: u8 = 0;
pub const ROLE_HOST: u8 = 1;
pub const ROLE_CLIENT: u8 = 2;

// How many render ticks between authoritative snapshot broadcasts (~60Hz tick -> ~10Hz snapshots).
const SNAPSHOT_EVERY: u32 = 6;
// How many ticks between local player-transform frames (higher rate: cheap + ephemeral).
const TRANSFORM_EVERY: u32 = 3;
// A remote player disappears if we haven't heard from them in this long.
const REMOTE_TTL_SECS: f32 = 6.0;

// ---- Wire DTOs (slim, serde-friendly; glam has serde on so Vec3 rides along) ----

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PlayerDto {
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    pub pitch: f32,
    pub on_ground: bool,
    pub sneaking: bool,
    pub gliding: bool,
    pub health: f32,
}

impl PlayerDto {
    fn of(p: &Player) -> Self {
        PlayerDto {
            pos: p.pos, vel: p.vel, yaw: p.yaw, pitch: p.pitch,
            on_ground: p.on_ground, sneaking: p.sneaking, gliding: p.gliding, health: p.health,
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MobDto {
    pub kind: u32,
    pub pos: Vec3,
    pub vel: Vec3,
    pub yaw: f32,
    pub health: f32,
    pub max_health: f32,
}

impl MobDto {
    fn of(m: &Mob) -> Self {
        MobDto { kind: m.kind.cell(), pos: m.pos, vel: m.vel, yaw: m.yaw, health: m.health, max_health: m.max_health }
    }
    fn to_mob(&self) -> Mob {
        let mut m = Mob::new(MobKind::from_cell(self.kind), self.pos, 1);
        m.vel = self.vel;
        m.yaw = self.yaw;
        m.health = self.health;
        m.max_health = self.max_health;
        m
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ProjDto {
    pub kind: u32,
    pub pos: Vec3,
    pub vel: Vec3,
    pub life: f32,
    pub from_player: bool,
}

fn proj_cell(k: ProjKind) -> u32 {
    match k {
        ProjKind::Fireball => 0, ProjKind::ShulkerBullet => 1, ProjKind::Firework => 2,
        ProjKind::Snowball => 3, ProjKind::EnderPearl => 4,
    }
}
fn proj_from_cell(c: u32) -> ProjKind {
    match c {
        1 => ProjKind::ShulkerBullet, 2 => ProjKind::Firework, 3 => ProjKind::Snowball,
        4 => ProjKind::EnderPearl, _ => ProjKind::Fireball,
    }
}

impl ProjDto {
    fn of(p: &Projectile) -> Self {
        ProjDto { kind: proj_cell(p.kind), pos: p.pos, vel: p.vel, life: p.life, from_player: p.from_player }
    }
    fn to_proj(&self) -> Projectile {
        Projectile {
            pos: self.pos, vel: self.vel, life: self.life, kind: proj_from_cell(self.kind),
            from_player: self.from_player, damage: 0.0, explosive: false,
        }
    }
}

/// The multiplayer protocol. Internally tagged (`{"type": "..."}`) so Kotlin can route each message
/// (e.g. transforms → presence, everything else → the world op log) without a Rust round-trip.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(tag = "type")]
pub enum NetMsg {
    /// Client → host on entry. Host replies with WorldInit + chunks + a first snapshot.
    Join { device: String, name: String },
    /// Host → client: world identity + host transform (client already has seed/name from the invite).
    WorldInit { seed: u32, name: String, player: PlayerDto },
    /// Full VOX3 chunk payload (host broadcast, or a client's edited chunk sent upstream).
    ChunkData { dim: u8, cx: i32, cz: i32, data: Vec<u8> },
    /// A single authoritative block write.
    BlockEdit { dim: u8, x: i32, y: i32, z: i32, id: Id, meta: u8 },
    /// Ephemeral player pose (relayed as presence; never persisted).
    PlayerTransform { device: String, player: PlayerDto },
    /// Host → clients: the full mob list at a reduced rate; clients interpolate.
    MobSnapshot { mobs: Vec<MobDto> },
    /// Host → clients: active projectiles.
    ProjSnapshot { projs: Vec<ProjDto> },
    /// Host → clients: the (shared) inventory as engine JSON.
    InventorySync { inv: String },
    /// Host → clients: a chest's contents. `key` is [dim, x, y, z].
    ContainerSync { key: [i32; 4], slots: Vec<InvSlot> },
    /// Host → clients: the authoritative world clock + weather.
    WorldClock { world_secs: f32, weather: u8, weather_cd: f32, rain: f32 },
    /// Client → host: a block place/break request (host validates + applies + rebroadcasts).
    EditIntent { device: String, dim: u8, x: i32, y: i32, z: i32, id: Id, meta: u8, break_it: bool },
    /// Client → host: an inventory move request.
    InvIntent { device: String, from: u32, to: u32 },
    /// Client → host: a container take/put request.
    ContainerIntent { device: String, take: bool, idx: u32 },
}

// ---- Shared state ----

struct RemotePlayer {
    pos: Vec3,
    yaw: f32,
    last: Instant,
}

static ROLE: AtomicU8 = AtomicU8::new(ROLE_OFFLINE);
static PUBLISH_TICK: AtomicU32 = AtomicU32::new(0);
// Set while applying a remote chunk/edit, so the edit-notify hook doesn't echo it back out.
static SUPPRESS_NOTE: AtomicBool = AtomicBool::new(false);

static INBOX: OnceLock<Mutex<Vec<NetMsg>>> = OnceLock::new();
static OUTBOX: OnceLock<Mutex<Vec<NetMsg>>> = OnceLock::new();
static EDITED: OnceLock<Mutex<HashSet<(i32, i32)>>> = OnceLock::new();
static REMOTE: OnceLock<Mutex<HashMap<String, RemotePlayer>>> = OnceLock::new();
static LOCAL_DEVICE: OnceLock<Mutex<String>> = OnceLock::new();

fn inbox() -> &'static Mutex<Vec<NetMsg>> { INBOX.get_or_init(|| Mutex::new(Vec::new())) }
fn outbox() -> &'static Mutex<Vec<NetMsg>> { OUTBOX.get_or_init(|| Mutex::new(Vec::new())) }
fn edited() -> &'static Mutex<HashSet<(i32, i32)>> { EDITED.get_or_init(|| Mutex::new(HashSet::new())) }
fn remote() -> &'static Mutex<HashMap<String, RemotePlayer>> { REMOTE.get_or_init(|| Mutex::new(HashMap::new())) }
fn local_device() -> String { LOCAL_DEVICE.get_or_init(|| Mutex::new(String::new())).lock().map(|s| s.clone()).unwrap_or_default() }

// ---- Kotlin-facing entry points (called from lib.rs JNI shims) ----

pub fn set_role(r: u8) { ROLE.store(r, Ordering::SeqCst); }
pub fn role() -> u8 { ROLE.load(Ordering::SeqCst) }
pub fn is_networked() -> bool { role() != ROLE_OFFLINE }

pub fn set_local_device(id: String) {
    if let Ok(mut d) = LOCAL_DEVICE.get_or_init(|| Mutex::new(String::new())).lock() { *d = id; }
}

/// Kotlin → Rust: feed decrypted, already-authorized message(s). Accepts a single `NetMsg` object or
/// a JSON array of them. Returns false on malformed input (never panics).
pub fn push_inbound_json(s: &str) -> bool {
    let msgs: Vec<NetMsg> = if let Ok(v) = serde_json::from_str::<Vec<NetMsg>>(s) {
        v
    } else if let Ok(one) = serde_json::from_str::<NetMsg>(s) {
        vec![one]
    } else {
        return false;
    };
    if let Ok(mut q) = inbox().lock() { q.extend(msgs); }
    true
}

/// Rust → Kotlin: pull all queued outbound messages as a JSON array (empty array when idle).
pub fn drain_outbound_json() -> String {
    let taken: Vec<NetMsg> = outbox().lock().map(|mut q| std::mem::take(&mut *q)).unwrap_or_default();
    serde_json::to_string(&taken).unwrap_or_else(|_| "[]".to_string())
}

/// Remote players as HUD JSON: `[{"device","x","y","z","yaw"}]` (stale entries pruned).
pub fn peers_json() -> String {
    prune_remote();
    let list: Vec<serde_json::Value> = remote().lock().map(|m| {
        m.iter().map(|(id, r)| serde_json::json!({
            "device": id, "x": r.pos.x, "y": r.pos.y, "z": r.pos.z, "yaw": r.yaw,
        })).collect()
    }).unwrap_or_default();
    serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
}

// ---- Engine-facing hooks ----

/// Called from `mark_neighbors_dirty` (the common path for every block edit) to record which chunk
/// changed, so `publish` can ship it. No-op offline or while applying a remote edit.
pub fn note_edited_chunk(cx: i32, cz: i32) {
    if !is_networked() || SUPPRESS_NOTE.load(Ordering::SeqCst) { return; }
    if let Ok(mut e) = edited().lock() { e.insert((cx, cz)); }
}

fn enqueue(msg: NetMsg) {
    if let Ok(mut q) = outbox().lock() { q.push(msg); }
}

fn upsert_remote(device: String, pos: Vec3, yaw: f32) {
    if device.is_empty() || device == local_device() { return; }
    if let Ok(mut m) = remote().lock() {
        m.insert(device, RemotePlayer { pos, yaw, last: Instant::now() });
    }
}

fn prune_remote() {
    if let Ok(mut m) = remote().lock() {
        m.retain(|_, r| r.last.elapsed().as_secs_f32() < REMOTE_TTL_SECS);
    }
}

/// Poses of remote players (pruned) for avatar rendering.
pub fn remote_player_poses() -> Vec<(Vec3, f32)> {
    prune_remote();
    remote().lock().map(|m| m.values().map(|r| (r.pos, r.yaw)).collect()).unwrap_or_default()
}

// Mark a chunk (and its edge neighbors) for remesh without going through the engine's edit-notify
// hook — used when applying remote data so we don't echo it straight back out.
fn dirty_chunk_and_neighbors(state: &mut EngineState, cx: i32, cz: i32) {
    let touch = |s: &mut EngineState, p: ChunkPos| { if let Some(c) = s.chunks.get_mut(p) { c.mesh_dirty = true; } };
    touch(state, ChunkPos(cx, cz));
    touch(state, ChunkPos(cx - 1, cz));
    touch(state, ChunkPos(cx + 1, cz));
    touch(state, ChunkPos(cx, cz - 1));
    touch(state, ChunkPos(cx, cz + 1));
}

fn apply_block(state: &mut EngineState, x: i32, y: i32, z: i32, id: Id, meta: u8) {
    SUPPRESS_NOTE.store(true, Ordering::SeqCst);
    state.chunks.set_block_meta_world(x, y, z, id, meta);
    SUPPRESS_NOTE.store(false, Ordering::SeqCst);
    let cp = ChunkPos::from_world(x, z);
    dirty_chunk_and_neighbors(state, cp.0, cp.1);
}

fn apply_chunk_data(state: &mut EngineState, cx: i32, cz: i32, data: &[u8]) {
    let cp = ChunkPos(cx, cz);
    state.chunks.load_or_gen(cp);
    let mut ok = false;
    if let Some(chunk) = state.chunks.get_mut(cp) {
        ok = crate::world::save::decode_chunk(data, chunk).is_ok();
        if ok { chunk.mesh_dirty = true; }
    }
    if ok { dirty_chunk_and_neighbors(state, cx, cz); }
}

// ---- Inbound: drain the queue and apply, at the top of each tick ----

pub fn apply_inbound(state: &mut EngineState) {
    if !is_networked() { return; }
    let msgs: Vec<NetMsg> = inbox().lock().map(|mut q| std::mem::take(&mut *q)).unwrap_or_default();
    if msgs.is_empty() { return; }
    let r = role();
    for msg in msgs {
        match msg {
            NetMsg::PlayerTransform { device, player } => upsert_remote(device, player.pos, player.yaw),

            // ---- Host applies validated client intents ----
            NetMsg::Join { .. } if r == ROLE_HOST => on_join(state),
            NetMsg::EditIntent { dim, x, y, z, id, meta, break_it, .. } if r == ROLE_HOST => {
                if dim == state.dim {
                    let write = if break_it { 0 } else { id };
                    apply_block(state, x, y, z, write, meta);
                    if let Ok(mut e) = edited().lock() { e.insert((ChunkPos::from_world(x, z).0, ChunkPos::from_world(x, z).1)); }
                }
            }
            NetMsg::InvIntent { from, to, .. } if r == ROLE_HOST => {
                state.inventory.move_item(from as usize, to as usize);
            }
            // A client's edited chunk, sent upstream: trust it (editor role was checked in Kotlin),
            // apply, and re-mark it so the host rebroadcasts the authoritative version.
            NetMsg::ChunkData { dim, cx, cz, data } if r == ROLE_HOST => {
                if dim == state.dim {
                    apply_chunk_data(state, cx, cz, &data);
                    if let Ok(mut e) = edited().lock() { e.insert((cx, cz)); }
                }
            }

            // ---- Client applies host snapshots ----
            NetMsg::ChunkData { dim, cx, cz, data } if r == ROLE_CLIENT => {
                if dim == state.dim { apply_chunk_data(state, cx, cz, &data); }
            }
            NetMsg::BlockEdit { dim, x, y, z, id, meta } if r == ROLE_CLIENT => {
                if dim == state.dim { apply_block(state, x, y, z, id, meta); }
            }
            NetMsg::MobSnapshot { mobs } if r == ROLE_CLIENT => {
                state.mobs = mobs.iter().map(|d| d.to_mob()).collect();
            }
            NetMsg::ProjSnapshot { projs } if r == ROLE_CLIENT => {
                state.projectiles = projs.iter().map(|d| d.to_proj()).collect();
            }
            NetMsg::InventorySync { inv } if r == ROLE_CLIENT => {
                if let Ok(parsed) = serde_json::from_str::<crate::inventory::Inventory>(&inv) {
                    state.inventory = parsed;
                }
            }
            NetMsg::ContainerSync { key, slots } if r == ROLE_CLIENT => {
                let k = (key[0] as u8, key[1], key[2], key[3]);
                state.containers.insert(k, slots);
            }
            NetMsg::WorldClock { world_secs, weather, weather_cd, rain } if r == ROLE_CLIENT => {
                state.start_time = Instant::now()
                    .checked_sub(std::time::Duration::from_secs_f32(world_secs.clamp(0.0, 86_400.0)))
                    .unwrap_or_else(Instant::now);
                state.weather = weather;
                state.weather_cd = weather_cd;
                state.rain = rain;
            }
            NetMsg::WorldInit { player, .. } if r == ROLE_CLIENT => {
                // The host's own transform arrives here first; show them immediately.
                upsert_remote("host".to_string(), player.pos, player.yaw);
            }
            _ => {}
        }
    }
}

// Host: a client just joined — send world identity, every loaded chunk, and a first full snapshot.
fn on_join(state: &mut EngineState) {
    enqueue(NetMsg::WorldInit { seed: state.seed, name: String::new(), player: PlayerDto::of(&state.player) });
    let dim = state.dim;
    let chunks: Vec<(i32, i32, Vec<u8>)> = state.chunks.chunks_iter()
        .map(|(pos, chunk)| (pos.0, pos.1, crate::world::save::encode_chunk(chunk)))
        .collect();
    for (cx, cz, data) in chunks {
        enqueue(NetMsg::ChunkData { dim, cx, cz, data });
    }
    enqueue(NetMsg::MobSnapshot { mobs: state.mobs.iter().map(MobDto::of).collect() });
    enqueue(NetMsg::InventorySync { inv: state.inventory.to_json() });
    enqueue(world_clock_msg(state));
}

fn world_clock_msg(state: &EngineState) -> NetMsg {
    let world_secs = (Instant::now() - state.start_time).as_secs_f32();
    NetMsg::WorldClock { world_secs, weather: state.weather, weather_cd: state.weather_cd, rain: state.rain }
}

// ---- Outbound: enqueue what we owe the network, at the end of each tick ----

pub fn publish(state: &mut EngineState) {
    if !is_networked() { return; }
    let tick = PUBLISH_TICK.fetch_add(1, Ordering::SeqCst);

    // Local player transform (both roles), so peers can draw our avatar.
    if tick % TRANSFORM_EVERY == 0 {
        enqueue(NetMsg::PlayerTransform { device: local_device(), player: PlayerDto::of(&state.player) });
    }

    // Ship edited chunks (host: authoritative broadcast; client: upstream to host).
    let dirty: Vec<(i32, i32)> = edited().lock().map(|mut e| e.drain().collect()).unwrap_or_default();
    if !dirty.is_empty() {
        let dim = state.dim;
        for (cx, cz) in dirty {
            if let Some(chunk) = state.chunks.get(ChunkPos(cx, cz)) {
                enqueue(NetMsg::ChunkData { dim, cx, cz, data: crate::world::save::encode_chunk(chunk) });
            }
        }
    }

    // Host-only authoritative snapshots at a reduced rate.
    if role() == ROLE_HOST && tick % SNAPSHOT_EVERY == 0 {
        enqueue(NetMsg::MobSnapshot { mobs: state.mobs.iter().map(MobDto::of).collect() });
        if !state.projectiles.is_empty() {
            enqueue(NetMsg::ProjSnapshot { projs: state.projectiles.iter().map(ProjDto::of).collect() });
        }
        enqueue(world_clock_msg(state));
        enqueue(NetMsg::InventorySync { inv: state.inventory.to_json() });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn netmsg_roundtrips() {
        let msgs = vec![
            NetMsg::Join { device: "d1".into(), name: "Bob".into() },
            NetMsg::BlockEdit { dim: 0, x: 3, y: 64, z: -7, id: 12, meta: 0 },
            NetMsg::PlayerTransform {
                device: "d1".into(),
                player: PlayerDto { pos: Vec3::new(1.0, 2.0, 3.0), vel: Vec3::ZERO, yaw: 0.5, pitch: -0.2, on_ground: true, sneaking: false, gliding: false, health: 20.0 },
            },
            NetMsg::WorldClock { world_secs: 123.5, weather: 1, weather_cd: 40.0, rain: 0.8 },
        ];
        let s = serde_json::to_string(&msgs).unwrap();
        let back: Vec<NetMsg> = serde_json::from_str(&s).unwrap();
        assert_eq!(back.len(), msgs.len());
        // Spot-check a discriminant survived the round-trip.
        assert!(matches!(back[1], NetMsg::BlockEdit { x: 3, z: -7, id: 12, .. }));
    }

    #[test]
    fn push_and_drain() {
        set_role(ROLE_CLIENT);
        assert!(push_inbound_json(r#"{"type":"MobSnapshot","mobs":[]}"#));
        let drained: Vec<NetMsg> = inbox().lock().map(|mut q| std::mem::take(&mut *q)).unwrap();
        assert_eq!(drained.len(), 1);
        set_role(ROLE_OFFLINE);
    }
}
