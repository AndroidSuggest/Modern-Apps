pub mod world;
pub mod blessing;
pub mod container;
pub mod entity;
pub mod item;
pub mod player;
pub mod raycast;
pub mod input;
pub mod inventory;
pub mod villager;
pub mod ambience;
pub mod fishing;
pub mod engine;
pub mod net;
pub mod texture_atlas;
pub mod vulkan;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jstring};
use jni::{Env, EnvUnowned};

#[cfg(target_os = "android")]
use std::os::raw::c_void as CVoid;

fn get_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeInit<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    files_dir: JString<'l>,
    seed: jint,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        let dir = match get_string(env, &files_dir) {
            Some(d) => d,
            None => return Ok(false),
        };
        Ok(engine::init_engine(dir, seed as u32))
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceCreated<'l>(
    env: EnvUnowned<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
) {
    #[cfg(target_os = "android")]
    {
        let env_raw = env.as_raw() as *mut CVoid;
        let surf_raw = surface.as_raw() as *mut CVoid;
        let window = unsafe { vulkan::context::ANativeWindow_fromSurface(env_raw, surf_raw) };
        if window.is_null() {
            return;
        }
        unsafe { vulkan::context::ANativeWindow_acquire(window) };
        let _ = engine::create_renderer(window, 1280, 720);
        std::thread::spawn(|| {
            let mut last = std::time::Instant::now();
            loop {
                let has_renderer = engine::with_engine(|s| s.renderer.is_some()).unwrap_or(false);
                if !has_renderer {
                    break;
                }
                engine::tick_and_render();
                let elapsed = last.elapsed();
                let target = std::time::Duration::from_millis(16);
                if elapsed < target {
                    std::thread::sleep(target - elapsed);
                }
                last = std::time::Instant::now();
                let running = engine::with_engine(|s| s.running).unwrap_or(false);
                if !running {
                    break;
                }
            }
        });
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (env, surface);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceChanged<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    width: jint,
    height: jint,
) {
    engine::resize_renderer(width, height);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceDestroyed<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_renderer();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeOnDestroy<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_engine();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onMoveInput<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    move_x: jfloat,
    move_y: jfloat,
) {
    input::set_move(move_x, move_y);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onLookInput<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    look_yaw_rate: jfloat,
    look_pitch_rate: jfloat,
) {
    input::set_look_rate(look_yaw_rate, look_pitch_rate);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setJump<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    held: jboolean,
) {
    input::set_jump(held);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setFlyDown<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    held: jboolean,
) {
    input::set_down(held);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setSneak<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    on: jboolean,
) {
    input::set_sneak(on);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_toggleFly<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    input::request_toggle_fly();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_breakBlockAt<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    x: jfloat,
    y: jfloat,
) -> jboolean {
    engine::break_block_at(x, y)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_placeBlockAt<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    x: jfloat,
    y: jfloat,
) -> jint {
    engine::place_block_at(x, y)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_selectSlot<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    slot: jint,
) {
    engine::with_engine(|s| {
        s.inventory.select(slot.max(0).min(8) as usize);
    });
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_moveItem<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, from: jint, to: jint,
) {
    if from >= 0 && to >= 0 { engine::inventory_move(from as usize, to as usize); }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_giveBlock<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, id: jint,
) {
    // The only id that crosses JNI as a number rather than as JSON. `as` would wrap a jint that
    // doesn't fit into a different, real block, so refuse it instead.
    if let Ok(id) = crate::world::block::Id::try_from(id) { if id > 0 { engine::inventory_give(id); } }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_craft<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, recipe: jint,
) -> jboolean {
    recipe >= 0 && engine::inventory_craft(recipe as usize)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getRecipesJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_recipes_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_trade<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    idx >= 0 && engine::do_trade(idx as usize)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getTradesJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_trades_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getInventoryJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        let json = engine::get_inventory_json();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getDebugJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        let json = engine::get_debug_json();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getStatsJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        let json = engine::get_stats_json();
        match env.new_string(json) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getHealthJson<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_health_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getSmeltingJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_smelting_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getSmeltJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_smelt_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getAmbienceJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_ambience_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_startSmelt<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, recipe: jint, blast: jboolean,
) -> jboolean {
    engine::start_smelt(recipe.max(0) as usize, blast)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_stopSmelt<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>,
) {
    engine::stop_smelt();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getContainerJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_container_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_containerTake<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::container_take(idx.max(0) as usize) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_containerPut<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::container_put(idx.max(0) as usize) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_closeContainer<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>,
) {
    engine::close_container();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getBlessingsJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_blessings_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getBlessingCatalogJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_blessing_catalog_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_attuneBlessing<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::attune_blessing(idx.max(0) as usize) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_releaseBlessing<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, slot: jint,
) -> jboolean {
    engine::release_blessing(slot.max(0) as usize) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getCutsJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(engine::get_cuts_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_cut<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    idx >= 0 && engine::do_cut(idx as usize)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeSetRole<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>, role: jint,
) {
    crate::net::set_role(role.clamp(0, 2) as u8);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeSetDevice<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, device: JString<'l>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        if let Some(d) = get_string(env, &device) { crate::net::set_local_device(d); }
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_netPushInbound<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>, json: JString<'l>,
) -> jboolean {
    env.with_env(|env| -> jni::errors::Result<jboolean> {
        match get_string(env, &json) {
            Some(s) => Ok(crate::net::push_inbound_json(&s)),
            None => Ok(false),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_netDrainOutbound<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(crate::net::drain_outbound_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getPeersJson<'l>(
    mut env: EnvUnowned<'l>, _class: JClass<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        match env.new_string(crate::net::peers_json()) {
            Ok(s) => Ok(s.into_raw()),
            Err(_) => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_netRequestContainer<'l>(
    _env: EnvUnowned<'l>, _class: JClass<'l>,
) {
    crate::engine::request_container_sync();
}

#[cfg(test)]
mod tests {
    use crate::world::chunk::{Chunk, ChunkPos};
    use crate::world::mesher::mesh_chunk;
    #[test]
    fn engine_smoke() {
        use crate::world::generator::TerrainGen;
        let gen = TerrainGen::new(42);
        let h = gen.height_at(0.0, 0.0);
        assert!(h > 0);
        let mut c = Chunk::new(ChunkPos(0, 0));
        gen.fill_chunk(&mut c);
        assert!(c.generated);
        let mesh = mesh_chunk(&c, &|_, _, _| (0, 0), &|_, _| [0.4, 0.7, 0.3]);
        let total: usize = mesh.iter().filter_map(|o| o.as_ref()).map(|m| m.vertices.len()).sum();
        assert!(total > 0 || c.sections.iter().all(|s| s.is_none() || s.as_ref().unwrap().is_empty()));
    }
}
