//! Channel, playlist, trending and comment extraction.
//!
//! These all hit `browse` or `next` and differ mainly in which renderer holds the items, so the
//! shared walking lives in [`collect_stream_items`] and [`find_continuation`].

use serde_json::{json, Value};

use crate::http::{HttpClient, Request, Result};
use crate::innertube::{client_version, desktop_body, endpoint_url, youtube_headers};
use crate::json::JsonExt;
use crate::linkhandler;
use crate::model::{
    ChannelInfo, Comment, CommentsPage, ItemsPage, PlaylistInfo, SearchItem,
};
use crate::parsing::{
    channel_url_from_navigation_endpoint, is_verified, parse_abbreviated_count, parse_duration,
    parse_view_count, text_from_object, thumbnails_from_array, thumbnails_from_info_item,
};
use crate::response::parse_innertube;

fn browse(
    http: &dyn HttpClient,
    extra: &[(&str, Value)],
    hl: &str,
    gl: &str,
) -> Result<Value> {
    let version = client_version(http);
    let body = desktop_body(&version, hl, gl, extra);
    let response = http.execute(
        Request::post_json(endpoint_url("browse"), &body).headers(&youtube_headers(&version)),
    )?;
    parse_innertube(&response)
}

fn next(http: &dyn HttpClient, extra: &[(&str, Value)], hl: &str, gl: &str) -> Result<Value> {
    let version = client_version(http);
    let body = desktop_body(&version, hl, gl, extra);
    let response = http.execute(
        Request::post_json(endpoint_url("next"), &body).headers(&youtube_headers(&version)),
    )?;
    parse_innertube(&response)
}

// ---------------------------------------------------------------------------
// Channel
// ---------------------------------------------------------------------------

/// Channel metadata plus the first page of its videos tab.
pub fn channel_info(
    http: &dyn HttpClient,
    id_or_handle: &str,
    hl: &str,
    gl: &str,
) -> Result<ChannelInfo> {
    let root = browse(http, &[("browseId", json!(resolve_browse_id(id_or_handle)))], hl, gl)?;

    let metadata = root.obj("metadata").obj("channelMetadataRenderer");
    let header = channel_header(&root);

    let mut info = ChannelInfo {
        id: metadata.str_or("externalId", id_or_handle).to_string(),
        url: metadata
            .str("channelUrl")
            .map(str::to_string)
            .unwrap_or_else(|| linkhandler::channel_url(id_or_handle)),
        name: metadata
            .str("title")
            .map(str::to_string)
            .or_else(|| text_from_object(header.obj("title")))
            .unwrap_or_default(),
        description: metadata.str("description").map(str::to_string),
        avatars: thumbnails_from_array(metadata.obj("avatar").arr("thumbnails")),
        banners: thumbnails_from_array(header.obj("banner").arr("thumbnails")),
        subscriber_count: text_from_object(header.obj("subscriberCountText"))
            .as_deref()
            .map_or(-1, parse_abbreviated_count),
        verified: is_verified(header.arr("badges")),
        ..Default::default()
    };

    let mut page = ItemsPage::default();
    collect_from_tabs(&root, &mut page);
    info.items = page.items;
    info.next_page_token = page.next_page_token;
    info.errors = page.errors;
    Ok(info)
}

/// Handles browse by `UC…` id directly; `@handle` and vanity names need the id resolving, which
/// the browse endpoint does when given the canonical URL instead.
fn resolve_browse_id(id_or_handle: &str) -> String {
    id_or_handle.to_string()
}

/// The header renderer moved between layouts; try each shape YouTube still serves.
fn channel_header(root: &Value) -> &Value {
    let header = root.obj("header");
    for key in [
        "c4TabbedHeaderRenderer",
        "carouselHeaderRenderer",
        "pageHeaderRenderer",
    ] {
        let candidate = header.obj(key);
        if !candidate.is_blank() {
            return candidate;
        }
    }
    header
}

