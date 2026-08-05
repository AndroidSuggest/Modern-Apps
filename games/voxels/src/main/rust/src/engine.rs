use crate::world::{ChunkMap, block::Block};
use crate::player::Player;
use crate::inventory::Inventory;
use crate::entity::{Mob, MobKind, Particle, Projectile, ProjKind, build_entity_mesh, tick_particles, append_particles, append_projectiles};
use crate::world::mesher;
use crate::vulkan::context::{VulkanContext, ANativeWindow};
use crate::vulkan::renderer::VulkanRenderer;
use glam::{Mat4, Vec3, vec3};
use std::sync::{Mutex, OnceLock, atomic::{AtomicBool, Ordering}};
use std::time::Instant;

pub struct EngineState {
    pub chunks: ChunkMap,
    pub player: Player,
    pub inventory: Inventory,
    pub save_dir: String,
    pub renderer: Option<VulkanRenderer>,
    pub width: u32,
    pub height: u32,
    pub start_time: Instant,
    pub last_tick: Instant,
    pub window_ptr: Option<*mut ANativeWindow>,
    pub needs_resize: bool,
    pub running: bool,
    pub mobs: Vec<Mob>,
    pub spawn_timer: f32,
    pub spawn_rng: u32,
    pub respawn: Option<Vec3>,
    pub checkpoint_cd: f32,
    pub beacon_cd: f32,
    pub particles: Vec<Particle>,
    pub projectiles: Vec<Projectile>,
    // Dimensions: 0 overworld, 1 nether, 2 end. `chunks` is the active dimension; the others are
    // stashed here while inactive.
    pub dim: u8,
    pub seed: u32,
    pub stored: [Option<ChunkMap>; 3],
    pub dim_pos: [Vec3; 3],
    pub dim_visited: [bool; 3],
    pub portal_armed: bool, // must step out of a portal before it fires again (no auto-bounce)
    pub portal_charge: f32,
    pub end_dragon_dead: bool,
    pub nether_wither_dead: bool,
    // Night tracking: `was_night` edge-detects the night->day flip so `night_survived` only latches
    // after the player has actually lived through a full night.
    pub was_night: bool,
    pub night_survived: bool,
}

// The world clock is offset by 60s so a fresh world opens at midday (day_t = 0.5 -> sun overhead).
const DAY_CYCLE: f32 = 120.0;
impl EngineState {
    pub fn world_time(&self) -> f32 { 60.0 + self.start_time.elapsed().as_secs_f32() }
    pub fn day_t(&self) -> f32 { (self.world_time() / DAY_CYCLE) % 1.0 }
    // The sun is below the horizon at both ends of the cycle (day_t = 0 is midnight).
    pub fn is_night(&self) -> bool { let t = self.day_t(); t < 0.25 || t > 0.75 }
}

unsafe impl Send for EngineState {}

static ENGINE: OnceLock<Mutex<Option<EngineState>>> = OnceLock::new();
static INIT_DONE: AtomicBool = AtomicBool::new(false);

fn engine_lock() -> &'static Mutex<Option<EngineState>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

pub fn init_engine(files_dir: String, seed: u32) -> bool {
    let mut guard = engine_lock().lock().unwrap();
    if guard.is_some() { return true; }
    let save_dir = files_dir.clone();
    let player_save = crate::world::save::load_player(&save_dir);
    let had_save = player_save.is_some();
    let progress = player_save.as_ref().map(|ps| ps.progress.clone()).unwrap_or_default();
    let (px, py, pz, yaw, pitch, inv) = if let Some(ps) = &player_save {
        let inv = {
            let mut inv = Inventory::default();
            inv.selected = ps.inventory.selected.min(8);
            for (i, slot) in ps.inventory.slots.iter().enumerate().take(inv.slots.len()) {
                inv.slots[i].id = slot.id;
                inv.slots[i].count = slot.count;
            }
            for (i, slot) in progress.armor.iter().enumerate().take(inv.armor.len()) {
                inv.armor[i] = crate::inventory::InvSlot { id: slot.id, count: slot.count };
            }
            inv.placed = ps.stats.placed;
            inv.broken = ps.stats.broken;
            inv
        };
        (ps.x, ps.y, ps.z, ps.yaw, ps.pitch, inv)
    } else {
        (0.0, 80.0, 0.0, 0.0, 0.0, Inventory::default())
    };

    let mut player = Player::new(px, py, pz);
    player.yaw = yaw;
    player.pitch = pitch;
    if had_save {
        player.max_health = progress.max_health.clamp(crate::player::MIN_MAX_HEALTH, crate::player::CAP_MAX_HEALTH);
        player.health = player.max_health;
    }

    let dim = if progress.dim < 3 { progress.dim } else { 0 };
    let mut chunks = ChunkMap::new_dim(seed, dim_dir(&save_dir, dim), dim);
    chunks.ensure_radius(px as i32, pz as i32);

    if !had_save {
        for y in (0..128).rev() {
            if chunks.get_block_world(0, y, 0) != 0 && Block::from_id(chunks.get_block_world(0, y, 0)).is_solid() {
                player.pos.y = y as f32 + 1.1;
                break;
            }
        }
    }

    let mut dim_pos = [Vec3::ZERO; 3];
    for (i, p) in progress.dim_pos.iter().enumerate().take(3) { dim_pos[i] = vec3(p[0], p[1], p[2]); }
    let mut dim_visited = [false; 3];
    for (i, v) in progress.dim_visited.iter().enumerate().take(3) { dim_visited[i] = *v; }
    dim_visited[dim as usize] = true;
    // Rewind the clock so the world resumes at the time of day it was saved at.
    let start_time = Instant::now()
        .checked_sub(std::time::Duration::from_secs_f32(progress.world_secs.clamp(0.0, 86_400.0)))
        .unwrap_or_else(Instant::now);

    *guard = Some(EngineState {
        chunks,
        player,
        inventory: inv,
        save_dir,
        renderer: None,
        width: 0,
        height: 0,
        start_time,
        last_tick: Instant::now(),
        window_ptr: None,
        needs_resize: false,
        running: true,
        mobs: Vec::new(),
        spawn_timer: 2.0,
        spawn_rng: seed ^ 0x9E3779B9,
        respawn: progress.respawn.map(|p| vec3(p[0], p[1], p[2])),
        checkpoint_cd: 0.0,
        beacon_cd: 0.0,
        particles: Vec::new(),
        projectiles: Vec::new(),
        dim,
        seed,
        stored: [None, None, None],
        dim_pos,
        dim_visited,
        portal_armed: true,
        portal_charge: 0.0,
        end_dragon_dead: progress.end_dragon_dead,
        nether_wither_dead: progress.nether_wither_dead,
        was_night: false,
        night_survived: player_save.as_ref().map(|ps| ps.stats.night_seen).unwrap_or(false),
    });
    INIT_DONE.store(true, Ordering::SeqCst);
    true
}

pub fn with_engine<F, R>(f: F) -> Option<R>
where F: FnOnce(&mut EngineState) -> R {
    let mut guard = engine_lock().lock().ok()?;
    let state = guard.as_mut()?;
    Some(f(state))
}

// Non-blocking variant: if the render thread currently holds the engine lock (it holds it for the
// whole frame, including the GPU fence wait), this returns None instead of stalling the caller.
// The Compose UI polls read-only JSON on the main thread, so it must never block on the 3D frame.
pub fn with_engine_try<F, R>(f: F) -> Option<R>
where F: FnOnce(&mut EngineState) -> R {
    let mut guard = engine_lock().try_lock().ok()?;
    let state = guard.as_mut()?;
    Some(f(state))
}

