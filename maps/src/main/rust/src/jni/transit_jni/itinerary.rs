//! Trip-itinerary JNI: `getTripItineraryNative`.
//!
//! Decodes a vehicle id back to its trip and returns the full stop-by-stop
//! run for the vehicle-details sheet.

use jni::objects::{JDoubleArray, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jint, jlong, jobject, jobjectArray};
use jni::JNIEnv;

use super::cache::transit_index;
use super::overlay::read_delay_entries;

// ---------------------------------------------------------------------------
// JNI: getTripItineraryNative (a trip's full stop list + times)
// ---------------------------------------------------------------------------

/// The itinerary of the trip `vehicle_id` names: header
/// `(route_name, headsign, color, route_type, feed, cancelled)` plus one
/// `RawTripStop` per stop `(name, lat, lon, arr_secs, dep_secs, motis_id)`.
/// Times are seconds since feed-local midnight in the query-day frame with
/// the realtime overlay applied — the same frame as departure-board times,
/// so Kotlin converts them identically. Returns null when the id does not
/// resolve or the trip does not run on the query service day.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getTripItineraryNative<'local>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    vehicle_id: jlong,
    weekday: jint,
    date: jint,
    prev_weekday: jint,
    prev_date: jint,
    overlay_coords: JDoubleArray<'local>,
    overlay_routes: JObjectArray<'local>,
    overlay_times: JIntArray<'local>,
) -> jobject {
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

    let itin = match crate::transit::trip_itinerary(
        &index,
        vehicle_id,
        crate::transit::Schedule {
            day: crate::transit::QueryDay {
                weekday: weekday.max(0) as u32,
                date: date.max(0) as u32,
                prev_weekday: prev_weekday.max(0) as u32,
                prev_date: prev_date.max(0) as u32,
            },
            overlay: overlay.as_ref(),
        },
    ) {
        Some(t) => t,
        None => return null,
    };

    let stop_class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawTripStop") {
        Ok(c) => c,
        Err(_) => return null,
    };
    // MUST match the RawTripStop ctor: name, lat, lon (D), arr, dep (I), motisId, cancelled (Z).
    let stop_ctor = "(Ljava/lang/String;DDIILjava/lang/String;Z)V";
    let stop_array = match env.new_object_array(itin.stops.len() as i32, &stop_class, JObject::null())
    {
        Ok(a) => a,
        Err(_) => return null,
    };
    for (i, s) in itin.stops.iter().enumerate() {
        let jname: JObject = match env.new_string(&s.name) {
            Ok(x) => x.into(),
            Err(_) => return null,
        };
        let jmotis: JObject = match env.new_string(&s.motis_id) {
            Ok(x) => x.into(),
            Err(_) => return null,
        };
        let obj = match env.new_object(
            &stop_class,
            stop_ctor,
            &[
                JValue::Object(&jname),
                JValue::Double(s.lat),
                JValue::Double(s.lon),
                JValue::Int(s.arr_secs as i32),
                JValue::Int(s.dep_secs as i32),
                JValue::Object(&jmotis),
                JValue::Bool(itin.cancelled as u8),
            ],
        ) {
            Ok(o) => o,
            Err(_) => return null,
        };
        if env.set_object_array_element(&stop_array, i as i32, &obj).is_err() {
            return null;
        }
    }

    let itin_class =
        match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawTripItinerary") {
            Ok(c) => c,
            Err(_) => return null,
        };
    // MUST match the RawTripItinerary ctor: route, headsign, color, type (I),
    // feed, cancelled (Z), stops ([L...RawTripStop;).
    let itin_ctor = "(Ljava/lang/String;Ljava/lang/String;IILjava/lang/String;Z[Lcom/vayunmathur/maps/util/OfflineRouter$RawTripStop;)V";
    let jroute: JObject = match env.new_string(&itin.route_name) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };
    let jhead: JObject = match env.new_string(&itin.headsign) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };
    let jfeed: JObject = match env.new_string(&itin.feed) {
        Ok(s) => s.into(),
        Err(_) => return null,
    };
    let jobjs: JObject = stop_array.into();
    match env.new_object(
        &itin_class,
        itin_ctor,
        &[
            JValue::Object(&jroute),
            JValue::Object(&jhead),
            JValue::Int(itin.color as i32),
            JValue::Int(itin.route_type as i32),
            JValue::Object(&jfeed),
            JValue::Bool(itin.cancelled as u8),
            JValue::Object(&jobjs),
        ],
    ) {
        Ok(o) => o.into_raw(),
        Err(_) => null,
    }
}
