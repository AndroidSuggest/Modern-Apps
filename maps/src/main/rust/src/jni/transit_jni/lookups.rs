//! Single-lookup JNI: `getFeedTimezoneNative` and `nearestStopMotisIdNative`.
//!
//! Pure move out of `super` (`transit_jni.rs`); no logic changes.

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jdouble, jstring};
use jni::JNIEnv;

use super::cache::transit_index;

// ---------------------------------------------------------------------------
// JNI: hasTransitArchiveNative (does the archive carry a transit section?)
// ---------------------------------------------------------------------------

/// Whether `<base_path>/basemap.mamaps` carries a transit section (kind 13).
///
/// The Kotlin discovery gate calls this before listing per-region
/// `<base>/*.transit` files: on an archive-only device there are no sidecar
/// files, so without this the gate finds nothing and every transit entry
/// point short-circuits before any JNI call. A pure presence probe — it reads
/// the archive's section directory without parsing the TRIX pack — so it is
/// cheap enough to call on every entry.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_hasTransitArchiveNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
) -> jboolean {
    let base: String = match env.get_string(&base_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let present = crate::transit::TransitIndex::has_archive_transit(
        &crate::graph::archive_path(&base),
    );
    present as jboolean
}

// ---------------------------------------------------------------------------
// JNI: getFeedTimezoneNative (IANA tz of the feed covering a coordinate)
// ---------------------------------------------------------------------------

/// IANA timezone (e.g. `America/Los_Angeles`) of the feed covering
/// `(lat, lon)` in `<base_path>/<feed>.transit`, from the v3 `FEED_TZ` section.
/// Returns null when the pack is absent/stale, doesn't cover the point, or its
/// feed had no `agency.txt` — the caller then falls back to the device zone.
/// Callers need this because the index is world-merged: every query time must be
/// expressed in the feed's local frame, not the device's.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_getFeedTimezoneNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    lat: jdouble,
    lon: jdouble,
) -> jstring {
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
    if !index.covers(lat, lon) {
        return null;
    }
    let tz = index.timezone_at(lat, lon);
    if tz.is_empty() {
        return null;
    }
    match env.new_string(&tz) {
        Ok(s) => s.into_raw(),
        Err(_) => null,
    }
}

// ---------------------------------------------------------------------------
// JNI: nearestStopMotisIdNative (MOTIS id of the stop nearest a coordinate)
// ---------------------------------------------------------------------------

/// MOTIS/Transitous stop id (e.g. `us-ca-SF-bayarea_901201`) of the stop nearest
/// `(lat, lon)` in `<base_path>/<feed>.transit`, from the v5 `FEED_MOTIS_PREFIX` +
/// `STOP_GTFS_ID` sections. Returns null when the pack is absent, predates v5,
/// doesn't cover the point, or its feed's Transitous source name was unknown at
/// build time.
///
/// Exists because the departure board fetches its realtime overlay *before* it
/// knows which stop the board is for, so it needs to name the stop up front. A
/// local lookup, no network — which is the whole point of baking the id.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_maps_util_OfflineRouter_nearestStopMotisIdNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _thiz: JObject<'local>,
    base_path: JString<'local>,
    feed: JString<'local>,
    lat: jdouble,
    lon: jdouble,
) -> jstring {
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
    if !index.covers(lat, lon) {
        return null;
    }
    match index.nearest_stop_motis_id(lat, lon) {
        Some(id) => match env.new_string(&id) {
            Ok(s) => s.into_raw(),
            Err(_) => null,
        },
        None => null,
    }
}
