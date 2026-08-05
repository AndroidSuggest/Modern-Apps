pub mod world;
pub mod container;
pub mod entity;
pub mod item;
pub mod player;
pub mod raycast;
pub mod input;
pub mod inventory;
pub mod engine;
pub mod texture_atlas;
pub mod vulkan;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jstring};
use jni::JNIEnv;

#[cfg(target_os = "android")]
use std::os::raw::c_void as CVoid;

fn get_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|js| js.into())
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeInit<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    files_dir: JString<'l>,
    seed: jint,
) -> jboolean {
    let dir = match get_string(&mut env, &files_dir) {
        Some(d) => d,
        None => return 0,
    };
    if engine::init_engine(dir, seed as u32) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceCreated<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
) {
    #[cfg(target_os = "android")]
    {
        let env_raw = env.get_raw() as *mut CVoid;
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
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    width: jint,
    height: jint,
) {
    engine::resize_renderer(width, height);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_surfaceDestroyed<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_renderer();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_nativeOnDestroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    engine::destroy_engine();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onMoveInput<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    move_x: jfloat,
    move_y: jfloat,
) {
    input::set_move(move_x, move_y);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onLookInput<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    look_yaw_rate: jfloat,
    look_pitch_rate: jfloat,
) {
    input::set_look_rate(look_yaw_rate, look_pitch_rate);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setJump<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    held: jboolean,
) {
    input::set_jump(held != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setFlyDown<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    held: jboolean,
) {
    input::set_down(held != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_setSneak<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    on: jboolean,
) {
    input::set_sneak(on != 0);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_toggleFly<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    input::request_toggle_fly();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_breakBlockAt<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    x: jfloat,
    y: jfloat,
) -> jboolean {
    if engine::break_block_at(x, y) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_placeBlockAt<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    x: jfloat,
    y: jfloat,
) -> jint {
    engine::place_block_at(x, y)
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_selectSlot<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    slot: jint,
) {
    engine::with_engine(|s| {
        s.inventory.select(slot.max(0).min(8) as usize);
    });
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_moveItem<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, from: jint, to: jint,
) {
    if from >= 0 && to >= 0 { engine::inventory_move(from as usize, to as usize); }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_giveBlock<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, id: jint,
) {
    if id > 0 { engine::inventory_give(id as u8); }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_craft<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, recipe: jint,
) -> jboolean {
    if recipe >= 0 && engine::inventory_craft(recipe as usize) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getRecipesJson<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    match env.new_string(engine::get_recipes_json()) { Ok(s) => s.into_raw(), Err(_) => null }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_trade<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    if idx >= 0 && engine::do_trade(idx as usize) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getTradesJson<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    match env.new_string(engine::get_trades_json()) { Ok(s) => s.into_raw(), Err(_) => null }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getInventoryJson<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    let json = engine::get_inventory_json();
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => null,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getDebugJson<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    let json = engine::get_debug_json();
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => null,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getStatsJson<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    let json = engine::get_stats_json();
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => null,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getHealthJson<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    match env.new_string(engine::get_health_json()) {
        Ok(s) => s.into_raw(),
        Err(_) => null,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getSmeltingJson<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    match env.new_string(engine::get_smelting_json()) { Ok(s) => s.into_raw(), Err(_) => null }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getSmeltJson<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    match env.new_string(engine::get_smelt_json()) { Ok(s) => s.into_raw(), Err(_) => null }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_startSmelt<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, recipe: jint, blast: jboolean,
) -> jboolean {
    engine::start_smelt(recipe.max(0) as usize, blast != 0) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_stopSmelt<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>,
) {
    engine::stop_smelt();
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_getContainerJson<'l>(
    mut env: JNIEnv<'l>, _class: JClass<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    match env.new_string(engine::get_container_json()) { Ok(s) => s.into_raw(), Err(_) => null }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_containerTake<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::container_take(idx.max(0) as usize) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_containerPut<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>, idx: jint,
) -> jboolean {
    engine::container_put(idx.max(0) as usize) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_closeContainer<'l>(
    _env: JNIEnv<'l>, _class: JClass<'l>,
) {
    engine::close_container();
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
        let mesh = mesh_chunk(&c, &|_, _, _| 0, &|_, _| [0.4, 0.7, 0.3]);
        let total: usize = mesh.iter().filter_map(|o| o.as_ref()).map(|m| m.vertices.len()).sum();
        assert!(total > 0 || c.sections.iter().all(|s| s.is_none() || s.as_ref().unwrap().is_empty()));
    }
}
