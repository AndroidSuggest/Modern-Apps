//! Nearby Share / Quick Share protocol core (no Play Services).
//!
//! Owns: the Nearby Connections connection handshake, the BetoCore UKEY2 D2D
//! handshake with the **`AES_256_CBC-HMAC_SHA256`** record protocol, the paired-key
//! exchange, `OfflineFrame`/Sharing `Frame` encode/decode and payload chunking,
//! plus the `BleAdvertisement` byte codec. Transport (NSD/BLE discovery, TCP
//! socket) stays in Kotlin.
//!
//! Wire facts are recovered from the GMS 26.24.34 decompile; see
//! `share/QUICK_SHARE_VERIFICATION.md` and the module docs in [`frame`].

// The JNI entry points mirror the Java parameter names in `ShareNative.kt`, which are
// camelCase by Kotlin convention. Renaming them here would only make the two sides
// harder to diff.
#![allow(non_snake_case)]

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

mod ble_adv;
mod endpoint_info;
mod frame;
mod payload;
mod presence;
mod session;

use session::{Role, Session};

// ---------------------------------------------------------------------------
// Global session registry
// ---------------------------------------------------------------------------

static SESSIONS: OnceLock<Mutex<HashMap<i64, Session>>> = OnceLock::new();
static NEXT_ID: OnceLock<Mutex<i64>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<i64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn next_id() -> &'static Mutex<i64> {
    NEXT_ID.get_or_init(|| Mutex::new(1))
}

/// Run `f` with the session for `handle`, or return `fallback` if it is gone.
///
/// A poisoned mutex means another thread panicked inside the protocol core; the
/// session map is still structurally sound, so recover the guard rather than
/// propagating the panic across the JNI boundary (which is undefined behaviour).
fn with_session<T>(handle: i64, fallback: T, f: impl FnOnce(&mut Session) -> T) -> T {
    let mut map = match sessions().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    match map.get_mut(&handle) {
        Some(s) => f(s),
        None => fallback,
    }
}

// ---------------------------------------------------------------------------
// JNI helpers
// ---------------------------------------------------------------------------

fn bytes_in(env: &mut JNIEnv, arr: &JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(arr).ok()
}
fn bytes_out(env: &JNIEnv, data: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(data) {
        Ok(a) => a.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
fn str_in(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|j| j.to_string_lossy().into_owned())
}

// ---------------------------------------------------------------------------
// JNI API — must match share/PROTOCOL_CONTRACT.md and ShareNative.kt.
// Class: com.vayunmathur.share.protocol.ShareNative
// Naming: Java_com_vayunmathur_share_protocol_ShareNative_<method>
// ---------------------------------------------------------------------------

/// long nativeInit(String localName, byte[] localEndpointInfo, String localEndpointId, boolean isInitiator) -> sessionHandle
///
/// `isInitiator` must be `true` for the side that dialled the TCP socket and
/// `false` for the side that accepted it. Only the initiator sends
/// `CONNECTION_REQUEST`, and only the initiator is the UKEY2 client.
///
/// `localEndpointId` must be the id this device advertises, so
/// `ConnectionRequestFrame.endpoint_id` matches the mDNS `WifiLanServiceInfo`; an empty
/// string falls back to a fresh random id.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeInit<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    jLocalName: JString<'l>,
    jLocalEndpointInfo: JByteArray<'l>,
    jLocalEndpointId: JString<'l>,
    isInitiator: jboolean,
) -> jlong {
    let local_name = str_in(&mut env, &jLocalName).unwrap_or_else(|| "Share".to_string());
    let local_info = bytes_in(&mut env, &jLocalEndpointInfo).unwrap_or_default();
    let local_endpoint_id = str_in(&mut env, &jLocalEndpointId).unwrap_or_default();
    let role = if isInitiator != 0 {
        Role::Initiator
    } else {
        Role::Responder
    };
    let id = {
        let mut guard = match next_id().lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        let id = *guard;
        *guard = guard.saturating_add(1);
        id
    };
    let sess = Session::new(role, local_name, local_info, local_endpoint_id);
    let mut map = match sessions().lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    let _ = map.insert(id, sess);
    id
}

