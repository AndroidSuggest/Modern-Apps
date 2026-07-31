//! End-to-end parse of a real InnerTube search response.
//!
//! `fixtures/search_response.json` is a trimmed capture of a live `youtubei/v1/search` reply for
//! "never gonna give you up" (2026-07-31). It keeps four `videoRenderer` entries — deliberately a
//! mix of verified and unverified channels, and of `/channel/UC…` and `/@handle` uploader URLs —
//! plus a `lockupViewModel` and the continuation section, so it covers the shapes that actually
//! vary between results.
//!
//! Drives the full `search()` path against a stub transport, exercising response validation,
//! section walking and every item parser. Only the socket is faked.

use youpipe_extractor::http::{Error, HttpClient, Request, Response, Result};
use youpipe_extractor::model::SearchItem;
use youpipe_extractor::search;

const FIXTURE: &str = include_str!("fixtures/search_response.json");

fn json_response(url: String, body: Vec<u8>) -> Response {
    Response {
        code: 200,
        body,
        latest_url: url,
        headers: [("Content-Type".to_string(), vec!["application/json".to_string()])]
            .into_iter()
            .collect(),
    }
}

/// Serves the fixture for the search POST and a canned client version for `sw.js`.
struct StubHttp;

impl HttpClient for StubHttp {
    fn execute(&self, request: Request<'_>) -> Result<Response> {
        let body = if request.url.contains("sw.js") {
            br#"{"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20260731.00.00"}"#.to_vec()
        } else {
            FIXTURE.as_bytes().to_vec()
        };
        Ok(json_response(request.url, body))
    }
}

#[test]
fn parses_a_real_search_response() {
    let result = search::search(&StubHttp, "never gonna give you up", None, "en-GB", "GB")
        .expect("search should succeed");

    assert!(result.errors.is_empty(), "unexpected parse errors: {:?}", result.errors);
    assert_eq!(result.items.len(), 5, "four videoRenderers plus one lockup");
    assert!(result.next_page_token.is_some(), "continuation token should be found");

    // No spelling correction on this query — the case that threw in the Kotlin port.
    assert_eq!(result.search_suggestion, None);
    assert!(!result.is_corrected_search);

    let streams: Vec<_> = result
        .items
        .iter()
        .filter_map(|i| match i {
            SearchItem::Stream(s) => Some(s),
            _ => None,
        })
        .collect();
    assert!(streams.len() >= 4);

    let rick = streams
        .iter()
        .find(|s| s.url.contains("dQw4w9WgXcQ"))
        .expect("the canonical result should be present");
    assert!(rick.name.contains("Never Gonna Give You Up"));
    assert_eq!(rick.duration_seconds, Some(214));
    assert!(rick.view_count > 1_000_000_000, "view count parsed as {}", rick.view_count);
    assert_eq!(rick.uploader_name.as_deref(), Some("Rick Astley"));
    assert!(rick.uploader_verified);
    assert!(!rick.thumbnails.is_empty());
    assert!(rick.thumbnails.iter().all(|t| t.url.starts_with("https://")));

    // Both uploader URL shapes YouTube emits must resolve.
    assert!(
        streams.iter().any(|s| s.uploader_url.as_deref().is_some_and(|u| u.contains("/channel/"))),
        "expected at least one /channel/ uploader URL"
    );

    // Unverified channels carry no ownerBadges key at all — must parse, not fail.
    assert!(
        streams.iter().any(|s| !s.uploader_verified),
        "fixture should include an unverified uploader"
    );
}

#[test]
fn a_transport_failure_surfaces_as_an_error_not_a_panic() {
    struct Failing;
    impl HttpClient for Failing {
        fn execute(&self, _: Request<'_>) -> Result<Response> {
            Err(Error::Network("offline".into()))
        }
    }
    // client_version falls back to the hardcoded value, then the search POST fails.
    assert!(search::search(&Failing, "q", None, "en-GB", "GB").is_err());
}

#[test]
fn an_html_error_page_is_rejected_rather_than_parsed() {
    struct HtmlPage;
    impl HttpClient for HtmlPage {
        fn execute(&self, request: Request<'_>) -> Result<Response> {
            Ok(Response {
                code: 200,
                body: vec![b'x'; 500],
                latest_url: request.url,
                headers: [("Content-Type".to_string(), vec!["text/html".to_string()])]
                    .into_iter()
                    .collect(),
            })
        }
    }
    let err = search::search(&HtmlPage, "q", None, "en-GB", "GB").unwrap_err();
    assert!(err.to_string().contains("HTML"), "got: {err}");
}
