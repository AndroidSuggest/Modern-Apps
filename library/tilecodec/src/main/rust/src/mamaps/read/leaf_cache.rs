//! A parsed-leaf cache shared by every reader of one archive.
//!
//! MapLibre parity (issue 815): MapLibre's `PMTilesFileSource` funnels every tile
//! through one actor holding a shared directory cache (up to 100 entries), so a
//! leaf is fetched once no matter how many tiles need it. Previously each
//! `MamapsArchive` carried a private 16-leaf MRU, so four tile workers covering
//! one viewport fetched and inflated the same cold leaves up to four times.
//!
//! Sharing is by `Arc<LeafCache>`: the entries are immutable once parsed, so a
//! hit is one lock, one `HashMap` lookup and one `Arc` clone — no 64 KB `Vec`
//! copy per tile, and no I/O under the lock on the hot path. Only a miss does
//! I/O, and it happens *outside* the lock: the caller fetches, inflates and
//! parses, then publishes with [`LeafCache::insert`], which keeps the first
//! copy when two workers race on the same cold leaf.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};

use crate::mamaps::index::LeafEntry;

/// How many parsed leaves a shared cache keeps by default.
///
/// Sixteen covered a viewport's walk when each worker had its own cache; shared
/// across all of a surface's workers the working set is the same leaves, so 64
/// leaves address 262 144 tiles — several screenfuls plus panning headroom —
/// while each entry stays a parsed in-memory index (tens of KB), not tile data.
pub const DEFAULT_LEAF_CACHE_CAPACITY: usize = 64;

struct State {
    entries: HashMap<u64, Arc<Vec<LeafEntry>>>,
    /// `leaf_offset`s in use order, most-recently-used last.
    order: VecDeque<u64>,
}

/// Parsed `.mamaps` leaves keyed by `leaf_offset`, shared across threads.
///
/// `Send + Sync` via interior mutability; construct once per surface and hand
/// an `Arc` clone to every `MamapsArchive` with
/// [`MamapsArchive::open_with_cache`](super::archive::MamapsArchive::open_with_cache).
/// A poisoned lock degrades to a miss (or a dropped insert), never a panic, so
/// a failed worker cannot wedge the rest of the surface.
pub struct LeafCache {
    capacity: usize,
    state: Mutex<State>,
}

impl LeafCache {
    /// A cache with [`DEFAULT_LEAF_CACHE_CAPACITY`] entries.
    pub fn new() -> Self {
        Self::with_capacity(DEFAULT_LEAF_CACHE_CAPACITY)
    }

    /// A cache with `capacity` entries. A zero capacity is raised to one.
    pub fn with_capacity(capacity: usize) -> Self {
        LeafCache {
            capacity: capacity.max(1),
            state: Mutex::new(State {
                entries: HashMap::new(),
                order: VecDeque::new(),
            }),
        }
    }

    /// The entry budget this cache was built with.
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    /// How many leaves are currently cached.
    pub fn len(&self) -> usize {
        self.state.lock().map(|s| s.entries.len()).unwrap_or(0)
    }

    /// Whether the cache currently holds no leaf.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// The parsed leaf at `leaf_offset`, promoted to most-recently-used.
    ///
    /// `None` on a miss — or when the lock is poisoned, which is treated as a
    /// miss so readers fall back to fetching rather than failing.
    pub fn get(&self, leaf_offset: u64) -> Option<Arc<Vec<LeafEntry>>> {
        let mut state = self.state.lock().ok()?;
        let entries = state.entries.get(&leaf_offset)?.clone();
        if state.order.back() != Some(&leaf_offset) {
            state.order.retain(|o| *o != leaf_offset);
            state.order.push_back(leaf_offset);
        }
        Some(entries)
    }

    /// Publish a parsed leaf, evicting least-recently-used entries past capacity.
    ///
    /// Returns the stored handle. When two workers race on the same cold leaf
    /// the first copy wins and the loser is dropped, so the cache never holds
    /// two copies of one leaf.
    pub fn insert(&self, leaf_offset: u64, entries: Vec<LeafEntry>) -> Arc<Vec<LeafEntry>> {
        let mut state = match self.state.lock() {
            Ok(s) => s,
            // Publish failed: hand the caller its own copy so the tile still resolves.
            Err(_) => return Arc::new(entries),
        };
        if let Some(existing) = state.entries.get(&leaf_offset) {
            let existing = existing.clone();
            state.order.retain(|o| *o != leaf_offset);
            state.order.push_back(leaf_offset);
            return existing;
        }
        let stored = Arc::new(entries);
        state.entries.insert(leaf_offset, Arc::clone(&stored));
        state.order.push_back(leaf_offset);
        while state.entries.len() > self.capacity {
            if let Some(oldest) = state.order.pop_front() {
                state.entries.remove(&oldest);
            } else {
                break;
            }
        }
        stored
    }
}

impl Default for LeafCache {
    fn default() -> Self {
        Self::new()
    }
}
