//! JNI surface for `com.vayunmathur.passwords.util.KdbxNative`.
//!
//! - `nativeImport(password, kdbxBytes) -> String?`  (JSON array of entries)
//! - `nativeExport(password, entriesJson) -> byte[]?` (encrypted KDBX4 bytes)
//!
//! Both return null on failure (wrong password, malformed vault, bad JSON).

use crate::{export_kdbx, import_kdbx};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use jni::JNIEnv;

fn get_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|js| js.into())
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_passwords_util_KdbxNative_nativeImport<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    password: JString<'l>,
    kdbx: JByteArray<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    let pw = match get_string(&mut env, &password) {
        Some(p) => p,
        None => return null,
    };
    let data = match env.convert_byte_array(&kdbx) {
        Ok(d) => d,
        Err(_) => return null,
    };
    match import_kdbx(&pw, &data) {
        Some(json) => match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(_) => null,
        },
        None => null,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_passwords_util_KdbxNative_nativeExport<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    password: JString<'l>,
    entries_json: JString<'l>,
) -> jbyteArray {
    let null = std::ptr::null_mut();
    let pw = match get_string(&mut env, &password) {
        Some(p) => p,
        None => return null,
    };
    let json = match get_string(&mut env, &entries_json) {
        Some(j) => j,
        None => return null,
    };
    match export_kdbx(&pw, &json) {
        Some(bytes) => match env.byte_array_from_slice(&bytes) {
            Ok(a) => a.into_raw(),
            Err(_) => null,
        },
        None => null,
    }
}
