//! The POI kind filter and the shared toggle snapshot read by the tile workers.

use super::{Layer, LayerToggles, Toggle};

/// Which POI kinds the map is narrowed to, or empty for "every kind the style draws".
///
/// The app's category chips (Food, Gas, Hotels, …) select a handful of kinds out of the six
/// `poi-*` layers' thirty-nine. That is a *sub-layer* filter, so it cannot be expressed by
/// turning layers off, and it cannot ride in [`SharedToggles`]' packed word either — there are
/// more kinds than a `u32` has bits.
///
/// Interned ids rather than names, sorted, so the per-feature test is a binary search over a
/// `u16` slice — the same shape [`Layer::matches_id`] already uses for the layer's own whitelist.
/// An `Arc` because a worker holds it for the length of a tile build while the host may replace it.
#[derive(Clone, Debug, Default)]
pub struct KindFilter(std::sync::Arc<[u16]>);

impl PartialEq for KindFilter {
    fn eq(&self, other: &KindFilter) -> bool {
        // By value, not by pointer: the host rebuilds this list on every recomposition, so a
        // pointer comparison would report a change on every frame and re-tessellate forever.
        self.0[..] == other.0[..]
    }
}

impl Eq for KindFilter {}

impl KindFilter {
    /// Every kind. The default, and what an empty chip selection means.
    pub fn all() -> KindFilter {
        KindFilter(std::sync::Arc::from(Vec::new()))
    }

    /// A filter over interned kind ids. Sorted and deduplicated here so callers need not be.
    pub fn new(mut kinds: Vec<u16>) -> KindFilter {
        kinds.sort_unstable();
        kinds.dedup();
        KindFilter(std::sync::Arc::from(kinds))
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// Does this filter admit `kind` on `layer`?
    ///
    /// Only POI layers are filtered. The chips narrow which *points of interest* are shown; a
    /// road or a country label is not one, and hiding the basemap because the user tapped
    /// "Coffee" would be absurd.
    pub fn admits(&self, layer: &Layer, kind: u16) -> bool {
        if self.0.is_empty() || layer.toggle != Some(Toggle::Poi) {
            return true;
        }
        self.0.binary_search(&kind).is_ok()
    }
}

/// The toggles, the POI kind filter and a generation counter, read as one snapshot and shared
/// with the tile workers.
///
/// Gating happens at **tessellation** time rather than at draw time, because that is
/// where the cost is: with POI off, no label is shaped and no placement candidate is
/// built for any resident tile. The price of gating there is that a toggle change
/// invalidates meshes, so every mesh records the generation it was built at and the
/// renderer re-requests anything stale. Re-tessellation goes through the existing worker
/// pool and reads the archive it already has, so nothing is refetched and nothing is
/// evicted — the same "re-decorate without reloading" shape as a palette switch, one step
/// heavier.
///
/// One snapshot rather than a field each so a worker's read is consistent: a mesh tagged with
/// one generation but built from another's flags would either never be refreshed or be refreshed
/// forever. This was a single `AtomicU32` while the state was two bits and a counter; the kind
/// filter does not fit in a word, so it is a lock — taken once per tile build, not per feature.
#[derive(Debug)]
pub struct SharedToggles(std::sync::RwLock<Snapshot>);

#[derive(Clone, Debug)]
struct Snapshot {
    toggles: LayerToggles,
    kinds: KindFilter,
    generation: u32,
}

impl SharedToggles {
    /// Generations start at 1, so a mesh from a default-initialised 0 is always stale.
    pub fn new(toggles: LayerToggles) -> SharedToggles {
        SharedToggles(std::sync::RwLock::new(Snapshot {
            toggles,
            kinds: KindFilter::all(),
            generation: 1,
        }))
    }

    /// The current toggles, kind filter and the generation they belong to.
    pub fn get(&self) -> (LayerToggles, KindFilter, u32) {
        // A poisoned lock means another thread panicked mid-set. The state behind it is three
        // plain values with no invariant between them beyond the generation, so reading it is
        // sound; refusing to draw the map because a worker died is worse than drawing it.
        let snapshot = self.0.read().unwrap_or_else(|e| e.into_inner());
        (snapshot.toggles, snapshot.kinds.clone(), snapshot.generation)
    }

    /// Set the toggles and the kind filter, bumping the generation. Returns `true` when
    /// anything changed.
    ///
    /// Both at once rather than one setter each: the host sets them from a single Compose
    /// effect, and two bumps would re-tessellate the whole resident set twice for one change.
    ///
    /// A no-op set must not bump: the host may call this on every recomposition, and a
    /// bump there would re-tessellate the whole resident set for nothing.
    pub fn set(&self, toggles: LayerToggles, kinds: KindFilter) -> bool {
        let mut snapshot = self.0.write().unwrap_or_else(|e| e.into_inner());
        if snapshot.toggles == toggles && snapshot.kinds == kinds {
            return false;
        }
        snapshot.toggles = toggles;
        snapshot.kinds = kinds;
        snapshot.generation = snapshot.generation.wrapping_add(1).max(1);
        true
    }
}
