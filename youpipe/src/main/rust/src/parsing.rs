//! Shared YouTube renderer parsing — the `YoutubeParsingHelper` equivalents.

use serde_json::Value;

use crate::json::JsonExt;
use crate::model::Thumbnail;

/// Flattens a YouTube text object to a plain string.
///
/// Text arrives either as `{"simpleText": "..."}` or as `{"runs": [{"text": "..."}, ...]}`.
/// Returns `None` for an absent or empty object, matching upstream's `getTextFromObject`.
/// The HTML-producing variant upstream also has is not ported — nothing here renders HTML.
pub fn text_from_object(text_object: &Value) -> Option<String> {
    if text_object.is_blank() {
        return None;
    }

    if let Some(simple) = text_object.str("simpleText") {
        return Some(simple.to_string());
    }

    let runs = text_object.arr("runs");
    if runs.is_empty() {
        return None;
    }

    let mut out = String::new();
    for run in runs {
        if let Some(text) = run.str("text") {
            out.push_str(text);
        }
    }
    Some(out)
}

/// Absolute URL for a thumbnail; YouTube emits protocol-relative and bare-host forms.
pub fn fix_thumbnail_url(url: &str) -> String {
    if let Some(rest) = url.strip_prefix("//") {
        format!("https://{rest}")
    } else if url.starts_with("http://") {
        url.replacen("http://", "https://", 1)
    } else if !url.starts_with("https://") {
        format!("https://{url}")
    } else {
        url.to_string()
    }
}

/// Images from a `thumbnails` array, skipping entries with no URL.
pub fn thumbnails_from_array(thumbnails: &[Value]) -> Vec<Thumbnail> {
    thumbnails
        .iter()
        .filter_map(|t| {
            let url = t.str("url").filter(|u| !u.is_empty())?;
            Some(Thumbnail {
                url: fix_thumbnail_url(url),
                width: t.int("width"),
                height: t.int("height"),
            })
        })
        .collect()
}

/// Thumbnails of an info item (`item.thumbnail.thumbnails`).
pub fn thumbnails_from_info_item(info_item: &Value) -> Vec<Thumbnail> {
    thumbnails_from_array(info_item.obj("thumbnail").arr("thumbnails"))
}

/// Whether an `ownerBadges` array marks the channel verified.
///
/// Absent for unverified channels — which is exactly the key whose absence crashed the Kotlin
/// port for 7 of 19 results on a live search — so this must tolerate an empty slice.
pub fn is_verified(badges: &[Value]) -> bool {
    badges.iter().any(|badge| {
        matches!(
            badge.obj("metadataBadgeRenderer").str("style"),
            Some("BADGE_STYLE_TYPE_VERIFIED") | Some("BADGE_STYLE_TYPE_VERIFIED_ARTIST")
        )
    })
}

/// Seconds from a `H:MM:SS` / `MM:SS` / `SS` duration label. `None` if unparseable.
pub fn parse_duration(input: &str) -> Option<i64> {
    let cleaned: String = input.chars().filter(|c| c.is_ascii_digit() || *c == ':').collect();
    if cleaned.is_empty() {
        return None;
    }
    let mut seconds: i64 = 0;
    for part in cleaned.split(':') {
        // Empty segments appear in malformed labels; treat as zero rather than bailing.
        let value: i64 = if part.is_empty() { 0 } else { part.parse().ok()? };
        seconds = seconds * 60 + value;
    }
    Some(seconds)
}

/// View count from labels like `"1,234,567 views"` or `"No views"`. `-1` when unknown,
/// which is upstream's sentinel.
pub fn parse_view_count(input: &str) -> i64 {
    let lowered = input.to_ascii_lowercase();
    if lowered.contains("no views") {
        return 0;
    }
    let digits: String = input.chars().filter(char::is_ascii_digit).collect();
    if digits.is_empty() {
        return -1;
    }
    digits.parse().unwrap_or(-1)
}

/// Count from an abbreviated label such as `"1.2M subscribers"` or `"15K"`.
/// `-1` when there is no number, matching upstream's unknown sentinel.
pub fn parse_abbreviated_count(input: &str) -> i64 {
    let trimmed = input.trim();
    let numeric: String = trimmed
        .chars()
        .take_while(|c| c.is_ascii_digit() || *c == '.' || *c == ',')
        .filter(|c| *c != ',')
        .collect();
    let Ok(base) = numeric.parse::<f64>() else {
        return -1;
    };
    let multiplier = match trimmed[numeric.len()..].trim_start().chars().next() {
        Some('K') | Some('k') => 1_000.0,
        Some('M') | Some('m') => 1_000_000.0,
        Some('B') | Some('b') => 1_000_000_000.0,
        _ => 1.0,
    };
    (base * multiplier) as i64
}

