//! Rail-line shapes JNI: `getRailLinesNative` and `getStopLinesNative`.
//!
//! Reads the drawable polylines straight from the on-device timetable pack
//! (sections 20-22), bypassing the tile layer that needs an archive rebuild.
//! Both entries share the `RawRailLine` ctor; only the route set differs
//! (viewport bbox vs the selected stop's routes).

use jni::objects::{JObject, JString, JValue};
use jni::sys::{jdouble, jobjectArray};
use jni::JNIEnv;

use super::cache::transit_index;

/// Build the `RawRailLine[]` for one drawable-line set. Factored so the bbox
/// and stop entries share the ctor descriptor (kept in one place so a field
/// addition cannot update one entry and miss the other).
fn rail_lines_to_java<'local>(
    env: &mut JNIEnv<'local>,
    lines: &[crate::transit::RailLine],
) -> Option<jobjectArray> {
    let class =
        env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawRailLine").ok()?;
    // MUST match the RawRailLine ctor: name, color, routeType (I), feed,
    // coords ([D), then the corridor slot (ordinal, lanes, taper) LAST.
    let ctor = "(Ljava/lang/String;IILjava/lang/String;[DIII)V";
    let array = env.new_object_array(lines.len() as i32, &class, JObject::null()).ok()?;

    for (i, l) in lines.iter().enumerate() {
        let jname: JObject = env.new_string(&l.name).ok()?.into();
        let jfeed: JObject = env.new_string(&l.feed).ok()?.into();
        let jcoords = env.new_double_array(l.coords.len() as i32).ok()?;
        if !l.coords.is_empty() && env.set_double_array_region(&jcoords, 0, &l.coords).is_err()
        {
            return None;
        }
        let jcoords_obj: JObject = jcoords.into();
        let obj = env
            .new_object(
                &class,
                ctor,
                &[
                    JValue::Object(&jname),
                    JValue::Int(l.color as i32),
                    JValue::Int(l.route_type as i32),
                    JValue::Object(&jfeed),
                    JValue::Object(&jcoords_obj),
                    JValue::Int(i32::from(l.ordinal)),
                    JValue::Int(i32::from(l.lanes)),
                    JValue::Int(i32::from(l.taper)),
                ],
            )
            .ok()?;
        if env.set_object_array_element(&array, i as i32, &obj).is_err() {
            return None;
        }
    }

    Some(array.into_raw())
}

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

    match rail_lines_to_java(&mut env, &lines) {
        Some(a) => a,
        None => null,
    }
}

/// Every line serving the stop nearest `(lat, lon)`, with its agency colour
/// and GTFS `route_type`: same `(name, color, route_type, feed, coords)` and
/// corridor slot as [`getRailLinesNative`]. Buses included — at one stop a
/// handful of bus polylines is context, not noise. Returns an empty array
/// (not null) when the pack is absent or no stop is near; null only on a JNI
/// failure. Bounded natively (`MAX_RAIL_LINES`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getStopLinesNative<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    lat: jdouble,
    lon: jdouble,
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

    // Same nearest-stop resolution as the departure board (which the lines
    // must agree with), with the pack bbox as the coverage gate.
    if !index.covers(lat, lon) {
        return match rail_lines_to_java(&mut env, &[]) {
            Some(a) => a,
            None => null,
        };
    }
    let lines = crate::transit::routes_for_stop(&index, lat, lon);

    match rail_lines_to_java(&mut env, &lines) {
        Some(a) => a,
        None => null,
    }
}
