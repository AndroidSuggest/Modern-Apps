use crate::world::{ChunkMap, block::{Block, Shape}};
use crate::player::Player;
use crate::inventory::Inventory;
use crate::entity::{Mob, MobKind, Particle, Projectile, ProjKind, build_entity_mesh, tick_particles, append_particles, append_projectiles};
use crate::blessing::Passive;
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
    pub containers: crate::container::Containers,
    pub open_chest: Option<crate::container::ContainerKey>,
    pub smelter: Smelter,
    // Milestone tracking for achievements.
    pub best_beacon: i32,
    pub deepest_y: i32,
    // Throttle for the Lu Ban mending blessing.
    pub mend_cd: f32,
    // Which villager's trade list is open, and how many trades each profession has completed (the
    // latter is what levels them up, since villager mobs aren't persisted).
    pub trade_prof: u8,
    pub trades_done: [u32; crate::villager::ALL.len()],
    // Weather (overworld only): the current state, seconds until the next roll, and the eased
    // intensity the renderer and the mob spawner actually read.
    pub weather: u8,
    pub weather_cd: f32,
    pub rain: f32,
    // Footsteps, cave ambience and the eerie score; see ambience.rs.
    pub ambience: crate::ambience::Ambience,
    prev_walk: f32,
    pub fishing: crate::fishing::Fishing,
    farm_cd: f32,
    // Recipes revealed so far. Matcha ships 115 recipe advancements that unlock a recipe once the
    // player first holds an ingredient; this is that, as a bitmask over RECIPES.
    pub known_recipes: Vec<bool>,
    known_cd: f32,
    // One-shot latches for the tutorial advancements. Not saved: the achievements manager persists
    // the unlock itself, so re-earning them in a later session costs nothing.
    pub did_shear: bool,
    pub did_fish: bool,
    pub did_brush: bool,
    pub did_harvest: bool,
    pub did_rest: bool,
}

// One furnace job at a time, owned by the world rather than by a specific furnace block: the player
// lights a recipe and it keeps burning while they walk away or close the menu.
#[derive(Default)]
pub struct Smelter {
    pub recipe: usize,
    pub active: bool,
    pub progress: f32,
    pub fuel_left: f32,
    pub fuel_max: f32,
    // Whether the furnace the player last opened was a Blast Furnace (gates the alloy recipes).
    pub blast: bool,
}

// Seconds per full day. Matcha's day_cycle_extender stretches vanilla's 1200s toward hour-long days;
// 900 keeps that unhurried feel without asking a mobile session to sit through a 10-minute night.
pub const DAY_CYCLE: f32 = 900.0;
// The clock starts half a cycle in so a fresh world opens at midday (day_t = 0.5 -> sun overhead).
const NOON_OFFSET: f32 = DAY_CYCLE * 0.5;
// Pure clock maths, split out from EngineState so they're testable without an Instant.
pub fn day_t_at(elapsed: f32) -> f32 { ((NOON_OFFSET + elapsed) / DAY_CYCLE) % 1.0 }
// The sun is below the horizon at both ends of the cycle (day_t = 0 is midnight). This band is not
// arbitrary: renderer.rs derives sun height as -cos(day_t * TAU), which is negative exactly here.
pub fn is_night_at(day_t: f32) -> bool { day_t < 0.25 || day_t > 0.75 }

// ---- Weather ----
// Matcha drives rain and clear weather explicitly from its mechanics. Here it's a slow random walk
// between three states, re-rolled every few minutes and persisted, with `rain` easing toward the
// target so the sky fades over rather than snapping.
pub const WEATHER_CLEAR: u8 = 0;
pub const WEATHER_RAIN: u8 = 1;
pub const WEATHER_STORM: u8 = 2;
/// How long a weather state lasts before the next roll.
const WEATHER_MIN_SECS: f32 = 150.0;
const WEATHER_MAX_SECS: f32 = 420.0;
/// Rain fades in and out over ~7 seconds.
const RAIN_FADE_PER_SEC: f32 = 0.14;
/// Rain this heavy lets hostile mobs spawn in daylight, the way an overcast sky does in Minecraft.
pub const RAIN_SPAWN_THRESHOLD: f32 = 0.55;

pub fn rain_target(weather: u8) -> f32 {
    match weather {
        WEATHER_RAIN => 0.65,
        WEATHER_STORM => 1.0,
        _ => 0.0,
    }
}

/// Pick the next weather state. `r` is a uniform sample in [0, 1). Clear is the most common state and
/// storms only arrive by way of rain, so the sky doesn't flip from sunshine to downpour.
pub fn next_weather(current: u8, r: f32) -> u8 {
    match current {
        WEATHER_RAIN => if r < 0.30 { WEATHER_STORM } else if r < 0.75 { WEATHER_CLEAR } else { WEATHER_RAIN },
        WEATHER_STORM => if r < 0.65 { WEATHER_RAIN } else { WEATHER_CLEAR },
        _ => if r < 0.28 { WEATHER_RAIN } else { WEATHER_CLEAR },
    }
}

pub fn weather_duration(r: f32) -> f32 {
    WEATHER_MIN_SECS + r.clamp(0.0, 1.0) * (WEATHER_MAX_SECS - WEATHER_MIN_SECS)
}

/// Just past sunrise, where resting at a Warding Stone lands you.
pub const DAWN: f32 = 0.27;
/// Seconds of world time between `day_t` and the next dawn. Always forward: the clock never rewinds.
pub fn secs_until_dawn(day_t: f32) -> f32 {
    let ahead = (DAWN - day_t).rem_euclid(1.0);
    ahead * DAY_CYCLE
}

impl EngineState {
    pub fn world_time(&self) -> f32 { NOON_OFFSET + self.start_time.elapsed().as_secs_f32() }
    pub fn day_t(&self) -> f32 { day_t_at(self.start_time.elapsed().as_secs_f32()) }
    pub fn is_night(&self) -> bool { is_night_at(self.day_t()) }
    /// Whether hostile mobs may spawn: after dark, or under heavy rain.
    pub fn mobs_can_spawn_hostile(&self) -> bool { self.is_night() || self.rain >= RAIN_SPAWN_THRESHOLD }
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
        player.blessings = progress.blessings;
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
    // Revealed recipes, unpacked from the saved bitmask. A save from before this existed reveals
    // nothing up front, and the first tick re-derives whatever the player already has.
    let mut known_recipes = vec![false; crate::inventory::RECIPES.len()];
    for (i, k) in known_recipes.iter_mut().enumerate() {
        *k = progress.known_recipes.get(i / 8).is_some_and(|b| b & (1 << (i % 8)) != 0);
    }

