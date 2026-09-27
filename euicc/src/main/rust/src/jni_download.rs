//! Split-phase download JNI entries: authenticate / finish / cancel plus the
//! atomic download entry kept for the platform service path.
//!
//! Split from `jni.rs` along module lines. The STORE DATA transport and string
//! helpers stay there and are imported; the session store and option parsing
//! live here with the entries that use them.

use jni::objects::{JClass, JString, JValue};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;

use std::cell::RefCell;
use std::collections::HashMap;

use crate::download;
use crate::es10;
use crate::es10b;
use crate::jni::{new_jstring, read_string};

/// Authenticated sessions awaiting confirm / confirmation-code / finish, keyed
/// by the SM-DP+ transaction id (opaque to Kotlin).
///
/// The split-phase flow opens one ISD-R channel per phase (each native entry
/// runs inside its own `withIsdrChannel`), so the session must survive across
/// JNI calls on the same thread. The map holds at most a handful of small
/// blobs (signed material + ids); finish/cancel consume.
const MAX_SESSIONS: usize = 4;

thread_local! {
    static SESSION: RefCell<HashMap<String, download::DownloadSession>> =
        RefCell::new(HashMap::new());
}

/// `nativeDownloadProfile(activationCode)` — runs the full SGP.22 download for
/// an activation code and returns a JSON `{success, message, iccid}` string.
/// Must be called while the ISD-R channel is open (inside `withIsdrChannel`).
///
/// Kept for the `EuiccManagerService` platform path; the UI flow uses the
/// split-phase entries. No nickname is applied here — Settings shows the
/// carrier metadata, and the user can rename from the LUI.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeDownloadProfile<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    activation_code: JString<'l>,
) -> jstring {
    // Resolve the HTTP bridge (library:network) once; harmless if already done.
    jni_http::init(&mut env);

    let Some(code) = read_string(&mut env, &activation_code) else {
        let json = serde_json::json!({ "success": false, "message": "Invalid activation code" });
        return new_jstring(&env, &json.to_string());
    };

    let json = match crate::download::download_profile(&mut env, &code) {
        Ok(result) => serde_json::json!({
            "success": result.success,
            "message": result.message,
            "iccid": result.installed_iccid,
        }),
        Err(message) => serde_json::json!({ "success": false, "message": message }),
    };
    new_jstring(&env, &json.to_string())
}

/// `nativeAuthenticate(activationCode, confirmationCode, tacHex, imei)` —
/// runs InitiateAuthentication → AuthenticateServer → AuthenticateClient and
/// returns a JSON session `{error, transactionId, carrier, profileName, iccid,
/// ccRequired}` without touching the eUICC's profile store. The opaque
/// `transactionId` handle feeds `nativeFinishDownload` / `nativeCancelDownload`.
///
/// `tacHex` is 8 hex digits (defaults to lpac's `35 29 06 11` when blank);
/// `imei` is optional digits (dropped when blank/invalid).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeAuthenticate<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    activation_code: JString<'l>,
    confirmation_code: JString<'l>,
    tac_hex: JString<'l>,
    imei: JString<'l>,
) -> jstring {
    jni_http::init(&mut env);

    let Some(code) = read_string(&mut env, &activation_code) else {
        return auth_error(&env, "Invalid activation code");
    };
    let options = download_options(&mut env, &confirmation_code, &tac_hex, &imei);
    // Stash the authenticated session for finish/cancel.
    let json = match crate::download::authenticate(&mut env, &code, &options) {
        Ok(session) => {
            let handle = session.transaction_id.clone();
            let reply = serde_json::json!({
                "error": serde_json::Value::Null,
                "transactionId": handle,
                "carrier": empty_to_null(&session.metadata.service_provider),
                "profileName": empty_to_null(&session.metadata.profile_name),
                "iccid": empty_to_null(&session.metadata.iccid_display),
                "ccRequired": session.cc_required,
            });
            SESSION.with(|s| {
                // Oldest-first eviction keeps the slot map bounded.
                let mut guard = s.borrow_mut();
                guard.insert(handle, session);
                while guard.len() > MAX_SESSIONS {
                    if let Some(oldest) = guard.keys().next().cloned() {
                        guard.remove(&oldest);
                    } else {
                        break;
                    }
                }
            });
            reply
        }
        Err(message) => serde_json::json!({
            "error": message,
            "transactionId": serde_json::Value::Null,
            "carrier": serde_json::Value::Null,
            "profileName": serde_json::Value::Null,
            "iccid": serde_json::Value::Null,
            "ccRequired": false,
        }),
    };
    new_jstring(&env, &json.to_string())
}

