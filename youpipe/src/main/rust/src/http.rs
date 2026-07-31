//! HTTP boundary.
//!
//! Requests are built and responses parsed in Rust; the bytes are moved by whatever implements
//! [`HttpClient`]. Today the only implementation is [`crate::jni_http::JniHttpClient`], which
//! up-calls the app's existing `MyDownloader` so cookie, proxy and TLS behaviour is unchanged and
//! the crate pulls in no HTTP/TLS dependencies. Swapping in a native stack later means adding one
//! implementation, not touching the extractors.

use std::collections::BTreeMap;
use std::fmt;

pub type Headers = BTreeMap<String, Vec<String>>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
}

impl Method {
    pub fn as_str(self) -> &'static str {
        match self {
            Method::Get => "GET",
            Method::Post => "POST",
        }
    }
}

pub struct Request<'a> {
    pub method: Method,
    pub url: String,
    pub headers: Headers,
    pub body: Option<&'a [u8]>,
}

impl<'a> Request<'a> {
    pub fn get(url: impl Into<String>) -> Self {
        Self { method: Method::Get, url: url.into(), headers: Headers::new(), body: None }
    }

    pub fn post_json(url: impl Into<String>, body: &'a [u8]) -> Self {
        let mut headers = Headers::new();
        headers.insert("Content-Type".into(), vec!["application/json".into()]);
        Self { method: Method::Post, url: url.into(), headers, body: Some(body) }
    }

    pub fn header(mut self, name: &str, value: &str) -> Self {
        self.headers.entry(name.to_string()).or_default().push(value.to_string());
        self
    }

    pub fn headers(mut self, extra: &Headers) -> Self {
        for (k, vs) in extra {
            self.headers.entry(k.clone()).or_default().extend(vs.iter().cloned());
        }
        self
    }
}

pub struct Response {
    pub code: u16,
    pub body: Vec<u8>,
    /// URL after redirects — the extractor checks this to detect the `/oops` error page.
    pub latest_url: String,
    pub headers: Headers,
}

impl Response {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .and_then(|(_, v)| v.first())
            .map(String::as_str)
    }

    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }
}

pub trait HttpClient: Send + Sync {
    fn execute(&self, request: Request<'_>) -> Result<Response>;
}

#[derive(Debug)]
pub enum Error {
    /// Transport failure — no usable response.
    Network(String),
    /// A response arrived but is not what we can parse (wrong content type, error page, 404…).
    Response(String),
    Parse(String),
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Network(m) => write!(f, "network error: {m}"),
            Error::Response(m) => write!(f, "bad response: {m}"),
            Error::Parse(m) => write!(f, "parse error: {m}"),
        }
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;
