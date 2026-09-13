use super::fetch::RangeFetcher;
use crate::tile::cache::RangeCache;
use tilecodec::proto::{err, Result};
use tilecodec::stream::RangeReader;

pub struct CachingRangeReader<F: RangeFetcher> {
    url: String,
    cache: RangeCache,
    // `pub(crate)` so the policy tests beside this module can count requests,
    // as they did when they were an inline `mod tests`. No logic change.
    pub(crate) fetcher: F,
    /// Set from Kotlin, which owns the `ConnectivityManager`. When offline the network is
    /// not attempted at all and a stale entry is served instead.
    online: std::sync::atomic::AtomicBool,
    /// False until the first read of the session has gone to the network.
    ///
    /// That read is the archive header, and it is the one entry the cache must not answer from
    /// itself — see the note in [`RangeReader::read`].
    prefix_checked: std::sync::atomic::AtomicBool,
}

impl<F: RangeFetcher> CachingRangeReader<F> {
    pub fn new(url: impl Into<String>, cache: RangeCache, fetcher: F) -> Self {
        CachingRangeReader {
            url: url.into(),
            cache,
            fetcher,
            online: std::sync::atomic::AtomicBool::new(true),
            prefix_checked: std::sync::atomic::AtomicBool::new(false),
        }
    }

    pub fn set_online(&self, online: bool) {
        self.online.store(online, std::sync::atomic::Ordering::Relaxed);
    }

    /// Re-check the cache's origin marker, now that the archive's `build_id` is known.
    ///
    /// See [`crate::tile::source::basemap_origin`] for why this is a second step rather than part of opening the cache.
    pub fn reset_origin(&self, origin: &str) {
        self.cache.reset_if_origin_changed(origin);
    }

    fn is_online(&self) -> bool {
        self.online.load(std::sync::atomic::Ordering::Relaxed)
    }
}

impl<F: RangeFetcher> RangeReader for CachingRangeReader<F> {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if length == 0 {
            return Ok(Vec::new());
        }
        let range = format!("bytes={}-{}", offset, offset + length as u64 - 1);
        let key = RangeCache::key(&self.url, &range);

        // The archive header decides whether every other entry is still valid, so it cannot be
        // served from the cache it is meant to validate. Once per session the first read — which
        // is always the header, because nothing can be located without it — goes to the network.
        //
        // Without this the staleness is self-consistent: a cached prefix reports the *previous*
        // build's id, `basemap_origin` therefore matches, the cache is kept, and every leaf index
        // in it keeps addressing the previous build's byte offsets. The archive is republished
        // under a stable URL by design, so nothing else ever catches it, and the entry only
        // expires on the 24-hour refresh interval. Republishing twice in an afternoon meant a map
        // built from two different archives at once: bodies that would not inflate, and a
        // coastline drawn from offsets that had moved.
        //
        // One small request per app start, and only when online.
        let first = !self.prefix_checked.swap(true, std::sync::atomic::Ordering::Relaxed);
        let revalidate = first && self.is_online();

        let cached = self.cache.read(&key);
        if let Some(entry) = &cached {
            if !revalidate && (self.cache.is_fresh(entry) || !self.is_online()) {
                return Ok(entry.body.clone());
            }
        }

        let response = match self.fetcher.fetch(&self.url, &range) {
            Ok(r) => r,
            Err(e) => {
                // Went offline mid-session: a stale entry is far better than a hole.
                if let Some(entry) = cached {
                    return Ok(entry.body);
                }
                return Err(e);
            }
        };

        if !(200..300).contains(&response.status) {
            if let Some(entry) = cached {
                return Ok(entry.body);
            }
            return err(format!(
                "range request for {range} of {} failed with HTTP {}",
                self.url, response.status
            ));
        }
        // Only a body we trust: a 206 partial whose length matches the request.
        if response.status == 206 && response.body.len() == length as usize {
            self.cache.write(&key, &response.body);
        }
        Ok(response.body)
    }
}
