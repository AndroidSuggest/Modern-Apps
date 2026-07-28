pub mod world;
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
) -> jboolean {
    let dir = match get_string(&mut env, &files_dir) {
        Some(d) => d,
        None => return 0,
    };
    if engine::init_engine(dir) { 1 } else { 0 }
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
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onJoystickInput<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    move_x: jfloat,
    move_y: jfloat,
    look_yaw: jfloat,
    look_pitch: jfloat,
) {
    input::set_move(move_x, move_y);
    input::add_look(look_yaw, look_pitch);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_onAction<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    jump: jboolean,
    sneak: jboolean,
    toggle_fly: jboolean,
) {
    input::set_action(jump != 0, sneak != 0, toggle_fly != 0, false);
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_breakBlock<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jboolean {
    if engine::break_block_action() { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_games_voxels_util_VoxelsNative_placeBlock<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jboolean {
    if engine::place_block_action() { 1 } else { 0 }
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
        let mesh = mesh_chunk(&c, &|_, _, _| 0);
        let total: usize = mesh.iter().filter_map(|o| o.as_ref()).map(|m| m.vertices.len()).sum();
        assert!(total > 0 || c.sections.iter().all(|s| s.is_none() || s.as_ref().unwrap().is_empty()));
    }
}