/// Watch URL for a video id.
pub fn stream_url(video_id: &str) -> String {
    format!("https://www.youtube.com/watch?v={video_id}")
}

/// Channel URL for a channel id.
pub fn channel_url(channel_id: &str) -> String {
    format!("https://www.youtube.com/channel/{channel_id}")
}

/// Playlist URL for a playlist id.
pub fn playlist_url(playlist_id: &str) -> String {
    format!("https://www.youtube.com/playlist?list={playlist_id}")
}

/// Channel URL out of a `navigationEndpoint`, preferring the canonical URL YouTube supplies
/// (which may be a `/@handle`) and falling back to the browse id.
pub fn channel_url_from_navigation_endpoint(endpoint: &Value) -> Option<String> {
    let canonical = endpoint
        .obj("commandMetadata")
        .obj("webCommandMetadata")
        .str("url");
    if let Some(url) = canonical.filter(|u| !u.is_empty()) {
        return Some(if url.starts_with("http") {
            url.to_string()
        } else {
            format!("https://www.youtube.com{url}")
        });
    }
    endpoint
        .obj("browseEndpoint")
        .str("browseId")
        .filter(|id| !id.is_empty())
        .map(channel_url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn text_from_simple_and_runs() {
        assert_eq!(text_from_object(&json!({"simpleText": "hi"})).as_deref(), Some("hi"));
        let runs = json!({"runs": [{"text": "a"}, {"text": "b"}]});
        assert_eq!(text_from_object(&runs).as_deref(), Some("ab"));
    }

    #[test]
    fn text_from_absent_object_is_none_not_panic() {
        assert_eq!(text_from_object(&json!({})), None);
        assert_eq!(text_from_object(&Value::Null), None);
        assert_eq!(text_from_object(&json!({"runs": []})), None);
    }

    #[test]
    fn verified_tolerates_missing_badges() {
        // The exact shape that crashed the Kotlin port.
        assert!(!is_verified(&[]));
        let badges = json!([{"metadataBadgeRenderer": {"style": "BADGE_STYLE_TYPE_VERIFIED"}}]);
        assert!(is_verified(badges.as_array().unwrap()));
        let other = json!([{"metadataBadgeRenderer": {"style": "BADGE_STYLE_TYPE_LIVE_NOW"}}]);
        assert!(!is_verified(other.as_array().unwrap()));
    }

    #[test]
    fn durations() {
        assert_eq!(parse_duration("1:02:03"), Some(3723));
        assert_eq!(parse_duration("4:20"), Some(260));
        assert_eq!(parse_duration("59"), Some(59));
        assert_eq!(parse_duration(""), None);
    }

    #[test]
    fn view_counts() {
        assert_eq!(parse_view_count("1,234,567 views"), 1234567);
        assert_eq!(parse_view_count("No views"), 0);
        assert_eq!(parse_view_count("some text"), -1);
    }

    #[test]
    fn thumbnail_urls_are_normalised() {
        assert_eq!(fix_thumbnail_url("//i.ytimg.com/a.jpg"), "https://i.ytimg.com/a.jpg");
        assert_eq!(fix_thumbnail_url("http://i.ytimg.com/a.jpg"), "https://i.ytimg.com/a.jpg");
        assert_eq!(fix_thumbnail_url("https://i.ytimg.com/a.jpg"), "https://i.ytimg.com/a.jpg");
        assert_eq!(fix_thumbnail_url("i.ytimg.com/a.jpg"), "https://i.ytimg.com/a.jpg");
    }

    #[test]
    fn channel_url_prefers_canonical_handle() {
        let ep = json!({
            "commandMetadata": {"webCommandMetadata": {"url": "/@someone"}},
            "browseEndpoint": {"browseId": "UC123"}
        });
        assert_eq!(
            channel_url_from_navigation_endpoint(&ep).as_deref(),
            Some("https://www.youtube.com/@someone")
        );
        let ep_id = json!({"browseEndpoint": {"browseId": "UC123"}});
        assert_eq!(
            channel_url_from_navigation_endpoint(&ep_id).as_deref(),
            Some("https://www.youtube.com/channel/UC123")
        );
    }
}
