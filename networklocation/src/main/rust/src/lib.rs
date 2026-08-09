//! Network-location native library: device-position estimation from the set of
//! beacon fixes returned by the Apple `gs-loc` proxy (and cached locally),
//! exposed to Kotlin via JNI.
//!
//! The estimation math lives in the dependency-free [`estimate`] module so it is
//! testable on the host; this file is only the thin JNI marshalling layer.

mod estimate;

pub use estimate::estimate_position;

#[cfg(not(test))]
mod jni_bindings {
    use super::*;
    use jni::objects::{JClass, JDoubleArray};
    use jni::sys::jdoubleArray;
    use jni::JNIEnv;

    /// `estimatePosition(beacons)`. `beacons` is interleaved
    /// `[lat0,lon0,acc0,lat1,lon1,acc1,...]` (degrees, degrees, metres); returns
    /// a 3-element `[lat, lon, accuracyMeters]` array, or null when there are no
    /// usable beacons / on error.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_networklocation_NetworkLocationNative_estimatePosition<
        'l,
    >(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        beacons: JDoubleArray<'l>,
    ) -> jdoubleArray {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let len = env.get_array_length(&beacons).unwrap_or(0) as usize;
            let mut buf = vec![0f64; len];
            if env.get_double_array_region(&beacons, 0, &mut buf).is_err() {
                return None;
            }
            estimate_position(&buf)
        }))
        .unwrap_or(None);

        let result = match result {
            Some(r) => r,
            None => return std::ptr::null_mut(),
        };
        let out = match env.new_double_array(result.len() as i32) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_double_array_region(&out, 0, &result).is_err() {
            return std::ptr::null_mut();
        }
        out.into_raw()
    }
}
