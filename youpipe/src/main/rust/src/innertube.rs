//! InnerTube client identity and request-context construction.
//!
//! Ported from PipePipe's `ClientsConstants` / `YoutubeParsingHelper`. Every InnerTube call sends
//! a `context.client` block describing which YouTube client it is pretending to be; get it wrong
//! and the endpoint returns an error page rather than JSON.

use serde_json::{json, Value};
use std::sync::{OnceLock, RwLock};

use crate::http::{Headers, HttpClient, Request, Result};

pub const YOUTUBEI_V1_URL: &str = "https://www.youtube.com/youtubei/v1/";
pub const DISABLE_PRETTY_PRINT: &str = "prettyPrint=false";

pub const WEB_CLIENT_ID: &str = "1";
pub const WEB_CLIENT_NAME: &str = "WEB";
pub const DESKTOP_CLIENT_PLATFORM: &str = "DESKTOP";

/// Last-resort client version, used only if extraction from `sw.js` fails. YouTube tolerates a
/// stale value for a while, but not forever — hence the extraction below.
pub const WEB_HARDCODED_CLIENT_VERSION: &str = "2.20260120.01.00";

const SW_JS_URL: &str = "https://www.youtube.com/sw.js";

/// Cached live client version. `RwLock` rather than `OnceLock` so it can be reset on failure.
fn cache() -> &'static RwLock<Option<String>> {
    static CACHE: OnceLock<RwLock<Option<String>>> = OnceLock::new();
    CACHE.get_or_init(|| RwLock::new(None))
}

/// The `Origin`/`Referer` pair YouTube expects on non-InnerTube fetches.
pub fn origin_referrer_headers(url: &str) -> Headers {
    let mut h = Headers::new();
    h.insert("Origin".into(), vec![url.to_string()]);
    h.insert("Referer".into(), vec![url.to_string()]);
    h
}

/// Headers for an InnerTube call from the WEB client.
pub fn youtube_headers(client_version: &str) -> Headers {
    let mut h = origin_referrer_headers("https://www.youtube.com");
    h.insert("X-YouTube-Client-Name".into(), vec![WEB_CLIENT_ID.to_string()]);
    h.insert("X-YouTube-Client-Version".into(), vec![client_version.to_string()]);
    // Consent cookie — without it EU-region requests get an interstitial instead of JSON.
    h.insert("Cookie".into(), vec!["SOCS=CAI".to_string()]);
    h
}

/// Live WEB client version, extracted from YouTube's service worker and cached.
///
/// Upstream tries `sw.js` first and falls back to scraping the HTML results page, specifically to
/// avoid fingerprinting on a pinned version. The HTML fallback is not ported yet; failure here
/// drops to [`WEB_HARDCODED_CLIENT_VERSION`], which is what upstream's last resort does anyway.
pub fn client_version(http: &dyn HttpClient) -> String {
    if let Some(v) = cache().read().ok().and_then(|g| g.clone()) {
        return v;
    }

    let extracted = extract_client_version_from_sw_js(http)
        .unwrap_or_else(|_| WEB_HARDCODED_CLIENT_VERSION.to_string());

    if let Ok(mut g) = cache().write() {
        *g = Some(extracted.clone());
    }
    extracted
}

fn extract_client_version_from_sw_js(http: &dyn HttpClient) -> Result<String> {
    let headers = origin_referrer_headers("https://www.youtube.com");
    let response = http.execute(Request::get(SW_JS_URL).headers(&headers))?;
    let body = response.text();

    // "INNERTUBE_CONTEXT_CLIENT_VERSION":"2.2026...."
    find_json_string_field(&body, "INNERTUBE_CONTEXT_CLIENT_VERSION")
        .or_else(|| find_json_string_field(&body, "clientVersion"))
        .ok_or_else(|| {
            crate::http::Error::Parse("no client version in sw.js".into())
        })
}

