//! Optional layers: POI, transit, traffic.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::style::{self, LayerToggles};
use jni::objects::{JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;
use super::handle::handle_mut;
use super::log::{log, log_info};
/// Turn the optional layers on or off, and narrow POI to a set of kinds.
///
/// Not free, unlike [`setPalette`](Java_com_vayunmathur_library_map_MapNative_setPalette):
/// POI and transit are gated at tessellation time so that leaving them off costs nothing,
/// which means turning one on invalidates every resident mesh. Bumping the generation is
/// all this does; the render loop notices the resident tiles are stale and re-requests
/// them through the existing worker pool, reading the archive it already has. Nothing is
/// refetched, nothing is evicted, and the old meshes keep drawing until the new ones land.
///
/// `kinds` is a comma-separated list of archive kind names — the app's category chips. Empty
/// means every kind the style draws. A name the schema has no id for is skipped rather than
/// refused: the chip list is app data and a typo there should narrow the map oddly, not blank it.
///
/// Traffic is a third optional layer but rides its own entry point
/// ([`setTrafficEnabled`](Java_com_vayunmathur_library_map_MapNative_setTrafficEnabled)) rather
/// than a fourth argument here, so adding it did not change this call's shape for the five
/// existing consumers.
///
/// A call that changes nothing bumps nothing, because the host is expected to call this
/// from a Compose effect that may re-run for unrelated reasons.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setLayers<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    poi: jboolean,
    transit: jboolean,
    kinds: JString<'l>,
) {
    let names: String = env.get_string(&kinds).map(Into::into).unwrap_or_default();
    let filter = style::KindFilter::new(
        names
            .split(',')
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .filter_map(style::kind_id)
            .collect(),
    );
    if let Some(map) = handle_mut(handle) {
        // Traffic is carried through its own setter, so preserve whatever it was set to.
        let traffic = map.toggles.get().0.traffic;
        let wanted =
            LayerToggles { poi: poi != 0, transit: transit != 0, traffic };
        if map.toggles.set(wanted, filter) {
            log_info(&format!(
                "layers changed: poi={} transit={} kinds=[{names}]",
                wanted.poi, wanted.transit,
            ));
        }
    }
}

/// Turn the live-traffic overlay on or off.
///
/// Gates the overlay at **tessellation** like [`setLayers`], so an archive without the layer —
/// or a consumer that never enables traffic — pays nothing: flipping it on bumps the toggle
/// generation and the resident tiles re-tessellate with the traffic layer through the existing
/// worker pool (nothing refetched, nothing evicted). It also flips a per-frame draw guard so a
/// toggle-off stops the overlay drawing immediately, before that re-tessellation lands.
///
/// The per-segment colours arrive separately through
/// [`setTrafficSpeeds`](Java_com_vayunmathur_library_map_MapNative_setTrafficSpeeds); this call
/// is only the geometry gate. Its own entry point rather than a fourth argument to [`setLayers`]
/// so the existing layer call keeps its shape.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setTrafficEnabled<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    enabled: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        let on = enabled != 0;
        // The renderer's per-frame draw guard, set every call so it tracks the toggle even
        // when the tessellation state is otherwise unchanged.
        map.renderer.set_traffic_enabled(on);
        // Re-use the current POI/transit/kinds snapshot and flip only traffic, so this
        // shares the one generation counter with setLayers rather than racing a second.
        let (current, kinds, _) = map.toggles.get();
        let wanted = LayerToggles { traffic: on, ..current };
        if map.toggles.set(wanted, kinds) {
            log_info(&format!("traffic layer {}", if on { "on" } else { "off" }));
        }
    }
}

/// Push the live-traffic colour table: `ids[i]` is a segment's `component_id` and
/// `colors[i]` the fully-resolved ARGB the device wants drawn for it.
///
/// Two parallel arrays rather than a packed buffer, agreed with the device workstream: it is
/// what a Kotlin caller already has in hand (a `LongArray` of ids and an `IntArray` of ARGB
/// from its own palette), and it crosses the boundary in two bulk `get_*_array_region` reads
/// with no per-element JNI traffic. The device owns the theme, so the colours are final here —
/// the renderer only looks them up.
///
/// Replacing the whole table each call, not merging: a viewport's worth of readings arrives at
/// once, and a stale id left behind would colour a road the latest data no longer covers.
/// Ids absent from the table draw nothing (the basemap road shows through), which is the
/// no-data behaviour. Mismatched lengths are truncated to the shorter. This is a pure
/// state swap — no tessellation and no upload — so it is the recolour hot path and is cheap.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setTrafficSpeeds<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JLongArray<'l>,
    colors: JIntArray<'l>,
) {
    let Some(map) = handle_mut(handle) else { return };
    let id_len = env.get_array_length(&ids).unwrap_or(0).max(0) as usize;
    let color_len = env.get_array_length(&colors).unwrap_or(0).max(0) as usize;
    let n = id_len.min(color_len);
    if n == 0 {
        // An empty push is a clear: the host has no readings for the current viewport.
        map.renderer.clear_traffic();
        return;
    }
    let mut id_buf = vec![0i64; n];
    let mut color_buf = vec![0i32; n];
    if env.get_long_array_region(&ids, 0, &mut id_buf).is_err()
        || env.get_int_array_region(&colors, 0, &mut color_buf).is_err()
    {
        log("the traffic arrays could not be read; leaving the colours unchanged");
        return;
    }
    // The bit pattern is what matters: a Kotlin colour is a signed `int` and an id is a signed
    // `long`, and both reinterpret to the unsigned the renderer keys and paints with.
    let ids_u64: Vec<u64> = id_buf.into_iter().map(|v| v as u64).collect();
    let colors_u32: Vec<u32> = color_buf.into_iter().map(|v| v as u32).collect();
    map.renderer.set_traffic_speeds(&ids_u64, &colors_u32);
}

/// Take the live-traffic overlay away: the toggle went off, or the viewport moved off the
/// squares the host has readings for.
///
/// Clears the pushed colours so the overlay stops drawing on the very next frame, without
/// waiting for the toggle-driven re-tessellation to evict the geometry. Cheap and idempotent,
/// like [`clearRegionMask`](Java_com_vayunmathur_library_map_MapNative_clearRegionMask).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearTraffic<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.clear_traffic();
    }
}
