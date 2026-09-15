//! JNI entry points for `com.vayunmathur.library.ml.VulkanBridge`.
//!
//! Thin marshalling layer only: each function converts its JNI arguments into
//! plain Rust values, delegates to [`vulkan_session`], [`tensors`] or [`probe`],
//! and converts the result back. No inference logic lives here.
//!
//! ## Expected Kotlin declarations (static natives on the `VulkanBridge` object)
//!
//! ```kotlin
//! static external fun probe(): String?
//! static external fun load(model: ByteArray, tag: String): Long
//! static external fun loadPreflight(model: ByteArray): String?
//! static external fun loadPath(path: String): Long
//! static external fun close(handle: Long)
//! static external fun run(handle: Long, flatInput: ByteArray): ByteArray?
//! static external fun lastOutputShapes(handle: Long): String?
//! static external fun lastOutputNames(handle: Long): String?
//! static external fun kvCreate(handle: Long, maxTokens: Int): Long
//! static external fun kvClose(kvHandle: Long)
//! static external fun runCached(handle: Long, kvHandle: Long, flatInput: ByteArray): ByteArray?
//! static external fun argmaxLastRow(logitsF32: ByteArray, cols: Int): Int
//! ```
//!
//! ## Handle protocol
//!
//! Successful `load` / `loadPath` calls leak a `Box<Mutex<GpuSession>>` and hand
//! the raw pointer to Java as a `jlong` (0 means failure). `close` reclaims it
//! exactly once. KV-cache handles work the same way with `Box<Mutex<KvCache>>`
//! via `kvCreate` / `kvClose`. Every entry point treats a 0 handle as "already
//! failed" and returns the failure value without touching JNI.
//!
//! ## Assumed sibling APIs
//!
//! * `super::probe::probe_json() -> String` — device capability report as JSON.
//! * `super::tensors::argmax_last_row_f32(bytes: &[u8], cols: usize) -> Option<usize>`
//!   — `bytes` is little-endian `f32` logits, `rows = bytes.len() / 4 / cols`.
//! * `super::vulkan_session::GpuSession` with `load(&[u8], &str)`, `load_path(&str)`,
//!   `preflight(&[u8]) -> Result<String, _>` (JSON summary), `run(&mut self, &[u8])`,
//!   `output_shapes_json(&self)`, `output_names_json(&self)`,
//!   `create_kv(&mut self, usize)`, and `run_cached(&mut self, &mut KvCache, &[u8])`.
//! * `super::vulkan_session::KvCache` — opaque autoregressive cache state.
//!
//! All fallible sibling calls only need `Result<T, E: Display>`; errors are
//! deliberately swallowed here and surface to Java as 0 / null.

// SAFETY: `unsafe` is used in this file only for JNI handle casts — turning the
// `jlong` back into the `Box<Mutex<..>>` pointer it came from (`Box::from_raw`,
// `&*raw`). Array traffic uses the safe copying `jni` APIs
// (`convert_byte_array`, `byte_array_from_slice`), never pinned raw elements.
#![allow(unsafe_code)]

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;

use super::probe;
use super::tensors;
use super::vulkan_session::{GpuSession, KvCache};

/// Runs `body`, returning `default` when it panics.
///
/// Every JNI entry point funnels through this so a Rust panic can never unwind
/// across the FFI boundary (which would abort the process).
fn guard<T>(default: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(default)
}

/// Copies a Java `byte[]` into a `Vec<u8>`, or `None` on JNI failure.
fn read_bytes(env: &mut JNIEnv, array: JByteArray) -> Option<Vec<u8>> {
    env.convert_byte_array(array).ok()
}

/// Copies a Java `String` into a Rust `String`, or `None` on JNI failure.
fn read_string(env: &mut JNIEnv, value: JString) -> Option<String> {
    env.get_string(value)
        .ok()
        .and_then(|text| text.to_str().map(str::to_owned).ok())
}

