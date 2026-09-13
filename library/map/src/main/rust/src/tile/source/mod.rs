//! The byte source under the PMTiles reader: the disk cache in front of Kotlin's HTTP
//! stack.
//!
//! # The policy
//!
//! Ported from `maps/.../util/MapTileCache.kt`, the version that has been in production
//! against this archive:
//!
//! * A cached range is served without touching the network when it is **fresh**, or
//!   whenever the device is **offline** — stale but usable, so a previously-viewed area
//!   keeps rendering with no network.
//! * A failed fetch falls back to a stale entry rather than leaving a hole in the map.
//! * **Only a 206 whose body is exactly the requested length is stored.** A `200`
//!   whole-file reply to a range request is what produced the "Prefix string too short"
//!   pmtiles failures the original records at `MapTileCache.kt:287-290`; caching one
//!   poisons every later read of that range.
//!
//! # Why HTTP goes back out through Kotlin
//!
//! `library/jni-http` is a *"flat-frame JNI HTTP bridge shared by the Rust
//! extractors — no object creation on the hot path"*. Using it means range requests are
//! served by `:library:network` and keep its reduced CA bundle and
//! `HttpURLConnection`-only policy, and this crate carries no HTTP client, no TLS stack
//! and no second trust store.

mod caching;
mod fetch;
mod file;
mod retry;
mod urls;

#[cfg(test)]
mod tests;

pub use caching::CachingRangeReader;
#[cfg(target_os = "android")]
pub use fetch::JniRangeFetcher;
pub use fetch::{RangeFetcher, RangeResponse};
pub use file::FileRangeReader;
pub use retry::{RETRY_BASE_MS, RETRY_MAX_MS, retry_delay_ms};
pub use urls::{BASEMAP_ARCHIVE_URL, BASEMAP_PMTILES_URL, CACHE_FORMAT, basemap_origin};