/// int nativeFeedInbound(long handle, byte[] bytes) -> 0 ok, <0 error
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeFeedInbound<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jBytes: JByteArray<'l>,
) -> jint {
    let bytes = match bytes_in(&mut env, &jBytes) {
        Some(b) => b,
        None => return -1,
    };
    with_session(handle, -2, |s| s.feed_inbound(&bytes))
}

/// byte[] nativeDrainOutbound(long handle) -> bytes to send, or null if none.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDrainOutbound<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    match with_session(handle, None, Session::outbound_drain) {
        Some(bytes) if !bytes.is_empty() => bytes_out(&env, &bytes),
        _ => std::ptr::null_mut(),
    }
}

/// int nativeQueryState(long handle) -> State ordinal (see PROTOCOL_CONTRACT.md)
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryState<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, |s| s.query_state())
}

/// byte[] nativeQueryPendingFiles(long handle) -> JSON array of {name,sizeBytes,mimeType}
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryPendingFiles<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    let json = with_session(handle, None, |s| Some(file_list_json(s.pending_files())));
    match json {
        Some(j) => bytes_out(&env, j.as_bytes()),
        None => std::ptr::null_mut(),
    }
}

/// int nativeAccept(long handle, boolean accept, String destDir) 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeAccept<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    accept: jboolean,
    jDestDir: JString<'l>,
) -> jint {
    let dest = str_in(&mut env, &jDestDir).unwrap_or_default();
    with_session(handle, -1, |s| s.accept(accept != 0, &dest))
}

/// int nativeSetFilesToSend(long handle, byte[] json) -> 0 ok
///
/// `json` has the same `[{"name","sizeBytes","mimeType"}]` shape that
/// `nativeQueryPendingFiles` returns.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeSetFilesToSend<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jJson: JByteArray<'l>,
) -> jint {
    let Some(bytes) = bytes_in(&mut env, &jJson) else {
        return -1;
    };
    let Ok(text) = String::from_utf8(bytes) else {
        return -1;
    };
    let Some(files) = parse_file_list_json(&text) else {
        return -1;
    };
    with_session(handle, -1, |s| {
        s.set_pending_files_for_send(files);
        0
    })
}

/// int nativeQueueIntroduction(long handle) -> 0 ok
///
/// Announces the files staged by `nativeSetFilesToSend`. Safe to call before the
/// paired-key exchange finishes: the frame is held and emitted once the session is
/// ready.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueueIntroduction<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, Session::queue_introduction)
}

/// int nativeSendKeepAlive(long handle) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeSendKeepAlive<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, Session::send_keep_alive)
}

/// int nativeOpenFile(long handle, String fileName, long fileSize) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeOpenFile<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jFileName: JString<'l>,
    fileSize: jlong,
) -> jint {
    let name = str_in(&mut env, &jFileName).unwrap_or_default();
    if name.is_empty() {
        return -1;
    }
    with_session(handle, -1, |s| s.open_file(&name, fileSize))
}

/// int nativeWriteChunk(long handle, byte[] chunk) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeWriteChunk<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    jChunk: JByteArray<'l>,
) -> jint {
    let bytes = match bytes_in(&mut env, &jChunk) {
        Some(b) => b,
        None => return -1,
    };
    with_session(handle, -2, |s| s.write_chunk(&bytes))
}

/// int nativeCloseFile(long handle) -> 0 ok
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeCloseFile<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jint {
    with_session(handle, -1, Session::close_file)
}

/// byte[] nativeDrainReceived(long handle) -> one received-chunk record, or null.
///
/// Call after every `nativeFeedInbound` until it returns null. The record layout is
/// fixed by `PROTOCOL_CONTRACT.md` §6; each call hands over one FILE `PayloadChunk`
/// and drops it, so the session never accumulates a whole file.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeDrainReceived<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    match with_session(handle, None, Session::drain_received) {
        Some(bytes) => bytes_out(&env, &bytes),
        None => std::ptr::null_mut(),
    }
}