pub fn destroy_engine() {
    let mut guard = match engine_lock().lock() { Ok(g) => g, Err(_) => return, };
    if let Some(mut state) = guard.take() {
        state.dim_pos[state.dim as usize] = state.player.pos;
        let ps = crate::world::save::PlayerSave {
            x: state.player.pos.x,
            y: state.player.pos.y,
            z: state.player.pos.z,
            yaw: state.player.yaw,
            pitch: state.player.pitch,
            inventory: crate::world::save::InventorySave {
                selected: state.inventory.selected,
                slots: state.inventory.slots.iter().map(|s| crate::world::save::InvSlotSave{ id: s.id, count: s.count }).collect(),
            },
            stats: crate::world::save::StatsSave {
                placed: state.inventory.placed,
                broken: state.inventory.broken,
                walked: state.player.walk_dist as i32,
                night_seen: state.night_survived,
            },
            progress: crate::world::save::ProgressSave {
                armor: state.inventory.armor.iter().map(|s| crate::world::save::InvSlotSave{ id: s.id, count: s.count }).collect(),
                max_health: state.player.max_health,
                dim: state.dim,
                dim_pos: state.dim_pos.iter().map(|p| [p.x, p.y, p.z]).collect(),
                dim_visited: state.dim_visited.to_vec(),
                respawn: state.respawn.map(|p| [p.x, p.y, p.z]),
                end_dragon_dead: state.end_dragon_dead,
                nether_wither_dead: state.nether_wither_dead,
                world_secs: state.start_time.elapsed().as_secs_f32(),
            },
        };
        let _ = crate::world::save::save_player(&state.save_dir, &ps);
        state.chunks.save_all();
        // Persist the stashed dimensions too (each writes to its own save subdir).
        for m in state.stored.iter().flatten() { m.save_all(); }
        if let Some(mut renderer) = state.renderer.take() {
            unsafe {
                renderer.destroy();
                renderer.ctx.destroy();
            }
            if let Some(win) = state.window_ptr {
                unsafe { crate::vulkan::context::ANativeWindow_release(win); }
            }
        }
        INIT_DONE.store(false, Ordering::SeqCst);
    }
}

pub fn create_renderer(window: *mut ANativeWindow, width: i32, height: i32) -> Result<(), String> {
    let w = width.max(1) as u32;
    let h = height.max(1) as u32;
    with_engine(|state| {
        state.window_ptr = Some(window);
        state.width = w;
        state.height = h;
        let ctx = unsafe { VulkanContext::new(window, width, height)? };
        let renderer = unsafe { VulkanRenderer::new(ctx, w, h)? };
        state.renderer = Some(renderer);
        state.chunks.ensure_radius(state.player.pos.x as i32, state.player.pos.z as i32);
        Ok::<(), String>(())
    }).unwrap_or(Err("engine not init".into()))?;
    rebuild_all_meshes();
    Ok(())
}

pub fn rebuild_all_meshes() {
    with_engine(|state| {
        if state.renderer.is_none() { return; }
        let positions: Vec<_> = state.chunks.chunks_iter().map(|(pos, _)| *pos).collect();
        for pos in positions { rebuild_chunk_meshes(state, pos); }
    });
}

pub fn rebuild_chunk_meshes(state: &mut EngineState, chunk_pos: crate::world::chunk::ChunkPos) {
    if state.chunks.get(chunk_pos).is_none() { return; }
    let meshes = {
        let chunk = state.chunks.get(chunk_pos).unwrap();
        let map_ptr = &state.chunks as *const ChunkMap;
        let closure = move |wx: i32, wy: i32, wz: i32| -> u8 {
            unsafe { (*map_ptr).get_block_world(wx, wy, wz) }
        };
        let tint = move |wx: i32, wz: i32| -> [f32;3] {
            unsafe { (*map_ptr).grass_tint(wx, wz) }
        };
        mesher::mesh_chunk(chunk, &closure, &tint)
    };
    if let Some(renderer) = state.renderer.as_mut() {
        for (sec_idx, mesh_opt) in meshes.into_iter().enumerate() {
            if let Some(mesh) = mesh_opt {
                if mesh.is_empty() { continue; }
                unsafe { renderer.enqueue_mesh(chunk_pos.0, sec_idx as i32, chunk_pos.1, Some(mesh)); }
            } else {
                unsafe { renderer.enqueue_mesh(chunk_pos.0, sec_idx as i32, chunk_pos.1, None); }
            }
        }
    }
}

pub fn resize_renderer(width: i32, height: i32) {
    with_engine(|state| {
        state.width = width.max(1) as u32;
        state.height = height.max(1) as u32;
        state.needs_resize = true;
    });
}

pub fn destroy_renderer() {
    with_engine(|state| {
        if let Some(mut r) = state.renderer.take() {
            unsafe { r.destroy(); r.ctx.destroy(); }
        }
        if let Some(win) = state.window_ptr.take() {
            unsafe { crate::vulkan::context::ANativeWindow_release(win); }
        }
    });
}