/// Builds a Java `String` from `text`, or null when `text` is `None` or the
/// allocation fails.
fn jstring_or_null(env: &mut JNIEnv, text: Option<String>) -> jstring {
    match text {
        Some(text) => match env.new_string(text) {
            Ok(string) => string.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// Builds a Java `byte[]` from `data`, or null when `data` is `None` or the
/// allocation fails.
fn jbytes_or_null(env: &mut JNIEnv, data: Option<Vec<u8>>) -> jbyteArray {
    match data {
        Some(data) => match env.byte_array_from_slice(&data) {
            Ok(array) => array.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// Leaks `session` to Java as a `jlong` handle (never 0 for a live session).
fn box_session(session: GpuSession) -> jlong {
    let raw: *mut Mutex<GpuSession> = Box::into_raw(Box::new(Mutex::new(session)));
    raw as jlong
}

/// Leaks a KV cache to Java as a `jlong` handle (never 0 for a live cache).
fn box_kv(cache: KvCache) -> jlong {
    let raw: *mut Mutex<KvCache> = Box::into_raw(Box::new(Mutex::new(cache)));
    raw as jlong
}

/// Locks the session behind `handle` and runs `op`, or returns `default` for a
/// null handle or a poisoned mutex.
fn with_session<R>(handle: jlong, default: R, op: impl FnOnce(&mut GpuSession) -> R) -> R {
    if handle == 0 {
        return default;
    }
    let raw = handle as *mut Mutex<GpuSession>;
    // SAFETY: non-null handle produced by `box_session` (`Box::into_raw`), freed
    // exactly once by `close`; Java owns the handle so it outlives this call.
    let mutex = unsafe { &*raw };
    match mutex.lock() {
        Ok(mut session) => op(&mut *session),
        Err(_) => default,
    }
}

/// Locks the session and KV cache behind their handles and runs `op`, or
/// returns `default` for a null handle or a poisoned mutex. Locks are always
/// taken session-first so concurrent callers share one lock order.
fn with_cached<R>(
    session_handle: jlong,
    kv_handle: jlong,
    default: R,
    op: impl FnOnce(&mut GpuSession, &mut KvCache) -> R,
) -> R {
    if session_handle == 0 || kv_handle == 0 {
        return default;
    }
    let session_raw = session_handle as *mut Mutex<GpuSession>;
    let kv_raw = kv_handle as *mut Mutex<KvCache>;
    // SAFETY: as in `with_session`; both handles are live `Box::into_raw`
    // pointers owned by Java for the duration of this call.
    let (session_mutex, kv_mutex) = unsafe { (&*session_raw, &*kv_raw) };
    match (session_mutex.lock(), kv_mutex.lock()) {
        (Ok(mut session), Ok(mut cache)) => op(&mut *session, &mut *cache),
        _ => default,
    }
}

/// Reports Vulkan device capabilities as a JSON string, or null on failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_probe(
    mut env: JNIEnv,
    _cls: JClass,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        jstring_or_null(&mut env, Some(probe::probe_json()))
    })
}

/// Loads a model from its bytes; `tag` is an opaque label for logs or caches.
/// Returns a session handle, or 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_load(
    mut env: JNIEnv,
    _cls: JClass,
    model: JByteArray,
    tag: JString,
) -> jlong {
    guard(0, || {
        let bytes = match read_bytes(&mut env, model) {
            Some(bytes) => bytes,
            None => return 0,
        };
        let tag = read_string(&mut env, tag).unwrap_or_default();
        match GpuSession::load(&bytes, &tag) {
            Ok(session) => box_session(session),
            Err(_) => 0,
        }
    })
}

/// Validates model bytes and returns a JSON summary string, or null when the
/// model cannot be loaded. Never creates a session.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_loadPreflight(
    mut env: JNIEnv,
    _cls: JClass,
    model: JByteArray,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let bytes = match read_bytes(&mut env, model) {
            Some(bytes) => bytes,
            None => return std::ptr::null_mut(),
        };
        let summary = match GpuSession::preflight(&bytes) {
            Ok(summary) => summary,
            Err(_) => return std::ptr::null_mut(),
        };
        jstring_or_null(&mut env, Some(summary))
    })
}

