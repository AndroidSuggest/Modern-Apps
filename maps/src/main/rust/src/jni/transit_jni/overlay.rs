//! Realtime-overlay decoder shared by the offline-transit JNI entry points.
//!
//! Pure move out of `super` (`transit_jni.rs`); no logic changes.

use jni::objects::{JDoubleArray, JIntArray, JObjectArray, JString};
use jni::JNIEnv;

/// Decode the realtime overlay's parallel arrays. `coords` is interleaved
/// `[lat, lon, ...]` and `times` is interleaved
/// `[sched_secs, delay_secs, cancelled, ...]`, so one entry spans 2 doubles,
/// 1 string and 3 ints. Any length mismatch yields no overlay rather than a
/// partial one — a wrong fingerprint would silently mis-delay a trip.
pub(super) fn read_delay_entries<'local>(
    env: &mut JNIEnv<'local>,
    coords: &JDoubleArray<'local>,
    routes: &JObjectArray<'local>,
    times: &JIntArray<'local>,
) -> Vec<crate::transit::DelayEntry> {
    let n = match env.get_array_length(routes) {
        Ok(n) if n > 0 => n as usize,
        _ => return Vec::new(),
    };
    if env.get_array_length(coords).unwrap_or(0) as usize != n * 2
        || env.get_array_length(times).unwrap_or(0) as usize != n * 3
    {
        return Vec::new();
    }
    let mut ll = vec![0f64; n * 2];
    if env.get_double_array_region(coords, 0, &mut ll).is_err() {
        return Vec::new();
    }
    let mut tv = vec![0i32; n * 3];
    if env.get_int_array_region(times, 0, &mut tv).is_err() {
        return Vec::new();
    }

    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        let name: Option<String> = match env.get_object_array_element(routes, i as i32) {
            Ok(obj) => {
                let s = JString::from(obj);
                // Convert to an owned String within this statement so the
                // borrowing `JavaStr` is dropped before `s`.
                let owned: Option<String> = env.get_string(&s).ok().map(|js| js.into());
                owned
            }
            Err(_) => None,
        };
        let name = match name {
            Some(n) => n,
            None => continue,
        };
        out.push(crate::transit::DelayEntry {
            lat: ll[i * 2],
            lon: ll[i * 2 + 1],
            route_name: name,
            sched_secs: tv[i * 3].max(0) as u32,
            delay_secs: tv[i * 3 + 1],
            cancelled: tv[i * 3 + 2] != 0,
        });
    }
    out
}
