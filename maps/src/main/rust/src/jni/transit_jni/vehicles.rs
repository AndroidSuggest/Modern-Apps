//! Simulated in-service vehicle positions JNI: `activeVehiclesNative`.
//!
//! Pure move out of `super` (`transit_jni.rs`); no logic changes.

use jni::objects::{JDoubleArray, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jdouble, jint, jobjectArray};
use jni::JNIEnv;

use super::cache::transit_index;
use super::overlay::read_delay_entries;

// ---------------------------------------------------------------------------
// JNI: activeVehiclesNative (simulated in-service vehicle positions)
// ---------------------------------------------------------------------------

/// Simulated moving transit vehicles for the visible bbox (WS-F): every trip in
/// `<base_path>/<feed>.transit` that is in service at `now_secs` (seconds since
/// feed-local midnight), interpolated to a live `lon,lat,bearing` along its
/// shape. Positions are computed on-device from the pack schedule + shape + the
/// realtime overlay — there is no live GPS feed.
///
/// `weekday`/`date`/`prev_*` are the query service days (as in
/// `findTransitRouteNative`, so a `>24:00:00` overnight trip is placed). The
/// `overlay_*` arrays carry MOTIS realtime so a delayed trip is drawn at its live
/// position and a cancelled one is suppressed; pass empty arrays for a
/// schedule-only simulation. Returns `OfflineRouter.RawVehicle[]` (possibly
/// empty), or `null` when the feed is absent/malformed.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_activeVehiclesNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    now_secs: jint,
    weekday: jint,
    date: jint,
    prev_weekday: jint,
    prev_date: jint,
    overlay_coords: JDoubleArray<'local>,
    overlay_routes: JObjectArray<'local>,
    overlay_times: JIntArray<'local>,
    min_lat: jdouble,
    min_lon: jdouble,
    max_lat: jdouble,
    max_lon: jdouble,
) -> jobjectArray {
    let null = std::ptr::null_mut();
    let base: String = match env.get_string(&base_path) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };
    let feed_name: String = match env.get_string(&feed) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };

    let index = match transit_index(&base, &feed_name) {
        Some(i) => i,
        None => return null,
    };

    let entries =
        read_delay_entries(&mut env, &overlay_coords, &overlay_routes, &overlay_times);
    let overlay = if entries.is_empty() {
        None
    } else {
        Some(crate::transit::DelayOverlay::build(&index, &entries))
    };

    let vehicles = crate::transit::active_vehicles(
        &index,
        now_secs.max(0) as u32,
        crate::transit::Schedule {
            day: crate::transit::QueryDay {
                weekday: weekday.max(0) as u32,
                date: date.max(0) as u32,
                prev_weekday: prev_weekday.max(0) as u32,
                prev_date: prev_date.max(0) as u32,
            },
            overlay: overlay.as_ref(),
        },
        min_lat,
        min_lon,
        max_lat,
        max_lon,
    );

    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawVehicle") {
        Ok(c) => c,
        Err(_) => return null,
    };
    // MUST match the RawVehicle ctor: lon, lat, bearing (D), colour, mode (I), id (J).
    let ctor = "(DDDIIJ)V";
    let array = match env.new_object_array(vehicles.len() as i32, &class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return null,
    };

    for (i, v) in vehicles.iter().enumerate() {
        let obj = match env.new_object(
            &class,
            ctor,
            &[
                JValue::Double(v.lon),
                JValue::Double(v.lat),
                JValue::Double(v.bearing),
                JValue::Int(v.route_color as i32),
                JValue::Int(v.route_type as i32),
                JValue::Long(v.id),
            ],
        ) {
            Ok(o) => o,
            Err(_) => return null,
        };
        if env.set_object_array_element(&array, i as i32, &obj).is_err() {
            return null;
        }
    }

    array.into_raw()
}