/// String nativeQueryFailureReason(long handle) -> why the session failed, or null.
///
/// Null while the session is healthy, for an unknown handle, or when the failure
/// carried no reason.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryFailureReason<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jni::sys::jobject {
    let reason = with_session(handle, None, |s| s.failure_reason().map(str::to_owned));
    match reason {
        Some(r) => match env.new_string(r) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// String nativeQueryPeerName(long handle) -> the peer's advertised device name, or null.
///
/// Null until the peer's `CONNECTION_REQUEST` has been read, and for an unknown handle.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeQueryPeerName<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jni::sys::jobject {
    let name = with_session(handle, None, |s| s.peer_name().map(str::to_owned));
    match name {
        Some(n) => match env.new_string(n) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// File-list JSON (PROTOCOL_CONTRACT.md §6)
// ---------------------------------------------------------------------------

fn json_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

fn file_list_json(files: &[payload::FileMeta]) -> String {
    let mut json = String::from("[");
    for (i, f) in files.iter().enumerate() {
        if i > 0 {
            json.push(',');
        }
        json.push_str(&format!(
            "{{\"name\":\"{}\",\"sizeBytes\":{},\"mimeType\":\"{}\"}}",
            json_escape(&f.name),
            f.size_bytes,
            json_escape(&f.mime_type),
        ));
    }
    json.push(']');
    json
}

/// Parse the fixed `[{name,sizeBytes,mimeType}]` shape produced by `ShareSession`.
///
/// Hand-rolled rather than adding `serde_json` to this crate: the shape is fixed by
/// `PROTOCOL_CONTRACT.md` §6 and both sides of it live in this repository.
fn parse_file_list_json(text: &str) -> Option<Vec<payload::FileMeta>> {
    fn string_field(obj: &str, key: &str) -> Option<String> {
        let after_key = obj.split_once(&format!("\"{key}\""))?.1;
        let after_colon = after_key.split_once(':')?.1;
        let open = after_colon.find('"')?;
        let rest = after_colon.get(open + 1..)?;
        let close = rest.find('"')?;
        rest.get(..close).map(str::to_string)
    }
    fn number_field(obj: &str, key: &str) -> Option<u64> {
        let after_key = obj.split_once(&format!("\"{key}\""))?.1;
        let after_colon = after_key.split_once(':')?.1;
        let digits: String = after_colon
            .trim_start()
            .chars()
            .take_while(char::is_ascii_digit)
            .collect();
        digits.parse().ok()
    }

    let inner = text
        .trim()
        .strip_prefix('[')
        .and_then(|s| s.strip_suffix(']'))?
        .trim();
    if inner.is_empty() {
        return Some(Vec::new());
    }
    let mut out = Vec::new();
    for obj in inner.split("},") {
        out.push(payload::FileMeta {
            name: string_field(obj, "name")?,
            size_bytes: number_field(obj, "sizeBytes")?,
            mime_type: string_field(obj, "mimeType").unwrap_or_default(),
            payload_id: 0,
        });
    }
    Some(out)
}

// ---------------------------------------------------------------------------
// Nearby Connections BleAdvertisement + service-id derivation (ble_adv.rs)
// ---------------------------------------------------------------------------

/// String nativeMdnsServiceType() -> `_FC9F5ED42C8A._tcp`
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeMdnsServiceType<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
) -> jni::sys::jobject {
    match env.new_string(ble_adv::mdns_service_type()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// byte[] nativeBleServiceIdHash() -> the 3-byte truncated SHA-256 of "NearbySharing"
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_share_protocol_ShareNative_nativeBleServiceIdHash<'l>(
    env: JNIEnv<'l>,
    _cls: JClass<'l>,
) -> jbyteArray {
    bytes_out(&env, &ble_adv::ble_service_id_hash())
}

include!("lib_part1.rs");