fn parse_legacy_comment(renderer: &Value) -> Comment {
    Comment {
        id: renderer.str_or("commentId", "").to_string(),
        text: text_from_object(renderer.obj("contentText")).unwrap_or_default(),
        author_name: text_from_object(renderer.obj("authorText")),
        author_url: channel_url_from_navigation_endpoint(renderer.obj("authorEndpoint")),
        author_thumbnails: thumbnails_from_array(
            renderer.obj("authorThumbnail").arr("thumbnails"),
        ),
        author_verified: is_verified(renderer.arr("authorCommentBadge")),
        like_count: renderer.int("likeCount"),
        reply_count: renderer.int("replyCount"),
        published_time: text_from_object(renderer.obj("publishedTimeText")),
        is_pinned: !renderer.obj("pinnedCommentBadge").is_blank(),
        is_hearted: !renderer.obj("actionButtons")
            .obj("commentActionButtonsRenderer")
            .obj("creatorHeart")
            .is_blank(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn continuation_found_in_both_spellings() {
        let endpoint = json!({"continuationEndpoint": {"continuationCommand": {"token": "A"}}});
        assert_eq!(find_continuation(&endpoint).as_deref(), Some("A"));

        let button = json!({
            "button": {"buttonRenderer": {"command": {"continuationCommand": {"token": "B"}}}}
        });
        assert_eq!(find_continuation(&button).as_deref(), Some("B"));

        assert_eq!(find_continuation(&json!({})), None);
    }

    #[test]
    fn grid_and_playlist_renderers_both_parse() {
        let contents = json!([
            {"gridVideoRenderer": {"videoId": "aaaaaaaaaaa", "title": {"simpleText": "Grid"}}},
            {"playlistVideoRenderer": {
                "videoId": "bbbbbbbbbbb",
                "title": {"runs": [{"text": "Playlist"}]},
                "lengthSeconds": "125"
            }}
        ]);
        let mut page = ItemsPage::default();
        collect_stream_items(contents.as_array().unwrap(), &mut page);
        assert_eq!(page.items.len(), 2);
        assert!(page.errors.is_empty());
        match &page.items[1] {
            SearchItem::Stream(s) => assert_eq!(s.duration_seconds, Some(125)),
            _ => panic!("expected a stream"),
        }
    }

    #[test]
    fn unknown_renderers_are_skipped_silently() {
        let contents = json!([{ "someFutureRenderer": {"x": 1} }]);
        let mut page = ItemsPage::default();
        collect_stream_items(contents.as_array().unwrap(), &mut page);
        assert!(page.items.is_empty());
        assert!(page.errors.is_empty(), "unknown renderers are not errors");
    }

    #[test]
    fn a_malformed_known_renderer_is_reported() {
        let contents = json!([{ "videoRenderer": {"title": {"simpleText": "no id"}} }]);
        let mut page = ItemsPage::default();
        collect_stream_items(contents.as_array().unwrap(), &mut page);
        assert_eq!(page.errors.len(), 1);
    }

    #[test]
    fn channel_header_falls_back_across_layouts() {
        let root = json!({"header": {"pageHeaderRenderer": {"title": {"simpleText": "X"}}}});
        assert!(!channel_header(&root).is_blank());
        let empty = json!({"header": {}});
        assert!(channel_header(&empty).is_blank());
    }
}
