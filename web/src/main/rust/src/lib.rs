//! Brave Shields engine for the `:web` browser, exposed to Kotlin as
//! `com.vayunmathur.web.shields.ShieldsNative`.
//!
//! The engine is Brave's own `adblock` crate, so the filter syntax, exception
//! precedence, `$removeparam` rewrites, `$redirect` bodies and cosmetic rules all
//! behave exactly as they do in Brave itself.
//!
//! Handle model: `nativeCreate*` leaks a `Box<Engine>` and returns the pointer as a
//! `jlong`; every other call borrows it. `Engine` is `Send + Sync` here (the
//! `single-thread` feature is off), so `nativeCheck` can be called from the WebView
//! render thread while the UI thread reads cosmetic resources.
//!
//! Everything crossing JNI is a JSON string — one hop per request, no object
//! construction on the hot path.

use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jlong, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::collections::HashSet;

/// Borrows the engine behind a handle. Returns `None` for the null handle, which is
/// what Kotlin passes before the engine has finished loading.
///
/// # Safety
/// `handle` must be a pointer previously returned by `nativeCreate`/`nativeCreateFromCache`
/// and not yet passed to `nativeDestroy`.
unsafe fn engine<'a>(handle: jlong) -> Option<&'a Engine> {
    if handle == 0 {
        return None;
    }
    Some(&*(handle as *const Engine))
}

fn jstr(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|s| s.into())
}

fn out(env: &mut JNIEnv, s: String) -> jstring {
    match env.new_string(s) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn json_string_array(raw: &str) -> Vec<String> {
    match serde_json::from_str::<Vec<String>>(raw) {
        Ok(v) => v,
        Err(_) => Vec::new(),
    }
}

/// uBlock Origin's `resources.json` and Brave's `adblock-resources` both deserialize
/// straight into `Vec<Resource>`; anything unparseable leaves scriptlets and
/// `$redirect` unavailable rather than failing the whole engine.
fn apply_resources(engine: &mut Engine, resources_json: &str) {
    if let Ok(resources) = serde_json::from_str::<Vec<Resource>>(resources_json) {
        engine.use_resources(resources);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCreate<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    filters: JString<'l>,
    resources_json: JString<'l>,
) -> jlong {
    let filters = match jstr(&mut env, &filters) {
        Some(f) => f,
        None => return 0,
    };
    let resources = jstr(&mut env, &resources_json).unwrap_or_default();

    let mut engine = Engine::new_with_list_text(filters);
    apply_resources(&mut engine, &resources);
    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCreateFromCache<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    cache: JByteArray<'l>,
    resources_json: JString<'l>,
) -> jlong {
    let bytes = match env.convert_byte_array(&cache) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let resources = jstr(&mut env, &resources_json).unwrap_or_default();

    let mut engine = Engine::default();
    if engine.deserialize(&bytes).is_err() {
        return 0;
    }
    // `deserialize` replaces the filter data but not the resource storage.
    apply_resources(&mut engine, &resources);
    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeSerialize<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jbyteArray {
    let null = std::ptr::null_mut();
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return null,
    };
    match env.byte_array_from_slice(&engine.serialize()) {
        Ok(a) => a.into_raw(),
        Err(_) => null,
    }
}

/// `{"blocked":bool,"important":bool,"exception":bool,"redirect":str?,"rewritten":str?}`
///
/// `redirect` carries the body of the uBO resource named by a `$redirect` rule, already
/// resolved by the engine. `rewritten` is the `$removeparam`-cleaned URL, which applies
/// only when the request is not blocked.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCheck<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    url: JString<'l>,
    source_url: JString<'l>,
    request_type: JString<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return null,
    };
    let (url, source_url, request_type) = match (
        jstr(&mut env, &url),
        jstr(&mut env, &source_url),
        jstr(&mut env, &request_type),
    ) {
        (Some(u), Some(s), Some(t)) => (u, s, t),
        _ => return null,
    };

    let request = match Request::new(&url, &source_url, &request_type, "get") {
        Ok(r) => r,
        Err(_) => return null,
    };
    let result = engine.check_network_request(&request);
    out(
        &mut env,
        json!({
            "blocked": result.should_block(),
            "important": result.important,
            "exception": result.exception.is_some(),
            "redirect": result.redirect,
            "rewritten": result.rewritten_url,
        })
        .to_string(),
    )
}

/// `{"hide":[sel],"procedural":[json],"exceptions":[sel],"script":str,"generichide":bool}`
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeCosmeticResources<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    url: JString<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return null,
    };
    let url = match jstr(&mut env, &url) {
        Some(u) => u,
        None => return null,
    };

    let res = engine.url_cosmetic_resources(&url);
    let to_array = |set: HashSet<String>| Value::from(set.into_iter().collect::<Vec<_>>());
    out(
        &mut env,
        json!({
            "hide": to_array(res.hide_selectors),
            "procedural": to_array(res.procedural_actions),
            "exceptions": to_array(res.exceptions),
            "script": res.injected_script,
            "generichide": res.generichide,
        })
        .to_string(),
    )
}

/// Second cosmetic pass: classes and ids discovered by the page's `MutationObserver`
/// are mapped to any generic hide rules that reference them. All three arguments are
/// JSON string arrays; the result is a JSON array of CSS selectors.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeHiddenClassIdSelectors<
    'l,
>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    classes: JString<'l>,
    ids: JString<'l>,
    exceptions: JString<'l>,
) -> jstring {
    let null = std::ptr::null_mut();
    let engine = match unsafe { engine(handle) } {
        Some(e) => e,
        None => return null,
    };
    let (classes, ids, exceptions) = match (
        jstr(&mut env, &classes),
        jstr(&mut env, &ids),
        jstr(&mut env, &exceptions),
    ) {
        (Some(c), Some(i), Some(e)) => (c, i, e),
        _ => return null,
    };

    let exceptions: HashSet<String> = json_string_array(&exceptions).into_iter().collect();
    let selectors = engine.hidden_class_id_selectors(
        json_string_array(&classes),
        json_string_array(&ids),
        &exceptions,
    );
    out(&mut env, Value::from(selectors).to_string())
}

/// # Safety
/// `handle` must not be used again after this call.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_web_shields_ShieldsNative_nativeDestroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}
