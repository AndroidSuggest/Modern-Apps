//! Search suggestions.
//!
//! Not an InnerTube endpoint — this is the old `suggestqueries` autocomplete service, which
//! answers with JSONP: `window.google.ac.h([ "query", [["suggestion",0,[...]], ...] ])`.

use serde_json::Value;

use crate::http::{Error, HttpClient, Request, Result};

const SUGGEST_URL: &str = "https://suggestqueries.google.com/complete/search";

pub fn suggestions_for(
    http: &dyn HttpClient,
    query: &str,
    hl: &str,
    gl: &str,
) -> Result<Vec<String>> {
    let url = format!(
        "{SUGGEST_URL}?client=youtube&ds=yt&gl={}&hl={}&q={}",
        encode(gl),
        encode(hl),
        encode(query)
    );
    let response = http.execute(Request::get(url))?;
    parse_jsonp(&response.text())
}

/// Pulls the suggestion strings out of the JSONP envelope.
fn parse_jsonp(body: &str) -> Result<Vec<String>> {
    let start = body
        .find('(')
        .ok_or_else(|| Error::Parse("suggestion response is not JSONP".into()))?;
    let end = body
        .rfind(')')
        .ok_or_else(|| Error::Parse("unterminated JSONP envelope".into()))?;
    if end <= start {
        return Err(Error::Parse("malformed JSONP envelope".into()));
    }

    let parsed: Value = serde_json::from_str(&body[start + 1..end])
        .map_err(|e| Error::Parse(format!("suggestion payload: {e}")))?;

    // [ "<query>", [ ["<suggestion>", 0, [...]], ... ], {...} ]
    Ok(parsed
        .get(1)
        .and_then(Value::as_array)
        .map(|entries| {
            entries
                .iter()
                .filter_map(|entry| entry.get(0).and_then(Value::as_str).map(str::to_string))
                .collect()
        })
        .unwrap_or_default())
}

fn encode(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for byte in value.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_jsonp_envelope() {
        let body = r#"window.google.ac.h(["never",[["never gonna give you up",0,[512]],["never ending",0,[512]]],{"a":"b"}])"#;
        assert_eq!(
            parse_jsonp(body).unwrap(),
            vec!["never gonna give you up".to_string(), "never ending".to_string()]
        );
    }

    #[test]
    fn empty_suggestion_list_is_not_an_error() {
        assert!(parse_jsonp(r#"window.google.ac.h(["xyzzy",[]])"#).unwrap().is_empty());
    }

    #[test]
    fn malformed_bodies_error_rather_than_panic() {
        assert!(parse_jsonp("not jsonp at all").is_err());
        assert!(parse_jsonp("h(").is_err());
    }

    #[test]
    fn query_encoding() {
        assert_eq!(encode("a b&c"), "a+b%26c");
    }
}
