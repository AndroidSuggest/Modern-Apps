//! Rail-line shapes JNI: `getRailLinesNative`.
//!
//! Reads the drawable polylines straight from the on-device timetable pack
//! (sections 20-22), bypassing the tile layer that needs an archive rebuild.

use jni::objects::{JDoubleArray, JObject, JString, JValue};
use jni::sys::{jdouble, jobjectArray};
use jni::JNIEnv;

use super::cache::transit_index;

// ---------------------------------------------------------------------------
// JNI: getRailLinesNative (drawable rail polylines + agency colours)
// ---------------------------------------------------------------------------

/// Every rail line serving the bbox, with its agency colour and GTFS
/// `route_type`: `(name, color, route_type, feed, coords)`. `coords` is a flat
/// `[lon0, lat0, ...]` like a `RawStep` geometry. Returns an empty array (not
/// null) when the pack is absent — no lines is a valid answer — and null only
/// on a JNI failure. Bounded natively (`MAX_RAIL_LINES`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getRailLinesNative<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
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

    // No covers() gate: a line serves the bbox when any of its stops does,
    // and the bbox centre may be nowhere near a stop. The stop-based cull in
    // `rail_lines` is the coverage check.
    let lines = crate::transit::rail_lines(&index, min_lat, min_lon, max_lat, max_lon);

    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawRailLine") {
        Ok(c) => c,
        Err(_) => return null,
    };
    // MUST match the RawRailLine ctor: name, color, routeType (I), feed, coords ([D).
    let ctor = "(Ljava/lang/String;IILjava/lang/String;[D)V";
    let array = match env.new_object_array(lines.len() as i32, &class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return null,
    };

    for (i, l) in lines.iter().enumerate() {
        let jname: JObject = match env.new_string(&l.name) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jfeed: JObject = match env.new_string(&l.feed) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jcoords = match env.new_double_array(l.coords.len() as i32) {
            Ok(a) => a,
            Err(_) => return null,
        };
        if !l.coords.is_empty()
            && env.set_double_array_region(&jcoords, 0, &l.coords).is_err()
        {
            return null;
        }
        let jcoords_obj: JObject = jcoords.into();
        let obj = match env.new_object(
            &class,
            ctor,
            &[
                JValue::Object(&jname),
                JValue::Int(l.color as i32),
                JValue::Int(l.route_type as i32),
                JValue::Object(&jfeed),
                JValue::Object(&jcoords_obj),
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
