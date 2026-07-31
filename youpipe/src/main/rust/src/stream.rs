//! Video extraction — the `YoutubeStreamExtractor` equivalent.
//!
//! Sends a `player` request as the WEB client (carrying the decoder's `signatureTimestamp`, which
//! the returned URLs are validated against), checks playability, then turns `streamingData` into
//! playable streams with their `n` throttling parameter decoded.
//!
//! Two deliberate departures from upstream, both noted at their call sites:
//!
//! * **No static itag table.** Upstream's `ItagItem` maps itag numbers to codec and resolution.
//!   Modern InnerTube responses carry `mimeType`, `bitrate`, `width`/`height`, `qualityLabel` and
//!   `audioSampleRate` inline, so those are read from the response instead. Streams whose itag is
//!   unknown to upstream therefore still work here.
//! * **No SABR.** When YouTube returns adaptive formats with no URLs and only a
//!   `serverAbrStreamingUrl`, that is reported via [`StreamInfo::sabr_only`] rather than handled.
//!   The app's existing Kotlin SABR stack owns that path.

use serde_json::{json, Value};

use crate::decoder::{self, Param};
use crate::http::{Error, HttpClient, Request, Result};
use crate::innertube::{client_version, endpoint_url, youtube_headers, WEB_CLIENT_NAME};
use crate::json::JsonExt;
use crate::linkhandler;
use crate::model::{AudioStream, StreamInfo, StreamKind, VideoStream};
use crate::parsing::{
    channel_url_from_navigation_endpoint, is_verified, parse_view_count, text_from_object,
    thumbnails_from_array,
};

/// Fetches and parses everything the player page needs for `video_id`.
pub fn stream_info(
    http: &dyn HttpClient,
    video_id: &str,
    hl: &str,
    gl: &str,
) -> Result<StreamInfo> {
    let version = client_version(http);
    let metadata = decoder::player_metadata(http)?;

    let player_response = fetch_player(http, video_id, &version, hl, gl, metadata.signature_timestamp)?;
    check_playability(&player_response)?;

    let video_details = player_response.obj("videoDetails");
    let microformat = player_response
        .obj("microformat")
        .obj("playerMicroformatRenderer");
    let streaming_data = player_response.obj("streamingData");

    let kind = stream_kind(&player_response, video_details);
    let mut info = StreamInfo {
        id: video_id.to_string(),
        url: linkhandler::stream_url(video_id),
        name: video_details.str_or("title", "").to_string(),
        kind,
        duration_seconds: video_details.int("lengthSeconds"),
        view_count: video_details
            .str("viewCount")
            .and_then(|v| v.parse().ok())
            .unwrap_or(-1),
        like_count: extract_like_count(&player_response),
        uploader_name: video_details.str("author").map(str::to_string),
        uploader_url: video_details.str("channelId").map(linkhandler::channel_url),
        uploader_verified: false, // only present in the `next` response, not the player one
        description: video_details.str("shortDescription").map(str::to_string),
        textual_upload_date: microformat
            .str("uploadDate")
            .or_else(|| microformat.str("publishDate"))
            .map(str::to_string),
        thumbnails: thumbnails_from_array(video_details.obj("thumbnail").arr("thumbnails")),
        category: microformat.str("category").map(str::to_string),
        tags: video_details
            .arr("keywords")
            .iter()
            .filter_map(|k| k.as_str().map(str::to_string))
            .collect(),
        age_limit: if microformat.bool("isFamilySafe") { 0 } else { 18 },
        dash_manifest_url: streaming_data.str("dashManifestUrl").map(str::to_string),
        hls_manifest_url: streaming_data.str("hlsManifestUrl").map(str::to_string),
        sabr_only: false,
        ..Default::default()
    };

    let formats = collect_formats(http, &metadata.player_id, streaming_data, &mut info);
    info.video_streams = formats.video;
    info.audio_streams = formats.audio;
    info.video_only_streams = formats.video_only;

    // Adaptive formats with neither `url` nor `signatureCipher` mean YouTube is serving SABR only.
    info.sabr_only = info.audio_streams.is_empty()
        && info.video_only_streams.is_empty()
        && !streaming_data.str_or("serverAbrStreamingUrl", "").is_empty();
    info.server_abr_streaming_url = streaming_data
        .str("serverAbrStreamingUrl")
        .map(str::to_string);

    Ok(info)
}

