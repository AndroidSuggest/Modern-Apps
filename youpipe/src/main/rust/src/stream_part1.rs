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
