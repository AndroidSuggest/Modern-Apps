//! Signature / throttling-parameter deobfuscation via the PipePipe decoder API.
//!
//! Upstream used to run YouTube's `base.js` in Rhino to undo the URL obfuscation. PipePipe
//! replaced that with a remote service, which is why this port needs no JS engine at all:
//!
//! * `GET /decoder/latest-player` → `{"player":"<id>","signatureTimestamp":<n>}`
//! * `GET /decoder/decode?player=<id>&sig=<v>` / `&n=<v>` →
//!   `{"type":"result","responses":[{"type":"result","data":{"<input>":"<output>"}}]}`
//!
//! Player metadata is cached for 24h and decoded values for the lifetime of the process, keyed by
//! player id so a player rollover invalidates naturally. Every stream URL needs its `n` decoded —
//! without it YouTube throttles to ~50 KB/s or returns 403 — so the cache matters.

use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

use crate::http::{Error, HttpClient, Request, Result};
use crate::json::JsonExt;

const API_BASE: &str = "https://api.pipepipe.dev/decoder/decode";
const LATEST_PLAYER_URL: &str = "https://api.pipepipe.dev/decoder/latest-player";
const USER_AGENT: &str = "PipePipe/4.9.0";
const PLAYER_METADATA_TTL_SECONDS: u64 = 24 * 60 * 60;

#[derive(Debug, Clone)]
pub struct PlayerMetadata {
    pub player_id: String,
    /// Must be echoed in the player request or the returned URLs will not validate.
    pub signature_timestamp: i64,
    fetched_at_secs: u64,
}

impl PlayerMetadata {
    fn is_expired(&self) -> bool {
        now_secs().saturating_sub(self.fetched_at_secs) >= PLAYER_METADATA_TTL_SECONDS
    }
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn metadata_cache() -> &'static Mutex<Option<PlayerMetadata>> {
    static CACHE: OnceLock<Mutex<Option<PlayerMetadata>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(None))
}

/// Decoded values keyed by `"<player_id>:<kind>:<input>"`.
fn decode_cache() -> &'static Mutex<HashMap<String, String>> {
    static CACHE: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn clear_caches() {
    if let Ok(mut g) = metadata_cache().lock() {
        *g = None;
    }
    if let Ok(mut g) = decode_cache().lock() {
        g.clear();
    }
}

/// Current player id and signature timestamp, cached for 24h.
pub fn player_metadata(http: &dyn HttpClient) -> Result<PlayerMetadata> {
    if let Some(cached) = metadata_cache().lock().ok().and_then(|g| g.clone()) {
        if !cached.is_expired() {
            return Ok(cached);
        }
    }

    let response = http.execute(Request::get(LATEST_PLAYER_URL).header("User-Agent", USER_AGENT))?;
    let parsed: Value =
        serde_json::from_slice(&response.body).map_err(|e| Error::Parse(e.to_string()))?;

    let player_id = parsed
        .str("player")
        .filter(|p| !p.is_empty())
        .ok_or_else(|| Error::Parse("latest-player response has no player id".into()))?
        .to_string();
    let signature_timestamp = parsed.int("signatureTimestamp");
    if signature_timestamp == 0 {
        return Err(Error::Parse("latest-player response has no signatureTimestamp".into()));
    }

    let metadata =
        PlayerMetadata { player_id, signature_timestamp, fetched_at_secs: now_secs() };
    if let Ok(mut g) = metadata_cache().lock() {
        *g = Some(metadata.clone());
    }
    Ok(metadata)
}

/// Which obfuscated value is being decoded.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Param {
    /// `s` from a `signatureCipher`.
    Signature,
    /// The `n` throttling parameter present on every stream URL.
    Throttling,
}

impl Param {
    fn key(self) -> &'static str {
        match self {
            Param::Signature => "sig",
            Param::Throttling => "n",
        }
    }
}

