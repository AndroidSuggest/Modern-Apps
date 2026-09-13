//! The navigation route overlay.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::overlay::{RouteSegment, RouteStyle};
use jni::objects::{JClass, JFloatArray, JIntArray};
use jni::sys::{jfloat, jint, jlong};
use jni::JNIEnv;
use super::handle::handle_mut;
use super::log::log;
/// Draw a navigation route line over the basemap and under the puck.
///
/// `points` is a flat `[lon0, lat0, lon1, lat1, …]` array holding every coloured run's
/// points concatenated; `segment_lengths` is the point count of each run in order, and
/// `segment_colors` the ARGB fill of each, index for index. Three bulk arrays rather than a
/// list of objects because a route is thousands of points and a per-point JNI crossing is
/// exactly what the rest of this boundary exists to avoid; `float` rather than `double` for
/// the same reason the camera's coordinates are (see
/// [`render`](Java_com_vayunmathur_library_map_MapNative_render)) — seven significant
/// digits is about a centimetre at the equator, and a route is a shape to follow rather
/// than a survey.
///
/// A **list of coloured runs**, one casing: the phone colours the route per navigation
/// step (traffic bands, transit brand colours, a travelled grey behind the puck), and that
/// colouring now lives in the renderer so the route pans in lock-step with the basemap. The
/// casing is drawn once over the whole route and each run's fill over it in run colour. A
/// single-colour route (Android Auto) is just a one-run list.
///
/// Tessellated here, on the calling thread, and uploaded once. That is affordable because
/// it happens when the route is set and never again: the mesh is zoom-independent, so no
/// frame and no zoom step rebuilds it. See [`crate::overlay`].
///
/// An empty array, a run of fewer than two distinct points, or lengths that sum past the
/// points available all draw nothing for the affected run, and a route with no drawable run
/// is the same outcome as
/// [`clearRoute`](Java_com_vayunmathur_library_map_MapNative_clearRoute). Arrays that cannot
/// be read leave the route **unchanged** rather than blanking a route being followed.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setRoute<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    points: JFloatArray<'l>,
    segment_lengths: JIntArray<'l>,
    segment_colors: JIntArray<'l>,
    width_dp: jfloat,
    casing_dp: jfloat,
    casing_color: jint,
) {
    let Some(map) = handle_mut(handle) else { return };
    let point_floats = match env.get_array_length(&points) {
        Ok(length) => length.max(0) as usize,
        // Reading failed, so we know nothing about the intended route. Leaving the current
        // one alone beats blanking a route the driver is following.
        Err(_) => {
            log("the route array could not be measured; leaving the route unchanged");
            return;
        }
    };
    let segment_count = env.get_array_length(&segment_lengths).unwrap_or(0).max(0) as usize;
    let color_count = env.get_array_length(&segment_colors).unwrap_or(0).max(0) as usize;
    let segment_count = segment_count.min(color_count);

    let mut flat = vec![0f32; point_floats];
    if env.get_float_array_region(&points, 0, &mut flat).is_err() {
        log("the route array could not be read; leaving the route unchanged");
        return;
    }
    let mut lengths = vec![0i32; segment_count];
    let mut colors = vec![0i32; segment_count];
    if segment_count > 0
        && (env.get_int_array_region(&segment_lengths, 0, &mut lengths).is_err()
            || env.get_int_array_region(&segment_colors, 0, &mut colors).is_err())
    {
        log("the route segment arrays could not be read; leaving the route unchanged");
        return;
    }

    // Walk the flat point buffer run by run: each length is a point count, so it consumes
    // twice that many floats. A length that would overrun the buffer is clamped, so a
    // mismatched pair truncates rather than reading past the array.
    let mut segments: Vec<RouteSegment> = Vec::with_capacity(segment_count);
    let mut cursor = 0usize;
    for (len, color) in lengths.into_iter().zip(colors) {
        let count = len.max(0) as usize;
        let end = (cursor + count * 2).min(flat.len());
        let run: Vec<(f64, f64)> =
            flat[cursor..end].chunks_exact(2).map(|pair| (pair[0] as f64, pair[1] as f64)).collect();
        cursor = end;
        // ARGB arrives as a signed `int` because that is what a Kotlin colour is; the bit
        // pattern is what matters and the cast keeps it.
        segments.push(RouteSegment { points: run, color: color as u32 });
    }

    let style = RouteStyle { width_dp, casing_dp, casing_color: casing_color as u32 };
    let mesh = crate::overlay::tessellate(&segments, style);
    if let Err(e) = map.renderer.set_route(mesh.as_ref()) {
        log(&format!("uploading the route failed: {e}"));
    }
}

/// Take the route away: navigation ended, or the host cleared it.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearRoute<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        if let Err(e) = map.renderer.set_route(None) {
            log(&format!("clearing the route failed: {e}"));
        }
    }
}
