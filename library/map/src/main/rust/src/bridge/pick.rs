//! Tap picking: labels and markers.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use super::handle::handle_mut;
use jni::objects::{JClass, JObject};
use jni::sys::{jfloat, jlong};
use jni::JNIEnv;
/// Task-17 pick: placed labels intersecting the query box (Dp from the
/// viewport top-left). Returns `\u{1}`-joined `layerId/name/kind/lon/lat/featureId`
/// strings in placement order (topmost first); empty when nothing hits. Dp→device-px via the
/// last frame's density, remembered on the map handle (same density the
/// boxes were built with — boxes are device px, the query arrives in Dp).
///
/// `kind` is the feature's own kind, not its layer's first one, so a `poi-food` hit says
/// `cafe` rather than `restaurant`. `featureId` is the archive's stable id, or `0` for a
/// feature that has none.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_pickLabels<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x0_dp: jfloat,
    y0_dp: jfloat,
    x1_dp: jfloat,
    y1_dp: jfloat,
) -> jni::objects::JObjectArray<'l> {
    let empty = env
        .new_object_array(0, "java/lang/String", JObject::null())
        .expect("pickLabels empty array");
    let Some(map) = handle_mut(handle) else {
        return empty;
    };
    let density = map.density;
    let hits = map.renderer.pick_labels((
        x0_dp as f32 * density,
        y0_dp as f32 * density,
        x1_dp as f32 * density,
        y1_dp as f32 * density,
    ));
    let layers = &map.layers;
    let out = match env.new_object_array(hits.len() as i32, "java/lang/String", JObject::null()) {
        Ok(a) => a,
        Err(_) => return empty,
    };
    for (i, h) in hits.iter().enumerate() {
        let layer_id = layers
            .get(h.layer_index)
            .map(|l| l.id.as_str())
            .unwrap_or("");
        let s = format!(
            "{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}\u{1}{}",
            layer_id, h.name, h.kind, h.lon, h.lat, h.feature_id, h.region_id,
        );
        let Ok(js) = env.new_string(s) else { continue };
        let _ = env.set_object_array_element(&out, i as i32, js);
    }
    out
}

/// Pick the renderer-drawn marker under a tap: the id-buffer readback path.
///
/// `x`/`y` are Dp from the viewport top-left, converted to device px with the last frame's
/// density (the same conversion [`pickLabels`](Java_com_vayunmathur_library_map_MapNative_pickLabels)
/// makes), because the id buffer is device-px. Returns the tapped marker's own id — the value the
/// host set on it in [`setMarkers`](Java_com_vayunmathur_library_map_MapNative_setMarkers) — so the
/// host rejoins the tap to its feature without matching on position, or `0` when the tap hit no
/// marker. That replaces the Compose CPU hit-test, which trailed the basemap on a pan and could not
/// place a pin's box under tilt at all.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_pickAt<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    x_dp: jfloat,
    y_dp: jfloat,
) -> jlong {
    let Some(map) = handle_mut(handle) else {
        return 0;
    };
    let density = map.density;
    // Negative Dp is off the top-left of the viewport; clamp to zero before scaling so the cast to
    // an unsigned device coordinate cannot wrap. The native side clamps the far edges to the extent.
    let x = (x_dp.max(0.0) * density).round() as u32;
    let y = (y_dp.max(0.0) * density).round() as u32;
    map.renderer.pick_at(x, y) as jlong
}
