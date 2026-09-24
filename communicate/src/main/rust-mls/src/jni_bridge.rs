//! JNI surface for `…rcs.e2e.RustMlsCrypto`.
//!
//! Flat byte protocol: every call takes opaque blobs in and returns
//! `byte[][]` (`[storage_out, result…]`) or `null` on error (with a
//! `RuntimeException` thrown carrying the message). Kotlin persists
//! `storage_out` in Room alongside its conversation rows.

use crate::group::{self, MESSAGE_MAGIC, WELCOME_MAGIC};
use crate::keys;
use crate::message::payload_kind;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray};
use jni::sys::{jbyteArray, jint, jobjectArray};
use jni::JNIEnv;

// PACKAGE STRUCTURE EXCEPTION (JNI)

fn bytes_in<'a>(env: &mut JNIEnv<'a>, arr: &JByteArray<'a>) -> Option<Vec<u8>> {
    if arr.is_null() {
        return None;
    }
    env.convert_byte_array(arr).ok()
}

fn bytes_out<'a>(env: &mut JNIEnv<'a>, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn throw_runtime(env: &mut JNIEnv<'_>, msg: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", msg);
}

fn arrays_out<'a>(env: &mut JNIEnv<'a>, parts: &[&[u8]]) -> jobjectArray {
    let cls = match env.find_class("[B") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array(parts.len() as i32, &cls, JObject::null()) {
        Ok(o) => o,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, part) in parts.iter().enumerate() {
        let jb = match env.byte_array_from_slice(part) {
            Ok(x) => x,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_object_array_element(&arr, i as i32, jb).is_err() {
            return std::ptr::null_mut();
        }
    }
    arr.into_raw()
}

fn fail<'a>(env: &mut JNIEnv<'a>, msg: String) -> jobjectArray {
    throw_runtime(env, &msg);
    std::ptr::null_mut()
}