fn fetch_player(
    http: &dyn HttpClient,
    video_id: &str,
    client_version: &str,
    hl: &str,
    gl: &str,
    signature_timestamp: i64,
) -> Result<Value> {
    let body = json!({
        "context": {
            "client": {
                "hl": hl,
                "gl": gl,
                "clientName": WEB_CLIENT_NAME,
                "clientVersion": client_version,
                "originalUrl": "https://www.youtube.com",
                "platform": "DESKTOP",
                "utcOffsetMinutes": 0,
            },
            "request": { "internalExperimentFlags": [], "useSsl": true },
            "user": { "lockedSafetyMode": false },
        },
        // The player validates returned URLs against this timestamp.
        "playbackContext": {
            "contentPlaybackContext": {
                "html5Preference": "HTML5_PREF_WANTS",
                "signatureTimestamp": signature_timestamp,
            }
        },
        "videoId": video_id,
        "contentCheckOk": true,
        "racyCheckOk": true,
    });
    let bytes = serde_json::to_vec(&body).map_err(|e| Error::Parse(e.to_string()))?;

    let response = http.execute(
        Request::post_json(endpoint_url("player"), &bytes)
            .headers(&youtube_headers(client_version)),
    )?;
    crate::response::parse_innertube(&response)
}

/// Maps `playabilityStatus` to a specific error.
///
/// A missing `status` is treated as playable, matching upstream — some responses omit it.
fn check_playability(player_response: &Value) -> Result<()> {
    let status_object = player_response.obj("playabilityStatus");
    let Some(status) = status_object.str("status") else {
        return Ok(());
    };
    if status.eq_ignore_ascii_case("ok") {
        return Ok(());
    }

    let reason = status_object
        .str("reason")
        .map(str::to_string)
        .or_else(|| text_from_object(status_object.obj("messages").at(0)))
        .unwrap_or_default();
    let lowered = reason.to_ascii_lowercase();

    let message = if status.eq_ignore_ascii_case("login_required") {
        if lowered.contains("inappropriate for some users") {
            "age-restricted: cannot be watched anonymously".to_string()
        } else if lowered.contains("private") {
            "this video is private".to_string()
        } else if lowered.contains("a bot") {
            "YouTube is blocking anonymous playback from this IP".to_string()
        } else {
            format!("login required: {reason}")
        }
    } else if lowered.contains("music premium") {
        "requires YouTube Music Premium".to_string()
    } else if lowered.contains("payment") {
        "this video is paid".to_string()
    } else if lowered.contains("members") {
        "members-only video".to_string()
    } else if lowered.contains("country") {
        "not available in this country".to_string()
    } else if lowered.contains("closed") || lowered.contains("terminated") {
        format!("account terminated: {reason}")
    } else {
        format!("{status}: {reason}")
    };
    Err(Error::Response(message))
}

fn stream_kind(player_response: &Value, video_details: &Value) -> StreamKind {
    if video_details.bool("isLiveContent") || video_details.bool("isLive") {
        return StreamKind::Live;
    }
    if player_response.obj("playabilityStatus").has("liveStreamability") {
        return StreamKind::Live;
    }
    // A finished livestream still exposes its DVR manifest.
    if video_details.bool("isLiveDvrEnabled") || video_details.bool("isPostLiveDvr") {
        return StreamKind::PostLive;
    }
    StreamKind::Video
}

