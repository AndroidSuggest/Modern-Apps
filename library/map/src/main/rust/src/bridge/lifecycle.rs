//! Surface lifecycle: create, resize, destroy, online flag, frame scheduling.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use super::handle::{handle_mut, MapHandle, OnlineFlag, TileResult, ZoomRange, WORKER_COUNT};
use super::log::log;
use super::workers::spawn_worker;
use crate::style::{self, LayerToggles, Palette, SharedToggles};
use crate::tile::select::TileId;
use crate::tile::source::BASEMAP_ARCHIVE_URL;
use crate::vulkan::context::{ANativeWindow_acquire, ANativeWindow_fromSurface};
use crate::vulkan::renderer::Renderer;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use std::collections::{HashMap, HashSet};
use std::os::raw::c_void;
use std::sync::{Arc, Mutex};
/// Create the renderer for `surface`. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called from the JVM with a live `Surface`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_create<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    surface: JObject<'l>,
    cache_dir: JString<'l>,
    local_path: JString<'l>,
    width: jint,
    height: jint,
    dark: jboolean,
    muted: jboolean,
) -> jlong {
    // The bridge back to `:library:network` has to be resolved before any worker thread
    // needs it; doing it here means the failure is visible at startup rather than as a
    // silently blank map.
    if !jni_http::init(&mut env) {
        log("library:network is missing, so remote tiles cannot be fetched");
        return 0;
    }

    let cache_dir: String = match env.get_string(&cache_dir) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    // A pushed on-device archive, or `None` when the path is null/empty: the worker then
    // reads that file directly instead of the built-in URL. A null or empty string means
    // "no local file", which is the default every existing caller passes.
    let local_path: Option<String> = if local_path.is_null() {
        None
    } else {
        match env.get_string(&local_path) {
            Ok(s) => {
                let s: String = s.into();
                if s.is_empty() {
                    None
                } else {
                    Some(s)
                }
            }
            Err(_) => None,
        }
    };

    let window = unsafe {
        let raw_env = env.get_raw() as *mut c_void;
        let raw_surface = surface.as_raw() as *mut c_void;
        ANativeWindow_fromSurface(raw_env, raw_surface)
    };
    if window.is_null() {
        log("ANativeWindow_fromSurface returned null");
        return 0;
    }
    unsafe { ANativeWindow_acquire(window) };

    let renderer = match unsafe {
        Renderer::new(
            window,
            width.max(1) as u32,
            height.max(1) as u32,
            std::path::Path::new(&cache_dir),
        )
    } {
        Ok(r) => r,
        Err(e) => {
            log(&format!("Vulkan init failed: {e}"));
            // The renderer never took ownership, so the window is released here.
            unsafe { crate::vulkan::context::ANativeWindow_release(window) };
            return 0;
        }
    };

    let online = Arc::new(OnlineFlag(std::sync::atomic::AtomicBool::new(true)));
    let (wanted_tx, wanted_rx) = std::sync::mpsc::channel::<TileId>();
    let (finished_tx, finished_rx) = std::sync::mpsc::channel::<(u64, TileResult)>();

    let zoom_range = Arc::new(ZoomRange::unknown());
    // Both optional layers start off: the five existing consumers never asked for POI
    // icons or transit lines, and defaulting them on would make every one of them pay
    // for shaping labels it does not draw.
    let toggles = Arc::new(SharedToggles::new(LayerToggles::default()));
    // One `Receiver` shared by every worker, so whichever is free takes the next tile.
    let queue = Arc::new(Mutex::new(wanted_rx));
    // One cold-start fetch shared by every worker: the header `build_id` (one JNI round trip,
    // not one per worker) and the forced first-read revalidation (one prefix read, not one per
    // worker). See `spawn_worker`.
    let header = Arc::new(std::sync::OnceLock::new());
    let prefix_gate = Arc::new(std::sync::atomic::AtomicBool::new(false));
    for index in 0..WORKER_COUNT {
        spawn_worker(
            index,
            BASEMAP_ARCHIVE_URL.to_string(),
            cache_dir.clone(),
            local_path.clone(),
            queue.clone(),
            finished_tx.clone(),
            online.clone(),
            zoom_range.clone(),
            toggles.clone(),
            header.clone(),
            prefix_gate.clone(),
        );
    }

    let handle = Box::new(MapHandle {
        renderer,
        layers: style::layers(),
        finished: finished_rx,
        wanted: wanted_tx,
        in_flight: HashSet::new(),
        absent: HashSet::new(),
        retry: HashMap::new(),
        online,
        palette: Palette::new(dark != 0, muted != 0),
        toggles,
        zoom_range,
        frames: 0,
        density: 1.0,
    });
    Box::into_raw(handle) as jlong
}

/// How long the host may wait before the next frame: `0` to draw again now, a positive number
/// of milliseconds to draw again then, or `-1` when nothing is pending at all.
///
/// The host renders on demand rather than every vsync, and asks this after each frame. It
/// answers for the pending work the Kotlin side cannot see:
///
/// - **tiles in flight** \u2014 draw now. A worker finishing a tile only reaches the screen through
///   [`render`](Java_com_vayunmathur_library_map_MapNative_render), which is what drains
///   `finished` and uploads. Nothing calls back into Kotlin when one lands, so idling with
///   requests outstanding leaves the map permanently missing whatever was still being fetched.
///   This also covers the bounded drain (a burst larger than `UPLOADS_PER_FRAME` finishes over
///   several frames, and the rest stay in `in_flight`) and the re-tessellation a layer toggle
///   triggers \u2014 both keep keys in `in_flight`.
/// - **the renderer's own pending work** \u2014 draw now. See [`Renderer::needs_frame`].
/// - **a failed tile inside its retry backoff** \u2014 draw *then*. This is why the answer is a
///   delay rather than a flag. A backed-off tile is deliberately not in `in_flight`, so
///   nothing else here reports it, and without a deadline to wake on, a tile that failed while
///   the camera was static would stay missing until the user happened to pan. Reporting the
///   wait instead lets the host sleep exactly that long and then retry once, rather than
///   either spinning through the backoff or never retrying at all.
///
/// Errs toward drawing throughout. A wasted frame costs one frame; a wrong `-1` freezes the
/// map until the user touches it.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_nextFrameDelayMillis<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jlong {
    let Some(map) = handle_mut(handle) else {
        return -1;
    };
    if !map.in_flight.is_empty() || map.renderer.needs_frame() {
        return 0;
    }
    let now = std::time::Instant::now();
    let soonest = map.retry.values().map(|(_, at)| *at).min();
    match soonest {
        // Already due but not yet re-requested: the fetch loop only runs inside a frame, so
        // this asks for the frame that will issue it.
        Some(at) if at <= now => 0,
        // At least 1, so a sub-millisecond wait is never confused with "draw now".
        Some(at) => (at - now).as_millis().max(1).min(jlong::MAX as u128) as jlong,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_resize<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    width: jint,
    height: jint,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer
            .resize(width.max(0) as u32, height.max(0) as u32);
    }
}

/// Switch palette. Free: colour is a push constant and the layer set is identical, so
/// nothing is re-tessellated or re-uploaded.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setPalette<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    dark: jboolean,
    muted: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        map.palette = Palette::new(dark != 0, muted != 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setOnline<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    online: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        map.online.set(online != 0);
    }
}

/// Destroy the renderer and release its window.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_destroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // Dropping the handle drops the sender, which ends the worker's `recv` loop, and then
    // the renderer, which waits for the device to go idle before freeing anything.
    unsafe { drop(Box::from_raw(handle as *mut MapHandle)) };
}
