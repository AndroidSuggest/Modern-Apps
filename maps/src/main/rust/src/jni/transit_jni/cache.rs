//! Transit-pack cache shared by the offline-transit JNI entry points.
//!
//! Pure move out of `super` (`transit_jni.rs`); no logic changes.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

/// Transit packs already opened, keyed by `(base_dir, feed)`.
///
/// `TransitIndex::load` mmaps and revalidates the entire pack — 2.84 GB for the
/// planetary one — and a single itinerary needs three or four of them (two RAPTOR
/// passes plus a timezone lookup). Shared behind an `Arc` like the road-graph
/// global, since a loaded index is immutable.
type TransitCache = HashMap<(String, String), Arc<crate::transit::TransitIndex>>;

fn transit_cache() -> &'static Mutex<TransitCache> {
    static T: OnceLock<Mutex<TransitCache>> = OnceLock::new();
    T.get_or_init(|| Mutex::new(HashMap::new()))
}

/// The pack for `feed` under `base`, loading it on first use. `None` when it is
/// absent or malformed; a failure is not cached, so a later republish is picked up.
pub(super) fn transit_index(base: &str, feed: &str) -> Option<Arc<crate::transit::TransitIndex>> {
    let key = (base.to_string(), feed.to_string());
    // Held across the load so two concurrent first queries map the pack once
    // rather than racing to build two 2.84 GB mappings.
    let mut cache = transit_cache().lock().ok()?;
    if let Some(idx) = cache.get(&key) {
        return Some(Arc::clone(idx));
    }
    let idx = Arc::new(crate::transit::TransitIndex::load(base, feed)?);
    cache.insert(key, Arc::clone(&idx));
    Some(idx)
}
