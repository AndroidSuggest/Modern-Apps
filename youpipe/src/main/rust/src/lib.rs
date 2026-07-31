//! YouTube extractor for the youpipe app — a Rust port of PipePipeExtractor.
//!
//! Layering, innermost first:
//!
//! * [`json`] — nanojson-compatible accessors. Absent keys yield traversable empties, never
//!   errors. Read the module docs before touching any extractor; it is the invariant the whole
//!   upstream design rests on.
//! * [`http`] — the [`HttpClient`](http::HttpClient) trait. No transport of its own.
//! * [`innertube`] — client identity, headers, request-context construction.
//! * [`parsing`] — renderer helpers shared across extractors.
//! * [`linkhandler`] — URL ↔ id.
//! * [`decoder`] — signature / throttling deobfuscation via the PipePipe API (no JS engine).
//! * [`search`], [`stream`], [`browse`], [`suggestions`] — the extractors.
//! * [`native_http`] — the HTTP transport, over the shared flat-framed JNI bridge.
//! * this module — the JNI boundary. Results cross as JSON.
//!
//! Everything below the JNI layer is plain Rust and unit-testable on the host with no Android or
//! JVM present: `cargo test -p youpipe_extractor`.

pub mod browse;
pub mod decoder;
pub mod http;
pub mod innertube;
pub mod json;
pub mod linkhandler;
pub mod model;
pub mod native_http;
pub mod parsing;
pub mod response;
pub mod search;
pub mod stream;
pub mod suggestions;

/// JNI exports. Android-only: the crate builds and tests on the host without them.
///
/// Every entry point has the same shape: read the arguments, build a [`JniHttpClient`] over the
/// Kotlin downloader, call one extractor, return a JSON envelope. Exceptions never cross the
/// boundary — failures come back as `{"ok":false,"error":"..."}`.
#[cfg(target_os = "android")]
mod android {
    use jni::objects::{JClass, JString};
    use jni::sys::jstring;
    use jni::JNIEnv;

    use crate::native_http::BridgeHttpClient;

    fn envelope<T: serde::Serialize>(result: crate::http::Result<T>) -> String {
        match result {
            Ok(value) => match serde_json::to_value(value) {
                Ok(data) => serde_json::json!({ "ok": true, "data": data }).to_string(),
                Err(e) => error_envelope(&e.to_string()),
            },
            Err(e) => error_envelope(&e.to_string()),
        }
    }

    fn error_envelope(message: &str) -> String {
        serde_json::json!({ "ok": false, "error": message }).to_string()
    }

    fn text(env: &mut JNIEnv<'_>, value: &JString<'_>) -> String {
        env.get_string(value).map(Into::into).unwrap_or_default()
    }

    /// Optional strings arrive as null; treat empty as absent too.
    fn optional_text(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<String> {
        if value.is_null() {
            return None;
        }
        let s = text(env, value);
        (!s.is_empty()).then_some(s)
    }

    fn to_jstring(env: &mut JNIEnv<'_>, value: String) -> jstring {
        env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_search<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        query: JString<'l>,
        filter: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let query = text(&mut env, &query);
        let filter = optional_text(&mut env, &filter);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::search::search(&client, &query, filter.as_deref(), &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_searchPage<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        token: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let token = text(&mut env, &token);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::search::search_page(&client, &token, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_suggestions<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        query: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let query = text(&mut env, &query);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::suggestions::suggestions_for(&client, &query, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_channelInfo<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        id: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let id = text(&mut env, &id);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::browse::channel_info(&client, &id, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_playlistInfo<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        id: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let id = text(&mut env, &id);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::browse::playlist_info(&client, &id, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_trending<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::browse::trending(&client, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_browseContinuation<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        token: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let token = text(&mut env, &token);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::browse::browse_continuation(&client, &token, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_comments<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        video_id: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let video_id = text(&mut env, &video_id);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::browse::comments(&client, &video_id, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_commentsPage<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        token: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let token = text(&mut env, &token);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            envelope(crate::browse::comments_page(&client, &token, &hl, &gl))
        };
        to_jstring(&mut env, payload)
    }

    /// Stream extraction runs two requests: `player` for the media, then `next` for the metadata
    /// only the watch page carries (verification, subscriber count, likes). A `next` failure is
    /// non-fatal — playback still works without those fields, so it is recorded and ignored.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_youpipe_nativeext_YouPipeNative_streamInfo<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        video_id: JString<'l>,
        hl: JString<'l>,
        gl: JString<'l>,
    ) -> jstring {
        let video_id = text(&mut env, &video_id);
        let hl = text(&mut env, &hl);
        let gl = text(&mut env, &gl);

        let payload = {
            // Resolve the bridge class on this thread before the first request.
            crate::native_http::init(&mut env);
            let client = BridgeHttpClient::new();
            let result = crate::stream::stream_info(&client, &video_id, &hl, &gl).map(|mut info| {
                if let Err(e) =
                    crate::stream::augment_from_next(&client, &video_id, &hl, &gl, &mut info)
                {
                    info.errors.push(format!("next request failed: {e}"));
                }
                info
            });
            envelope(result)
        };
        to_jstring(&mut env, payload)
    }
}
