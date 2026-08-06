//! URL ↔ id conversion — the `linkHandler` package equivalent.
//!
//! Accepts the forms YouTube and its mirrors actually hand out: `watch?v=`, `youtu.be/`,
//! `/shorts/`, `/live/`, `/embed/`, `/v/`, attribution links, and the `/@handle`, `/c/`, `/user/`
//! and `/channel/` channel spellings.

/// Host suffixes treated as YouTube.
const YOUTUBE_HOSTS: &[&str] = &[
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtu.be",
    "www.youtu.be",
    "youtube-nocookie.com",
    "www.youtube-nocookie.com",
];

/// A video id is exactly 11 chars of the URL-safe base64 alphabet.
fn is_video_id(candidate: &str) -> bool {
    candidate.len() == 11
        && candidate
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn is_channel_id(candidate: &str) -> bool {
    candidate.starts_with("UC") && candidate.len() >= 20
}

/// Host, path segments, and query pairs of a parsed URL.
type UrlParts = (String, Vec<String>, Vec<(String, String)>);

/// Splits a URL into (host, path segments, query pairs) without a URL crate.
fn split_url(url: &str) -> Option<UrlParts> {
    let rest = url
        .strip_prefix("https://")
        .or_else(|| url.strip_prefix("http://"))
        .unwrap_or(url);
    let (authority, remainder) = match rest.find(['/', '?', '#']) {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    if authority.is_empty() {
        return None;
    }
    let host = authority
        .rsplit('@')
        .next()
        .unwrap_or(authority)
        .split(':')
        .next()
        .unwrap_or(authority)
        .to_ascii_lowercase();

    let without_fragment = remainder.split('#').next().unwrap_or("");
    let (path, query) = match without_fragment.split_once('?') {
        Some((p, q)) => (p, q),
        None => (without_fragment, ""),
    };

    let segments: Vec<String> = path
        .split('/')
        .filter(|s| !s.is_empty())
        .map(percent_decode)
        .collect();

    let params: Vec<(String, String)> = query
        .split('&')
        .filter(|s| !s.is_empty())
        .filter_map(|pair| {
            let (k, v) = pair.split_once('=')?;
            Some((k.to_string(), percent_decode(v)))
        })
        .collect();

    Some((host, segments, params))
}

/// Minimal percent-decoding, enough for ids and query values.
fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
                match u8::from_str_radix(hex, 16) {
                    Ok(byte) => {
                        out.push(byte);
                        i += 3;
                    }
                    Err(_) => {
                        out.push(bytes[i]);
                        i += 1;
                    }
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub fn is_youtube_url(url: &str) -> bool {
    split_url(url).is_some_and(|(host, _, _)| {
        YOUTUBE_HOSTS.iter().any(|h| host == *h || host.ends_with(&format!(".{h}")))
    })
}

/// Video id from any recognised watch URL, or from a bare id.
pub fn stream_id(url: &str) -> Option<String> {
    if is_video_id(url) {
        return Some(url.to_string());
    }
    let (host, segments, params) = split_url(url)?;

    // youtu.be/<id>
    if host.ends_with("youtu.be") {
        return segments.first().filter(|s| is_video_id(s)).cloned();
    }

    if let Some(v) = params.iter().find(|(k, _)| k == "v").map(|(_, v)| v) {
        if is_video_id(v) {
            return Some(v.clone());
        }
    }

    // /shorts/<id>, /live/<id>, /embed/<id>, /v/<id>
    if let Some(first) = segments.first() {
        if matches!(first.as_str(), "shorts" | "live" | "embed" | "v") {
            return segments.get(1).filter(|s| is_video_id(s)).cloned();
        }
    }

    // attribution_link?u=/watch%3Fv%3D<id>
    if let Some(u) = params.iter().find(|(k, _)| k == "u").map(|(_, v)| v) {
        return stream_id(&format!("https://www.youtube.com{u}"));
    }

    None
}

/// Playlist id from a URL, or from a bare id.
pub fn playlist_id(url: &str) -> Option<String> {
    if (url.starts_with("PL") || url.starts_with("UU") || url.starts_with("OL") || url.starts_with("RD"))
        && !url.contains('/')
    {
        return Some(url.to_string());
    }
    let (_, _, params) = split_url(url)?;
    params
        .iter()
        .find(|(k, _)| k == "list")
        .map(|(_, v)| v.clone())
        .filter(|v| !v.is_empty())
}

/// Channel id or handle from a URL. Returns the browse-ready id: `UC…` or `@handle`.
pub fn channel_id(url: &str) -> Option<String> {
    if is_channel_id(url) || url.starts_with('@') {
        return Some(url.to_string());
    }
    let (_, segments, _) = split_url(url)?;
    let first = segments.first()?;

    if let Some(handle) = first.strip_prefix('@') {
        return (!handle.is_empty()).then(|| first.clone());
    }
    match first.as_str() {
        "channel" => segments.get(1).filter(|s| is_channel_id(s)).cloned(),
        // Legacy vanity forms; the browse call resolves them.
        "c" | "user" => segments.get(1).cloned(),
        _ => None,
    }
}

pub fn stream_url(video_id: &str) -> String {
    format!("https://www.youtube.com/watch?v={video_id}")
}

pub fn channel_url(channel_id: &str) -> String {
    if let Some(handle) = channel_id.strip_prefix('@') {
        format!("https://www.youtube.com/@{handle}")
    } else {
        format!("https://www.youtube.com/channel/{channel_id}")
    }
}

pub fn playlist_url(playlist_id: &str) -> String {
    format!("https://www.youtube.com/playlist?list={playlist_id}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stream_ids_from_every_watch_form() {
        let id = Some("dQw4w9WgXcQ".to_string());
        for url in [
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?t=30",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/v/dQw4w9WgXcQ",
            "http://youtube.com/watch?v=dQw4w9WgXcQ",
            "dQw4w9WgXcQ",
        ] {
            assert_eq!(stream_id(url), id, "failed for {url}");
        }
    }

    #[test]
    fn attribution_links_are_unwrapped() {
        let url = "https://www.youtube.com/attribution_link?a=x&u=%2Fwatch%3Fv%3DdQw4w9WgXcQ";
        assert_eq!(stream_id(url).as_deref(), Some("dQw4w9WgXcQ"));
    }

    #[test]
    fn rejects_non_video_urls() {
        assert_eq!(stream_id("https://www.youtube.com/feed/subscriptions"), None);
        assert_eq!(stream_id("https://www.youtube.com/watch?v=tooshort"), None);
    }

    #[test]
    fn playlist_ids() {
        assert_eq!(
            playlist_id("https://www.youtube.com/playlist?list=PLabc123").as_deref(),
            Some("PLabc123")
        );
        assert_eq!(
            playlist_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLxyz").as_deref(),
            Some("PLxyz")
        );
        assert_eq!(playlist_id("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), None);
    }

    #[test]
    fn channel_ids_in_all_spellings() {
        assert_eq!(
            channel_id("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw").as_deref(),
            Some("UCuAXFkgsw1L7xaCfnd5JJOw")
        );
        assert_eq!(
            channel_id("https://www.youtube.com/@RickAstleyYT").as_deref(),
            Some("@RickAstleyYT")
        );
        assert_eq!(channel_id("https://www.youtube.com/c/RickAstley").as_deref(), Some("RickAstley"));
        assert_eq!(channel_id("https://www.youtube.com/user/RickAstley").as_deref(), Some("RickAstley"));
    }

    #[test]
    fn host_recognition() {
        assert!(is_youtube_url("https://www.youtube.com/watch?v=x"));
        assert!(is_youtube_url("https://youtu.be/x"));
        assert!(is_youtube_url("https://music.youtube.com/watch?v=x"));
        assert!(!is_youtube_url("https://vimeo.com/12345"));
        assert!(!is_youtube_url("https://notyoutube.com/watch?v=x"));
    }

    #[test]
    fn percent_decoding() {
        assert_eq!(percent_decode("a%20b"), "a b");
        assert_eq!(percent_decode("a+b"), "a b");
        assert_eq!(percent_decode("%2Fwatch"), "/watch");
    }
}
