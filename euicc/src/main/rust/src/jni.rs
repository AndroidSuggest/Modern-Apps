//! Thin JNI marshalling layer bridging the Kotlin `EuiccNative` object to the
//! native SGP.22 core. This file contains no protocol logic; later phases add
//! the ES10/ES9+ entry points and the `transmitApdu` upcall here.

use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

use crate::VERSION;

/// `nativeVersion()` — returns the native core's version string, or null on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeVersion<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    match env.new_string(VERSION) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
