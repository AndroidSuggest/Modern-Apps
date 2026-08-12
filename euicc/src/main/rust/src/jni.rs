//! Thin JNI marshalling layer bridging the Kotlin `EuiccNative` object to the
//! native SGP.22 core, plus the STORE DATA transport that carries ES10 command
//! TLVs to the ISD-R.
//!
//! The protocol/ASN.1 logic lives in [`crate::asn1`] and [`crate::es10`]; this
//! file only wraps requests in GlobalPlatform STORE DATA APDUs, drives them over
//! the `EuiccNative.transmitApdu` upcall (which owns the telephony logical
//! channel on the Kotlin side), and converts results to Kotlin types.

use jni::objects::{JByteArray, JClass, JString, JValue};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

use crate::{es10, VERSION};

const EUICC_NATIVE_CLASS: &str = "com/vayunmathur/euicc/EuiccNative";
const TRANSMIT_METHOD: &str = "transmitApdu";
/// `([B)[B` — command APDU bytes → response APDU bytes (data followed by SW1 SW2).
const TRANSMIT_SIG: &str = "([B)[B";

/// Sends one command APDU to the eUICC by calling back into Kotlin
/// `EuiccNative.transmitApdu`, which transmits over the open logical channel and
/// returns the response bytes (response data followed by the two status bytes).
fn transmit_apdu(env: &mut JNIEnv, apdu: &[u8]) -> Result<Vec<u8>, String> {
    let arr: JByteArray = env
        .byte_array_from_slice(apdu)
        .map_err(|e| format!("apdu marshal: {e}"))?;
    let result = env
        .call_static_method(
            EUICC_NATIVE_CLASS,
            TRANSMIT_METHOD,
            TRANSMIT_SIG,
            &[JValue::Object(&arr)],
        )
        .map_err(|e| {
            // A pending exception poisons the next JNI call on this thread.
            let _ = env.exception_clear();
            format!("transmitApdu call failed: {e}")
        })?;
    let object = result.l().map_err(|e| format!("bad return: {e}"))?;
    if object.is_null() {
        return Err("transmitApdu returned null".into());
    }
    env.convert_byte_array(JByteArray::from(object))
        .map_err(|e| format!("reading response: {e}"))
}

/// Sends an ES10 command TLV to the ISD-R via GlobalPlatform STORE DATA,
/// splitting into ≤255-byte blocks, and returns the final response TLV with the
/// trailing `90 00` status stripped.
fn store_data(env: &mut JNIEnv, command: &[u8]) -> Result<Vec<u8>, String> {
    // A zero-length command still sends one (empty) block.
    let blocks: Vec<&[u8]> = if command.is_empty() {
        vec![&command[0..0]]
    } else {
        command.chunks(255).collect()
    };
    let last = blocks.len() - 1;

    let mut response = Vec::new();
    for (i, block) in blocks.iter().enumerate() {
        // P1: 0x11 for an intermediate block, 0x91 for the last (b8 = last block).
        let p1 = if i == last { 0x91 } else { 0x11 };
        let mut apdu = vec![0x80u8, 0xE2, p1, i as u8, block.len() as u8];
        apdu.extend_from_slice(block);
        response = transmit_apdu(env, &apdu)?;
    }

    if response.len() < 2 {
        return Err("APDU response too short".into());
    }
    let (data, sw) = response.split_at(response.len() - 2);
    if sw != [0x90, 0x00] {
        return Err(format!("eUICC returned SW={:02X}{:02X}", sw[0], sw[1]));
    }
    Ok(data.to_vec())
}

/// Returns `s` as a new Java string, or null (used on the error path).
fn new_jstring(env: &JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Reads a Java string into a Rust `String`, or `None` on error.
fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|js| js.into())
}

/// Sends a request and parses its INTEGER result, returning the eUICC result
/// code (0 = success) or -1 on a transport/parse error.
fn run_result(env: &mut JNIEnv, request: &[u8], tag: u32) -> jint {
    match store_data(env, request).and_then(|resp| es10::parse_result(&resp, tag)) {
        Ok(code) => code as jint,
        Err(_) => -1,
    }
}