/// Loads a model from a filesystem path. Returns a session handle, or 0.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_loadPath(
    mut env: JNIEnv,
    _cls: JClass,
    path: JString,
) -> jlong {
    guard(0, || {
        let path = match read_string(&mut env, path) {
            Some(path) => path,
            None => return 0,
        };
        match GpuSession::load_path(&path) {
            Ok(session) => box_session(session),
            Err(_) => 0,
        }
    })
}

/// Frees the session behind `handle`. Null handles are ignored.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_close(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) {
    guard((), || {
        if handle == 0 {
            return;
        }
        let raw = handle as *mut Mutex<GpuSession>;
        // SAFETY: pointer came from `box_session` and `close` runs exactly once
        // per handle, so this reclaims the sole ownership; the value drops here.
        let _owned: Box<Mutex<GpuSession>> = unsafe { Box::from_raw(raw) };
    });
}

/// Runs inference: `flat_input` is the `tensors`-framed input, the return value
/// the framed output bytes, or null on failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_run(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    flat_input: JByteArray,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let input = match read_bytes(&mut env, flat_input) {
            Some(input) => input,
            None => return std::ptr::null_mut(),
        };
        let output = with_session(handle, None, |session| session.run(&input).ok());
        jbytes_or_null(&mut env, output)
    })
}

/// Returns the last inference output shapes as a JSON array-of-arrays string,
/// or null for a bad handle or JNI failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_lastOutputShapes(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let json = with_session(handle, None, |session| {
            Some(session.output_shapes_json())
        });
        jstring_or_null(&mut env, json)
    })
}

/// Returns the last inference output names as a JSON array-of-strings string,
/// or null for a bad handle or JNI failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_lastOutputNames(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let json = with_session(handle, None, |session| Some(session.output_names_json()));
        jstring_or_null(&mut env, json)
    })
}

/// Creates an autoregressive KV cache for `handle` holding up to `max_tokens`
/// tokens. Returns a cache handle, or 0 on failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_kvCreate(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    max_tokens: jint,
) -> jlong {
    guard(0, || {
        with_session(handle, 0, |session| {
            let max_tokens = max_tokens.max(0) as usize;
            match session.create_kv(max_tokens) {
                Ok(cache) => box_kv(cache),
                Err(_) => 0,
            }
        })
    })
}

/// Frees the KV cache behind `kv_handle`. Null handles are ignored.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_kvClose(
    _env: JNIEnv,
    _cls: JClass,
    kv_handle: jlong,
) {
    guard((), || {
        if kv_handle == 0 {
            return;
        }
        let raw = kv_handle as *mut Mutex<KvCache>;
        // SAFETY: pointer came from `box_kv` and `kvClose` runs exactly once per
        // handle, so this reclaims the sole ownership; the value drops here.
        let _owned: Box<Mutex<KvCache>> = unsafe { Box::from_raw(raw) };
    });
}

/// Runs one cached decoding step: `flat_input` is the framed single-step input,
/// the return value the framed output bytes, or null on failure.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_runCached(
    mut env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    kv_handle: jlong,
    flat_input: JByteArray,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let input = match read_bytes(&mut env, flat_input) {
            Some(input) => input,
            None => return std::ptr::null_mut(),
        };
        let output = with_cached(handle, kv_handle, None, |session, cache| {
            session.run_cached(cache, &input).ok()
        });
        jbytes_or_null(&mut env, output)
    })
}

/// Returns the argmax index over the last row of little-endian `f32` logits
/// with `cols` columns, or -1 when the shape is invalid.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_VulkanBridge_argmaxLastRow(
    mut env: JNIEnv,
    _cls: JClass,
    logits: JByteArray,
    cols: jint,
) -> jint {
    guard(-1, || {
        if cols <= 0 {
            return -1;
        }
        let bytes = match read_bytes(&mut env, logits) {
            Some(bytes) => bytes,
            None => return -1,
        };
        match tensors::argmax_last_row_f32(&bytes, cols as usize) {
            Some(index) => index.min(i32::MAX as usize) as jint,
            None => -1,
        }
    })
}