    // Trade counts are stored as a Vec so old saves load; a short or long one is padded/truncated.
    let mut trades_done = [0u32; crate::villager::ALL.len()];
    for (i, n) in progress.trades_done.iter().enumerate().take(trades_done.len()) { trades_done[i] = *n; }

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
        containers: crate::container::Containers::load(&files_dir),
        open_chest: None,
        smelter: Smelter::default(),
        best_beacon: progress.best_beacon,
        deepest_y: if had_save { progress.deepest_y } else { py as i32 },
        mend_cd: 0.0,
        trade_prof: 0,
        trades_done,
        weather: if progress.weather <= WEATHER_STORM { progress.weather } else { WEATHER_CLEAR },
        weather_cd: progress.weather_cd.clamp(0.0, WEATHER_MAX_SECS),
        // Resume mid-downpour rather than fading in from a clear sky.
        rain: rain_target(progress.weather),
        ambience: crate::ambience::Ambience::default(),
        prev_walk: 0.0,
        fishing: crate::fishing::Fishing::default(),
        farm_cd: 0.0,
        known_recipes,
        known_cd: 0.0,
        did_shear: false, did_fish: false, did_brush: false, did_harvest: false, did_rest: false,
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
                best_beacon: state.best_beacon,
                deepest_y: state.deepest_y,
                blessings: state.player.blessings,
                trades_done: state.trades_done.to_vec(),
                weather: state.weather,
                weather_cd: state.weather_cd,
                known_recipes: {
                    let mut bits = vec![0u8; state.known_recipes.len().div_ceil(8)];
                    for (i, k) in state.known_recipes.iter().enumerate() {
                        if *k { bits[i / 8] |= 1 << (i % 8); }
                    }
                    bits
                },
            },
        };
        let _ = crate::world::save::save_player(&state.save_dir, &ps);
        let _ = state.containers.save(&state.save_dir);
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
        let closure = move |wx: i32, wy: i32, wz: i32| -> (u8, u8) {
            unsafe { ((*map_ptr).get_block_world(wx, wy, wz), (*map_ptr).get_meta_world(wx, wy, wz)) }
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

        tick_smelter(state, dt);
        tick_blessings(state, dt);
        // Overworld-only depth record, so the Nether's low ceiling doesn't hand out the mining badge.
        if state.dim == 0 { state.deepest_y = state.deepest_y.min(state.player.pos.y as i32); }

        // Elytra equipped in the chest slot enables gliding.
        state.player.elytra = state.inventory.armor[1].id == 188;
        state.player.tick(dt, &input_mut, &state.chunks);
        state.player.tick_status(dt);
        // Lava burns (Fire Resistance negates it).
        {
            let p = state.player.pos;
            let feet = state.chunks.get_block_world(p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
            let head = state.chunks.get_block_world(p.x.floor() as i32, (p.y + 1.0).floor() as i32, p.z.floor() as i32);
            if (feet == 84 || head == 84) && !state.player.has_effect(crate::item::Effect::FireResistance)
                && !state.player.blessed(Passive::Pyre) {
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
                state.best_beacon = state.best_beacon.max(tier);
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
            let terrain = crate::entity::Terrain {
                solid: &|x, y, z| chunks.solid_at(x, y, z),
                surface: &|x, z, ceiling| chunks.surface_below(x, z, ceiling, 2),
            };
            let ward = if state.player.blessed(Passive::WardUndead) { WARD_UNDEAD_RADIUS } else { 0.0 };
            for m in state.mobs.iter_mut() {
                m.repelled = if is_undead(m.kind) { ward } else { 0.0 };
                m.tick(dt, player_pos, &terrain);
            }
        }
        // Mob melee contact damage + creeper fuse + ranged fire.
        let mut incoming = 0.0f32;
        let mut explosions: Vec<Vec3> = Vec::new();
        let mut new_shots: Vec<Projectile> = Vec::new();
        // Who actually landed a melee blow this tick, so Warding strikes back at them alone.
        let mut melee: Vec<usize> = Vec::new();
        let eye = player_pos + vec3(0.0, 1.2, 0.0);
        for (mi, m) in state.mobs.iter_mut().enumerate() {
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
                m.attack_cd = 1.0; incoming += m.kind.contact_damage(); melee.push(mi);
            } else if m.kind.hostile() && !m.kind.is_boss() && !ranged && d < 1.7 && m.attack_cd <= 0.0 {
                m.attack_cd = 0.8; incoming += m.kind.contact_damage(); melee.push(mi);
            }
        }
        state.projectiles.extend(new_shots);
        tick_projectiles(state, dt);
        if incoming > 0.0 { hurt_player_from(state, incoming, &melee); }
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
        // Remove dead mobs and auto-collect their drops. Glaucus makes ordinary kills pay double,
        // but not bosses — a second Nether Star would hand out a free extra beacon.
        let mut loot: Vec<(u8, bool)> = Vec::new();
        state.mobs.retain(|m| if m.health <= 0.0 {
            // An elite was twice the fight, so it pays twice.
            let times = if m.elite { 2 } else { 1 };
            for _ in 0..times { loot.extend(m.kind.loot().iter().map(|&id| (id, m.kind.is_boss()))); }
            false
        } else { true });
        let lucky = state.player.blessed(Passive::SeaLuck);
        for (id, boss) in loot {
            let n = if lucky && !boss { 2 } else { 1 };
            for _ in 0..n { state.inventory.add_block(id); }
        }
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
                    // Hostiles come out after dark — and, as in Minecraft, under a heavy enough sky.
                    let hostile = state.mobs_can_spawn_hostile();
                    let kind = if state.dim == 1 {
                        // Nether: hostile natives only (Ghasts are rarer floating threats).
                        match (rand(&mut rng) * 8.0) as u32 { 0 => MobKind::Ghast, 1 | 2 => MobKind::Blaze, 3 | 4 => MobKind::WitherSkeleton, _ => MobKind::Zombie }
                    } else if state.dim == 2 {
                        // Sparse End hostiles: End-city Shulker guardians + wandering wither skeletons.
                        if rand(&mut rng) < 0.4 { MobKind::Shulker } else { MobKind::WitherSkeleton }
                    } else if hostile {
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
                    state.particles.push(Particle { pos: base + r * (0.55 * side), vel: Vec3::ZERO, life: 0.5, max_life: 0.5, size: 0.09, color: [0.88, 0.95, 1.0], gravity: crate::entity::BURST_GRAVITY });
                }
            }
        }
        tick_particles(&mut state.particles, dt);
        tick_weather(state, dt, player_pos);
        tick_ambience(state, dt, player_pos);
        tick_fishing(state, dt, player_pos);
        tick_farmland(state, dt, player_pos);
        tick_recipe_unlocks(state, dt);
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
            let eb = (eye.x.floor() as i32, eye.y.floor() as i32, eye.z.floor() as i32);
            let underwater = if state.chunks.get_block_world(eb.0, eb.1, eb.2) == 12 { 1.0 } else { 0.0 };
            // Tangaroa lights the water up the way night vision lights the dark.
            let conduit = underwater > 0.5 && state.player.blessed(Passive::Conduit);
            let nv = if state.player.night_vision() || conduit { 1.0 } else { 0.0 };
            let dim = state.dim;
            let rain = state.rain;
            unsafe {
                renderer.update_ubo(view_proj, state.player.pos, time, underwater, nv, dim, rain);
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
static SMELT_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
static AMBIENCE_CACHE: OnceLock<Mutex<String>> = OnceLock::new();
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
    let has = |id: u8| state.inventory.slots.iter().any(|s| s.id == id && s.count > 0);
    let armor_at_least = |lo: u8, hi: u8| state.inventory.armor.iter().all(|s| s.id >= lo && s.id <= hi);
    let stats = serde_json::json!({
        "placed": state.inventory.placed, "broken": state.inventory.broken,
        "walked": state.player.walk_dist as i32, "night": state.night_survived,
        "nether": state.dim_visited[1], "end": state.dim_visited[2],
        "dragon": state.end_dragon_dead, "wither": state.nether_wither_dead,
        "beacon": state.best_beacon,
        "elytra": state.player.elytra,
        "maxHearts": state.player.max_health >= crate::player::CAP_MAX_HEALTH,
        // Full diamond (175..178) or full adamant (199..202) counts as end-tier armor.
        "fullArmor": armor_at_least(175, 178) || armor_at_least(199, 202),
        "silver": has(193), "steel": has(195), "adamant": has(196),
        "blessing": state.inventory.slots.iter().any(|s| s.count > 0 && crate::blessing::is_blessing(s.id))
            || state.player.blessings.slots.iter().any(|&id| id != 0),
        "depth": state.deepest_y,
        "attuned": state.player.blessings.slots.iter().filter(|&&s| s != 0).count(),
        // The verbs added on top of Matcha's own systems, for the tutorial chain.
        "traded": state.trades_done.iter().any(|&n| n > 0),
        "trader": state.trades_done.iter().any(|&n| crate::villager::level_for(n) >= crate::villager::MAX_LEVEL),
        "sheared": state.did_shear, "fished": state.did_fish, "brushed": state.did_brush,
        "harvested": state.did_harvest, "rested": state.did_rest,
        "recipes": state.known_recipes.iter().filter(|k| **k).count(),
    }).to_string();
    let inv = state.inventory.to_json();
    let effects: Vec<_> = state.player.effects.iter().map(|e| serde_json::json!({"k": e.kind.key(), "amp": e.amp, "t": e.secs.ceil() as i32})).collect();
    let estus: i32 = state.inventory.slots.iter().filter(|s| s.id == 128).map(|s| s.count).sum();
    let boss_mob = state.mobs.iter().find(|m| m.kind.is_boss());
    let boss: f32 = boss_mob.map(|m| (m.health / m.max_health).clamp(0.0, 1.0)).unwrap_or(-1.0);
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
    let sm = &state.smelter;
    let secs = crate::inventory::SMELTING.get(sm.recipe).map(|r| r.secs).unwrap_or(1.0);
    let smelt = serde_json::json!({
        "active": sm.active, "recipe": sm.recipe,
        "progress": (sm.progress / secs.max(0.001)).clamp(0.0, 1.0),
        "fuel": if sm.fuel_max > 0.0 { (sm.fuel_left / sm.fuel_max).clamp(0.0, 1.0) } else { 0.0 },
        "blast": sm.blast,
    }).to_string();
    if let Ok(mut c) = cref(&SMELT_CACHE, "{}").lock() { *c = smelt; }
    let a = &state.ambience;
    let ambience = serde_json::json!({
        "stepN": a.step_n, "stepMat": a.step_mat,
        "cueN": a.cue_n, "cueKind": a.cue_kind,
        "cast": state.fishing.is_cast(), "bite": state.fishing.biting(),
        "eerie": a.eerie, "rain": state.rain,
    }).to_string();
    if let Ok(mut c) = cref(&AMBIENCE_CACHE, "{}").lock() { *c = ambience; }
}

