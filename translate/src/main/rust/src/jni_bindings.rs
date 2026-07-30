//! JNI entry points for `com.vayunmathur.translate.util.TranslateNative`.
//!
//! Every extern fn is wrapped in `catch_unwind` so a Rust panic becomes a thrown
//! Java exception (or a safe default) instead of unwinding across the FFI
//! boundary (UB). String handling never `unwrap`s: malformed Modified-UTF-8 or a
//! null jstring is turned into a clean error / empty value. This mirrors the
//! safe-helper pattern in `pdf/src/main/rust/src/jni_bindings.rs`.

use crate::*;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Convert a (possibly null) JString to Rust String, clearing any pending
/// exception on decode failure and returning an empty string.
fn jstr<'local>(env: &mut JNIEnv<'local>, s: &JString<'local>) -> String {
    if s.is_null() {
        return String::new();
    }
    match env.get_string(s) {
        Ok(js) => js.into(),
        Err(_) => {
            let _ = env.exception_clear();
            String::new()
        }
    }
}

/// Convert a nullable JString into `Option<String>` (None if the ref is null).
fn jstr_opt<'local>(env: &mut JNIEnv<'local>, s: &JString<'local>) -> Option<String> {
    if s.is_null() {
        None
    } else {
        Some(jstr(env, s))
    }
}

/// Convert `Option<String>` into a jstring, returning null on `None` or on any
/// allocation/encoding error (so the Kotlin side sees `null`).
fn string_or_null<'local>(env: &mut JNIEnv<'local>, value: Option<String>) -> jstring {
    match value {
        Some(s) => match env.new_string(s) {
            Ok(js) => js.into_raw(),
            Err(_) => {
                let _ = env.exception_clear();
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

/// `TranslateNative.nativeLoadModel(String) -> long`. Returns a non-zero handle,
/// or 0 when no model is available (always, in this stubbed build).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_translate_util_TranslateNative_nativeLoadModel<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    model_dir: JString<'local>,
) -> jlong {
    match catch_unwind(AssertUnwindSafe(|| {
        let dir = jstr(&mut env, &model_dir);
        load_model(&dir) as jlong
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new("java/lang/RuntimeException", "Native panic in nativeLoadModel");
            0
        }
    }
}

/// `TranslateNative.nativeFreeModel(long)`. Safe with a 0 handle.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_translate_util_TranslateNative_nativeFreeModel<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(AssertUnwindSafe(|| {
        free_model(handle as ModelHandle);
    }));
    if res.is_err() {
        let _ = env.exception_clear();
    }
}

/// `TranslateNative.nativeDetectLanguage(long, String) -> String`. Null when the
/// language cannot be determined (always, in this stubbed build).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_translate_util_TranslateNative_nativeDetectLanguage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    text: JString<'local>,
) -> jstring {
    match catch_unwind(AssertUnwindSafe(|| {
        let t = jstr(&mut env, &text);
        let detected = detect_language(handle as ModelHandle, &t);
        string_or_null(&mut env, detected)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `TranslateNative.nativeTranslate(long, String, String?, String) -> String`.
/// `from` may be null (auto-detect). Returns null when no model is loaded (so the
/// Kotlin side reports "not installed") — never a fabricated translation.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_translate_util_TranslateNative_nativeTranslate<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    text: JString<'local>,
    from: JString<'local>,
    to: JString<'local>,
) -> jstring {
    match catch_unwind(AssertUnwindSafe(|| {
        let t = jstr(&mut env, &text);
        let from_opt = jstr_opt(&mut env, &from);
        let to_s = jstr(&mut env, &to);
        let out = translate(handle as ModelHandle, &t, from_opt.as_deref(), &to_s);
        string_or_null(&mut env, out)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `TranslateNative.nativeSpeechAvailable() -> boolean`. False in this build.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_translate_util_TranslateNative_nativeSpeechAvailable<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| speech_available() as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            JNI_FALSE
        }
    }
}