pub fn tick_and_render() {
    let now = Instant::now();
    with_engine(|state| {
        let dt = (now - state.last_tick).as_secs_f32().clamp(0.0, 0.05);
        state.last_tick = now;
        if !state.running { return; }

        let input = crate::input::snapshot_and_clear_look();
        if input.toggle_fly { state.player.flying = !state.player.flying; }

        // Rate-based look: (curved) stick displacement -> angular velocity (rad/s at full deflection).
        let look_speed = 3.2;
        state.player.yaw += input.look_yaw_rate * look_speed * dt;
        state.player.pitch += input.look_pitch_rate * look_speed * dt;
        state.player.pitch = state.player.pitch.clamp(-1.55, 1.55);

        let mut input_mut = input;
        let move_len = (input.move_forward*input.move_forward + input.move_right*input.move_right).sqrt();
        if move_len > 0.9 { input_mut.sprint = true; }

        // Latch "survived a night" on the night -> day flip, as long as the player is alive and in
        // the overworld (the Nether and End have no day cycle).
        let night_now = state.is_night();
        if state.was_night && !night_now && !state.player.dead && state.dim == 0 { state.night_survived = true; }
        state.was_night = night_now;

        // Elytra equipped in the chest slot enables gliding.
        state.player.elytra = state.inventory.armor[1].id == 188;
        state.player.tick(dt, &input_mut, &state.chunks);
        state.player.tick_status(dt);
        // Lava burns (Fire Resistance negates it).
        {
            let p = state.player.pos;
            let feet = state.chunks.get_block_world(p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            let head = state.chunks.get_block_world(p.x.floor() as i32, (p.y + 1.0).floor() as i32, p.z.floor() as i32);
            if (feet == 84 || head == 84) && !state.player.has_effect(crate::item::Effect::FireResistance) {
                state.player.health -= 6.0 * dt;
                if state.player.health <= 0.0 { state.player.health = 0.0; state.player.dead = true; }
            }
        }
        // Portal traversal: step out to re-arm, dwell inside to travel.
        {
            let p = state.player.pos;
            let bl = state.chunks.get_block_world(p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            if bl != 86 && bl != 87 {
                state.portal_armed = true;
                state.portal_charge = 0.0;
            } else if state.portal_armed {
                state.portal_charge += dt;
                if state.portal_charge > 0.4 {
                    let target = if bl == 86 { if state.dim == 1 { 0 } else { 1 } } else if state.dim == 2 { 0 } else { 2 };
                    switch_dimension(state, target);
                }
            }
        }

        // Warding stone checkpoint: standing near one sets respawn + slowly refills Estus (bonfire).
        state.checkpoint_cd = (state.checkpoint_cd - dt).max(0.0);
        {
            let p = state.player.pos;
            let (bx, by, bz) = (p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            let mut near = None;
            'scan: for dy in -2..=2 { for dx in -4..=4 { for dz in -4..=4 {
                if state.chunks.get_block_world(bx + dx, by + dy, bz + dz) == 81 {
                    near = Some(vec3((bx + dx) as f32 + 0.5, (by + dy) as f32 + 1.0, (bz + dz) as f32 + 0.5));
                    break 'scan;
                }
            }}}
            if let Some(cp) = near {
                state.respawn = Some(cp);
                if state.checkpoint_cd <= 0.0 {
                    state.checkpoint_cd = 2.0;
                    let have: i32 = state.inventory.slots.iter().filter(|s| s.id == 128).map(|s| s.count).sum();
                    if have < 4 { state.inventory.add_block(128); }
                    state.player.add_effect(crate::item::Effect::Regeneration, 2.5, 0);
                }
            }
        }

        // Beacon: a lit beacon standing on a pyramid of mineral blocks projects buffs to nearby players.
        // Scan (throttled) for the nearest beacon around the player, grade its pyramid, and grant effects.
        state.beacon_cd = (state.beacon_cd - dt).max(0.0);
        if state.beacon_cd <= 0.0 {
            state.beacon_cd = 0.5;
            let p = state.player.pos;
            let (bx, by, bz) = (p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            let mut best: Option<(i32, i32)> = None; // (tier, dist2) of the nearest in-range beacon
            for dy in -12..=12 { for dx in -16..=16 { for dz in -16..=16 {
                if state.chunks.get_block_world(bx + dx, by + dy, bz + dz) == 88 {
                    let tier = beacon_tier(&state.chunks, bx + dx, by + dy, bz + dz);
                    if tier == 0 { continue; }
                    let d2 = dx * dx + dz * dz;
                    let range = tier * 10 + 10;
                    if d2 <= range * range && best.map_or(true, |b| d2 < b.1) {
                        best = Some((tier, d2));
                    }
                }
            }}}
            if let Some((tier, _)) = best {
                use crate::item::Effect::*;
                let e = &mut state.player;
                e.add_effect(Speed, 6.0, 0);
                if tier >= 2 { e.add_effect(Haste, 6.0, 0); }
                if tier >= 3 { e.add_effect(Resistance, 6.0, 0); }
                if tier >= 4 { e.add_effect(Strength, 6.0, 0); e.add_effect(Regeneration, 3.0, 0); e.add_effect(JumpBoost, 6.0, 0); }
            }
        }

        let px = state.player.pos.x as i32;
        let pz = state.player.pos.z as i32;
        state.chunks.ensure_radius(px, pz);

        let dirty_positions: Vec<_> = state.chunks.chunks_iter().filter(|(_, c)| c.mesh_dirty).map(|(p,_)| *p).take(4).collect();
        for pos in dirty_positions {
            if let Some(ch) = state.chunks.get_mut(pos) { ch.mesh_dirty = false; }
            rebuild_chunk_meshes(state, pos);
        }

        // --- Mobs: AI/physics tick, spawn/despawn near the player. ---
        let player_pos = state.player.pos;
        {
            let chunks = &state.chunks;
            let solid = |x: i32, y: i32, z: i32| { let id = chunks.get_block_world(x, y, z); id != 0 && Block::from_id(id).is_solid() };
            for m in state.mobs.iter_mut() { m.tick(dt, player_pos, &solid); }
        }
        // Mob melee contact damage + creeper fuse + ranged fire.
        let mut incoming = 0.0f32;
        let mut explosions: Vec<Vec3> = Vec::new();
        let mut new_shots: Vec<Projectile> = Vec::new();
        let eye = player_pos + vec3(0.0, 1.2, 0.0);
        for m in state.mobs.iter_mut() {
            m.attack_cd = (m.attack_cd - dt).max(0.0);
            let d = (m.pos - player_pos).length();
            let ranged = matches!(m.kind, MobKind::Blaze | MobKind::Shulker | MobKind::Ghast);
            let max_range = if m.kind == MobKind::Ghast { 42.0 } else { 30.0 };
            if m.kind == MobKind::Creeper {
                if d < 3.2 { m.fuse += dt; if m.fuse >= 1.4 { explosions.push(m.pos); m.health = 0.0; } }
                else { m.fuse = (m.fuse - dt * 0.6).max(0.0); }
            } else if ranged && d > 3.0 && d < max_range && m.attack_cd <= 0.0 {
                // Fire a projectile at the player's chest.
                m.attack_cd = match m.kind { MobKind::Blaze => 1.6, MobKind::Ghast => 3.0, _ => 2.2 };
                let origin = m.pos + vec3(0.0, m.kind.height() * 0.6, 0.0);
                let dir = (eye - origin).normalize_or_zero();
                let (kind, spd, dmg, explosive) = match m.kind {
                    MobKind::Blaze => (ProjKind::Fireball, 16.0, 5.0, false),
                    MobKind::Ghast => (ProjKind::Fireball, 12.0, 7.0, true),
                    _ => (ProjKind::ShulkerBullet, 9.0, 4.0, false),
                };
                new_shots.push(Projectile { pos: origin, vel: dir * spd, life: 5.0, kind, from_player: false, damage: dmg, explosive });
            } else if m.kind.is_boss() && d < 5.0 && m.attack_cd <= 0.0 {
                m.attack_cd = 1.0; incoming += m.kind.contact_damage();
            } else if m.kind.hostile() && !m.kind.is_boss() && !ranged && d < 1.7 && m.attack_cd <= 0.0 {
                m.attack_cd = 0.8; incoming += m.kind.contact_damage();
            }
        }
        state.projectiles.extend(new_shots);
        tick_projectiles(state, dt);
        if incoming > 0.0 { hurt_player(state, incoming); }
        for c in explosions { explode(state, c, 3.0); }
        // Slain Ender Dragon: mark defeated + a victory burst of particles.
        if let Some(dpos) = state.mobs.iter().find(|m| m.kind == MobKind::Dragon && m.health <= 0.0).map(|m| m.pos) {
            state.end_dragon_dead = true;
            spawn_particles(&mut state.spawn_rng, &mut state.particles, dpos, 60, [0.7, 0.3, 0.95], 8.0, 1.4, 0.4);
        }
        // Slain Wither: mark defeated + drop the Nether Star (via loot) + a dark burst.
        if let Some(wpos) = state.mobs.iter().find(|m| m.kind == MobKind::Wither && m.health <= 0.0).map(|m| m.pos) {
            state.nether_wither_dead = true;
            spawn_particles(&mut state.spawn_rng, &mut state.particles, wpos, 60, [0.15, 0.15, 0.2], 8.0, 1.4, 0.4);
        }
        // Remove dead mobs and auto-collect their drops.
        let mut loot: Vec<u8> = Vec::new();
        state.mobs.retain(|m| if m.health <= 0.0 { loot.extend_from_slice(m.kind.loot()); false } else { true });
        for id in loot { state.inventory.add_block(id); }
        state.mobs.retain(|m| (m.pos - player_pos).length() < 96.0 && m.pos.y > -8.0);
        state.spawn_timer -= dt;
        if state.spawn_timer <= 0.0 {
            state.spawn_timer = 2.5;
            if state.mobs.len() < 22 {
                use crate::world::chunk::CHUNK_HEIGHT;
                let mut rng = state.spawn_rng;
                let mut rand = |r: &mut u32| { let mut x = *r; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *r = x; (x >> 8) as f32 / 16_777_216.0 };
                let ang = rand(&mut rng) * std::f32::consts::TAU;
                let dist = 24.0 + rand(&mut rng) * 20.0;
                let sx = player_pos.x + ang.cos() * dist;
                let sz = player_pos.z + ang.sin() * dist;
                let (bx, bz) = (sx.floor() as i32, sz.floor() as i32);
                let mut gy = None;
                for y in (2..CHUNK_HEIGHT as i32).rev() {
                    let id = state.chunks.get_block_world(bx, y, bz);
                    if id != 0 && Block::from_id(id).is_solid() { gy = Some(y); break; }
                }
                if let Some(gy) = gy {
                    let night = state.is_night();
                    let kind = if state.dim == 1 {
                        // Nether: hostile natives only (Ghasts are rarer floating threats).
                        match (rand(&mut rng) * 8.0) as u32 { 0 => MobKind::Ghast, 1 | 2 => MobKind::Blaze, 3 | 4 => MobKind::WitherSkeleton, _ => MobKind::Zombie }
                    } else if state.dim == 2 {
                        // Sparse End hostiles: End-city Shulker guardians + wandering wither skeletons.
                        if rand(&mut rng) < 0.4 { MobKind::Shulker } else { MobKind::WitherSkeleton }
                    } else if night {
                        if rand(&mut rng) < 0.5 { MobKind::Zombie } else { MobKind::Creeper }
                    } else if rand(&mut rng) < 0.18 {
                        MobKind::Villager
                    } else {
                        match (rand(&mut rng) * 4.0) as u32 { 0 => MobKind::Pig, 1 => MobKind::Cow, 2 => MobKind::Sheep, _ => MobKind::Chicken }
                    };
                    let pos = vec3(sx, gy as f32 + 1.0, sz);
                    let seed = rng ^ (bx as u32).wrapping_mul(2654435761) ^ (bz as u32).wrapping_mul(40503);
                    state.mobs.push(Mob::new(kind, pos, seed));
                }
                state.spawn_rng = rng;
            }
        }
        // Death: burn one heart of max HP (floored) and respawn at world spawn — the "lives" system.
        if state.player.dead {
            use crate::world::chunk::CHUNK_HEIGHT;
            state.player.max_health = (state.player.max_health - 2.0).max(crate::player::MIN_MAX_HEALTH);
            state.player.pos = if let Some(rp) = state.respawn {
                rp
            } else {
                state.chunks.ensure_radius(0, 0);
                let mut ty = 80;
                for y in (1..CHUNK_HEIGHT as i32).rev() {
                    let id = state.chunks.get_block_world(0, y, 0);
                    if id != 0 && Block::from_id(id).is_solid() { ty = y; break; }
                }
                vec3(0.5, ty as f32 + 1.1, 0.5)
            };
            state.player.vel = Vec3::ZERO;
            state.player.health = state.player.max_health;
            state.player.absorption = 0.0;
            state.player.effects.clear();
            state.player.dead = false;
            state.player.air_max_y = state.player.pos.y;
            state.mobs.clear();
        }
        // Elytra glide vapor trail: white streaks off both shoulders (the visible "wings" in 1st person).
        if state.player.gliding {
            let base = state.player.pos + vec3(0.0, 1.25, 0.0);
            let r = state.player.right();
            for &side in &[-1.0f32, 1.0] {
                if state.particles.len() < 400 {
                    state.particles.push(Particle { pos: base + r * (0.55 * side), vel: Vec3::ZERO, life: 0.5, max_life: 0.5, size: 0.09, color: [0.88, 0.95, 1.0] });
                }
            }
        }
        tick_particles(&mut state.particles, dt);
        let (mut entity_verts, mut entity_indices) = build_entity_mesh(&state.mobs);
        {
            let right = state.player.right();
            append_particles(&mut entity_verts, &mut entity_indices, &state.particles, right, Vec3::Y);
            append_projectiles(&mut entity_verts, &mut entity_indices, &state.projectiles, right, Vec3::Y);
        }

        // Start at midday (day_t=0.5 -> sun overhead) so the world is lit when the app opens.
        let time = state.world_time();
        if let Some(renderer) = state.renderer.as_mut() {
            if state.needs_resize {
                let w = state.width; let h = state.height;
                unsafe { let _ = renderer.resize(w, h); }
                state.needs_resize = false;
            }
            let eye = state.player.eye_pos();
            let center = eye + state.player.forward();
            let up = Vec3::Y;
            let view = Mat4::look_at_rh(eye, center, up);
            // Aspect + pre-rotation come from the swapchain's surface transform (Android portrait-native
            // panels present landscape via ROTATE_90/270, with images in native orientation).
            let (swap_aspect, pre_rot_angle) = renderer.swapchain.pre_rotation();
            let ext = renderer.swapchain.extent;
            let (ew, eh) = (ext.width.max(1) as f32, ext.height.max(1) as f32);
            let aspect = if swap_aspect { eh / ew } else { ew / eh };
            let proj = Mat4::perspective_rh(70f32.to_radians(), aspect, 0.1, 500.0);
            let vulkan_correction = Mat4::from_cols_array(&[1.0,0.0,0.0,0.0, 0.0,-1.0,0.0,0.0, 0.0,0.0,0.5,0.0, 0.0,0.0,0.5,1.0]);
            let view_proj = Mat4::from_rotation_z(pre_rot_angle) * vulkan_correction * proj * view;
            // Start at midday (day_t=0.5 -> sun overhead) so the world is lit when the app opens.
            let eb = (eye.x.floor() as i32, eye.y.floor() as i32, eye.z.floor() as i32);
            let underwater = if state.chunks.get_block_world(eb.0, eb.1, eb.2) == 12 { 1.0 } else { 0.0 };
            let nv = if state.player.night_vision() { 1.0 } else { 0.0 };
            let dim = state.dim;
            unsafe {
                renderer.update_ubo(view_proj, state.player.pos, time, underwater, nv, dim);
                renderer.upload_entity_mesh(&entity_verts, &entity_indices);
                let _ = renderer.draw_frame();
            }
        }
        publish_ui(state);
    });
}

// UI state caches. The render thread PUBLISHES fresh JSON into these every tick (while it holds the
// engine lock); the UI getters just read the cache. This avoids the UI ever contending with the render
// thread for the engine lock (which it holds ~continuously), so inventory/debug update every frame.
static DEBUG_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static INV_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static STATS_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static HEALTH_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
fn cref(c: &'static OnceLock<Mutex<String>>, default: &str) -> &'static Mutex<String> { c.get_or_init(|| Mutex::new(default.to_string())) }

// Called from the render tick (holds the engine lock) to refresh the UI caches.
fn publish_ui(state: &EngineState) {
    let fps = if let Some(r) = &state.renderer {
        let elapsed = (Instant::now() - state.start_time).as_secs_f32();
        if elapsed>0.1 { r.frame_count as f32 / elapsed } else { 0.0 }
    } else { 0.0 };
    let debug = serde_json::json!({
        "fps": format!("{:.1}", fps),
        "pos": format!("{:.1},{:.1},{:.1}", state.player.pos.x, state.player.pos.y, state.player.pos.z),
        "yaw": format!("{:.1}", state.player.yaw.to_degrees()),
        "chunks": state.chunks.len(),
        "flying": state.player.flying,
        "on_ground": state.player.on_ground,
        "time": format!("{:.1}s", (Instant::now() - state.start_time).as_secs_f32()),
        "meshes": state.renderer.as_ref().map(|r| r.gpu_meshes.len()).unwrap_or(0),
        "mobs": state.mobs.len(),
        "drawn": state.renderer.as_ref().map(|r| r.drawn_sections).unwrap_or(0),
        "gpu": state.renderer.as_ref().map(|r| {
            let p = r.pass_ms;
            format!("sh{:.1} main{:.1} bloom{:.1} comp{:.1}", p[0], p[1], p[2], p[3])
        }).unwrap_or_default(),
    }).to_string();
    let stats = serde_json::json!({
        "placed": state.inventory.placed, "broken": state.inventory.broken,
        "walked": state.player.walk_dist as i32, "night": state.night_survived,
    }).to_string();
    let inv = state.inventory.to_json();
    let effects: Vec<_> = state.player.effects.iter().map(|e| serde_json::json!({"k": e.kind.key(), "amp": e.amp, "t": e.secs.ceil() as i32})).collect();
    let estus: i32 = state.inventory.slots.iter().filter(|s| s.id == 128).map(|s| s.count).sum();
    let boss_mob = state.mobs.iter().find(|m| m.kind.is_boss());
    let boss: f32 = boss_mob.map(|m| (m.health / m.kind.max_health()).clamp(0.0, 1.0)).unwrap_or(-1.0);
    let boss_name = match boss_mob.map(|m| m.kind) { Some(MobKind::Dragon) => "Ender Dragon", Some(MobKind::Wither) => "The Wither", _ => "" };
    let health = serde_json::json!({
        "hp": state.player.health, "max": state.player.max_health, "absorb": state.player.absorption,
        "dead": state.player.dead, "estus": estus, "effects": effects, "boss": boss, "bossName": boss_name,
        "elytra": state.player.elytra, "gliding": state.player.gliding,
    }).to_string();
    if let Ok(mut c) = cref(&DEBUG_CACHE, "{}").lock() { *c = debug; }
    if let Ok(mut c) = cref(&STATS_CACHE, "{}").lock() { *c = stats; }
    if let Ok(mut c) = cref(&INV_CACHE, r#"{"selected":0,"slots":[]}"#).lock() { *c = inv; }
    if let Ok(mut c) = cref(&HEALTH_CACHE, "{}").lock() { *c = health; }
}

pub fn get_debug_json() -> String { cref(&DEBUG_CACHE, r#"{"error":"no engine"}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_inventory_json() -> String { cref(&INV_CACHE, r#"{"selected":0,"slots":[]}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| r#"{"selected":0,"slots":[]}"#.into()) }
pub fn get_stats_json() -> String { cref(&STATS_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_health_json() -> String { cref(&HEALTH_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }

pub fn inventory_move(from: usize, to: usize) { with_engine(|s| s.inventory.move_item(from, to)); }
pub fn inventory_give(id: u8) { with_engine(|s| s.inventory.give(id)); }
pub fn inventory_craft(recipe: usize) -> bool { with_engine(|s| s.inventory.craft(recipe)).unwrap_or(false) }
pub fn do_trade(idx: usize) -> bool { with_engine(|s| s.inventory.trade(idx)).unwrap_or(false) }
pub fn get_trades_json() -> String {
    let items: Vec<_> = crate::inventory::TRADES.iter()
        .map(|(c, cn, g, gn)| serde_json::json!({"cost": c, "costN": cn, "give": g, "giveN": gn}))
        .collect();
    serde_json::json!(items).to_string()
}
pub fn get_recipes_json() -> String {
    let items: Vec<_> = crate::inventory::RECIPES.iter()
        .map(|(i1, c1, i2, c2, oid, oc)| serde_json::json!({"in": i1, "inN": c1, "in2": i2, "in2N": c2, "out": oid, "outN": oc}))
        .collect();
    serde_json::json!(items).to_string()
}

// Build a world-space pick ray from a tap at pixel (px, py) using a pinhole model matching the
// renderer's 70deg vertical FOV. Avoids the swapchain/matrix Y-flip quirks entirely.
fn screen_ray(state: &EngineState, px: f32, py: f32) -> (Vec3, Vec3) {
    let eye = state.player.eye_pos();
    let forward = state.player.forward();
    let world_up = Vec3::Y;
    let right = forward.cross(world_up).normalize_or_zero();
    let up = right.cross(forward).normalize_or_zero();
    let w = state.width.max(1) as f32;
    let h = state.height.max(1) as f32;
    let aspect = w / h;
    let thf_y = (70f32.to_radians() * 0.5).tan();
    let thf_x = thf_y * aspect;
    let ndc_x = (px / w) * 2.0 - 1.0;
    let ndc_y = (py / h) * 2.0 - 1.0; // screen y grows downward
    let dir = (forward + right * (ndc_x * thf_x) + up * (-ndc_y * thf_y)).normalize_or_zero();
    (eye, dir)
}

fn dim_dir(base: &str, dim: u8) -> String {
    match dim { 1 => format!("{}/nether", base), 2 => format!("{}/end", base), _ => base.to_string() }
}

// Travel to another dimension: stash the current world, swap in the target, place the player on a
// safe landing (or their saved position), and rebuild all chunk meshes (mesh keys are per-coord).
// A beacon's power tier = how many complete pyramid layers of mineral blocks sit beneath it.
// Layer k (k=1..4) at depth k must be a full (2k+1)x(2k+1) square of iron/diamond/emerald blocks
// centred under the beacon. Tier stops at the first incomplete layer.
fn beacon_tier(chunks: &ChunkMap, x: i32, y: i32, z: i32) -> i32 {
    let is_mineral = |id: u8| matches!(id, 23 | 24 | 25);
    let mut tier = 0;
    for k in 1..=4i32 {
        let mut full = true;
        'layer: for dx in -k..=k { for dz in -k..=k {
            if !is_mineral(chunks.get_block_world(x + dx, y - k, z + dz)) { full = false; break 'layer; }
        }}
        if full { tier = k; } else { break; }
    }
    tier
}

fn switch_dimension(state: &mut EngineState, target: u8) {
    if target == state.dim { return; }
    let from = state.dim;
    state.dim_pos[from as usize] = state.player.pos;
    let placeholder = ChunkMap::new_dim(state.seed, dim_dir(&state.save_dir, target), target);
    let cur = std::mem::replace(&mut state.chunks, placeholder);
    state.stored[from as usize] = Some(cur);
    if let Some(m) = state.stored[target as usize].take() { state.chunks = m; }
    state.dim = target;

    let prev = state.player.pos;
    let (mut ax, mut az) = (prev.x, prev.z);
    if target == 1 { ax /= 8.0; az /= 8.0; }               // nether is 8x compressed
    else if target == 0 && from == 1 { ax *= 8.0; az *= 8.0; }
    if target == 2 { ax = 0.0; az = 0.0; }                 // end: central island
    state.chunks.ensure_radius(ax as i32, az as i32);

    if state.dim_visited[target as usize] {
        state.player.pos = state.dim_pos[target as usize];
    } else {
        let (bx, bz) = (ax as i32, az as i32);
        let py: i32 = match target { 1 => 42, 2 => 66, _ => 70 };
        let mat = if target == 2 { 85 } else { 32 };
        for dx in -2..=2 { for dz in -2..=2 {
            state.chunks.set_block_world(bx + dx, py - 1, bz + dz, mat);
            state.chunks.set_block_world(bx + dx, py, bz + dz, 0);
            state.chunks.set_block_world(bx + dx, py + 1, bz + dz, 0);
            state.chunks.set_block_world(bx + dx, py + 2, bz + dz, 0);
        }}
        // Build a return portal beside the landing so the player can travel back.
        if target == 1 || target == 0 {
            build_nether_portal(&mut state.chunks, bx + 2, py, bz);
        } else if target == 2 {
            state.chunks.set_block_world(bx + 2, py - 1, bz, 85);
            state.chunks.set_block_world(bx + 2, py, bz, 87); // end return portal
        }
        state.player.pos = vec3(bx as f32 + 0.5, py as f32 + 0.2, bz as f32 + 0.5);
        state.dim_pos[target as usize] = state.player.pos;
    }
    state.dim_visited[target as usize] = true;
    state.portal_armed = false;
    state.portal_charge = 0.0;
    state.player.vel = Vec3::ZERO;
    state.player.air_max_y = state.player.pos.y;
    state.mobs.clear();
    state.particles.clear();
    state.projectiles.clear();
    // Summon the Ender Dragon on arrival in the End (once).
    if target == 2 && !state.end_dragon_dead {
        state.mobs.push(Mob::new(MobKind::Dragon, vec3(0.0, 86.0, 30.0), 0xD2A6));
    }
    // Summon the Wither on arrival in the Nether (once).
    if target == 1 && !state.nether_wither_dead {
        let wpos = vec3(state.player.pos.x, state.player.pos.y + 12.0, state.player.pos.z - 16.0);
        state.mobs.push(Mob::new(MobKind::Wither, wpos, 0x175E));
    }

    if let Some(r) = state.renderer.as_mut() { unsafe { r.clear_meshes(); } }
    let positions: Vec<_> = state.chunks.chunks_iter().map(|(p, _)| *p).collect();
    for pos in positions { rebuild_chunk_meshes(state, pos); }
}

// Build a small obsidian nether-portal frame (interior filled with portal blocks) in the X-Y plane.
fn build_nether_portal(chunks: &mut ChunkMap, x: i32, y: i32, z: i32) {
    for gx in -1..=2 { for gy in -1..=3 {
        let border = gx == -1 || gx == 2 || gy == -1 || gy == 3;
        chunks.set_block_world(x + gx, y + gy, z, if border { 78 } else { 86 });
    }}
}

// Flood the air interior enclosed by obsidian in a vertical plane (axis 0 = X-Y, 1 = Z-Y).
fn portal_interior(chunks: &ChunkMap, sx: i32, sy: i32, sz: i32, axis: u8) -> Option<Vec<(i32, i32, i32)>> {
    if chunks.get_block_world(sx, sy, sz) != 0 { return None; }
    let mut region: Vec<(i32, i32, i32)> = Vec::new();
    let mut stack = vec![(sx, sy, sz)];
    while let Some(c) = stack.pop() {
        if region.contains(&c) { continue; }
        if chunks.get_block_world(c.0, c.1, c.2) != 0 { continue; }
        if region.len() > 18 { return None; } // not enclosed
        region.push(c);
        let nbrs = if axis == 0 {
            [(c.0 + 1, c.1, c.2), (c.0 - 1, c.1, c.2), (c.0, c.1 + 1, c.2), (c.0, c.1 - 1, c.2)]
        } else {
            [(c.0, c.1, c.2 + 1), (c.0, c.1, c.2 - 1), (c.0, c.1 + 1, c.2), (c.0, c.1 - 1, c.2)]
        };
        for n in nbrs {
            let nb = chunks.get_block_world(n.0, n.1, n.2);
            if nb == 0 { if !region.contains(&n) && !stack.contains(&n) { stack.push(n); } }
            else if nb != 78 { return None; } // border must be obsidian
        }
    }
    if (2..=15).contains(&region.len()) { Some(region) } else { None }
}

// Ignite a nether portal by tapping its obsidian frame: fill the enclosed interior with portal blocks.
fn light_portal(state: &mut EngineState, x: i32, y: i32, z: i32) -> bool {
    for axis in 0..2u8 {
        if let Some(cells) = portal_interior(&state.chunks, x, y + 1, z, axis) {
            for (cx, cy, cz) in &cells {
                state.chunks.set_block_world(*cx, *cy, *cz, 86);
                mark_neighbors_dirty(state, *cx, *cz);
            }
            return true;
        }
    }
    false
}

// Loot a chest deterministically from its position (each chest is consumed after one open).
fn loot_chest(state: &mut EngineState, x: i32, y: i32, z: i32) {
    let mut r = ((x as u64).wrapping_mul(73856093) ^ (y as u64).wrapping_mul(19349663) ^ (z as u64).wrapping_mul(83492791)) | 1;
    let mut next = |r: &mut u64| { *r ^= *r << 13; *r ^= *r >> 7; *r ^= *r << 17; *r };
    // (item id, max stack from this chest). The End's chests (dim 2) hold the endgame reward pool:
    // an elytra, diamond gear, a heart container and gems.
    let end_pool: [(u8, i32); 9] = [(188, 1), (176, 1), (175, 1), (170, 1), (129, 1), (155, 3), (156, 4), (133, 2), (24, 2)];
    let over_pool: [(u8, i32); 10] = [(157, 8), (154, 4), (155, 1), (156, 2), (131, 4), (133, 1), (168, 1), (128, 1), (138, 3), (137, 2)];
    let pool: &[(u8, i32)] = if state.dim == 2 { &end_pool } else { &over_pool };
    let n = 2 + (next(&mut r) % 3) as usize; // 2..4 stacks
    for _ in 0..n {
        let (id, maxc) = pool[(next(&mut r) as usize) % pool.len()];
        if crate::item::has_durability(id) {
            state.inventory.add_item_with_count(id, crate::item::max_durability(id));
        } else {
            let c = 1 + (next(&mut r) % maxc as u64) as i32;
            for _ in 0..c { state.inventory.add_block(id); }
        }
    }
}

// Apply damage to the player through equipped armor (each defense point cuts ~4%, capped), wearing
// the armor down when a hit actually lands.
fn hurt_player(state: &mut EngineState, amt: f32) {
    let def = state.inventory.armor_defense();
    let reduced = amt * (1.0 - (def * 0.04)).max(0.2);
    let before = state.player.health;
    state.player.damage(reduced);
    if state.player.health < before { state.inventory.damage_armor(); }
}

// Advance projectiles: move, home (shulker bullets), trail sparks, and resolve block/mob/player hits.
// Player-thrown projectiles damage mobs; ender pearls teleport the player; ghast fireballs explode.
fn tick_projectiles(state: &mut EngineState, dt: f32) {
    let eye = state.player.pos + vec3(0.0, 1.0, 0.0);
    let mut projs = std::mem::take(&mut state.projectiles);
    let mut survivors: Vec<Projectile> = Vec::with_capacity(projs.len());
    let mut explosions: Vec<Vec3> = Vec::new();
    let mut teleport: Option<Vec3> = None;
    let mut bursts: Vec<(Vec3, [f32; 3], bool)> = Vec::new(); // (pos, color, big)
    let mut incoming = 0.0f32;
    for mut p in projs.drain(..) {
        p.life -= dt;
        // Motion: shulker bullets home; fireworks rise; snowballs/pearls fall; fireballs fly straight.
        match p.kind {
            ProjKind::ShulkerBullet => {
                let want = (eye - p.pos).normalize_or_zero() * p.vel.length();
                p.vel = (p.vel * 0.90 + want * 0.10).normalize_or_zero() * p.vel.length();
            }
            ProjKind::Firework => { p.vel.y += 6.0 * dt; }
            _ => { p.vel.y -= p.gravity() * dt; }
        }
        p.pos += p.vel * dt;
        // Block collision.
        let (bx, by, bz) = (p.pos.x.floor() as i32, p.pos.y.floor() as i32, p.pos.z.floor() as i32);
        let solid = { let id = state.chunks.get_block_world(bx, by, bz); id != 0 && Block::from_id(id).is_solid() };
        let mut hit = p.life <= 0.0 || solid;
        // Player-thrown projectiles hit mobs.
        if !hit && p.from_player {
            for m in state.mobs.iter_mut() {
                let center = m.pos + vec3(0.0, m.kind.height() * 0.5, 0.0);
                if (center - p.pos).length() < m.kind.hit_radius() + 0.4 {
                    let dmg = match p.kind { ProjKind::Fireball => 6.0, ProjKind::Snowball => 1.0, _ => 0.0 };
                    if dmg > 0.0 { m.health -= dmg; }
                    hit = true; break;
                }
            }
        }
        // Enemy projectiles hit the player.
        if !hit && !p.from_player && (p.pos - eye).length() < 0.8 { incoming += p.damage; hit = true; }
        if hit {
            match p.kind {
                ProjKind::EnderPearl => teleport = Some(p.pos),
                ProjKind::Fireball if p.explosive => explosions.push(p.pos),
                _ => bursts.push((p.pos, p.color(), matches!(p.kind, ProjKind::Fireball | ProjKind::Firework))),
            }
            continue;
        }
        // Trail spark.
        if state.particles.len() < 400 { state.particles.push(Particle { pos: p.pos, vel: Vec3::ZERO, life: 0.3, max_life: 0.3, size: p.size() * 0.7, color: p.color() }); }
        survivors.push(p);
    }
    state.projectiles = survivors;
    if incoming > 0.0 { hurt_player(state, incoming); }
    for c in explosions { explode(state, c, 2.5); }
    if let Some(tp) = teleport {
        state.player.pos = tp + vec3(0.0, 0.5, 0.0);
        state.player.vel = Vec3::ZERO;
        state.player.air_max_y = state.player.pos.y; // no fall damage from the teleport
        state.player.damage(2.0); // ender pearls jar you a little
        spawn_particles(&mut state.spawn_rng, &mut state.particles, tp, 16, [0.25, 0.85, 0.7], 4.0, 0.6, 0.14);
    }
    for (pos, color, big) in bursts {
        let n = if big { 24 } else { 10 };
        spawn_particles(&mut state.spawn_rng, &mut state.particles, pos, n, color, if big { 5.0 } else { 3.0 }, 0.6, if big { 0.22 } else { 0.13 });
    }
}

// Spawn a small burst of particles (capped so the buffer never overflows).
fn spawn_particles(rng: &mut u32, out: &mut Vec<Particle>, center: Vec3, n: usize, color: [f32; 3], speed: f32, life: f32, size: f32) {
    let mut r = |s: &mut u32| { let mut x = *s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *s = x; (x >> 8) as f32 / 16_777_216.0 * 2.0 - 1.0 };
    for _ in 0..n {
        if out.len() > 500 { break; }
        let v = vec3(r(rng) * speed, r(rng).abs() * speed + 1.0, r(rng) * speed);
        out.push(Particle { pos: center, vel: v, life, max_life: life, size, color });
    }
}

fn mark_neighbors_dirty(state: &mut EngineState, x: i32, z: i32) {
    use crate::world::chunk::ChunkPos;
    let cp = ChunkPos::from_world(x, z);
    if let Some(ch) = state.chunks.get_mut(cp) { ch.mesh_dirty = true; }
    let lx = x.rem_euclid(16); let lz = z.rem_euclid(16);
    if lx==0 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0-1, cp.1)) { ch.mesh_dirty=true; } }
    if lx==15 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0+1, cp.1)) { ch.mesh_dirty=true; } }
    if lz==0 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0, cp.1-1)) { ch.mesh_dirty=true; } }
    if lz==15 { if let Some(ch) = state.chunks.get_mut(ChunkPos(cp.0, cp.1+1)) { ch.mesh_dirty=true; } }
}