pub fn get_debug_json() -> String { cref(&DEBUG_CACHE, r#"{"error":"no engine"}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_inventory_json() -> String { cref(&INV_CACHE, r#"{"selected":0,"slots":[]}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| r#"{"selected":0,"slots":[]}"#.into()) }
pub fn get_stats_json() -> String { cref(&STATS_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_health_json() -> String { cref(&HEALTH_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_smelt_json() -> String { cref(&SMELT_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_ambience_json() -> String { cref(&AMBIENCE_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }

pub fn inventory_move(from: usize, to: usize) { with_engine(|s| s.inventory.move_item(from, to)); }
pub fn inventory_give(id: u8) { with_engine(|s| s.inventory.give(id)); }
pub fn inventory_craft(recipe: usize) -> bool { with_engine(|s| s.inventory.craft(recipe)).unwrap_or(false) }
pub fn do_trade(idx: usize) -> bool {
    with_engine(|s| {
        let prof = crate::villager::Profession::from_index(s.trade_prof as usize);
        let level = crate::villager::level_for(s.trades_done[prof.index()]);
        let Some(offer) = crate::villager::offers(prof, level).get(idx).copied() else { return false; };
        if !s.inventory.trade_offer(&offer) { return false; }
        s.trades_done[prof.index()] = s.trades_done[prof.index()].saturating_add(1);
        true
    }).unwrap_or(false)
}
pub fn get_trades_json() -> String {
    with_engine(|s| {
        let prof = crate::villager::Profession::from_index(s.trade_prof as usize);
        let done = s.trades_done[prof.index()];
        let level = crate::villager::level_for(done);
        let items: Vec<_> = crate::villager::offers(prof, level).iter().enumerate()
            .map(|(i, o)| serde_json::json!({
                "cost": o.cost, "costN": o.cost_n,
                "cost2": o.cost2, "cost2N": o.cost2_n,
                "give": o.give, "giveN": crate::villager::give_count(o),
                "level": crate::villager::level_of_offer(prof, i),
            }))
            .collect();
        serde_json::json!({
            "prof": prof.name(), "level": level, "maxLevel": crate::villager::MAX_LEVEL,
            "done": done, "nextAt": crate::villager::next_level_at(level), "trades": items,
        }).to_string()
    }).unwrap_or_else(|| "{}".to_string())
}

// The stonecutter's whole catalog; the UI filters it down to what the player is carrying.
pub fn get_cuts_json() -> String {
    let items: Vec<_> = crate::inventory::cut_variants().iter()
        .map(|c| serde_json::json!({ "in": c.input, "out": c.output, "outN": c.count }))
        .collect();
    serde_json::json!(items).to_string()
}
pub fn do_cut(idx: usize) -> bool { with_engine(|s| s.inventory.cut(idx)).unwrap_or(false) }
pub fn get_recipes_json() -> String {
    let known = with_engine(|s| s.known_recipes.clone()).unwrap_or_default();
    let items: Vec<_> = crate::inventory::RECIPES.iter().enumerate()
        .map(|(i, (i1, c1, i2, c2, oid, oc))| serde_json::json!({
            "in": i1, "inN": c1, "in2": i2, "in2N": c2, "out": oid, "outN": oc,
            "cat": crate::inventory::recipe_category(*oid),
            "known": known.get(i).copied().unwrap_or(false),
        }))
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
    let is_mineral = |id: u8| matches!(id, 23 | 24 | 25 | 93 | 94 | 95);
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

// Make sure a chest at this position has a container, rolling world loot the first time a chest the
// player never placed is opened.
fn ensure_chest(state: &mut EngineState, x: i32, y: i32, z: i32) -> crate::container::ContainerKey {
    let key = (state.dim, x, y, z);
    if !state.containers.contains(key) {
        let lucky = state.player.blessed(Passive::SeaLuck);
        state.containers.insert(key, crate::container::roll_loot(x, y, z, state.dim, lucky));
    }
    key
}

// Apply damage to the player through equipped armor (each defense point cuts ~4%, capped), wearing
// the armor down when a hit actually lands. Warding reflects a share back at whoever landed the
// blow, which is only known for melee — ranged and explosive hits pass None and reflect nothing.
fn hurt_player(state: &mut EngineState, amt: f32) { hurt_player_from(state, amt, &[]) }

fn hurt_player_from(state: &mut EngineState, amt: f32, attackers: &[usize]) {
    let def = state.inventory.armor_defense();
    let reduced = amt * (1.0 - (def * 0.04)).max(0.2);
    let before = state.player.health;
    state.player.damage(reduced);
    if state.player.health < before {
        if !state.player.blessed(Passive::ArmorWard) { state.inventory.damage_armor(); }
        if state.player.blessed(Passive::Thorns) {
            for &i in attackers {
                if let Some(m) = state.mobs.get_mut(i) { m.health -= reduced * 0.5; }
            }
        }
    }
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
            // Apollo doubles what a thrown weapon does on impact.
            let marksman = if state.player.blessed(Passive::Marksman) { 2.0 } else { 1.0 };
            for m in state.mobs.iter_mut() {
                let center = m.pos + vec3(0.0, m.kind.height() * 0.5, 0.0);
                if (center - p.pos).length() < m.kind.hit_radius() + 0.4 {
                    let dmg = match p.kind { ProjKind::Fireball => 6.0, ProjKind::Snowball => 1.0, _ => 0.0 };
                    if dmg > 0.0 { m.health -= dmg * marksman; }
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
        if state.particles.len() < 400 { state.particles.push(Particle { pos: p.pos, vel: Vec3::ZERO, life: 0.3, max_life: 0.3, size: p.size() * 0.7, color: p.color(), gravity: crate::entity::BURST_GRAVITY }); }
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

// Run the line: hold the float in place, count down to a bite, and give up if the player walks off.
fn tick_fishing(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    let Some(b) = state.fishing.bobber else { return; };
    let bob = vec3(b[0], b[1], b[2]);
    // The rod has to stay in hand and the player within reach of the water.
    if state.inventory.selected_block() != crate::fishing::ROD
        || (bob - player_pos).length() > crate::fishing::LEASH
        || state.chunks.get_block_world(b[0].floor() as i32, b[1].floor() as i32, b[2].floor() as i32) != 12
    {
        state.fishing.reel_in();
        return;
    }
    if state.fishing.tick(dt) {
        // A bite: splash, so the player knows to strike without needing to watch a HUD element.
        spawn_particles(&mut state.spawn_rng, &mut state.particles, bob, 10, [0.60, 0.80, 0.95], 3.0, 0.5, 0.09);
    }
    // The float itself, redrawn each frame as a short-lived particle.
    if state.particles.len() < 480 {
        let color = if state.fishing.biting() { [1.0, 0.85, 0.35] } else { [0.90, 0.25, 0.20] };
        state.particles.push(Particle {
            pos: bob + vec3(0.0, 0.1, 0.0), vel: Vec3::ZERO,
            life: 0.12, max_life: 0.12, size: 0.11, color, gravity: 0.0,
        });
    }
}

// Feed the atmosphere system: how far the player walked, what they're standing on, and how dark and
// deep it is where they are.
fn tick_ambience(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    let walked = (state.player.walk_dist - state.prev_walk).max(0.0);
    state.prev_walk = state.player.walk_dist;

    let (px, pz) = (player_pos.x.floor() as i32, player_pos.z.floor() as i32);
    let under = state.chunks.get_block_world(px, (player_pos.y - 0.1).floor() as i32, pz);

    // "Deep" means genuinely enclosed: a solid ceiling somewhere overhead, well below the surface.
    let head = player_pos.y.floor() as i32 + 2;
    let covered = (head..head + 40).any(|y| {
        let id = state.chunks.get_block_world(px, y, pz);
        id != 0 && Block::from_id(id).blocks_light()
    });
    let deep = covered && player_pos.y < 50.0;
    // The Nether and End are always oppressive; the overworld only after dark.
    let dark = state.dim != 0 || state.is_night();

    let mut r = state.spawn_rng;
    r ^= r << 13; r ^= r >> 17; r ^= r << 5;
    state.spawn_rng = r;
    let roll = (r >> 8) as f32 / 16_777_216.0;

    state.ambience.tick(dt, walked, state.player.on_ground, under, dark, deep, roll);
}

// Advance the weather random walk and drop precipitation around the player. Only the overworld has a
// sky to rain from; the Nether and End are left alone.
fn tick_weather(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    if state.dim != 0 {
        state.rain = 0.0;
        return;
    }
    let mut rng = state.spawn_rng;
    let mut rand = |s: &mut u32| { let mut x = *s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *s = x; (x >> 8) as f32 / 16_777_216.0 };

    state.weather_cd -= dt;
    if state.weather_cd <= 0.0 {
        state.weather = next_weather(state.weather, rand(&mut rng));
        state.weather_cd = weather_duration(rand(&mut rng));
    }
    // Ease toward the target so the sky fades between states instead of snapping.
    let target = rain_target(state.weather);
    let step = RAIN_FADE_PER_SEC * dt;
    state.rain += (target - state.rain).clamp(-step, step);

    if state.rain > 0.02 {
        spawn_precipitation(state, &mut rng, &mut rand, player_pos);
    }
    state.spawn_rng = rng;
}

/// Precipitation sits in its own slice of the particle budget so a downpour can never crowd out the
/// combat and mining bursts that the player actually needs to see.
const RAIN_BUDGET: usize = 260;
/// How far above the player drops appear, and the radius they fall inside.
const RAIN_HEIGHT: f32 = 13.0;
const RAIN_RADIUS: f32 = 13.0;

fn spawn_precipitation(
    state: &mut EngineState,
    rng: &mut u32,
    rand: &mut impl FnMut(&mut u32) -> f32,
    player_pos: Vec3,
) {
    // Nothing falls on a player who is under cover: if there's solid material overhead, skip it. This
    // stands in for a per-column sky test and costs one short scan instead of one per drop.
    let (px, pz) = (player_pos.x.floor() as i32, player_pos.z.floor() as i32);
    let head = player_pos.y.floor() as i32 + 2;
    for y in head..=(head + RAIN_HEIGHT as i32) {
        let id = state.chunks.get_block_world(px, y, pz);
        if id != 0 && Block::from_id(id).blocks_light() { return; }
    }
    // Snow rather than rain wherever the ground is frozen over.
    let ground = state.chunks.get_block_world(px, player_pos.y.floor() as i32 - 1, pz);
    let snowy = matches!(ground, 11 | 42 | 43 | 44);

    let want = (state.rain * 22.0) as usize;
    for _ in 0..want {
        if state.particles.len() >= RAIN_BUDGET { break; }
        let ang = rand(rng) * std::f32::consts::TAU;
        let r = rand(rng).sqrt() * RAIN_RADIUS;
        let pos = vec3(
            player_pos.x + ang.cos() * r,
            player_pos.y + RAIN_HEIGHT * (0.6 + 0.4 * rand(rng)),
            player_pos.z + ang.sin() * r,
        );
        let p = if snowy {
            // Flakes drift: almost no gravity, a little sideways wander, and a long life.
            Particle {
                pos, vel: vec3((rand(rng) - 0.5) * 0.8, -1.6, (rand(rng) - 0.5) * 0.8),
                life: 6.0, max_life: 6.0, size: 0.075, color: [0.95, 0.97, 1.0], gravity: 0.35,
            }
        } else {
            Particle {
                pos, vel: vec3(0.0, -14.0, 0.0),
                life: 1.3, max_life: 1.3, size: 0.045, color: [0.62, 0.72, 0.85], gravity: 6.0,
            }
        };
        state.particles.push(p);
    }
}

// Spawn a small burst of particles (capped so the buffer never overflows).
fn spawn_particles(rng: &mut u32, out: &mut Vec<Particle>, center: Vec3, n: usize, color: [f32; 3], speed: f32, life: f32, size: f32) {
    let mut r = |s: &mut u32| { let mut x = *s; x ^= x << 13; x ^= x >> 17; x ^= x << 5; *s = x; (x >> 8) as f32 / 16_777_216.0 * 2.0 - 1.0 };
    for _ in 0..n {
        if out.len() > 500 { break; }
        let v = vec3(r(rng) * speed, r(rng).abs() * speed + 1.0, r(rng) * speed);
        out.push(Particle { pos: center, vel: v, life, max_life: life, size, color, gravity: crate::entity::BURST_GRAVITY });
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
    // Brushing: sweep suspicious sand for whatever was buried in it. The sand stays behind either
    // way, so a dig site can't be turned into a hole by accident.
    if sel == crate::item::BRUSH {
        if let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, player_reach(state)) {
            let (x, y, z) = hit.pos;
            if state.chunks.get_block_world(x, y, z) == Block::SuspiciousSand as u8 {
                let mut r = state.spawn_rng;
                r ^= r << 13; r ^= r >> 17; r ^= r << 5;
                state.spawn_rng = r;
                let id = buried_find((r >> 8) as f32 / 16_777_216.0);
                if !state.inventory.has_room_for(id, 1) { return false; }
                state.inventory.add_block(id);
                state.did_brush = true;
                state.chunks.set_block_world(x, y, z, Block::Sand as u8);
                mark_neighbors_dirty(state, x, z);
                damage_tool(state);
                let c = vec3(x as f32 + 0.5, y as f32 + 1.0, z as f32 + 0.5);
                spawn_particles(&mut state.spawn_rng, &mut state.particles, c, 12, [0.86, 0.78, 0.58], 2.5, 0.6, 0.1);
                return true;
            }
        }
        return false;
    }
    // Fishing: the first tap casts into water, the second strikes. Striking on a bite lands a catch;
    // striking early just reels the line back in.
    if sel == crate::fishing::ROD && state.player.eat_cd <= 0.0 {
        state.player.eat_cd = 0.4;
        if state.fishing.is_cast() {
            if state.fishing.biting() {
                let mut r = state.spawn_rng;
                r ^= r << 13; r ^= r >> 17; r ^= r << 5;
                state.spawn_rng = r;
                let roll = (r >> 8) as f32 / 16_777_216.0;
                let id = crate::fishing::catch_of_the_day(roll, state.player.blessed(Passive::SeaLuck));
                if state.inventory.has_room_for(id, 1) {
                    state.inventory.add_block(id);
                    state.did_fish = true;
                    damage_tool(state);
                }
            }
            state.fishing.reel_in();
            return true;
        }
        // Cast: find open water along the look direction and drop the float on its surface.
        if let Some(at) = water_surface_along(state, origin, dir, player_reach(state) + 6.0) {
            let mut r = state.spawn_rng;
            r ^= r << 13; r ^= r >> 17; r ^= r << 5;
            state.spawn_rng = r;
            state.fishing.cast(at, (r >> 8) as f32 / 16_777_216.0);
            return true;
        }
        return false;
    }
    // Throwables: snowball (light damage/knock) and ender pearl (teleport to impact).
    if (sel == 190 || sel == 191) && state.player.eat_cd <= 0.0 {
        // Paris never spends the charge; everyone else does.
        let free = state.player.blessed(Passive::Infinity);
        if free || state.inventory.consume_selected().is_some() {
            let f = state.player.forward();
            let origin = state.player.eye_pos() + f * 0.5;
            let (kind, spd) = if sel == 191 { (ProjKind::EnderPearl, 16.0) } else { (ProjKind::Snowball, 20.0) };
            // Artemis throws a spread of three; an ender pearl always flies alone so the
            // teleport destination stays predictable.
            let spread: &[f32] = if state.player.blessed(Passive::Multishot) && kind == ProjKind::Snowball {
                &[-0.12, 0.0, 0.12]
            } else {
                &[0.0]
            };
            let right = state.player.right();
            for &off in spread {
                let dir = (f + right * off).normalize_or_zero();
                state.projectiles.push(Projectile { pos: origin, vel: dir * spd, life: 5.0, kind, from_player: true, damage: 0.0, explosive: false });
            }
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
    let reach = player_reach(state);
    let block_hit = crate::raycast::raycast(&state.chunks, origin, dir, reach);
    let block_dist = block_hit.as_ref().map(|h| (vec3(h.pos.0 as f32 + 0.5, h.pos.1 as f32 + 0.5, h.pos.2 as f32 + 0.5) - origin).length()).unwrap_or(f32::INFINITY);
    if state.player.attack_cd <= 0.0 {
        if let Some(idx) = nearest_mob_hit(state, origin, dir, reach - 1.5, block_dist) {
            state.player.attack_cd = 0.45;
            let base = 4.0 + item::sword_damage(sel) + item::pick_damage(sel) + state.player.strength_bonus();
            let dmg = base * state.player.might_mult() * slayer_mult(&state.player, state.mobs[idx].kind);
            damage_tool(state);
            let ppos = state.player.pos;
            // Talos turns every swing into a launch.
            let (kb_h, kb_v) = if state.player.blessed(Passive::Impact) { (2.4, 12.0) } else { (0.45, 6.0) };
            let mpos;
            {
                let m = &mut state.mobs[idx];
                m.health -= dmg;
                let kb = { let k = m.pos - ppos; vec3(k.x, 0.0, k.z).normalize_or_zero() };
                m.pos += kb * kb_h; m.vel.y = kb_v;
                mpos = m.pos + vec3(0.0, 0.5, 0.0);
            }
            state.player.lifesteal(dmg);
            state.player.wind_burst();
            spawn_particles(&mut state.spawn_rng, &mut state.particles, mpos, 6, [0.85, 0.12, 0.12], 3.0, 0.4, 0.09);
            return true;
        }
    }
    // Break a block.
    if let Some(hit) = block_hit {
        let (x, y, z) = hit.pos;
        let id = state.chunks.get_block_world(x, y, z);
        if id == 83 { // chest: breaking it spills its contents and returns the chest itself
            let key = ensure_chest(state, x, y, z);
            // Only break the chest once everything actually fit — otherwise the leftovers would be
            // dropped on the floor, which this game has no representation for.
            let mut left = match state.containers.remove(key) {
                Some(slots) => slots,
                None => Vec::new(),
            };
            for s in left.iter_mut() { state.inventory.take_from(s); }
            if left.iter().any(|s| s.id != 0) {
                state.containers.insert(key, left);
                return false;
            }
            state.inventory.add_block(83);
            if state.open_chest == Some(key) { state.open_chest = None; }
            state.chunks.set_block_world(x, y, z, 0);
            mark_neighbors_dirty(state, x, z);
            return true;
        }
        if id != 0 {
            // Stone/ore only drops when mined with a pickaxe; soft blocks always drop.
            let drops = !Block::from_id(id).needs_pickaxe() || item::is_pickaxe(sel);
            let meta_before = state.chunks.get_meta_world(x, y, z);
            state.chunks.set_block_world(x, y, z, 0);
            // Leaves occasionally give up an apple. Rolled against the world RNG rather than the
            // block position, so one lucky coordinate can't be replanted into an apple farm.
            if is_leaves(id) {
                let r = &mut state.spawn_rng;
                *r ^= *r << 13; *r ^= *r >> 17; *r ^= *r << 5;
                if *r % 20 == 0 { state.inventory.add_block(130); }
            }
            let crop = Block::from_id(id);
            if crop.is_crop() {
                harvest_crop(state, crop, meta_before);
                state.inventory.broken += 1;
            } else if drops {
                // Eros doubles what an ore gives up.
                let n = if state.player.blessed(Passive::Fortune) && is_ore(id) { 2 } else { 1 };
                for _ in 0..n { state.inventory.add_block(id); }
                state.inventory.broken += 1;
            }
            damage_tool(state);
            mark_neighbors_dirty(state, x, z);
            let c = vec3(x as f32 + 0.5, y as f32 + 0.5, z as f32 + 0.5);
            spawn_particles(&mut state.spawn_rng, &mut state.particles, c, 7, [0.55, 0.45, 0.35], 2.6, 0.5, 0.11);
            return true;
        }
    }
    false
}

// Advance the active furnace job: burn fuel, accumulate progress and bank finished batches. Stops
// itself when it runs out of inputs or fuel so the player never silently loses items.
fn tick_smelter(state: &mut EngineState, dt: f32) {
    if !state.smelter.active { return; }
    let Some(recipe) = crate::inventory::SMELTING.get(state.smelter.recipe) else {
        state.smelter.active = false;
        return;
    };
    let stop = |s: &mut Smelter| { s.active = false; s.progress = 0.0; };
    if recipe.blast && !state.smelter.blast { stop(&mut state.smelter); return; }
    if !state.inventory.can_smelt(recipe) { stop(&mut state.smelter); return; }
    // Pause instead of burning fuel when the result would have nowhere to go.
    if !state.inventory.has_room_for(recipe.out, recipe.out_n) { return; }
    if state.smelter.fuel_left <= 0.0 {
        let spare = [recipe.in1, recipe.in2];
        match state.inventory.consume_fuel(&spare) {
            Some(secs) => { state.smelter.fuel_left = secs; state.smelter.fuel_max = secs; }
            None => { stop(&mut state.smelter); return; }
        }
    }
    state.smelter.fuel_left -= dt;
    state.smelter.progress += dt;
    if state.smelter.progress >= recipe.secs {
        state.smelter.progress -= recipe.secs;
        state.inventory.take_smelt_inputs(recipe);
        state.inventory.give_smelt_output(recipe);
        if !state.inventory.can_smelt(recipe) { stop(&mut state.smelter); }
    }
}

// Light a furnace recipe. `blast` reports which kind of furnace the player tapped. A job already in
// flight is left alone: switching would throw away its progress and the fuel already spent on it.
pub fn start_smelt(recipe: usize, blast: bool) -> bool {
    with_engine(|state| {
        let Some(r) = crate::inventory::SMELTING.get(recipe) else { return false; };
        if r.blast && !blast { return false; }
        if state.smelter.active && state.smelter.recipe != recipe { return false; }
        if !state.inventory.can_smelt(r) { return false; }
        state.smelter.blast = blast;
        if state.smelter.recipe != recipe { state.smelter.progress = 0.0; }
        state.smelter.recipe = recipe;
        state.smelter.active = true;
        true
    }).unwrap_or(false)
}
pub fn stop_smelt() { with_engine(|s| { s.smelter.active = false; s.smelter.progress = 0.0; }); }
pub fn get_smelting_json() -> String {
    let items: Vec<_> = crate::inventory::SMELTING.iter()
        .map(|s| serde_json::json!({
            "in": s.in1, "inN": s.n1, "in2": s.in2, "in2N": s.n2,
            "out": s.out, "outN": s.out_n, "secs": s.secs, "blast": s.blast,
        }))
        .collect();
    serde_json::json!(items).to_string()
}

// ---- Chest containers ----
pub fn get_container_json() -> String {
    with_engine_try(|state| match state.open_chest {
        Some(key) => state.containers.to_json(key),
        None => r#"{"slots":[]}"#.to_string(),
    }).unwrap_or_else(|| r#"{"slots":[]}"#.to_string())
}
// Move a chest stack into the player's inventory, leaving behind whatever didn't fit.
pub fn container_take(idx: usize) -> bool {
    with_engine(|state| {
        let Some(key) = state.open_chest else { return false; };
        let Some(slot) = state.containers.slot_mut(key, idx) else { return false; };
        if slot.id == 0 { return false; }
        let mut held = *slot;
        state.inventory.take_from(&mut held);
        let moved = held.count < slot.count || held.id == 0;
        if let Some(slot) = state.containers.slot_mut(key, idx) { *slot = held; }
        moved
    }).unwrap_or(false)
}
// Move a whole player stack into the chest, putting back anything that didn't fit.
pub fn container_put(inv_idx: usize) -> bool {
    with_engine(|state| {
        let Some(key) = state.open_chest else { return false; };
        if inv_idx >= crate::inventory::SLOTS { return false; }
        let s = state.inventory.slots[inv_idx];
        if s.id == 0 { return false; }
        let left = state.containers.add(key, s.id, s.count);
        if left >= s.count { return false; } // chest full, nothing moved
        state.inventory.slots[inv_idx] = if left > 0 {
            crate::inventory::InvSlot { id: s.id, count: left }
        } else {
            crate::inventory::InvSlot::default()
        };
        true
    }).unwrap_or(false)
}
pub fn close_container() { with_engine(|s| s.open_chest = None); }

// ---- Blessings ----
// Bind the blessing held in an inventory slot, consuming the charm. Fails if the slot doesn't hold
// a blessing, it's already bound, or every attunement slot is taken.
pub fn attune_blessing(inv_idx: usize) -> bool {
    with_engine(|state| {
        if inv_idx >= crate::inventory::SLOTS { return false; }
        let id = state.inventory.slots[inv_idx].id;
        if !crate::blessing::is_blessing(id) { return false; }
        if !state.player.blessings.attune(id) { return false; }
        let slot = &mut state.inventory.slots[inv_idx];
        slot.count -= 1;
        if slot.count <= 0 { *slot = crate::inventory::InvSlot::default(); }
        true
    }).unwrap_or(false)
}
// Unbind an attunement, handing the charm back. Refuses if there's nowhere to put it.
pub fn release_blessing(slot: usize) -> bool {
    with_engine(|state| {
        let Some(&id) = state.player.blessings.slots.get(slot) else { return false; };
        if id == 0 { return false; }
        if !state.inventory.has_room_for(id, 1) { return false; }
        state.player.blessings.release(slot);
        state.inventory.add_block(id);
        true
    }).unwrap_or(false)
}
pub fn get_blessings_json() -> String {
    with_engine_try(|state| state.player.blessings.to_json())
        .unwrap_or_else(|| crate::blessing::Attunement::default().to_json())
}
pub fn get_blessing_catalog_json() -> String { crate::blessing::catalog_json() }

// Ore blocks, for the Fortune blessing.
fn is_ore(id: u8) -> bool { matches!(id, 18..=22 | 90 | 91 | 92) }
// Leaf blocks, which sometimes drop an apple.
fn is_leaves(id: u8) -> bool { matches!(id, 5 | 28 | 31 | 48 | 80) }

// Blessings that act over time rather than at a single event: Lu Ban repairs gear a point at a
// time, Demeter freezes the water the player walks over.
fn tick_blessings(state: &mut EngineState, dt: f32) {
    if state.player.blessed(crate::blessing::Passive::Mending) {
        state.mend_cd -= dt;
        if state.mend_cd <= 0.0 {
            state.mend_cd = 1.0;
            state.inventory.mend_one();
        }
    }
    if state.player.blessed(Passive::FrostWalker) {
        let p = state.player.pos;
        let y = (p.y - 0.4).floor() as i32;
        let mut froze = Vec::new();
        for dx in -1..=1 { for dz in -1..=1 {
            let (x, z) = (p.x.floor() as i32 + dx, p.z.floor() as i32 + dz);
            if state.chunks.get_block_world(x, y, z) == 12 {
                state.chunks.set_block_world(x, y, z, 43); // ice
                froze.push((x, z));
            }
        }}
        for (x, z) in froze { mark_neighbors_dirty(state, x, z); }
    }
}

// How far the player can reach; Will stretches it well past arm's length.
fn player_reach(state: &EngineState) -> f32 {
    if state.player.blessed(Passive::Reach) { 9.0 } else { 6.0 }
}
// Damage multiplier against a particular kind of foe, from the slayer blessings.
fn slayer_mult(player: &Player, kind: MobKind) -> f32 {
    let horror = matches!(kind, MobKind::Creeper | MobKind::Shulker | MobKind::Ghast);
    if (is_undead(kind) && player.blessed(Passive::SmiteUndead)) || (horror && player.blessed(Passive::BaneOfHorrors)) { 2.0 } else { 1.0 }
}
/// Reveal any recipe whose ingredients the player is carrying. Throttled: this walks every recipe,
/// and nothing about it needs to happen at frame rate.
fn tick_recipe_unlocks(state: &mut EngineState, dt: f32) {
    state.known_cd -= dt;
    if state.known_cd > 0.0 { return; }
    state.known_cd = 0.5;
    for (i, r) in crate::inventory::RECIPES.iter().enumerate() {
        if state.known_recipes[i] { continue; }
        let (in1, _, in2, _, _, _) = *r;
        let have1 = state.inventory.count_of(in1) > 0;
        let have2 = in2 == 0 || state.inventory.count_of(in2) > 0;
        if have1 && have2 { state.known_recipes[i] = true; }
    }
}

/// Seconds between growth passes, and how many random spots each pass checks. Sampling beats
/// scanning: a full sweep of the loaded world every tick would dwarf everything else the engine does.
const FARM_INTERVAL: f32 = 2.0;
const FARM_SAMPLES: usize = 40;
const FARM_RANGE: i32 = 24;

fn tick_farmland(state: &mut EngineState, dt: f32, player_pos: Vec3) {
    if state.dim != 0 { return; }
    state.farm_cd -= dt;
    if state.farm_cd > 0.0 { return; }
    state.farm_cd = FARM_INTERVAL;

    let (px, py, pz) = (player_pos.x.floor() as i32, player_pos.y.floor() as i32, player_pos.z.floor() as i32);
    let mut r = state.spawn_rng;
    let mut next = |s: &mut u32| { *s ^= *s << 13; *s ^= *s >> 17; *s ^= *s << 5; *s };
    let mut grown: Vec<(i32, i32)> = Vec::new();
    for _ in 0..FARM_SAMPLES {
        let a = next(&mut r);
        let x = px + (a % (FARM_RANGE as u32 * 2 + 1)) as i32 - FARM_RANGE;
        let b = next(&mut r);
        let z = pz + (b % (FARM_RANGE as u32 * 2 + 1)) as i32 - FARM_RANGE;
        let c = next(&mut r);
        let y = py + (c % 9) as i32 - 4;

        let id = state.chunks.get_block_world(x, y, z);
        if !Block::from_id(id).is_crop() { continue; }
        let meta = state.chunks.get_meta_world(x, y, z);
        let stage = crate::world::block::crop_stage(meta);
        if stage >= crate::world::block::CROP_RIPE { continue; }
        // Crops only grow on tended ground; break the farmland and the field stalls.
        if state.chunks.get_block_world(x, y - 1, z) != Block::Farmland as u8 { continue; }
        state.chunks.set_block_meta_world(x, y, z, id, crate::world::block::crop_meta(stage + 1));
        grown.push((x, z));
    }
    state.spawn_rng = r;
    for (x, z) in grown { mark_neighbors_dirty(state, x, z); }
}

/// A ripe crop pays out; an unripe one only returns the seed that was put in.
fn harvest_crop(state: &mut EngineState, crop: Block, meta: u8) {
    let seed = crop.crop_seed();
    if crate::world::block::crop_stage(meta) < crate::world::block::CROP_RIPE {
        state.inventory.add_block(seed);
        return;
    }
    let produce = crop.crop_yield();
    // Eros is a harvest blessing as much as a mining one.
    let n = if state.player.blessed(Passive::Fortune) { 5 } else { 3 };
    for _ in 0..n { state.inventory.add_block(produce); }
    state.did_harvest = true;
    // And the seed back, so a field is self-sustaining once it's planted.
    if seed != produce { state.inventory.add_block(seed); }
}

/// What a dig site gives up. Matcha's archaeology yields pottery sherds, which this game doesn't
/// model, so the pool is the small treasures a buried cache would plausibly hold.
pub fn buried_find(roll: f32) -> u8 {
    match (roll.clamp(0.0, 0.999) * 100.0) as u32 {
        0..=29 => 137,                                  // leather scraps
        30..=54 => 236,                                 // copper ingot
        55..=74 => Block::Amethyst as u8,
        75..=89 => 237,                                 // gold ingot
        90..=96 => 156,                                 // emerald
        _ => 155,                                       // diamond
    }
}

/// March along the aim direction looking for the top of a body of water to drop a float onto.
fn water_surface_along(state: &EngineState, origin: Vec3, dir: Vec3, max_dist: f32) -> Option<[f32; 3]> {
    let mut t = 1.0f32;
    while t < max_dist {
        let p = origin + dir * t;
        let (x, y, z) = (p.x.floor() as i32, p.y.floor() as i32, p.z.floor() as i32);
        let id = state.chunks.get_block_world(x, y, z);
        if id == 12 {
            // Float on the surface of this column, not wherever the ray happened to enter it.
            let mut top = y;
            while state.chunks.get_block_world(x, top + 1, z) == 12 { top += 1; }
            return Some([x as f32 + 0.5, top as f32 + 0.9, z as f32 + 0.5]);
        }
        if id != 0 && Block::from_id(id).is_solid() { return None; } // the shore is in the way
        t += 0.4;
    }
    None
}

/// Wool from one shearing. A sheep only carries one coat, so this is the whole yield.
const WOOL_PER_SHEARING: i32 = 3;
/// Anubis keeps the dead this far back.
const WARD_UNDEAD_RADIUS: f32 = 6.0;
pub fn is_undead(kind: MobKind) -> bool {
    matches!(kind, MobKind::Zombie | MobKind::WitherSkeleton | MobKind::Wither)
}
// Wear the held tool, unless Daedalus is holding it together.
fn damage_tool(state: &mut EngineState) {
    if !state.player.blessed(Passive::ToolWard) { state.inventory.damage_selected(); }
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
            // A blown-up chest hands what it can to the player; the rest goes with the blast. Leaving
            // the entry behind would let a chest placed here later inherit the old loot.
            if id == 83 {
                let key = (state.dim, x, y, z);
                if let Some(mut slots) = state.containers.remove(key) {
                    for s in slots.iter_mut() { state.inventory.take_from(s); }
                }
                if state.open_chest == Some(key) { state.open_chest = None; }
            }
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

/// Which way a stair placed now should face. Its low side looks back at the player so that walking
/// forward climbs it. Yaw 0 looks north (-Z).
fn stair_facing(yaw: f32) -> u8 {
    use crate::world::block::{FACE_EAST, FACE_NORTH, FACE_SOUTH, FACE_WEST};
    let turns = (yaw / std::f32::consts::FRAC_PI_2).round() as i32;
    match turns.rem_euclid(4) {
        0 => FACE_SOUTH, // looking north, so approach from the south
        1 => FACE_EAST,
        2 => FACE_NORTH,
        _ => FACE_WEST,
    }
}

/// The meta byte for placing `block` against `hit`. Cubes get 0; slabs and stairs pick their half
/// from the face that was clicked, falling back to which half of a side face was hit.
fn placement_meta(block: Block, hit: &crate::raycast::HitResult, origin: Vec3, dir: Vec3, yaw: f32) -> u8 {
    use crate::world::block::META_TOP;
    if block.shape() == Shape::Cube { return 0; }
    let top = match hit.normal.1 {
        1 => false,  // placed on a surface: rests on the floor of its cell
        -1 => true,  // placed under a ceiling: hangs from the top
        _ => {
            // A side face: the half of the face that was clicked decides.
            let point = origin + dir.normalize_or_zero() * hit.dist;
            point.y - point.y.floor() > 0.5
        }
    };
    let mut meta = if top { META_TOP } else { 0 };
    if block.shape() == Shape::Stairs { meta |= stair_facing(yaw); }
    meta
}

fn do_place(state: &mut EngineState, origin: Vec3, dir: Vec3) -> bool {
    let sel = state.inventory.selected_block();
    // Seeds are items, so planting has to be handled before the is_item bail-out below.
    if let Some(crop) = Block::crop_from_seed(sel) {
        if let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, player_reach(state)) {
            let (x, y, z) = hit.pos;
            let (tx, ty, tz) = (x + hit.normal.0, y + hit.normal.1, z + hit.normal.2);
            let on_farmland = state.chunks.get_block_world(x, y, z) == Block::Farmland as u8 && hit.normal.1 == 1;
            // Plant only once the write is known to have landed, so an unloaded chunk can't eat the seed.
            if on_farmland && state.chunks.get_block_world(tx, ty, tz) == 0
                && state.chunks.set_block_meta_world(tx, ty, tz, crop as u8, crate::world::block::crop_meta(0))
            {
                if state.inventory.consume_selected().is_none() {
                    state.chunks.set_block_world(tx, ty, tz, 0);
                    return false;
                }
                mark_neighbors_dirty(state, tx, tz);
                return true;
            }
        }
        return false;
    }
    // Items (food, estus, materials) are never placeable as blocks.
    if sel == 0 || crate::item::is_item(sel) { return false; }
    let block = Block::from_id(sel);
    let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, player_reach(state)) else { return false; };

    // Two matching slabs in one cell make the full block again.
    if block.shape() == Shape::Slab {
        let (tx, ty, tz) = hit.pos;
        if state.chunks.get_block_world(tx, ty, tz) == sel {
            let existing_top = state.chunks.get_meta_world(tx, ty, tz) & crate::world::block::META_TOP != 0;
            // Only the face on the cell's empty side can complete it.
            if (existing_top && hit.normal.1 == -1) || (!existing_top && hit.normal.1 == 1) {
                if state.inventory.consume_selected().is_some() {
                    state.chunks.set_block_world(tx, ty, tz, block.parent() as u8);
                    mark_neighbors_dirty(state, tx, tz);
                    return true;
                }
            }
        }
    }

    let (px, py, pz) = hit.prev;
    if state.chunks.get_block_world(px, py, pz) != 0 { return false; }
    let meta = placement_meta(block, &hit, origin, dir, state.player.yaw);
    // Refuse only if the block's actual geometry would intersect the player — a slab at their feet
    // is fine even though a full cube there would not be.
    let pmin = [state.player.pos.x - 0.3, state.player.pos.y, state.player.pos.z - 0.3];
    let pmax = [state.player.pos.x + 0.3, state.player.pos.y + 1.8, state.player.pos.z + 0.3];
    let cell = [px as f32, py as f32, pz as f32];
    if block.collision_boxes(meta).as_slice().iter().any(|b| b.overlaps_at(cell, pmin, pmax)) { return false; }

    if let Some(id) = state.inventory.consume_selected() {
        state.chunks.set_block_meta_world(px, py, pz, id, meta);
        // A chest the player places starts empty; only chests already in the world roll loot.
        if id == 83 { state.containers.insert_empty((state.dim, px, py, pz)); }
        mark_neighbors_dirty(state, px, pz);
        return true;
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
            let reach = player_reach(state);
            let block_hit = crate::raycast::raycast(&state.chunks, o, d, reach);
            let bdist = block_hit.as_ref().map(|h| (vec3(h.pos.0 as f32 + 0.5, h.pos.1 as f32 + 0.5, h.pos.2 as f32 + 0.5) - o).length()).unwrap_or(f32::INFINITY);
            if let Some(idx) = nearest_mob_hit(state, o, d, reach - 1.5, bdist) {
                if state.mobs[idx].kind == MobKind::Villager {
                    state.trade_prof = state.mobs[idx].profession;
                    return 20;
                }
                // Shearing a sheep: wool now, and the sheep walks away unharmed to regrow it.
                let held = state.inventory.selected_block();
                if crate::item::is_shears(held) && state.mobs[idx].kind == MobKind::Sheep && !state.mobs[idx].sheared {
                    if !state.inventory.has_room_for(Block::Wool as u8, WOOL_PER_SHEARING) { return 0; }
                    state.mobs[idx].sheared = true;
                    state.did_shear = true;
                    for _ in 0..WOOL_PER_SHEARING { state.inventory.add_block(Block::Wool as u8); }
                    damage_tool(state);
                    return 1;
                }
            }
            if let Some(hit) = block_hit {
                let (x, y, z) = hit.pos;
                let id = state.chunks.get_block_world(x, y, z);
                // Resting at a Warding Stone burns off the night. Matcha sets `can_sleep: always` and
                // fast-forwards time; there are no beds here, so the bonfire does the job.
                if id == 81 {
                    if !state.is_night() { return 0; }
                    let skip = secs_until_dawn(state.day_t());
                    state.start_time -= std::time::Duration::from_secs_f32(skip);
                    state.did_rest = true;
                    state.player.heal(state.player.max_health);
                    // You wake to whatever weather the night left behind, but never mid-downpour.
                    state.weather = WEATHER_CLEAR;
                    state.weather_cd = weather_duration(0.4);
                    let c = vec3(x as f32 + 0.5, y as f32 + 1.2, z as f32 + 0.5);
                    spawn_particles(&mut state.spawn_rng, &mut state.particles, c, 20, [1.0, 0.82, 0.42], 2.0, 1.0, 0.12);
                    return 1;
                }
                if id == 83 { // chest: open its container
                    let key = ensure_chest(state, x, y, z);
                    state.open_chest = Some(key);
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::world::block::{FACE_EAST, FACE_NORTH, FACE_SOUTH, FACE_WEST};

    // Yaw 0 looks north, and yaw grows counter-clockwise (see Player::forward). A stair's low side
    // must end up facing the player so that walking forward climbs it.
    #[test]
    fn stairs_face_back_toward_the_player() {
        use std::f32::consts::FRAC_PI_2;
        assert_eq!(stair_facing(0.0), FACE_SOUTH, "looking north, approach from the south");
        assert_eq!(stair_facing(FRAC_PI_2), FACE_EAST, "looking west, approach from the east");
        assert_eq!(stair_facing(FRAC_PI_2 * 2.0), FACE_NORTH);
        assert_eq!(stair_facing(FRAC_PI_2 * 3.0), FACE_WEST);
        // Wraps cleanly, and snaps from in-between angles.
        assert_eq!(stair_facing(FRAC_PI_2 * 4.0), FACE_SOUTH);
        assert_eq!(stair_facing(-FRAC_PI_2), FACE_WEST);
        assert_eq!(stair_facing(0.3), FACE_SOUTH, "a small tilt still reads as north");
    }

    // A fresh world must open in daylight, and the night has to be long enough to matter but short
    // enough to wait out on a phone.
    #[test]
    fn the_day_starts_at_noon_and_night_is_half_the_cycle() {
        assert!((day_t_at(0.0) - 0.5).abs() < 1e-4, "a fresh world opens at midday");
        assert!(!is_night_at(day_t_at(0.0)));
        // Quarter-cycle steps walk noon -> dusk -> midnight -> dawn -> noon.
        let q = DAY_CYCLE * 0.25;
        assert!(is_night_at(day_t_at(q * 1.5)), "dusk has fallen a cycle-eighth after sunset");
        assert!(is_night_at(day_t_at(q * 2.0)), "midnight");
        assert!(!is_night_at(day_t_at(q * 4.0)), "back to noon a full day later");

        let steps = 2000;
        let nights = (0..steps).filter(|i| is_night_at(day_t_at(DAY_CYCLE * *i as f32 / steps as f32))).count();
        let fraction = nights as f32 / steps as f32;
        assert!((fraction - 0.5).abs() < 0.01, "sun-below-horizon is half the cycle, got {fraction}");
        let night_secs = DAY_CYCLE * fraction;
        assert!((240.0..600.0).contains(&night_secs), "night lasts {night_secs}s, outside the playable range");
    }

    // world_secs is stored as elapsed-since-start and load rewinds start_time by it, so a save taken
    // at some time of day reopens at that same time of day. The clamp is the part that can bite: a
    // session past the clamp silently jumps, so the ceiling has to be a whole number of days.
    #[test]
    fn a_saved_clock_resumes_at_the_same_time_of_day() {
        const CLAMP: f32 = 86_400.0;
        for elapsed in [0.0f32, 37.5, DAY_CYCLE * 0.3, DAY_CYCLE * 1.7, CLAMP - 1.0] {
            let resumed = day_t_at(elapsed.clamp(0.0, CLAMP));
            assert!((day_t_at(elapsed) - resumed).abs() < 1e-4, "{elapsed}s round-tripped to a different phase");
        }
        assert!((CLAMP / DAY_CYCLE).fract() < 1e-6, "the world_secs clamp must be a whole number of days");
    }

    // Resting always moves the clock forward to the same point in the morning, never backwards and
    // never by more than a day.
    #[test]
    fn resting_always_skips_forward_to_dawn() {
        for t in [0.0f32, 0.1, 0.24, 0.26, 0.28, 0.5, 0.76, 0.99] {
            let skip = secs_until_dawn(t);
            assert!(skip >= 0.0, "the clock went backwards from {t}");
            assert!(skip <= DAY_CYCLE + 1e-3, "skipping {skip}s from {t} is more than a day");
            let landed = (t + skip / DAY_CYCLE) % 1.0;
            assert!((landed - DAWN).abs() < 1e-3 || (landed - DAWN).abs() > 0.999, "{t} landed at {landed}");
            assert!(!is_night_at(DAWN), "dawn must not itself count as night");
        }
        // Resting at dawn costs a whole day rather than doing nothing surprising.
        assert!((secs_until_dawn(DAWN) - 0.0).abs() < 1e-3 || secs_until_dawn(DAWN) >= DAY_CYCLE - 1e-3);
    }

    // A dig site has to be worth digging: every roll gives a real item, most of them modest.
    #[test]
    fn every_buried_find_is_a_real_item() {
        let mut diamonds = 0;
        let n = 10_000;
        for i in 0..n {
            let id = buried_find(i as f32 / n as f32);
            assert!(id != 0, "an empty dig site");
            assert!(id <= crate::world::block::MAX_BLOCK_ID || crate::item::is_item(id), "{id} is not an id");
            if id == 155 { diamonds += 1; }
        }
        let rate = diamonds as f32 / n as f32;
        assert!(rate > 0.0 && rate < 0.06, "diamonds turn up {rate} of the time, which is not a treasure");
    }

    // The weather is a random walk, so what matters is that it can't wander somewhere invalid and that
    // clear skies stay the common case rather than the game raining most of the time.
    #[test]
    fn weather_stays_mostly_clear_and_never_leaves_its_three_states() {
        let mut w = WEATHER_CLEAR;
        let mut counts = [0usize; 3];
        let mut storm_from = [0usize; 3];
        let steps = 20_000;
        let mut s = 0x1234_5678u32;
        for _ in 0..steps {
            s ^= s << 13; s ^= s >> 17; s ^= s << 5;
            let r = (s >> 8) as f32 / 16_777_216.0;
            let next = next_weather(w, r);
            assert!(next <= WEATHER_STORM, "weather wandered to {next}");
            if next == WEATHER_STORM { storm_from[w as usize] += 1; }
            w = next;
            counts[w as usize] += 1;
        }
        let clear = counts[WEATHER_CLEAR as usize] as f32 / steps as f32;
        assert!(clear > 0.5, "it rains too much: clear only {clear} of the time");
        assert!(counts[WEATHER_RAIN as usize] > 0 && counts[WEATHER_STORM as usize] > 0, "some weather never happens");
        assert_eq!(storm_from[WEATHER_CLEAR as usize], 0, "a storm must build through rain, not out of sunshine");
    }

    #[test]
    fn rain_intensity_lines_up_with_the_spawn_rule() {
        assert_eq!(rain_target(WEATHER_CLEAR), 0.0);
        assert!(rain_target(WEATHER_RAIN) < rain_target(WEATHER_STORM));
        assert!(rain_target(WEATHER_STORM) <= 1.0);
        // Rain is heavy enough to bring hostiles out; a clear sky never is.
        assert!(rain_target(WEATHER_RAIN) >= RAIN_SPAWN_THRESHOLD);
        assert!(rain_target(WEATHER_CLEAR) < RAIN_SPAWN_THRESHOLD);
        // A corrupt saved value reads as clear rather than as a permanent storm.
        assert_eq!(rain_target(200), 0.0);
        for r in [0.0f32, 0.5, 1.0, -3.0, 7.0] {
            let d = weather_duration(r);
            assert!((WEATHER_MIN_SECS..=WEATHER_MAX_SECS).contains(&d), "duration {d} out of range");
        }
    }
}