/// Likes live in the `next` response for the web client; the player response carries them only
/// for some videos. `-1` when unknown.
fn extract_like_count(player_response: &Value) -> i64 {
    player_response
        .obj("microformat")
        .obj("playerMicroformatRenderer")
        .str("likeCount")
        .and_then(|v| v.parse().ok())
        .unwrap_or(-1)
}

#[derive(Default)]
struct Formats {
    video: Vec<VideoStream>,
    audio: Vec<AudioStream>,
    video_only: Vec<VideoStream>,
}

fn collect_formats(
    http: &dyn HttpClient,
    player_id: &str,
    streaming_data: &Value,
    info: &mut StreamInfo,
) -> Formats {
    let mut out = Formats::default();

    // `formats` are muxed (audio+video); `adaptiveFormats` are one track each.
    for format in streaming_data.arr("formats") {
        match build_url(http, player_id, format) {
            Ok(Some(url)) => out.video.push(video_stream(format, url, false)),
            Ok(None) => {}
            Err(e) => info.errors.push(format!("itag {}: {e}", format.int("itag"))),
        }
    }

    for format in streaming_data.arr("adaptiveFormats") {
        let mime = format.str_or("mimeType", "");
        let url = match build_url(http, player_id, format) {
            Ok(Some(url)) => url,
            Ok(None) => continue,
            Err(e) => {
                info.errors.push(format!("itag {}: {e}", format.int("itag")));
                continue;
            }
        };
        if mime.starts_with("audio/") {
            out.audio.push(audio_stream(format, url));
        } else if mime.starts_with("video/") {
            out.video_only.push(video_stream(format, url, true));
        }
    }

    out
}

/// Resolves a format's playable URL, decoding the signature and `n` parameter.
///
/// `Ok(None)` means the format carries no URL at all — the SABR case — which is not an error.
fn build_url(http: &dyn HttpClient, player_id: &str, format: &Value) -> Result<Option<String>> {
    let base = if let Some(url) = format.str("url") {
        url.to_string()
    } else {
        let cipher = format
            .str("signatureCipher")
            .or_else(|| format.str("cipher"))
            .unwrap_or_default();
        if cipher.is_empty() {
            return Ok(None);
        }
        let params = parse_query(cipher);
        let url = params
            .iter()
            .find(|(k, _)| k == "url")
            .map(|(_, v)| v.clone())
            .ok_or_else(|| Error::Parse("signatureCipher without url".into()))?;
        let s = params
            .iter()
            .find(|(k, _)| k == "s")
            .map(|(_, v)| v.clone())
            .unwrap_or_default();
        let sp = params
            .iter()
            .find(|(k, _)| k == "sp")
            .map(|(_, v)| v.clone())
            .unwrap_or_else(|| "signature".to_string());
        let signature = decoder::decode(http, player_id, Param::Signature, &s)?;
        format!("{url}&{sp}={signature}")
    };

    // Without a decoded `n` YouTube throttles hard or returns 403.
    decoder::deobfuscate_throttling(http, player_id, &base).map(Some)
}

/// Splits a `signatureCipher` blob, which is itself URL-encoded query syntax.
fn parse_query(query: &str) -> Vec<(String, String)> {
    query
        .split('&')
        .filter_map(|pair| {
            let (k, v) = pair.split_once('=')?;
            Some((k.to_string(), percent_decode(v)))
        })
        .collect()
}

fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                match u8::from_str_radix(
                    std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or(""),
                    16,
                ) {
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

/// `video/mp4; codecs="avc1.64001F"` → `avc1.64001F`
fn codec_from_mime(mime: &str) -> Option<String> {
    let after = mime.split("codecs=").nth(1)?;
    Some(after.trim_matches(['"', ' ']).to_string())
}

fn video_stream(format: &Value, url: String, video_only: bool) -> VideoStream {
    VideoStream {
        url,
        itag: format.int("itag"),
        mime_type: format.str_or("mimeType", "").to_string(),
        codec: codec_from_mime(format.str_or("mimeType", "")),
        resolution: format.str("qualityLabel").map(str::to_string),
        width: format.int("width"),
        height: format.int("height"),
        fps: format.int("fps"),
        bitrate: format.int("bitrate"),
        content_length: format.int("contentLength"),
        init_start: format.obj("initRange").int("start"),
        init_end: format.obj("initRange").int("end"),
        index_start: format.obj("indexRange").int("start"),
        index_end: format.obj("indexRange").int("end"),
        video_only,
    }
}

fn audio_stream(format: &Value, url: String) -> AudioStream {
    let track = format.obj("audioTrack");
    AudioStream {
        url,
        itag: format.int("itag"),
        mime_type: format.str_or("mimeType", "").to_string(),
        codec: codec_from_mime(format.str_or("mimeType", "")),
        bitrate: format.int("bitrate"),
        average_bitrate: format.int("averageBitrate"),
        // YouTube sends the sample rate as a string; `int` handles that.
        sample_rate: format.int("audioSampleRate"),
        channels: if format.has("audioChannels") { format.int("audioChannels") } else { 2 },
        content_length: format.int("contentLength"),
        init_start: format.obj("initRange").int("start"),
        init_end: format.obj("initRange").int("end"),
        index_start: format.obj("indexRange").int("start"),
        index_end: format.obj("indexRange").int("end"),
        track_id: track.str("id").map(str::to_string),
        track_name: track.str("displayName").map(str::to_string),
        is_drc: format.bool("isDrc"),
    }
}

/// Enriches [`StreamInfo`] from the `next` endpoint: uploader verification, subscriber count,
/// likes and related videos, none of which the player response carries.
pub fn augment_from_next(
    http: &dyn HttpClient,
    video_id: &str,
    hl: &str,
    gl: &str,
    info: &mut StreamInfo,
) -> Result<()> {
    let version = client_version(http);
    let body = crate::innertube::desktop_body(
        &version,
        hl,
        gl,
        &[("videoId", json!(video_id)), ("contentCheckOk", json!(true)), ("racyCheckOk", json!(true))],
    );
    let response = http.execute(
        Request::post_json(endpoint_url("next"), &body).headers(&youtube_headers(&version)),
    )?;
    let root = crate::response::parse_innertube(&response)?;

    let results = root
        .obj("contents")
        .obj("twoColumnWatchNextResults")
        .obj("results")
        .obj("results")
        .arr("contents");

    for item in results {
        if item.has("videoPrimaryInfoRenderer") {
            let primary = item.obj("videoPrimaryInfoRenderer");
            if info.view_count < 0 {
                info.view_count = text_from_object(
                    primary.obj("viewCount").obj("videoViewCountRenderer").obj("viewCount"),
                )
                .as_deref()
                .map_or(-1, parse_view_count);
            }
            if info.like_count < 0 {
                info.like_count = extract_likes_from_primary(primary);
            }
        } else if item.has("videoSecondaryInfoRenderer") {
            let owner = item
                .obj("videoSecondaryInfoRenderer")
                .obj("owner")
                .obj("videoOwnerRenderer");
            info.uploader_verified = is_verified(owner.arr("badges"));
            info.uploader_subscriber_count =
                text_from_object(owner.obj("subscriberCountText"))
                    .as_deref()
                    .map_or(-1, crate::parsing::parse_abbreviated_count);
            if info.uploader_url.is_none() {
                info.uploader_url =
                    channel_url_from_navigation_endpoint(owner.obj("navigationEndpoint"));
            }
            if let Some(avatars) = Some(owner.obj("thumbnail").arr("thumbnails")) {
                info.uploader_avatars = thumbnails_from_array(avatars);
            }
        }
    }

    Ok(())
}

/// Likes come from the accessibility label ("like this video along with 1,234,567 other people"),
/// because the visible label is abbreviated.
fn extract_likes_from_primary(primary: &Value) -> i64 {
    for button in primary
        .obj("videoActions")
        .obj("menuRenderer")
        .arr("topLevelButtons")
    {
        let view_model = button
            .obj("segmentedLikeDislikeButtonViewModel")
            .obj("likeButtonViewModel")
            .obj("likeButtonViewModel")
            .obj("toggleButtonViewModel")
            .obj("toggleButtonViewModel")
            .obj("defaultButtonViewModel")
            .obj("buttonViewModel");
        if let Some(text) = view_model.str("accessibilityText") {
            let count = parse_view_count(text);
            if count >= 0 {
                return count;
            }
        }
    }
    -1
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn playability_ok_and_missing_status_both_pass() {
        assert!(check_playability(&json!({"playabilityStatus": {"status": "OK"}})).is_ok());
        // A missing status must not be an error — upstream tolerates it.
        assert!(check_playability(&json!({})).is_ok());
    }

    #[test]
    fn playability_failures_map_to_specific_messages() {
        let cases = [
            ("LOGIN_REQUIRED", "This video is private", "private"),
            ("LOGIN_REQUIRED", "Sign in to confirm you're not a bot", "blocking anonymous"),
            ("UNPLAYABLE", "requires Music Premium", "Music Premium"),
            ("UNPLAYABLE", "not available in your country", "country"),
            ("ERROR", "This account has been terminated", "terminated"),
        ];
        for (status, reason, expected) in cases {
            let response = json!({"playabilityStatus": {"status": status, "reason": reason}});
            let err = check_playability(&response).unwrap_err().to_string();
            assert!(err.contains(expected), "for {reason:?} got {err:?}");
        }
    }

    #[test]
    fn codec_extraction() {
        assert_eq!(
            codec_from_mime(r#"video/mp4; codecs="avc1.64001F""#).as_deref(),
            Some("avc1.64001F")
        );
        assert_eq!(codec_from_mime("audio/webm").as_deref(), None);
    }

    #[test]
    fn signature_cipher_is_split_correctly() {
        let cipher = "s=SIGVALUE&sp=sig&url=https%3A%2F%2Fexample.com%2Fvideoplayback%3Fitag%3D18";
        let params = parse_query(cipher);
        let url = params.iter().find(|(k, _)| k == "url").unwrap();
        assert_eq!(url.1, "https://example.com/videoplayback?itag=18");
        assert_eq!(params.iter().find(|(k, _)| k == "sp").unwrap().1, "sig");
    }

    #[test]
    fn audio_sample_rate_arrives_as_a_string() {
        let format = json!({
            "itag": 140,
            "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
            "bitrate": 130000,
            "audioSampleRate": "44100",
            "audioChannels": 2
        });
        let stream = audio_stream(&format, "https://x".into());
        assert_eq!(stream.sample_rate, 44100);
        assert_eq!(stream.channels, 2);
        assert_eq!(stream.codec.as_deref(), Some("mp4a.40.2"));
    }

    #[test]
    fn audio_channels_default_to_two_when_absent() {
        let format = json!({ "itag": 251, "mimeType": "audio/webm" });
        assert_eq!(audio_stream(&format, "u".into()).channels, 2);
    }

    #[test]
    fn a_format_with_no_url_or_cipher_is_skipped_not_an_error() {
        struct Never;
        impl HttpClient for Never {
            fn execute(&self, _: Request<'_>) -> Result<crate::http::Response> {
                panic!("must not perform a request for a URL-less format");
            }
        }
        // The SABR-only shape: adaptive formats with neither url nor signatureCipher.
        let format = json!({ "itag": 313, "mimeType": "video/webm" });
        assert!(matches!(build_url(&Never, "p", &format), Ok(None)));
    }

    #[test]
    fn missing_ranges_are_zero_not_a_failure() {
        // Progressive itag 18 has no initRange/indexRange.
        let format = json!({ "itag": 18, "mimeType": "video/mp4", "width": 640, "height": 360 });
        let stream = video_stream(&format, "u".into(), false);
        assert_eq!(stream.init_start, 0);
        assert_eq!(stream.index_end, 0);
        assert_eq!(stream.width, 640);
    }
}