// Ray vs mob AABBs: index of the nearest mob hit within `reach` and nearer than `limit`.
fn nearest_mob_hit(state: &EngineState, origin: Vec3, dir: Vec3, reach: f32, limit: f32) -> Option<usize> {
    let mut best: Option<(usize, f32)> = None;
    for (i, m) in state.mobs.iter().enumerate() {
        let h = m.kind.height();
        let hr = m.kind.hit_radius();
        let min = vec3(m.pos.x - hr, m.pos.y, m.pos.z - hr);
        let max = vec3(m.pos.x + hr, m.pos.y + h, m.pos.z + hr);
        let mut tmin = 0.0f32; let mut tmax = reach.min(limit);
        let mut hit = true;
        for a in 0..3 {
            let (o, d, lo, hi) = (origin[a], dir[a], min[a], max[a]);
            if d.abs() < 1e-6 { if o < lo || o > hi { hit = false; break; } }
            else {
                let (mut t1, mut t2) = ((lo - o) / d, (hi - o) / d);
                if t1 > t2 { std::mem::swap(&mut t1, &mut t2); }
                tmin = tmin.max(t1); tmax = tmax.min(t2);
                if tmin > tmax { hit = false; break; }
            }
        }
        if hit && tmin >= 0.0 && best.map(|(_, t)| tmin < t).unwrap_or(true) { best = Some((i, tmin)); }
    }
    best.map(|(i, _)| i)
}