fn collect_from_tabs(root: &Value, page: &mut ItemsPage) {
    for tab in root.obj("contents").obj("twoColumnBrowseResultsRenderer").arr("tabs") {
        let content = tab.obj("tabRenderer").obj("content");
        // Grid layout (videos tab) and list layout (home tab) both appear.
        for section in content
            .obj("richGridRenderer")
            .arr("contents")
            .iter()
            .chain(content.obj("sectionListRenderer").arr("contents"))
        {
            walk_section(section, page);
        }
    }
}

fn walk_section(section: &Value, page: &mut ItemsPage) {
    if section.has("richItemRenderer") {
        collect_stream_items(&[section.obj("richItemRenderer").obj("content").clone()], page);
    } else if section.has("continuationItemRenderer") {
        page.next_page_token = find_continuation(section.obj("continuationItemRenderer"));
    } else if section.has("itemSectionRenderer") {
        for inner in section.obj("itemSectionRenderer").arr("contents") {
            if inner.has("shelfRenderer") {
                let items = inner
                    .obj("shelfRenderer")
                    .obj("content")
                    .obj("verticalListRenderer")
                    .arr("items");
                collect_stream_items(items, page);
            } else if inner.has("gridRenderer") {
                collect_stream_items(inner.obj("gridRenderer").arr("items"), page);
            } else {
                collect_stream_items(std::slice::from_ref(inner), page);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Playlist
// ---------------------------------------------------------------------------

/// Playlist metadata plus its first page of videos.
pub fn playlist_info(
    http: &dyn HttpClient,
    playlist_id: &str,
    hl: &str,
    gl: &str,
) -> Result<PlaylistInfo> {
    // Playlists browse under a "VL" prefix.
    let root = browse(http, &[("browseId", json!(format!("VL{playlist_id}")))], hl, gl)?;

    let microformat = root.obj("microformat").obj("microformatDataRenderer");
    let sidebar_primary = root
        .obj("sidebar")
        .obj("playlistSidebarRenderer")
        .arr("items")
        .first()
        .map(|i| i.obj("playlistSidebarPrimaryInfoRenderer").clone())
        .unwrap_or(Value::Null);

    let header = root.obj("header").obj("playlistHeaderRenderer");

    let mut info = PlaylistInfo {
        id: playlist_id.to_string(),
        url: linkhandler::playlist_url(playlist_id),
        name: text_from_object(header.obj("title"))
            .or_else(|| text_from_object(sidebar_primary.obj("title")))
            .or_else(|| microformat.str("title").map(str::to_string))
            .unwrap_or_default(),
        description: microformat.str("description").map(str::to_string),
        thumbnails: thumbnails_from_array(microformat.obj("thumbnail").arr("thumbnails")),
        stream_count: text_from_object(header.obj("numVideosText"))
            .as_deref()
            .map_or(-1, parse_view_count),
        ..Default::default()
    };

    let mut page = ItemsPage::default();
    for tab in root.obj("contents").obj("twoColumnBrowseResultsRenderer").arr("tabs") {
        let contents = tab
            .obj("tabRenderer")
            .obj("content")
            .obj("sectionListRenderer")
            .arr("contents");
        for section in contents {
            let items = section
                .obj("itemSectionRenderer")
                .arr("contents")
                .first()
                .map(|c| c.obj("playlistVideoListRenderer").arr("contents"))
                .unwrap_or_default();
            collect_stream_items(items, &mut page);
        }
    }
    info.items = page.items;
    info.next_page_token = page.next_page_token;
    info.errors = page.errors;
    Ok(info)
}

// ---------------------------------------------------------------------------
// Trending
// ---------------------------------------------------------------------------

pub fn trending(http: &dyn HttpClient, hl: &str, gl: &str) -> Result<ItemsPage> {
    let root = browse(http, &[("browseId", json!("FEtrending"))], hl, gl)?;
    let mut page = ItemsPage::default();
    collect_from_tabs(&root, &mut page);
    Ok(page)
}

// ---------------------------------------------------------------------------
// Continuations
// ---------------------------------------------------------------------------

/// Next page of any browse-backed list.
pub fn browse_continuation(
    http: &dyn HttpClient,
    token: &str,
    hl: &str,
    gl: &str,
) -> Result<ItemsPage> {
    let root = browse(http, &[("continuation", json!(token))], hl, gl)?;
    let mut page = ItemsPage::default();

    for action in root.arr("onResponseReceivedActions").iter().chain(root.arr("onResponseReceivedEndpoints")) {
        let items = action
            .obj("appendContinuationItemsAction")
            .arr("continuationItems");
        for item in items {
            if item.has("continuationItemRenderer") {
                page.next_page_token = find_continuation(item.obj("continuationItemRenderer"));
            } else if item.has("richItemRenderer") {
                collect_stream_items(&[item.obj("richItemRenderer").obj("content").clone()], &mut page);
            } else {
                collect_stream_items(std::slice::from_ref(item), &mut page);
            }
        }
    }
    Ok(page)
}

/// Continuation token from a `continuationItemRenderer`, covering both the endpoint and the
/// newer command-executor spelling.
fn find_continuation(renderer: &Value) -> Option<String> {
    let direct = renderer
        .obj("continuationEndpoint")
        .obj("continuationCommand")
        .str("token");
    if let Some(token) = direct.filter(|t| !t.is_empty()) {
        return Some(token.to_string());
    }
    renderer
        .obj("button")
        .obj("buttonRenderer")
        .obj("command")
        .obj("continuationCommand")
        .str("token")
        .filter(|t| !t.is_empty())
        .map(str::to_string)
}

/// Turns renderer entries into stream items. Unknown renderers are skipped silently; malformed
/// known ones are reported without dropping the page.
fn collect_stream_items(contents: &[Value], page: &mut ItemsPage) {
    for item in contents {
        let renderer = ["videoRenderer", "gridVideoRenderer", "playlistVideoRenderer"]
            .iter()
            .map(|k| item.obj(k))
            .find(|r| !r.is_blank());

        let Some(renderer) = renderer else {
            if item.has("continuationItemRenderer") {
                page.next_page_token = find_continuation(item.obj("continuationItemRenderer"));
            }
            continue;
        };

        match parse_list_video(renderer) {
            Ok(entry) => page.items.push(entry),
            Err(message) => page.errors.push(message),
        }
    }
}

fn parse_list_video(renderer: &Value) -> std::result::Result<SearchItem, String> {
    let video_id = renderer
        .str("videoId")
        .filter(|id| !id.is_empty())
        .ok_or_else(|| "video renderer without videoId".to_string())?;

    let name = text_from_object(renderer.obj("title"))
        .ok_or_else(|| format!("no title for video {video_id}"))?;

    let duration_seconds = renderer
        .str("lengthSeconds")
        .and_then(|s| s.parse().ok())
        .or_else(|| text_from_object(renderer.obj("lengthText")).as_deref().and_then(parse_duration));

    let uploader_endpoint = renderer
        .obj("longBylineText")
        .arr("runs")
        .first()
        .map(|r| r.obj("navigationEndpoint"))
        .unwrap_or(&Value::Null);

    Ok(SearchItem::Stream(crate::model::StreamItem {
        url: linkhandler::stream_url(video_id),
        name,
        duration_seconds,
        uploader_name: text_from_object(renderer.obj("longBylineText"))
            .or_else(|| text_from_object(renderer.obj("shortBylineText"))),
        uploader_url: channel_url_from_navigation_endpoint(uploader_endpoint),
        uploader_verified: is_verified(renderer.arr("ownerBadges")),
        textual_upload_date: text_from_object(renderer.obj("publishedTimeText")),
        view_count: text_from_object(renderer.obj("viewCountText"))
            .as_deref()
            .map_or(-1, parse_view_count),
        thumbnails: thumbnails_from_info_item(renderer),
        is_live: false,
        is_short: false,
    }))
}

// ---------------------------------------------------------------------------
// Comments
// ---------------------------------------------------------------------------

/// First page of comments for a video.
///
/// Comments need two round trips: the `next` response only carries a token for the comment
/// section, which must then be fetched.
pub fn comments(http: &dyn HttpClient, video_id: &str, hl: &str, gl: &str) -> Result<CommentsPage> {
    let root = next(
        http,
        &[("videoId", json!(video_id)), ("contentCheckOk", json!(true)), ("racyCheckOk", json!(true))],
        hl,
        gl,
    )?;

    let token = root
        .obj("contents")
        .obj("twoColumnWatchNextResults")
        .obj("results")
        .obj("results")
        .arr("contents")
        .iter()
        .find_map(|item| find_continuation(item.obj("itemSectionRenderer").obj("contents").at(0).obj("continuationItemRenderer"))
            .or_else(|| {
                item.obj("itemSectionRenderer")
                    .arr("contents")
                    .iter()
                    .find_map(|c| find_continuation(c.obj("continuationItemRenderer")))
            }));

    match token {
        Some(token) => comments_page(http, &token, hl, gl),
        // Comments disabled, or the section had not loaded — an empty page, not an error.
        None => Ok(CommentsPage::default()),
    }
}

/// A page of comments from a continuation token.
pub fn comments_page(
    http: &dyn HttpClient,
    token: &str,
    hl: &str,
    gl: &str,
) -> Result<CommentsPage> {
    let root = next(http, &[("continuation", json!(token))], hl, gl)?;
    let mut page = CommentsPage::default();

    for action in root
        .arr("onResponseReceivedEndpoints")
        .iter()
        .chain(root.arr("onResponseReceivedActions"))
    {
        let items = if action.has("reloadContinuationItemsCommand") {
            action.obj("reloadContinuationItemsCommand").arr("continuationItems")
        } else {
            action.obj("appendContinuationItemsAction").arr("continuationItems")
        };

        for item in items {
            if item.has("continuationItemRenderer") {
                page.next_page_token = find_continuation(item.obj("continuationItemRenderer"));
                continue;
            }
            // Comments moved from commentThreadRenderer to the view-model shape.
            let view_model = item
                .obj("commentThreadRenderer")
                .obj("commentViewModel")
                .obj("commentViewModel");
            if !view_model.is_blank() {
                if let Some(comment) = parse_comment_view_model(&root, view_model) {
                    page.comments.push(comment);
                }
                continue;
            }
            let legacy = item.obj("commentThreadRenderer").obj("comment").obj("commentRenderer");
            if !legacy.is_blank() {
                page.comments.push(parse_legacy_comment(legacy));
            }
        }
    }
    Ok(page)
}

/// The view-model shape stores payloads out of line, keyed by `commentKey`, in `frameworkUpdates`.
fn parse_comment_view_model(root: &Value, view_model: &Value) -> Option<Comment> {
    let key = view_model.str("commentKey")?;
    let entity = root
        .obj("frameworkUpdates")
        .obj("entityBatchUpdate")
        .arr("mutations")
        .iter()
        .find(|m| m.str("entityKey") == Some(key))
        .map(|m| m.obj("payload").obj("commentEntityPayload").clone())?;

    let author = entity.obj("author");
    Some(Comment {
        id: entity.obj("properties").str_or("commentId", "").to_string(),
        text: entity.obj("properties").obj("content").str_or("content", "").to_string(),
        author_name: author.str("displayName").map(str::to_string),
        author_url: author.str("channelId").map(linkhandler::channel_url),
        author_thumbnails: thumbnails_from_array(author.obj("avatarThumbnailUrl").arr("thumbnails")),
        author_verified: author.bool("isVerified"),
        like_count: entity.obj("toolbar").str("likeCountNotliked").map_or(-1, parse_abbreviated_count),
        reply_count: entity.obj("toolbar").str("replyCount").and_then(|v| v.parse().ok()).unwrap_or(0),
        published_time: entity.obj("properties").str("publishedTime").map(str::to_string),
        is_pinned: false,
        is_hearted: false,
    })
}

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
