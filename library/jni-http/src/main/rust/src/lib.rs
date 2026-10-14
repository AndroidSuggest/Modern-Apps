//! JNI HTTP bridge for the Rust crates.
//!
//! Networking stays on the platform stack (`library:network`), so Rust inherits the app's proxy,
//! cookie and TLS behaviour and no crate links its own copy of rustls. This is the bridge Rust
//! calls to get bytes.
//!
//! It is built for being called from Rust in a loop, which the previous bridge was not:
//!
//! * **No object creation.** One `byte[]` each way, described in [`frame`]. The old bridge
//!   allocated a `NativeHttpResponse` and Rust read four fields back out with `GetFieldID` +
//!   `GetObjectField`, which is four extra JNI round trips per request.
//! * **No per-call lookups.** The class and method id are resolved once into a
//!   [`jni::objects::Global`] and reused, instead of `FindClass` on every request.
//! * **No thread churn for callers already on a JNI thread.** [`request_with_env`] borrows the
//!   caller's `Env`; only background threads pay `attach_current_thread`.
//! * **Failures are data, not exceptions.** A transport error comes back as status 0 with the
//!   message in the body, so Rust never has to `ExceptionCheck` between calls.
//!
//! The Kotlin counterpart is `com.vayunmathur.library.network.NativeHttpBridge`.

pub mod frame;

use std::sync::OnceLock;

use jni::objects::{Global, JByteArray, JClass, JObject, JString, JValue};
use jni::{Env, JavaVM, jni_sig, jni_str};

pub use frame::{Header, ResponseFrame};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get = 0,
    Post = 1,
    Head = 2,
}

#[derive(Debug)]
pub enum Error {
    /// The JVM could not be reached, or the bridge class is missing.
    Bridge(String),
    /// The request was attempted and failed at the transport level.
    Transport(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Bridge(m) => write!(f, "jni bridge error: {m}"),
            Error::Transport(m) => write!(f, "network error: {m}"),
        }
    }
}

impl From<jni::errors::Error> for Error {
    fn from(value: jni::errors::Error) -> Self {
        Error::Bridge(value.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;

/// Cached JVM handle and bridge class, resolved once per process.
struct Cached {
    vm: JavaVM,
    class: Global<JClass<'static>>,
}

static CACHED: OnceLock<Cached> = OnceLock::new();

/// Resolves and caches the JVM and bridge class.
///
/// Call once from any JNI entry point before background threads need the bridge; calling it
/// again is harmless. Returns false if the class could not be found, which means the calling
/// module is missing a dependency on `library:network`.
pub fn init(env: &mut Env) -> bool {
    if CACHED.get().is_some() {
        return true;
    }
    let (Ok(vm), Ok(class)) = (
        env.get_java_vm(),
        env.find_class(jni_str!("com/vayunmathur/library/network/NativeHttpBridge"))
    ) else {
        let _ = env.exception_clear();
        return false;
    };
    let Ok(class) = env.new_global_ref(class) else {
        return false;
    };
    CACHED.set(Cached { vm, class }).is_ok() || CACHED.get().is_some()
}

fn cached() -> Result<&'static Cached> {
    CACHED
        .get()
        .ok_or_else(|| Error::Bridge("bridge not initialised; call jni_http::init first".into()))
}

/// Performs a request using an existing `Env`.
///
/// Prefer this on threads that entered from Java — it avoids an attach/detach per call.
pub fn request_with_env(
    env: &mut Env,
    method: Method,
    url: &str,
    headers: &[Header],
    body: Option<&[u8]>,
) -> Result<ResponseFrame> {
    let class = &cached()?.class;

    let jurl: JString = env
        .new_string(url)
        .map_err(|e| Error::Bridge(format!("url: {e}")))?;
    let jheaders: JByteArray = env
        .byte_array_from_slice(&frame::encode_headers(headers))
        .map_err(|e| Error::Bridge(format!("headers: {e}")))?;
    let jbody: JObject = match body {
        Some(b) => env
            .byte_array_from_slice(b)
            .map_err(|e| Error::Bridge(format!("body: {e}")))?
            .into(),
        None => JObject::null(),
    };

    let result = env
        .call_static_method(
            class,
            jni_str!("request"),
            jni_sig!("(ILjava/lang/String;[B[B)[B"),
            &[
                JValue::Int(method as i32),
                JValue::Object(&jurl),
                JValue::Object(&jheaders),
                JValue::Object(&jbody),
            ],
        )
        .map_err(|e| {
            // Leaving a pending exception poisons the next JNI call on this thread.
            let _ = env.exception_clear();
            Error::Bridge(format!("request call failed: {e}"))
        })?;

    let object = result.l().map_err(|e| Error::Bridge(format!("bad return: {e}")))?;
    if object.is_null() {
        return Err(Error::Transport("bridge returned null".into()));
    }
    let bytes = env
        .convert_byte_array(env.cast_local::<JByteArray>(object)?)
        .map_err(|e| Error::Bridge(format!("reading response frame: {e}")))?;

    let frame = ResponseFrame::decode(&bytes)
        .ok_or_else(|| Error::Bridge("malformed response frame".into()))?;
    match frame.error() {
        Some(message) => Err(Error::Transport(message)),
        None => Ok(frame),
    }
}

/// Performs a request from a thread that may not be attached to the JVM.
///
/// Attaches for the duration of the call. On a thread that already has an `Env`, use
/// [`request_with_env`] instead.
pub fn request(
    method: Method,
    url: &str,
    headers: &[Header],
    body: Option<&[u8]>,
) -> Result<ResponseFrame> {
    let cached = cached()?;
    cached
        .vm
        .attach_current_thread(|env| request_with_env(env, method, url, headers, body))
}

/// Convenience for a GET with no extra headers.
pub fn get(url: &str) -> Result<ResponseFrame> {
    request(Method::Get, url, &[], None)
}