fn do_break(state: &mut EngineState, origin: Vec3, dir: Vec3) -> bool {
    use crate::item::{self, Effect};
    let sel = state.inventory.selected_block();
    // Equip armor (hold): swap it into its slot, returning any displaced piece to the inventory.
    if item::is_armor(sel) && state.player.eat_cd <= 0.0 {
        if let Some((id, dur)) = state.inventory.take_selected() {
            if let Some(old) = state.inventory.equip_armor(id, dur) { state.inventory.add_item_with_count(old.id, old.count); }
            state.player.eat_cd = 0.4;
            return true;
        }
    }
    // Firework rocket: consume one to launch a burst; boosts the player forward while gliding.
    if item::is_firework(sel) && state.player.eat_cd <= 0.0 {
        if state.inventory.consume_selected().is_some() {
            state.player.firework_boost();
            let f = state.player.forward();
            let origin = state.player.eye_pos() + f * 0.5;
            state.projectiles.push(Projectile { pos: origin, vel: f * 5.0 + vec3(0.0, 7.0, 0.0), life: 1.1, kind: ProjKind::Firework, from_player: true, damage: 0.0, explosive: false });
            state.player.eat_cd = 0.4;
            return true;
        }
    }
    // Throwables: snowball (light damage/knock) and ender pearl (teleport to impact).
    if (sel == 190 || sel == 191) && state.player.eat_cd <= 0.0 {
        if state.inventory.consume_selected().is_some() {
            let f = state.player.forward();
            let origin = state.player.eye_pos() + f * 0.5;
            let (kind, spd) = if sel == 191 { (ProjKind::EnderPearl, 16.0) } else { (ProjKind::Snowball, 20.0) };
            state.projectiles.push(Projectile { pos: origin, vel: f * spd, life: 5.0, kind, from_player: true, damage: 0.0, explosive: false });
            state.player.eat_cd = 0.4;
            return true;
        }
    }
    // Deflect an incoming fireball by attacking it — bats it back (now player-owned) at the shooter.
    {
        let eye_pos = state.player.eye_pos();
        let f = state.player.forward();
        let mut best: Option<(usize, f32)> = None;
        for (i, p) in state.projectiles.iter().enumerate() {
            if p.from_player || p.kind != ProjKind::Fireball { continue; }
            let to = p.pos - eye_pos;
            let d = to.length();
            if d < 4.5 && to.normalize_or_zero().dot(f) > 0.5 && best.map_or(true, |b| d < b.1) { best = Some((i, d)); }
        }
        if let Some((i, _)) = best {
            let p = &mut state.projectiles[i];
            p.vel = -p.vel * 1.15;
            p.from_player = true;
            return true;
        }
    }
    // Consumables (hold to use): eat food any time, drink estus / use heart container when applicable.
    if state.player.eat_cd <= 0.0 {
        // Food can be eaten any time (even at full health) to gain its effects.
        if item::is_food(sel) {
            if let Some(id) = state.inventory.consume_selected() {
                if let Some(effs) = item::food_effects(id) { for &(k, s, a) in effs { state.player.add_effect(k, s, a); } }
                state.player.eat_cd = 1.2;
                return true;
            }
        } else if item::is_estus(sel) && state.player.health < state.player.max_health {
            if state.inventory.consume_selected().is_some() {
                state.player.heal(8.0);
                state.player.add_effect(Effect::Regeneration, 3.0, 1);
                state.player.add_effect(Effect::Resistance, 3.0, 0);
                state.player.eat_cd = 0.8;
                return true;
            }
        } else if item::is_heart_container(sel) && state.player.max_health < crate::player::CAP_MAX_HEALTH {
            if state.inventory.consume_selected().is_some() {
                state.player.max_health = (state.player.max_health + 2.0).min(crate::player::CAP_MAX_HEALTH);
                state.player.heal(2.0);
                state.player.eat_cd = 0.8;
                return true;
            }
        }
    }
    // Attack a mob if one is under the cursor within reach and nearer than any block.
    let block_hit = crate::raycast::raycast(&state.chunks, origin, dir, 6.0);
    let block_dist = block_hit.as_ref().map(|h| (vec3(h.pos.0 as f32 + 0.5, h.pos.1 as f32 + 0.5, h.pos.2 as f32 + 0.5) - origin).length()).unwrap_or(f32::INFINITY);
    if state.player.attack_cd <= 0.0 {
        if let Some(idx) = nearest_mob_hit(state, origin, dir, 4.5, block_dist) {
            state.player.attack_cd = 0.45;
            let dmg = 4.0 + item::sword_damage(sel) + item::pick_damage(sel) + state.player.strength_bonus();
            state.inventory.damage_selected();
            let ppos = state.player.pos;
            let mpos;
            {
                let m = &mut state.mobs[idx];
                m.health -= dmg;
                let kb = { let k = m.pos - ppos; vec3(k.x, 0.0, k.z).normalize_or_zero() };
                m.pos += kb * 0.45; m.vel.y = 6.0;
                mpos = m.pos + vec3(0.0, 0.5, 0.0);
            }
            spawn_particles(&mut state.spawn_rng, &mut state.particles, mpos, 6, [0.85, 0.12, 0.12], 3.0, 0.4, 0.09);
            return true;
        }
    }
    // Break a block.
    if let Some(hit) = block_hit {
        let (x, y, z) = hit.pos;
        let id = state.chunks.get_block_world(x, y, z);
        if id == 83 { // chest: breaking it loots it (never yields a placeable chest item)
            loot_chest(state, x, y, z);
            state.chunks.set_block_world(x, y, z, 0);
            mark_neighbors_dirty(state, x, z);
            return true;
        }
        if id != 0 {
            // Stone/ore only drops when mined with a pickaxe; soft blocks always drop.
            let drops = !Block::from_id(id).needs_pickaxe() || item::is_pickaxe(sel);
            state.chunks.set_block_world(x, y, z, 0);
            if drops { state.inventory.add_block(id); state.inventory.broken += 1; }
            state.inventory.damage_selected();
            mark_neighbors_dirty(state, x, z);
            let c = vec3(x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5);
            spawn_particles(&mut state.spawn_rng, &mut state.particles, c, 7, [0.55, 0.45, 0.35], 2.6, 0.5, 0.11);
            return true;
        }
    }
    false
}