/// Pulls `"<field>":"<value>"` out of a blob without a regex engine.
fn find_json_string_field(haystack: &str, field: &str) -> Option<String> {
    let needle = format!("\"{field}\":\"");
    let start = haystack.find(&needle)? + needle.len();
    let rest = &haystack[start..];
    let end = rest.find('"')?;
    let value = &rest[..end];
    (!value.is_empty()).then(|| value.to_string())
}

/// Reset the cached version. Exposed for tests and for recovery after auth-style failures.
pub fn reset_client_version() {
    if let Ok(mut g) = cache().write() {
        *g = None;
    }
}

/// The `context` block shared by every WEB InnerTube request.
///
/// Mirrors PipePipe's `prepareDesktopJsonBuilder`. Callers add their own top-level fields
/// (`query`, `continuation`, `videoId`, …) alongside it.
pub fn desktop_context(client_version: &str, hl: &str, gl: &str) -> Value {
    json!({
        "context": {
            "client": {
                "hl": hl,
                "gl": gl,
                "clientName": WEB_CLIENT_NAME,
                "clientVersion": client_version,
                "originalUrl": "https://www.youtube.com",
                "platform": DESKTOP_CLIENT_PLATFORM,
                "utcOffsetMinutes": 0,
            },
            "request": {
                "internalExperimentFlags": [],
                "useSsl": true,
            },
            "user": {
                "lockedSafetyMode": false,
            },
        }
    })
}

/// A desktop request body: the shared context plus `extra` merged in at the top level.
pub fn desktop_body(client_version: &str, hl: &str, gl: &str, extra: &[(&str, Value)]) -> Vec<u8> {
    let mut body = desktop_context(client_version, hl, gl);
    if let Some(map) = body.as_object_mut() {
        for (k, v) in extra {
            map.insert((*k).to_string(), v.clone());
        }
    }
    serde_json::to_vec(&body).unwrap_or_else(|_| b"{}".to_vec())
}

/// Full URL for an InnerTube endpoint, e.g. `search` or `player`.
pub fn endpoint_url(endpoint: &str) -> String {
    format!("{YOUTUBEI_V1_URL}{endpoint}?{DISABLE_PRETTY_PRINT}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::json::JsonExt;

    #[test]
    fn context_matches_upstream_shape() {
        let ctx = desktop_context("2.20260120.01.00", "en-GB", "GB");
        let client = ctx.obj("context").obj("client");
        assert_eq!(client.str("clientName"), Some("WEB"));
        assert_eq!(client.str("hl"), Some("en-GB"));
        assert_eq!(client.str("gl"), Some("GB"));
        assert_eq!(client.str("platform"), Some("DESKTOP"));
        assert_eq!(client.int("utcOffsetMinutes"), 0);
        assert!(ctx.obj("context").obj("request").bool("useSsl"));
        // lockedSafetyMode must be present and false, not absent.
        assert!(ctx.obj("context").obj("user").has("lockedSafetyMode"));
        assert!(!ctx.obj("context").obj("user").bool("lockedSafetyMode"));
    }

    #[test]
    fn extra_fields_land_at_top_level_beside_context() {
        let body = desktop_body("v", "en", "US", &[("query", json!("cats"))]);
        let parsed: Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(parsed.str("query"), Some("cats"));
        assert!(parsed.has("context"));
    }

    #[test]
    fn parses_client_version_out_of_sw_js_blob() {
        let blob = r#"...{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260731.00.00","x":1}..."#;
        assert_eq!(
            find_json_string_field(blob, "INNERTUBE_CONTEXT_CLIENT_VERSION").as_deref(),
            Some("2.20260731.00.00")
        );
        assert_eq!(find_json_string_field(blob, "NOPE"), None);
    }

    #[test]
    fn endpoint_url_disables_pretty_print() {
        assert_eq!(
            endpoint_url("search"),
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
        );
    }
}
