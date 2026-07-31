//! [`HttpClient`] over the shared JNI bridge.
//!
//! Networking runs on the platform stack via `library:network`, so the extractor inherits the
//! app's proxy, cookie and TLS behaviour and this crate links no TLS of its own.
//!
//! The bridge is flat-framed (see `jni_http::frame`): one JNI call and one `byte[]` each way,
//! with the class and method id cached. Call [`init`] from a JNI entry point before use.

use jni_http::{Header, Method as BridgeMethod};

use crate::http::{Error, Headers, HttpClient, Method, Request, Response, Result};

/// Resolves the bridge class. Safe to call repeatedly; returns false if `library:network` is
/// missing from the calling module.
pub fn init(env: &mut jni::JNIEnv) -> bool {
    jni_http::init(env)
}

#[derive(Default)]
pub struct BridgeHttpClient;

impl BridgeHttpClient {
    pub fn new() -> Self {
        Self
    }
}

impl HttpClient for BridgeHttpClient {
    fn execute(&self, request: Request<'_>) -> Result<Response> {
        // The bridge takes a flat pair list; our Headers is a multimap.
        let headers: Vec<Header> = request
            .headers
            .iter()
            .flat_map(|(name, values)| values.iter().map(move |v| (name.clone(), v.clone())))
            .collect();

        let method = match request.method {
            Method::Get => BridgeMethod::Get,
            Method::Post => BridgeMethod::Post,
        };

        let frame = jni_http::request(method, &request.url, &headers, request.body)
            .map_err(|e| Error::Network(e.to_string()))?;

        let mut out = Headers::new();
        for (name, value) in frame.headers {
            out.entry(name).or_default().push(value);
        }

        Ok(Response {
            code: frame.status,
            body: frame.body,
            latest_url: frame.final_url,
            headers: out,
        })
    }
}
