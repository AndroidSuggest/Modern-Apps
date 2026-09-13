//! App pins and simulated transit vehicles.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::marker::Marker;
use jni::objects::{JClass, JFloatArray, JIntArray, JLongArray};
use jni::sys::jlong;
use jni::JNIEnv;
use super::handle::handle_mut;
use super::log::log;
/// Replace the app's pins with a marker set, drawn by the renderer as billboarded sprites.
///
/// Three parallel bulk arrays, the same convention as
/// [`setRoute`](Java_com_vayunmathur_library_map_MapNative_setRoute) and
/// [`setTrafficSpeeds`](Java_com_vayunmathur_library_map_MapNative_setTrafficSpeeds): `ids[i]` is
/// the host's own stable id for marker `i` (echoed back by
/// [`pickAt`](Java_com_vayunmathur_library_map_MapNative_pickAt)), `lonLat` holds
/// `[lon0, lat0, lon1, lat1, …]`, and `icons[i]` is the icon id (see `crate::marker::icon`). Bulk
/// arrays rather than a list of objects so a viewport's worth of pins crosses the boundary in a
/// few `get_*_array_region` reads with no per-pin JNI traffic; `float` coordinates for the same
/// reason the camera's are.
///
/// The whole set is replaced each call, not merged — a stale pin left behind would sit under the
/// finger and pick wrong. Moving the pins into the renderer is what stops them trailing the basemap
/// on a pan or tilt the way the Compose overlays did. Mismatched lengths are truncated to the
/// shortest; an empty set is the same as
/// [`clearMarkers`](Java_com_vayunmathur_library_map_MapNative_clearMarkers). Arrays that cannot be
/// read leave the markers **unchanged** rather than blanking them.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setMarkers<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JLongArray<'l>,
    lon_lat: JFloatArray<'l>,
    icons: JIntArray<'l>,
) {
    let Some(map) = handle_mut(handle) else { return };
    let id_len = env.get_array_length(&ids).unwrap_or(0).max(0) as usize;
    let icon_len = env.get_array_length(&icons).unwrap_or(0).max(0) as usize;
    let coord_len = env.get_array_length(&lon_lat).unwrap_or(0).max(0) as usize;
    // Each marker consumes two floats (lon, lat), so the coordinate array bounds the count too.
    let n = id_len.min(icon_len).min(coord_len / 2);
    if n == 0 {
        map.renderer.set_markers(Vec::new());
        return;
    }
    let mut id_buf = vec![0i64; n];
    let mut icon_buf = vec![0i32; n];
    let mut coord_buf = vec![0f32; n * 2];
    if env.get_long_array_region(&ids, 0, &mut id_buf).is_err()
        || env.get_int_array_region(&icons, 0, &mut icon_buf).is_err()
        || env.get_float_array_region(&lon_lat, 0, &mut coord_buf).is_err()
    {
        log("the marker arrays could not be read; leaving the markers unchanged");
        return;
    }
    let markers: Vec<Marker> = (0..n)
        .map(|i| Marker {
            // A Kotlin id is a signed `long` and an icon a signed `int`; the bit pattern is what
            // matters and the cast keeps it.
            id: id_buf[i] as u64,
            lon: coord_buf[i * 2] as f64,
            lat: coord_buf[i * 2 + 1] as f64,
            icon: icon_buf[i] as u32,
        })
        .collect();
    map.renderer.set_markers(markers);
}

/// Take every marker away: the host cleared its pins.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearMarkers<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_markers(Vec::new());
    }
}

/// Replace the simulated transit vehicles with a set the renderer draws as billboarded sprites.
///
/// The same three parallel bulk arrays as
/// [`setMarkers`](Java_com_vayunmathur_library_map_MapNative_setMarkers) — `ids[i]` the host's
/// stable per-trip id, `lonLat` the flat `[lon0, lat0, lon1, lat1, …]`, and `icons[i]` the mode
/// sprite id (a vehicle uses the `VEHICLE_*` ids in `crate::marker::icon`) — because a vehicle is
/// just a [`Marker`] whose icon names a mode sprite, so it reuses the marker draw path verbatim.
///
/// Separate from [`setMarkers`](Java_com_vayunmathur_library_map_MapNative_setMarkers) so the app's
/// ~1 Hz vehicle recompute replaces only the vehicles, leaving the pins (which change on a tap or
/// search) untouched, and so the moving vehicle sprites stay out of the pin id-buffer pick. The
/// whole set is replaced each call — a trip that ended, left the bbox, or was cancelled must drop
/// out rather than linger. Mismatched lengths are truncated to the shortest; an empty set is the
/// same as [`clearVehicles`](Java_com_vayunmathur_library_map_MapNative_clearVehicles). Arrays that
/// cannot be read leave the vehicles **unchanged** rather than blanking them.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setVehicles<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JLongArray<'l>,
    lon_lat: JFloatArray<'l>,
    icons: JIntArray<'l>,
) {
    let Some(map) = handle_mut(handle) else { return };
    let id_len = env.get_array_length(&ids).unwrap_or(0).max(0) as usize;
    let icon_len = env.get_array_length(&icons).unwrap_or(0).max(0) as usize;
    let coord_len = env.get_array_length(&lon_lat).unwrap_or(0).max(0) as usize;
    // Each vehicle consumes two floats (lon, lat), so the coordinate array bounds the count too.
    let n = id_len.min(icon_len).min(coord_len / 2);
    if n == 0 {
        map.renderer.set_vehicles(Vec::new());
        return;
    }
    let mut id_buf = vec![0i64; n];
    let mut icon_buf = vec![0i32; n];
    let mut coord_buf = vec![0f32; n * 2];
    if env.get_long_array_region(&ids, 0, &mut id_buf).is_err()
        || env.get_int_array_region(&icons, 0, &mut icon_buf).is_err()
        || env.get_float_array_region(&lon_lat, 0, &mut coord_buf).is_err()
    {
        log("the vehicle arrays could not be read; leaving the vehicles unchanged");
        return;
    }
    let vehicles: Vec<Marker> = (0..n)
        .map(|i| Marker {
            id: id_buf[i] as u64,
            lon: coord_buf[i * 2] as f64,
            lat: coord_buf[i * 2 + 1] as f64,
            icon: icon_buf[i] as u32,
        })
        .collect();
    map.renderer.set_vehicles(vehicles);
}

/// Take every simulated vehicle away: the transit toggle went off, or the surface was hidden.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearVehicles<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_vehicles(Vec::new());
    }
}