/// Decodes one value, consulting the cache first.
pub fn decode(
    http: &dyn HttpClient,
    player_id: &str,
    param: Param,
    value: &str,
) -> Result<String> {
    let cache_key = format!("{player_id}:{}:{value}", param.key());
    if let Some(hit) = decode_cache().lock().ok().and_then(|g| g.get(&cache_key).cloned()) {
        return Ok(hit);
    }

    let url = format!("{API_BASE}?player={player_id}&{}={}", param.key(), url_encode(value));
    let response = http.execute(Request::get(url).header("User-Agent", USER_AGENT))?;
    let parsed: Value =
        serde_json::from_slice(&response.body).map_err(|e| Error::Parse(e.to_string()))?;

    if parsed.str("type") != Some("result") {
        return Err(Error::Response(format!(
            "decoder API error: {}",
            parsed.str_or("error", "unknown")
        )));
    }

    let decoded = parsed
        .arr("responses")
        .first()
        .map(|first| first.obj("data").str_or(value, "").to_string())
        .filter(|d| !d.is_empty())
        .ok_or_else(|| Error::Response(format!("decoder returned nothing for {value}")))?;

    if let Ok(mut g) = decode_cache().lock() {
        g.insert(cache_key, decoded.clone());
    }
    Ok(decoded)
}

/// Rewrites a stream URL with its `n` parameter decoded.
///
/// A URL with no `n` is returned unchanged — that is not an error.
pub fn deobfuscate_throttling(
    http: &dyn HttpClient,
    player_id: &str,
    url: &str,
) -> Result<String> {
    let Some(obfuscated) = throttling_param(url) else {
        return Ok(url.to_string());
    };
    let decoded = decode(http, player_id, Param::Throttling, &obfuscated)?;
    Ok(url.replace(&obfuscated, &decoded))
}

/// Value of the `n` query parameter, if present.
fn throttling_param(url: &str) -> Option<String> {
    // Cheap reject first: the scan below is hot, once per stream URL.
    if !url.contains("&n=") && !url.contains("?n=") {
        return None;
    }
    let query = url.split_once('?')?.1;
    query.split('&').find_map(|pair| {
        let (k, v) = pair.split_once('=')?;
        (k == "n" && !v.is_empty()).then(|| v.to_string())
    })
}

fn url_encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::Response;

    struct Stub(&'static str);
    impl HttpClient for Stub {
        fn execute(&self, request: Request<'_>) -> Result<Response> {
            Ok(Response {
                code: 200,
                body: self.0.as_bytes().to_vec(),
                latest_url: request.url,
                headers: Default::default(),
            })
        }
    }

    #[test]
    fn parses_a_real_decoder_reply() {
        // Captured from the live API.
        let stub = Stub(
            r#"{"type":"result","responses":[{"type":"result","data":{"1cGkVaLzUYuCTZ":"AP6BpT6jXLoKj"}}],"meta":{"player_id":"bed7a914"}}"#,
        );
        clear_caches();
        let out = decode(&stub, "bed7a914", Param::Throttling, "1cGkVaLzUYuCTZ").unwrap();
        assert_eq!(out, "AP6BpT6jXLoKj");
    }

    #[test]
    fn decoder_error_replies_become_errors() {
        clear_caches();
        let stub = Stub(r#"{"type":"error","error":"Failed to download player: 404 Not Found"}"#);
        let err = decode(&stub, "bad", Param::Throttling, "x").unwrap_err();
        assert!(err.to_string().contains("404"), "got: {err}");
    }

    #[test]
    fn latest_player_is_parsed() {
        clear_caches();
        let stub = Stub(r#"{"player":"bed7a914","variant":"main","signatureTimestamp":20662}"#);
        let meta = player_metadata(&stub).unwrap();
        assert_eq!(meta.player_id, "bed7a914");
        assert_eq!(meta.signature_timestamp, 20662);
    }

    #[test]
    fn throttling_param_extraction() {
        assert_eq!(throttling_param("https://x/y?a=1&n=ABC&b=2").as_deref(), Some("ABC"));
        assert_eq!(throttling_param("https://x/y?n=ABC").as_deref(), Some("ABC"));
        assert_eq!(throttling_param("https://x/y?a=1"), None, "no n means no work");
        // `n` must be a whole key, not a suffix of one.
        assert_eq!(throttling_param("https://x/y?cpn=ABC"), None);
    }

    #[test]
    fn a_url_without_n_passes_through_untouched() {
        clear_caches();
        let stub = Stub("{}");
        let url = "https://example.com/videoplayback?itag=18";
        assert_eq!(deobfuscate_throttling(&stub, "p", url).unwrap(), url);
    }

    #[test]
    fn url_encoding_escapes_reserved_characters() {
        assert_eq!(url_encode("a+b/c=d"), "a%2Bb%2Fc%3Dd");
        assert_eq!(url_encode("plain-Value_1.0~"), "plain-Value_1.0~");
    }
}