// Destroy blocks in a sphere and hurt the player — creeper explosions.
fn explode(state: &mut EngineState, center: Vec3, radius: f32) {
    let r = radius.ceil() as i32;
    let (cx, cy, cz) = (center.x.floor() as i32, center.y.floor() as i32, center.z.floor() as i32);
    let mut cols: Vec<(i32, i32)> = Vec::new();
    for dx in -r..=r { for dy in -r..=r { for dz in -r..=r {
        if ((dx*dx + dy*dy + dz*dz) as f32).sqrt() > radius { continue; }
        let (x, y, z) = (cx + dx, cy + dy, cz + dz);
        let id = state.chunks.get_block_world(x, y, z);
        if id != 0 && id != 13 && Block::from_id(id).is_solid() {
            state.chunks.set_block_world(x, y, z, 0);
            cols.push((x, z));
        }
    }}}
    for (x, z) in cols { mark_neighbors_dirty(state, x, z); }
    let pd = (state.player.pos - center).length();
    if pd < radius * 2.0 { hurt_player(state, (1.0 - pd / (radius * 2.0)).max(0.0) * 22.0); }
    spawn_particles(&mut state.spawn_rng, &mut state.particles, center, 30, [0.28, 0.25, 0.22], 6.0, 0.9, 0.3);
    spawn_particles(&mut state.spawn_rng, &mut state.particles, center, 12, [1.0, 0.55, 0.15], 5.0, 0.5, 0.22);
}

