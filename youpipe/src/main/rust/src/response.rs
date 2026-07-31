//! Shared InnerTube response validation.
//!
//! Mirrors upstream's `getValidJsonResponseBody`. YouTube signals some failures by serving an
//! HTML error page with a 200 status, so the content type and length are checked before parsing.

use serde_json::Value;

use crate::http::{Error, Response, Result};

pub fn parse_innertube(response: &Response) -> Result<Value> {
    if response.code == 0 {
        return Err(Error::Network(response.text()));
    }
    if response.code == 404 {
        return Err(Error::Response("not found (404)".into()));
    }
    if response.body.len() < 50 {
        return Err(Error::Response(format!(
            "response too short ({} bytes)",
            response.body.len()
        )));
    }
    // A redirect to /oops or /error means the content is gone.
    if response.latest_url.contains("youtube.com/oops")
        || response.latest_url.contains("youtube.com/error")
    {
        return Err(Error::Response("content unavailable".into()));
    }
    if let Some(content_type) = response.header("Content-Type") {
        if content_type.to_ascii_lowercase().contains("text/html") {
            return Err(Error::Response(format!(
                "got HTML, expected JSON (final url: {})",
                response.latest_url
            )));
        }
    }
    serde_json::from_slice(&response.body).map_err(|e| Error::Parse(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response(code: u16, body: &str, content_type: &str, url: &str) -> Response {
        Response {
            code,
            body: body.as_bytes().to_vec(),
            latest_url: url.to_string(),
            headers: [("Content-Type".to_string(), vec![content_type.to_string()])]
                .into_iter()
                .collect(),
        }
    }

    #[test]
    fn valid_json_parses() {
        let body = format!("{{\"a\":\"{}\"}}", "x".repeat(60));
        let r = response(200, &body, "application/json", "https://www.youtube.com/youtubei/v1/search");
        assert!(parse_innertube(&r).is_ok());
    }

    #[test]
    fn html_error_pages_are_rejected() {
        let r = response(200, &"x".repeat(500), "text/html", "https://www.youtube.com/x");
        assert!(parse_innertube(&r).unwrap_err().to_string().contains("HTML"));
    }

    #[test]
    fn transport_failure_is_reported_as_network() {
        let r = response(0, "connection reset", "", "https://x");
        assert!(matches!(parse_innertube(&r), Err(Error::Network(_))));
    }

    #[test]
    fn redirect_to_oops_is_content_unavailable() {
        let body = format!("{{\"a\":\"{}\"}}", "x".repeat(60));
        let r = response(200, &body, "application/json", "https://www.youtube.com/oops");
        assert!(parse_innertube(&r).unwrap_err().to_string().contains("unavailable"));
    }

    #[test]
    fn short_and_missing_bodies_are_rejected() {
        let r = response(200, "{}", "application/json", "https://x");
        assert!(parse_innertube(&r).is_err());
    }
}
