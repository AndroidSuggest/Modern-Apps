//! Palette switching and the region mask.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use super::handle::handle_mut;
use crate::style::Palette;
use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
/// Mask everything outside the region with this id, or clear the mask when it is zero.
///
/// The id is the tagged OSM relation id of an admin boundary, baked onto the tapped place label at
/// build time (see the `places` region-link table) — so the label names its own outline directly
/// and there is no point/level guess. Returns 1 when the mask is set (or intentionally cleared for
/// a zero id) **and** a resident tile actually carries that region, 0 when the region's tiles are
/// not loaded yet so the host retries next frame. `RegionBuffers::id` is what `record_region_mask`
/// filters on, so once a piece is resident the mask draws.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setRegionMask<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    region_id: jlong,
) -> jlong {
    let Some(map) = handle_mut(handle) else {
        return 0;
    };
    let id = region_id as u64;
    if id == 0 {
        // No linked region: clear the mask and report resolved so the host stops retrying.
        map.renderer.set_region_mask(None);
        return 1;
    }
    map.renderer.set_region_mask(Some(id));
    // Resolved only once a tile carrying this region is resident; until then the host retries.
    if map.renderer.region_resident(id) {
        1
    } else {
        0
    }
}

/// Take the region mask away: the details sheet closed, or the selection moved on.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearRegionMask<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_region_mask(None);
    }
}