fn do_place(state: &mut EngineState, origin: Vec3, dir: Vec3) -> bool {
    // Items (food, estus, materials) are never placeable as blocks.
    let sel = state.inventory.selected_block();
    if sel == 0 || crate::item::is_item(sel) { return false; }
    if let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, 6.0) {
        let (px,py,pz) = hit.prev;
        if state.chunks.get_block_world(px,py,pz) != 0 { return false; }
        let min_check = vec3(state.player.pos.x - 0.3, state.player.pos.y, state.player.pos.z - 0.3);
        let max_check = vec3(state.player.pos.x + 0.3, state.player.pos.y + 1.8, state.player.pos.z + 0.3);
        let inside_x = (px as f32 + 1.0) > min_check.x && (px as f32) < max_check.x;
        let inside_y = (py as f32 + 1.0) > min_check.y && (py as f32) < max_check.y;
        let inside_z = (pz as f32 + 1.0) > min_check.z && (pz as f32) < max_check.z;
        if inside_x && inside_y && inside_z { return false; }
        if let Some(id) = state.inventory.consume_selected() {
            state.chunks.set_block_world(px,py,pz, id);
            mark_neighbors_dirty(state, px, pz);
            return true;
        }
    }
    false
}

pub fn break_block_at(px: f32, py: f32) -> bool {
    with_engine(|state| {
        let (o, d) = screen_ray(state, px, py);
        do_break(state, o, d)
    }).unwrap_or(false)
}