/// `nativeFinishDownload(transactionId, confirmationCode)` — runs
/// PrepareDownload (with `hashCc`) → GetBoundProfilePackage → BPP install with
/// progress callbacks, returning `{success, message, iccid}`. Consumes the session.
/// `nickname` (from the carrier preview) is applied via SetNickname while the
/// channel is still open, so the profile shows a name immediately.
/// `nativeFinishDownload(transactionId, confirmationCode, nickname, callback)`
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeFinishDownload<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    transaction_id: JString<'l>,
    confirmation_code: JString<'l>,
    nickname: JString<'l>,
    callback: jni::objects::JObject<'l>,
) -> jstring {
    jni_http::init(&mut env);

    let Some(handle) = read_string(&mut env, &transaction_id) else {
        return finish_json(&env, false, "Invalid download session");
    };
    let code = read_string(&mut env, &confirmation_code).filter(|c| !c.is_empty());
    let nickname = read_string(&mut env, &nickname).filter(|c| !c.trim().is_empty());
    let session = SESSION.with(|s| s.borrow_mut().remove(&handle));
    let Some(session) = session else {
        return finish_json(&env, false, "Download session expired — start again");
    };

    let mut progress = JniProgress {
        callback: env.new_global_ref(&callback).ok(),
    };
    let json = match crate::download::finish_download(&mut env, &session, code.as_deref(), &mut progress)
    {
        Ok(result) => {
            // Seed the profile nickname from the carrier preview while the
            // channel is open; best-effort (a rename can always be retried).
            if result.success {
                if let Some(name) = nickname.as_deref() {
                    let iccid = crate::es10::hex_decode(&result.installed_iccid);
                    if !iccid.is_empty() {
                        let _ = crate::jni::store_data(
                            &mut env,
                            &crate::es10::build_set_nickname(&iccid, name.trim()),
                        );
                    }
                }
            }
            serde_json::json!({
                "success": result.success,
                "message": result.message,
                "iccid": result.installed_iccid,
            })
        }
        Err(message) => serde_json::json!({ "success": false, "message": message }),
    };
    new_jstring(&env, &json.to_string())
}

/// `nativeCancelDownload(transactionId, reason)` — ES10 CancelSession + ES9+
/// cancelSession for an authenticated-but-unfinished session. Best-effort;
/// always consumes the session and returns true.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_euicc_EuiccNative_nativeCancelDownload<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    transaction_id: JString<'l>,
    reason: jint,
) -> jboolean {
    let Some(handle) = read_string(&mut env, &transaction_id) else {
        return 0;
    };
    let session = SESSION.with(|s| s.borrow_mut().remove(&handle));
    if let Some(session) = session {
        crate::download::cancel_session(&mut env, &session, cancel_reason(reason));
    }
    1
}

// ---------------------------------------------------------------------------
// Split-phase download helpers
// ---------------------------------------------------------------------------

/// Builds [`download::DownloadOptions`] from the authenticate call's string
/// args. Blank TAC hex falls back to [`es10b::DEFAULT_TAC`]; blank IMEI is
/// dropped; blank confirmation code is dropped.
fn download_options(
    env: &mut JNIEnv,
    confirmation_code: &JString,
    tac_hex: &JString,
    imei: &JString,
) -> download::DownloadOptions {
    let confirmation_code = read_string(env, confirmation_code).filter(|c| !c.trim().is_empty());
    let imei = read_string(env, imei).filter(|c| !c.trim().is_empty());
    let tac = read_string(env, tac_hex)
        .filter(|t| !t.trim().is_empty())
        .and_then(|t| parse_tac(&t))
        .or(Some(es10b::DEFAULT_TAC));
    download::DownloadOptions {
        confirmation_code,
        tac,
        imei,
    }
}

/// Parses 8 hex digits into a TAC. Malformed input falls back to the default
/// (never block a download on a settings typo).
fn parse_tac(hex: &str) -> Option<[u8; 4]> {
    let bytes = es10::hex_decode(hex.trim());
    if bytes.len() == 4 {
        Some([bytes[0], bytes[1], bytes[2], bytes[3]])
    } else {
        None
    }
}

/// Maps the Kotlin cancel-reason int onto [`es10b::CancelReason`].
fn cancel_reason(reason: jint) -> es10b::CancelReason {
    match reason {
        1 => es10b::CancelReason::Postponed,
        2 => es10b::CancelReason::Timeout,
        3 => es10b::CancelReason::PprNotAllowed,
        4 => es10b::CancelReason::MetadataMismatch,
        5 => es10b::CancelReason::LoadBppExecutionError,
        _ => es10b::CancelReason::EndUserRejection,
    }
}

/// Renders empty strings as JSON null for the authenticate reply.
fn empty_to_null(s: &str) -> serde_json::Value {
    if s.is_empty() {
        serde_json::Value::Null
    } else {
        serde_json::Value::String(s.to_string())
    }
}

/// Authenticate-path error reply.
fn auth_error(env: &JNIEnv, message: &str) -> jstring {
    let json = serde_json::json!({
        "error": message,
        "transactionId": serde_json::Value::Null,
        "carrier": serde_json::Value::Null,
        "profileName": serde_json::Value::Null,
        "iccid": serde_json::Value::Null,
        "ccRequired": false,
    });
    new_jstring(env, &json.to_string())
}

/// Finish-path `{success, message}` reply.
fn finish_json(env: &JNIEnv, success: bool, message: &str) -> jstring {
    let json = serde_json::json!({ "success": success, "message": message });
    new_jstring(env, &json.to_string())
}

/// [`download::ProgressCallback`] that forwards segment counts to Kotlin
/// `onProgress(done, total)` and aborts when it returns false. When the
/// callback cannot be retained as a global ref (OOM), progress degrades to a
/// no-op and the install still proceeds.
struct JniProgress {
    callback: Option<jni::objects::GlobalRef>,
}

impl download::ProgressCallback for JniProgress {
    fn on_progress(&mut self, env: &mut JNIEnv, done: usize, total: usize) -> bool {
        let Some(callback) = &self.callback else {
            return true;
        };
        let result = env
            .call_method(
                callback.as_obj(),
                "onProgress",
                "(II)Z",
                &[JValue::Int(done as jint), JValue::Int(total as jint)],
            )
            .and_then(|v| v.z());
        // Clear a pending Kotlin exception so later JNI calls stay valid.
        let _ = env.exception_clear();
        result.unwrap_or(true)
    }
}