/// `nativeGetProfiles()` — installed profiles as a JSON array, or null on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeGetProfiles<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let result = store_data(&mut env, &es10::build_get_profiles())
        .and_then(|resp| es10::parse_profiles(&resp));
    match result {
        Ok(profiles) => {
            let arr: Vec<_> = profiles
                .iter()
                .map(|p| {
                    serde_json::json!({
                        "iccid": p.iccid,
                        "iccidDisplay": p.iccid_display,
                        "isdpAid": p.isdp_aid,
                        "state": p.state,
                        "class": p.class,
                        "nickname": p.nickname,
                        "serviceProvider": p.service_provider,
                        "name": p.name,
                    })
                })
                .collect();
            new_jstring(&env, &serde_json::Value::Array(arr).to_string())
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// `nativeEnableProfile(iccid)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeEnableProfile<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
) -> jint {
    let Some(hex) = read_string(&mut env, &iccid) else {
        return -1;
    };
    run_result(&mut env, &es10::build_enable(&es10::hex_decode(&hex), true), es10::RESULT_TAG_ENABLE)
}

/// `nativeDisableProfile(iccid)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeDisableProfile<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
) -> jint {
    let Some(hex) = read_string(&mut env, &iccid) else {
        return -1;
    };
    run_result(&mut env, &es10::build_disable(&es10::hex_decode(&hex), true), es10::RESULT_TAG_DISABLE)
}

/// `nativeDeleteProfile(iccid)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeDeleteProfile<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
) -> jint {
    let Some(hex) = read_string(&mut env, &iccid) else {
        return -1;
    };
    run_result(&mut env, &es10::build_delete(&es10::hex_decode(&hex)), es10::RESULT_TAG_DELETE)
}

/// `nativeSetNickname(iccid, nickname)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeSetNickname<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    iccid: JString<'l>,
    nickname: JString<'l>,
) -> jint {
    let (Some(hex), Some(name)) = (read_string(&mut env, &iccid), read_string(&mut env, &nickname))
    else {
        return -1;
    };
    run_result(
        &mut env,
        &es10::build_set_nickname(&es10::hex_decode(&hex), &name),
        es10::RESULT_TAG_SET_NICKNAME,
    )
}

/// `nativeListNotifications()` — pending notifications as a JSON array, or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeListNotifications<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let result = store_data(&mut env, &es10::build_list_notifications())
        .and_then(|resp| es10::parse_notifications(&resp));
    match result {
        Ok(notes) => {
            let arr: Vec<_> = notes
                .iter()
                .map(|n| {
                    serde_json::json!({
                        "seqNumber": n.seq_number,
                        "operation": n.operation,
                        "address": n.address,
                        "iccidDisplay": n.iccid_display,
                    })
                })
                .collect();
            new_jstring(&env, &serde_json::Value::Array(arr).to_string())
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// `nativeRemoveNotification(seq)` — 0 on success, eUICC error code, or -1.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeRemoveNotification<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    seq: jint,
) -> jint {
    if seq < 0 {
        return -1;
    }
    match store_data(&mut env, &es10::build_remove_notification(seq as u32))
        .and_then(|resp| es10::parse_remove_result(&resp))
    {
        Ok(code) => code as jint,
        Err(_) => -1,
    }
}

/// `nativeVersion()` — native core version string.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeVersion<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    new_jstring(&env, VERSION)
}

/// `nativeGetEid()` — 32-hex-digit EID, or null on error (with a Kotlin
/// exception left pending is avoided; null signals failure).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeGetEid<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let result = store_data(&mut env, &es10::build_get_eid())
        .and_then(|resp| es10::parse_eid(&resp));
    match result {
        Ok(eid) => new_jstring(&env, &eid),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `nativeGetEuiccInfo()` — EUICCInfo1 subset as a JSON string, or null on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeGetEuiccInfo<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let result = store_data(&mut env, &es10::build_get_euicc_info1())
        .and_then(|resp| es10::parse_euicc_info1(&resp));
    match result {
        Ok(info) => {
            let json = serde_json::json!({
                "svn": info.svn,
                "ciPkIdListForVerification": info.ci_pkid_verification,
                "ciPkIdListForSigning": info.ci_pkid_signing,
            });
            new_jstring(&env, &json.to_string())
        }
        Err(_) => std::ptr::null_mut(),
    }
}
