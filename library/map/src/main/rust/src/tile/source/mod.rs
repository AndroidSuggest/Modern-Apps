//! The byte source under the mamaps reader: the disk cache in front of Kotlin's HTTP
//! stack.
//!
//! # The policy
//!
//! * A cached range is served without touching the network when it is **fresh**, or
//!   whenever the device is **offline** — stale but usable, so a previously-viewed area
//!   keeps rendering with no network.
//! * A failed fetch or a non-2xx status is an error: the reader never serves a stale
//!   entry for a range whose refetch failed.
//! * **Only a 206 whose body is exactly the requested length is stored.**
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
pub use retry::{retry_delay_ms, RETRY_BASE_MS, RETRY_MAX_MS};
pub use urls::{basemap_origin, BASEMAP_ARCHIVE_URL, CACHE_FORMAT};