// Returns: 0 = nothing, 1 = placed a block, 10+menu = tapped an interactive block (open its menu).
pub fn place_block_at(px: f32, py: f32) -> i32 {
    with_engine(|state| {
        let (o, d) = screen_ray(state, px, py);
        // Tapping a villager opens trade; tapping an interactive block opens its menu — unless
        // sneaking, which places instead.
        if !state.player.sneaking {
            let block_hit = crate::raycast::raycast(&state.chunks, o, d, 6.0);
            let bdist = block_hit.as_ref().map(|h| (vec3(h.pos.0 as f32 + 0.5, h.pos.1 as f32 + 0.5, h.pos.2 as f32 + 0.5) - o).length()).unwrap_or(f32::INFINITY);
            if let Some(idx) = nearest_mob_hit(state, o, d, 4.5, bdist) {
                if state.mobs[idx].kind == MobKind::Villager { return 20; }
            }
            if let Some(hit) = block_hit {
                let (x, y, z) = hit.pos;
                let id = state.chunks.get_block_world(x, y, z);
                if id == 83 { // chest: one-time loot, then it's consumed
                    loot_chest(state, x, y, z);
                    state.chunks.set_block_world(x, y, z, 0);
                    mark_neighbors_dirty(state, x, z);
                    return 30;
                }
                // Tap an obsidian frame with flint & steel to ignite a nether portal.
                if id == 78 && state.dim != 2 && crate::item::is_flint_steel(state.inventory.selected_block()) {
                    if light_portal(state, x, y, z) { state.inventory.damage_selected(); return 41; }
                }
                let m = crate::world::block::Block::from_id(id).menu();
                if m != 0 { return 10 + m; }
            }
        }
        if do_place(state, o, d) { 1 } else { 0 }
    }).unwrap_or(0)
}
