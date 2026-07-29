use crate::world::{ChunkMap, block::Block};
use crate::player::Player;
use crate::inventory::Inventory;
use crate::entity::{Mob, MobKind, build_entity_mesh};
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
    let (px, py, pz, yaw, pitch, inv) = if let Some(ps) = player_save {
        let inv = {
            let mut inv = Inventory::default();
            inv.selected = ps.inventory.selected.min(8);
            for (i, slot) in ps.inventory.slots.iter().enumerate().take(inv.slots.len()) {
                inv.slots[i].id = slot.id;
                inv.slots[i].count = slot.count;
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

    let mut chunks = ChunkMap::new(seed, save_dir.clone());
    chunks.ensure_radius(px as i32, pz as i32);

    if !had_save {
        for y in (0..128).rev() {
            if chunks.get_block_world(0, y, 0) != 0 && Block::from_id(chunks.get_block_world(0, y, 0)).is_solid() {
                player.pos.y = y as f32 + 1.1;
                break;
            }
        }
    }

    *guard = Some(EngineState {
        chunks,
        player,
        inventory: inv,
        save_dir,
        renderer: None,
        width: 0,
        height: 0,
        start_time: Instant::now(),
        last_tick: Instant::now(),
        window_ptr: None,
        needs_resize: false,
        running: true,
        mobs: Vec::new(),
        spawn_timer: 2.0,
        spawn_rng: seed ^ 0x9E3779B9,
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
                night_seen: state.inventory.placed > 0 && state.start_time.elapsed().as_secs() > 60,
            },
        };
        let _ = crate::world::save::save_player(&state.save_dir, &ps);
        state.chunks.save_all();
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

        state.player.tick(dt, &input_mut, &state.chunks);

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
                    let world_time = 60.0 + (Instant::now() - state.start_time).as_secs_f32();
                    let day_t = (world_time / 120.0) % 1.0;
                    // Sun is below the horizon (night) near the ends of the cycle; ~noon at day_t=0.5.
                    let night = day_t < 0.25 || day_t > 0.75;
                    let kind = if night {
                        if rand(&mut rng) < 0.5 { MobKind::Zombie } else { MobKind::Creeper }
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
        let (entity_verts, entity_indices) = build_entity_mesh(&state.mobs);

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
            let time = 60.0 + (Instant::now() - state.start_time).as_secs_f32();
            let eb = (eye.x.floor() as i32, eye.y.floor() as i32, eye.z.floor() as i32);
            let underwater = if state.chunks.get_block_world(eb.0, eb.1, eb.2) == 12 { 1.0 } else { 0.0 };
            unsafe {
                renderer.update_ubo(view_proj, state.player.pos, time, underwater);
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
    let time = (Instant::now() - state.start_time).as_secs_f32();
    let day_t = (time / 120.0) % 1.0;
    let stats = serde_json::json!({ "placed": state.inventory.placed, "broken": state.inventory.broken, "walked": state.player.walk_dist as i32, "night": day_t > 0.5 && day_t < 0.92 }).to_string();
    let inv = state.inventory.to_json();
    if let Ok(mut c) = cref(&DEBUG_CACHE, "{}").lock() { *c = debug; }
    if let Ok(mut c) = cref(&STATS_CACHE, "{}").lock() { *c = stats; }
    if let Ok(mut c) = cref(&INV_CACHE, r#"{"selected":0,"slots":[]}"#).lock() { *c = inv; }
}

pub fn get_debug_json() -> String { cref(&DEBUG_CACHE, r#"{"error":"no engine"}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }
pub fn get_inventory_json() -> String { cref(&INV_CACHE, r#"{"selected":0,"slots":[]}"#).lock().map(|c| c.clone()).unwrap_or_else(|_| r#"{"selected":0,"slots":[]}"#.into()) }
pub fn get_stats_json() -> String { cref(&STATS_CACHE, "{}").lock().map(|c| c.clone()).unwrap_or_else(|_| "{}".into()) }

pub fn inventory_move(from: usize, to: usize) { with_engine(|s| s.inventory.move_item(from, to)); }
pub fn inventory_give(id: u8) { with_engine(|s| s.inventory.give(id)); }
pub fn inventory_craft(recipe: usize) -> bool { with_engine(|s| s.inventory.craft(recipe)).unwrap_or(false) }
pub fn get_recipes_json() -> String {
    let items: Vec<_> = crate::inventory::RECIPES.iter()
        .map(|(iid, ic, oid, oc)| serde_json::json!({"in": iid, "inN": ic, "out": oid, "outN": oc}))
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

fn do_break(state: &mut EngineState, origin: Vec3, dir: Vec3) -> bool {
    if let Some(hit) = crate::raycast::raycast(&state.chunks, origin, dir, 6.0) {
        let (x,y,z) = hit.pos;
        let id = state.chunks.get_block_world(x,y,z);
        if id != 0 {
            state.chunks.set_block_world(x,y,z, 0);
            state.inventory.add_block(id);
            state.inventory.broken += 1;
            mark_neighbors_dirty(state, x, z);
            return true;
        }
    }
    false
}

fn do_place(state: &mut EngineState, origin: Vec3, dir: Vec3) -> bool {
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
        // Tapping an interactive block opens its menu — unless sneaking, which places instead.
        if !state.player.sneaking {
            if let Some(hit) = crate::raycast::raycast(&state.chunks, o, d, 6.0) {
                let (x, y, z) = hit.pos;
                let m = crate::world::block::Block::from_id(state.chunks.get_block_world(x, y, z)).menu();
                if m != 0 { return 10 + m; }
            }
        }
        if do_place(state, o, d) { 1 } else { 0 }
    }).unwrap_or(0)
}
