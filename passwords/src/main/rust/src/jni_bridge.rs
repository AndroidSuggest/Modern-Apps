//! JNI surface for `com.vayunmathur.passwords.util.KdbxNative`.
//!
//! - `nativeImport(password, kdbxBytes) -> String?`  (JSON array of entries)
//! - `nativeExport(password, entriesJson) -> byte[]?` (encrypted KDBX4 bytes)
//!
//! Both return null on failure (wrong password, malformed vault, bad JSON).

use crate::{export_kdbx, import_kdbx};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jstring};
use jni::{Env, EnvUnowned};

fn get_string(env: &Env, s: &JString) -> Option<String> {
    s.try_to_string(env).ok()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_passwords_util_KdbxNative_nativeImport<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    password: JString<'l>,
    kdbx: JByteArray<'l>,
) -> jstring {
    env.with_env(|env| -> jni::errors::Result<jstring> {
        let null = std::ptr::null_mut();
        let pw = match get_string(env, &password) {
            Some(p) => p,
            None => return Ok(null),
        };
        let data = match env.convert_byte_array(&kdbx) {
            Ok(d) => d,
            Err(_) => return Ok(null),
        };
        match import_kdbx(&pw, &data) {
            Some(json) => match env.new_string(json) {
                Ok(s) => Ok(s.into_raw()),
                Err(_) => Ok(null),
            },
            None => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_passwords_util_KdbxNative_nativeExport<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    password: JString<'l>,
    entries_json: JString<'l>,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<jbyteArray> {
        let null = std::ptr::null_mut();
        let pw = match get_string(env, &password) {
            Some(p) => p,
            None => return Ok(null),
        };
        let json = match get_string(env, &entries_json) {
            Some(j) => j,
            None => return Ok(null),
        };
        match export_kdbx(&pw, &json) {
            Some(bytes) => match env.byte_array_from_slice(&bytes) {
                Ok(a) => Ok(a.into_raw()),
                Err(_) => Ok(null),
            },
            None => Ok(null),
        }
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}
