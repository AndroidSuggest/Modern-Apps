//! Data returned across the JNI boundary.
//!
//! Serialised to JSON and decoded on the Kotlin side. Passing JSON rather than constructing Java
//! objects from Rust keeps the boundary one function wide and debuggable — you can log the exact
//! payload — at the cost of one serialise/parse round trip per call, which is noise next to the
//! network request that produced it.

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ItemType {
    Stream,
    Channel,
    Playlist,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct StreamItem {
    pub url: String,
    pub name: String,
    /// `None` for live streams, which report no duration.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_seconds: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uploader_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uploader_url: Option<String>,
    pub uploader_verified: bool,
    /// As shown by YouTube ("3 years ago"); the app localises its own absolute dates.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub textual_upload_date: Option<String>,
    /// `-1` when unknown, matching upstream's convention.
    pub view_count: i64,
    pub thumbnails: Vec<Thumbnail>,
    pub is_live: bool,
    pub is_short: bool,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct ChannelItem {
    pub url: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    /// `-1` when unknown.
    pub subscriber_count: i64,
    pub stream_count: i64,
    pub verified: bool,
    pub thumbnails: Vec<Thumbnail>,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct PlaylistItem {
    pub url: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uploader_name: Option<String>,
    /// `-1` when unknown.
    pub stream_count: i64,
    pub thumbnails: Vec<Thumbnail>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type")]
pub enum SearchItem {
    #[serde(rename = "STREAM")]
    Stream(StreamItem),
    #[serde(rename = "CHANNEL")]
    Channel(ChannelItem),
    #[serde(rename = "PLAYLIST")]
    Playlist(PlaylistItem),
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct Thumbnail {
    pub url: String,
    pub width: i64,
    pub height: i64,
}

#[derive(Debug, Default, Serialize)]
pub struct SearchResult {
    pub items: Vec<SearchItem>,
    /// Continuation token for the next page; absent when there are no more results.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
    /// YouTube's "did you mean" / "showing results for" suggestion.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub search_suggestion: Option<String>,
    pub is_corrected_search: bool,
    /// Non-fatal problems hit while parsing individual items. Upstream collects these rather
    /// than failing the page, and the app surfaces them without discarding good results.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<String>,
}

// ---------------------------------------------------------------------------
// Video playback
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StreamKind {
    #[default]
    Video,
    Live,
    /// A finished livestream, still served from its DVR window.
    PostLive,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct VideoStream {
    pub url: String,
    pub itag: i64,
    pub mime_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub codec: Option<String>,
    /// Label such as `1080p60`; absent on some adaptive formats.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub resolution: Option<String>,
    pub width: i64,
    pub height: i64,
    pub fps: i64,
    pub bitrate: i64,
    pub content_length: i64,
    // Byte ranges for DASH. Zero when the format is progressive.
    pub init_start: i64,
    pub init_end: i64,
    pub index_start: i64,
    pub index_end: i64,
    /// True for adaptive video tracks, which carry no audio.
    pub video_only: bool,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct AudioStream {
    pub url: String,
    pub itag: i64,
    pub mime_type: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub codec: Option<String>,
    pub bitrate: i64,
    pub average_bitrate: i64,
    pub sample_rate: i64,
    pub channels: i64,
    pub content_length: i64,
    pub init_start: i64,
    pub init_end: i64,
    pub index_start: i64,
    pub index_end: i64,
    /// Language track id such as `en.4`, when the video has dubs.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub track_name: Option<String>,
    pub is_drc: bool,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct StreamInfo {
    pub id: String,
    pub url: String,
    pub name: String,
    pub kind: StreamKind,
    pub duration_seconds: i64,
    /// `-1` when unknown.
    pub view_count: i64,
    pub like_count: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uploader_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uploader_url: Option<String>,
    pub uploader_verified: bool,
    pub uploader_subscriber_count: i64,
    pub uploader_avatars: Vec<Thumbnail>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub textual_upload_date: Option<String>,
    pub thumbnails: Vec<Thumbnail>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
    pub tags: Vec<String>,
    pub age_limit: i64,
    pub video_streams: Vec<VideoStream>,
    pub audio_streams: Vec<AudioStream>,
    pub video_only_streams: Vec<VideoStream>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dash_manifest_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hls_manifest_url: Option<String>,
    /// True when YouTube returned only SABR formats, so there are no direct stream URLs and the
    /// app's SABR path must take over.
    pub sabr_only: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub server_abr_streaming_url: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<String>,
}

// ---------------------------------------------------------------------------
// Lists
// ---------------------------------------------------------------------------

#[derive(Debug, Default, Clone, Serialize)]
pub struct ItemsPage {
    pub items: Vec<SearchItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<String>,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct ChannelInfo {
    pub id: String,
    pub url: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    pub avatars: Vec<Thumbnail>,
    pub banners: Vec<Thumbnail>,
    /// `-1` when unknown.
    pub subscriber_count: i64,
    pub verified: bool,
    pub items: Vec<SearchItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<String>,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct PlaylistInfo {
    pub id: String,
    pub url: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uploader_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uploader_url: Option<String>,
    pub thumbnails: Vec<Thumbnail>,
    /// `-1` when unknown.
    pub stream_count: i64,
    pub items: Vec<SearchItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<String>,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct Comment {
    pub id: String,
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub author_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub author_url: Option<String>,
    pub author_thumbnails: Vec<Thumbnail>,
    pub author_verified: bool,
    /// `-1` when unknown.
    pub like_count: i64,
    pub reply_count: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub published_time: Option<String>,
    pub is_pinned: bool,
    pub is_hearted: bool,
}

#[derive(Debug, Default, Clone, Serialize)]
pub struct CommentsPage {
    pub comments: Vec<Comment>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub next_page_token: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub errors: Vec<String>,
}
