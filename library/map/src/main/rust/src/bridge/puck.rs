//! The user-location puck.
//!
//! Pure move out of `bridge.rs`; no logic changes.
use crate::vulkan::renderer::UserPuck;
use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jlong};
use jni::JNIEnv;
use super::handle::handle_mut;
/// Show the user-location puck at `lon`/`lat`, drawn inside the renderer's own frame.
///
/// Free in the same sense as
/// [`setPalette`](Java_com_vayunmathur_library_map_MapNative_setPalette): pure state,
/// nothing re-tessellated and nothing re-uploaded. The quad is already on the GPU and
/// everything that varies about the puck is a push constant.
///
/// `bearing` is degrees clockwise from north and is only read when `has_bearing` is set;
/// without it the dot draws and the cone does not, which is what a fix with no heading
/// should look like rather than one pointing north.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_setUserPuck<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lon: jfloat,
    lat: jfloat,
    bearing: jfloat,
    has_bearing: jboolean,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_user_puck(Some(UserPuck {
            lon: lon as f64,
            lat: lat as f64,
            bearing: (has_bearing != 0).then_some(bearing),
        }));
    }
}

/// Take the puck away: no fix, or a host that stopped asking for one.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_map_MapNative_clearUserPuck<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Some(map) = handle_mut(handle) {
        map.renderer.set_user_puck(None);
    }
}
