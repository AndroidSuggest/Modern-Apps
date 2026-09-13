//! Live-traffic JNI: `updateTrafficNative`, `notifyTrafficFetchFinishedNative`,
//! `getTrafficSegmentsNative`, `getTrafficTileNative` and
//! `ensureTrafficLoadedNative`, plus the per-square segment cache they share.
//! Pure move out of `lib.rs`; no logic changes.

use std::collections::BTreeMap;
use std::sync::{Mutex, OnceLock};

use jni::objects::{JByteArray, JLongArray, JObject};
use jni::sys::{jboolean, jbyteArray, jdouble, jdoubleArray, jint};
use jni::JNIEnv;

use super::routes::{ensure_traffic_loaded, graph};

/// Per-square traffic segments + requested-square set for the overlay/tiles.
pub(crate) struct TrafficMeta {
    by_square: BTreeMap<i32, Vec<f64>>,
    pub(crate) requested: Vec<u32>,
}

pub(crate) fn traffic_meta() -> &'static Mutex<TrafficMeta> {
    static M: OnceLock<Mutex<TrafficMeta>> = OnceLock::new();
    M.get_or_init(|| {
        Mutex::new(TrafficMeta {
            by_square: BTreeMap::new(),
            requested: Vec::new(),
        })
    })
}

// ---------------------------------------------------------------------------
// JNI: updateTrafficNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_updateTrafficNative<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    edge_ids: JLongArray<'local>,
    speeds: JByteArray<'local>,
    packed_square: jint,
) {
    let g = match graph() {
        Some(g) => g,
        None => return,
    };

    let len = env.get_array_length(&edge_ids).unwrap_or(0);
    if len <= 0 {
        // Still record an (empty) square so the overlay clears it.
        let mut meta = traffic_meta().lock().unwrap();
        meta.by_square.entry(packed_square).or_default().clear();
        return;
    }
    let mut ids = vec![0i64; len as usize];
    if env.get_long_array_region(&edge_ids, 0, &mut ids).is_err() {
        return;
    }
    let mut sp = vec![0i8; len as usize];
    if env.get_byte_array_region(&speeds, 0, &mut sp).is_err() {
        return;
    }

    let mut speeds_w = super::routes::traffic_speeds().write().unwrap();
    let mut meta = traffic_meta().lock().unwrap();
    let segments = meta.by_square.entry(packed_square).or_default();
    segments.clear();

    for i in 0..len as usize {
        let edge_id = ids[i] as u64;
        let speed = sp[i] as u8;
        if edge_id < g.edge_count {
            if speed < 255 {
                speeds_w.insert(edge_id, speed);
                // The only entry point with no source node in hand: the id comes
                // from Kotlin. Recovered first, because reading the record needs it.
                let source = g.find_node_idx_for_edge(edge_id);
                let edge = g.edge(source, edge_id);
                let node_u = g.node(source);
                if edge.target < g.node_count {
                    let node_v = g.node(edge.target);
                    let ratio = if edge.speed_limit > 0 {
                        speed as f64 / edge.speed_limit as f64
                    } else {
                        1.0
                    };
                    segments.push(node_u.lat_e7 as f64 * 1e-7);
                    segments.push(node_u.lon_e7 as f64 * 1e-7);
                    segments.push(node_v.lat_e7 as f64 * 1e-7);
                    segments.push(node_v.lon_e7 as f64 * 1e-7);
                    segments.push(ratio);
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// JNI: notifyTrafficFetchFinishedNative (no-op, kept for ABI compatibility)
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_notifyTrafficFetchFinishedNative<
    'local,
>(
    _env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    _packed_square: jint,
) {
}

// ---------------------------------------------------------------------------
// JNI: getTrafficSegmentsNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficSegmentsNative<
    'local,
>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
) -> jdoubleArray {
    let null = std::ptr::null_mut();
    let meta = traffic_meta().lock().unwrap();

    let mut flattened: Vec<f64> = Vec::new();
    for segments in meta.by_square.values() {
        flattened.extend_from_slice(segments);
        if flattened.len() > 50000 {
            break;
        }
    }
    if flattened.len() > 50000 {
        flattened.truncate(50000);
    }

    match env.new_double_array(flattened.len() as i32) {
        Ok(arr) => {
            if !flattened.is_empty()
                && env.set_double_array_region(&arr, 0, &flattened).is_err()
            {
                return null;
            }
            arr.into_raw()
        }
        Err(_) => null,
    }
}

// ---------------------------------------------------------------------------
// JNI: getTrafficTileNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTrafficTileNative<'local>(
    env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    z: jint,
    x: jint,
    y: jint,
) -> jbyteArray {
    let null = std::ptr::null_mut();
    let tile = {
        let meta = traffic_meta().lock().unwrap();
        crate::mvt::generate_traffic_tile(&meta.by_square, z, x, y)
    };
    let bytes = match tile {
        Some(b) => b,
        None => return null,
    };

    // set_byte_array_region takes &[i8]; reinterpret the gzip bytes.
    let signed: &[i8] =
        unsafe { std::slice::from_raw_parts(bytes.as_ptr() as *const i8, bytes.len()) };
    match env.new_byte_array(bytes.len() as i32) {
        Ok(arr) => {
            if env.set_byte_array_region(&arr, 0, signed).is_err() {
                return null;
            }
            arr.into_raw()
        }
        Err(_) => null,
    }
}

// ---------------------------------------------------------------------------
// JNI: ensureTrafficLoadedNative
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_ensureTrafficLoadedNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    thiz: JObject<'local>,
    lat: jdouble,
    lon: jdouble,
    force_async: jboolean,
) {
    if graph().is_none() {
        return;
    }
    ensure_traffic_loaded(
        &mut env,
        &thiz,
        (lat * 1e7) as i32,
        (lon * 1e7) as i32,
        force_async != 0,
    );
}