/// Generate an identity for `e164`. Returns `[storage(empty), identity]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_generateIdentity<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    e164: JByteArray<'local>,
) -> jobjectArray {
    let id_b = match bytes_in(&mut env, &e164) {
        Some(b) => b,
        None => return fail(&mut env, "e164 null".to_string()),
    };
    let id = String::from_utf8(id_b).unwrap_or_default();
    match keys::generate_identity(&id) {
        Ok(state) => arrays_out(&mut env, &[&[], &keys::serialize_identity(&state)]),
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Build a key package. `(storage_in, identity)` → `[storage_out, key_package_tls]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_buildKeyPackage<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
) -> jobjectArray {
    let s = bytes_in(&mut env, &storage).unwrap_or_default();
    let id = match bytes_in(&mut env, &identity) {
        Some(b) => b,
        None => return fail(&mut env, "identity null".to_string()),
    };
    match group::build_key_package(&s, &id) {
        Ok((storage_out, kp)) => arrays_out(&mut env, &[&storage_out, &kp]),
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Create a group. `(storage_in, identity)` → `[storage_out, group_id]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_createGroup<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
) -> jobjectArray {
    let s = bytes_in(&mut env, &storage).unwrap_or_default();
    let id = match bytes_in(&mut env, &identity) {
        Some(b) => b,
        None => return fail(&mut env, "identity null".to_string()),
    };
    match group::create_group(&s, &id) {
        Ok(created) => arrays_out(&mut env, &[&created.storage, &created.group_id]),
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Add members. `(storage_in, identity, group_id, key_packages[])`
/// → `[storage_out, commit_tls, welcome_framed]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_addMembers<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
    group_id: JByteArray<'local>,
    key_packages: JObjectArray<'local>,
) -> jobjectArray {
    let s = bytes_in(&mut env, &storage).unwrap_or_default();
    let id = match bytes_in(&mut env, &identity) {
        Some(b) => b,
        None => return fail(&mut env, "identity null".to_string()),
    };
    let gid = match bytes_in(&mut env, &group_id) {
        Some(b) => b,
        None => return fail(&mut env, "group_id null".to_string()),
    };
    let len = env.get_array_length(&key_packages).unwrap_or(0);
    let mut kps = Vec::with_capacity(len.max(0) as usize);
    for i in 0..len {
        let item: JByteArray = match env.get_object_array_element(&key_packages, i) {
            Ok(o) => o.into(),
            Err(_) => return fail(&mut env, "key package null".to_string()),
        };
        match bytes_in(&mut env, &item) {
            Some(b) => kps.push(b),
            None => return fail(&mut env, "key package null".to_string()),
        }
    }
    match group::add_members(&s, &id, &gid, &kps) {
        Ok(added) => arrays_out(&mut env, &[&added.storage, &added.commit, &added.welcome]),
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Join from a Welcome envelope. `(storage_in, welcome_framed)`
/// → `[storage_out, group_id]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_joinGroup<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    welcome: JByteArray<'local>,
) -> jobjectArray {
    let s = bytes_in(&mut env, &storage).unwrap_or_default();
    let w = match bytes_in(&mut env, &welcome) {
        Some(b) => b,
        None => return fail(&mut env, "welcome null".to_string()),
    };
    match group::join_group(&s, &w) {
        Ok((storage_out, gid)) => arrays_out(&mut env, &[&storage_out, &gid]),
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Encrypt. `(storage_in, identity, group_id, plaintext)`
/// → `[storage_out, framed_payload]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_encrypt<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
    group_id: JByteArray<'local>,
    plaintext: JByteArray<'local>,
) -> jobjectArray {
    let s = bytes_in(&mut env, &storage).unwrap_or_default();
    let id = match bytes_in(&mut env, &identity) {
        Some(b) => b,
        None => return fail(&mut env, "identity null".to_string()),
    };
    let gid = match bytes_in(&mut env, &group_id) {
        Some(b) => b,
        None => return fail(&mut env, "group_id null".to_string()),
    };
    let pt = bytes_in(&mut env, &plaintext).unwrap_or_default();
    match group::encrypt(&s, &id, &gid, &pt) {
        Ok(enc) => arrays_out(&mut env, &[&enc.storage, &enc.payload]),
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Decrypt. `(storage_in, group_id, framed_payload)`
/// → `[storage_out, is_application(1B), plaintext]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_decrypt<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    group_id: JByteArray<'local>,
    payload: JByteArray<'local>,
) -> jobjectArray {
    let s = bytes_in(&mut env, &storage).unwrap_or_default();
    let gid = match bytes_in(&mut env, &group_id) {
        Some(b) => b,
        None => return fail(&mut env, "group_id null".to_string()),
    };
    let p = match bytes_in(&mut env, &payload) {
        Some(b) => b,
        None => return fail(&mut env, "payload null".to_string()),
    };
    match group::decrypt(&s, &gid, &p) {
        Ok(dec) => {
            let flag = [u8::from(dec.is_application)];
            arrays_out(&mut env, &[&dec.storage, &flag, &dec.plaintext])
        }
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Remove members by leaf index. `(storage_in, identity, group_id, indices_csv)`
/// → `[storage_out, commit_tls]`. Indices are ASCII decimal, comma-separated.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_removeMembers<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    storage: JByteArray<'local>,
    identity: JByteArray<'local>,
    group_id: JByteArray<'local>,
    indices_csv: JByteArray<'local>,
) -> jobjectArray {
    let s = bytes_in(&mut env, &storage).unwrap_or_default();
    let id = match bytes_in(&mut env, &identity) {
        Some(b) => b,
        None => return fail(&mut env, "identity null".to_string()),
    };
    let gid = match bytes_in(&mut env, &group_id) {
        Some(b) => b,
        None => return fail(&mut env, "group_id null".to_string()),
    };
    let csv = bytes_in(&mut env, &indices_csv).unwrap_or_default();
    let indices: Vec<u32> = String::from_utf8_lossy(&csv)
        .split(',')
        .filter_map(|t| t.trim().parse().ok())
        .collect();
    match group::remove_members(&s, &id, &gid, &indices) {
        Ok((storage_out, commit)) => arrays_out(&mut env, &[&storage_out, &commit]),
        Err(e) => fail(&mut env, e.to_string()),
    }
}

/// Probe framing: 0=welcome, 1=message, 2=unknown.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_payloadKind<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
    payload: JByteArray<'local>,
) -> jint {
    let p = bytes_in(&mut env, &payload).unwrap_or_default();
    match payload_kind(&p) {
        crate::message::PayloadKind::Welcome => 0,
        crate::message::PayloadKind::Message => 1,
        crate::message::PayloadKind::Unknown => 2,
    }
}

/// Magic prefixes (for Kotlin-side routing without a JNI round trip).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_welcomeMagic<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> jbyteArray {
    bytes_out(&mut env, WELCOME_MAGIC)
}

/// Magic prefixes (for Kotlin-side routing without a JNI round trip).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_communicate_data_rcs_e2e_RustMlsCrypto_messageMagic<
    'local,
>(
    mut env: JNIEnv<'local>,
    _cls: JClass<'local>,
) -> jbyteArray {
    bytes_out(&mut env, MESSAGE_MAGIC)
}
