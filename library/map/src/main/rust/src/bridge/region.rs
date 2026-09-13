//! Palette switching and the region mask.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::style::Palette;
use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jint, jlong};
use jni::JNIEnv;
use super::handle::handle_mut;
/// Dim everything outside the region containing this point, and report which one that is.
///
/// Takes a place's coordinates rather than a region id because nothing in the archive links the
/// two: a city is a `places` **node** with its own OSM id, while its outline is a `boundaries`
/// **relation**, and OSM does not oblige the node to be a member of the relation. Containment is
/// the link - a city label sits inside its own boundary.
///
/// `level_min`/`level_max` are the inclusive OSM `admin_level` band the selection means, and are
/// not optional: every label is contained by a whole stack of regions, so containment alone
/// cannot say whether a tap on "California" meant the state or the county its label sits in.
///
/// Returns the region's OSM relation id, or 0 when no resident tile covers the point. Returning
/// it rather than nothing lets the host tell "no region here" from "not loaded yet" and retry.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setRegionMask<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lon: jfloat,
    lat: jfloat,
    level_min: jint,
    level_max: jint,
) -> jlong {
    let Some(map) = handle_mut(handle) else { return 0 };
    let levels = (level_min.max(0) as u16)..=(level_max.max(0) as u16);
    match map.renderer.region_at(lon as f64, lat as f64, levels) {
        Some(id) => {
            map.renderer.set_region_mask(Some(id));
            id as jlong
        }
        None => {
            map.renderer.set_region_mask(None);
            0
        }
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
