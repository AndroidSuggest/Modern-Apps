//! Offline RAPTOR route planner JNI: `findTransitRouteNative`.
//!
//! Pure move out of `super` (`transit_jni.rs`); no logic changes.

use jni::objects::{JDoubleArray, JIntArray, JObject, JObjectArray, JString, JValue};
use jni::sys::{jdouble, jint, jobjectArray};
use jni::JNIEnv;

use super::super::routes::snap_walk_legs;
use super::cache::transit_index;
use super::overlay::read_delay_entries;

// ---------------------------------------------------------------------------
// JNI: findTransitRouteNative (offline RAPTOR over a per-region transit index)
// ---------------------------------------------------------------------------

/// Plan an offline transit journey using the compact `.transit` index at
/// `<base_path>/<feed>.transit` (produced by `scripts/maps/gtfs_ingest`).
/// Returns `OfflineRouter.RawStep[]` (walk + wait + ride legs) or `null` when
/// the feed is absent, does not cover the endpoints, or no journey exists — in
/// which case the Kotlin side falls back to the P10 online Transitous planner.
///
/// The `overlay_*` arrays carry MOTIS realtime board entries so RAPTOR avoids
/// cancelled trips and plans against live times; pass empty arrays for a
/// schedule-only plan. See `transit::DelayOverlay` for why the join is a
/// fingerprint rather than a trip id.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_findTransitRouteNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    s_lat: jdouble,
    s_lon: jdouble,
    e_lat: jdouble,
    e_lon: jdouble,
    dep_secs: jint,
    weekday: jint,
    date: jint,
    prev_weekday: jint,
    prev_date: jint,
    overlay_coords: JDoubleArray<'local>,
    overlay_routes: JObjectArray<'local>,
    overlay_times: JIntArray<'local>,
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
    if !index.covers(s_lat, s_lon) || !index.covers(e_lat, e_lon) {
        return null;
    }

    let entries =
        read_delay_entries(&mut env, &overlay_coords, &overlay_routes, &overlay_times);
    let overlay = if entries.is_empty() {
        None
    } else {
        Some(crate::transit::DelayOverlay::build(&index, &entries))
    };

    let mut legs = match crate::transit::plan(
        &index,
        s_lat,
        s_lon,
        e_lat,
        e_lon,
        dep_secs.max(0) as u32,
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
        Some(l) if !l.is_empty() => l,
        _ => return null,
    };
    snap_walk_legs(&mut legs);

    let class = match env.find_class("com/vayunmathur/maps/util/OfflineRouter$RawStep") {
        Ok(c) => c,
        Err(_) => return null,
    };
    // MUST match the descriptor at the driving call site above: one ctor, two
    // callers. The trailing two Strings are the ride's MOTIS board/alight ids;
    // the trailing `[DDD` is WS-G's per-coordinate elevation array + route
    // ascent/descent, which a transit leg leaves empty/zero (no baked terrain).
    let ctor = "(ILjava/lang/String;JJ[DDZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I[ILjava/lang/String;IIILjava/lang/String;Ljava/lang/String;[DDD)V";
    let array = match env.new_object_array(legs.len() as i32, &class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return null,
    };

    for (i, leg) in legs.iter().enumerate() {
        let jname: JObject = match env.new_string(&leg.name) {
            Ok(s) => s.into(),
            Err(_) => return null,
        };
        let jgeom = match env.new_double_array(leg.coords.len() as i32) {
            Ok(a) => a,
            Err(_) => return null,
        };
        if !leg.coords.is_empty()
            && env.set_double_array_region(&jgeom, 0, &leg.coords).is_err()
        {
            return null;
        }
        let jgeom_obj: JObject = jgeom.into();

        let opt_str = |env: &mut JNIEnv<'local>, s: &str| -> JObject<'local> {
            if s.is_empty() {
                JObject::null()
            } else {
                match env.new_string(s) {
                    Ok(js) => js.into(),
                    Err(_) => JObject::null(),
                }
            }
        };
        let is_transit = leg.kind.is_transit();
        // A WAIT leg needs its stop name for the "wait at X" instruction, but
        // must not carry a feed or it would render as a ride in the UI.
        let has_stops = leg.kind != crate::transit::LegKind::Walk;
        let jfeed = if is_transit { opt_str(&mut env, &leg.feed) } else { JObject::null() };
        let jcode = if has_stops { opt_str(&mut env, &leg.from_stop) } else { JObject::null() };
        let jend = if has_stops { opt_str(&mut env, &leg.to_stop) } else { JObject::null() };
        let jheadsign = if is_transit {
            opt_str(&mut env, &leg.headsign)
        } else {
            JObject::null()
        };
        // Only a ride carries realtime, so only a ride carries stop ids. Empty on
        // a pre-v5 pack, which leaves the overlay with nothing to ask about.
        let jboard = if is_transit {
            opt_str(&mut env, &leg.board_stop_motis_id)
        } else {
            JObject::null()
        };
        let jalight = if is_transit {
            opt_str(&mut env, &leg.alight_stop_motis_id)
        } else {
            JObject::null()
        };

        // Every leg's duration comes from RAPTOR's own times. A walk leg's
        // `dist_m` may have been redrawn along the road graph, which is longer
        // than the estimate the journey was planned on, so deriving the duration
        // from it would contradict the departure the plan was built around.
        let duration_10ms: i64 = (leg.arr_secs.saturating_sub(leg.dep_secs) as i64) * 100;
        let dist_mm: i64 = (leg.dist_m * 1000.0) as i64;
        // Ordinals mirror RouteService.API.Maneuver.
        let maneuver: i32 = match leg.kind {
            crate::transit::LegKind::Walk => 0,  // MANEUVER_UNSPECIFIED
            crate::transit::LegKind::Wait => 21, // WAIT
            crate::transit::LegKind::Ride => 22, // RIDE
        };

        let jlanes = match env.new_int_array(0) {
            Ok(a) => a,
            Err(_) => return null,
        };
        let jlanes_obj: JObject = jlanes.into();

        // A transit leg carries no baked terrain: an empty elevation array and zero ascent/descent.
        let jelev = match env.new_double_array(0) {
            Ok(a) => a,
            Err(_) => return null,
        };
        let jelev_obj: JObject = jelev.into();

        let obj = match env.new_object(
            &class,
            ctor,
            &[
                JValue::Int(maneuver),
                JValue::Object(&jname),
                JValue::Long(dist_mm),
                JValue::Long(duration_10ms),
                JValue::Object(&jgeom_obj),
                JValue::Double(1.0),
                JValue::Bool(is_transit as u8),
                JValue::Object(&jfeed),
                JValue::Object(&jcode),
                JValue::Object(&jend),
                JValue::Int(leg.stop_count),
                JValue::Object(&jlanes_obj),
                JValue::Object(&jheadsign),
                JValue::Int(leg.route_color as i32),
                JValue::Int(leg.dep_secs as i32),
                JValue::Int(leg.arr_secs as i32),
                JValue::Object(&jboard),
                JValue::Object(&jalight),
                JValue::Object(&jelev_obj),
                JValue::Double(0.0),
                JValue::Double(0.0),
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
