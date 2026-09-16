//! Mobile shader fallback wiring.
//!
//! Additive host-side policy layer: re-uses [`crate::shaders_mobile`] (no GPU
//! dependency) to give sessions a thermal-aware pipeline cache and a warmup
//! plan that hides the first-run compile hitch. Split out of `vulkan_session`
//! to keep each file under the repo's per-file line limit.

use crate::shaders_mobile::{
    CachedPipeline, DeviceCaps, DispatchChunk, MobileShaderError, PipelineCache,
    PipelineCacheKey, ShaderPath, WarmupEntry, chunk_dispatch, select_path, warmup_plan,
    workgroup_for_path,
};
use crate::tensors::Dtype;

/// Mobile fallback state kept alongside a `GpuSession`.
///
/// Bundles the [`DeviceCaps`] snapshot taken at session setup, the in-memory
/// [`PipelineCache`] keyed by (model hash, path, caps), and the thermal row
/// budget used by [`MobilePipelineState::chunks`]. Create one per session via
/// [`MobilePipelineState::new`], call [`MobilePipelineState::plan_warmup`]
/// during the loading screen, and compile + [`MobilePipelineState::insert`]
/// one pipeline per returned entry so steady-state inference hits the cache
/// instead of the driver compiler.
#[derive(Debug)]
pub struct MobilePipelineState {
    /// Device capabilities driving path and workgroup selection.
    caps: DeviceCaps,
    /// In-memory compiled-pipeline entries.
    cache: PipelineCache,
    /// Maximum output rows per dispatch; thermal control (see [`chunk_dispatch`]).
    rows_per_dispatch: u64,
}

impl MobilePipelineState {
    /// Build state for `caps` with the default thermal budget
    /// ([`crate::shaders_mobile::DEFAULT_MAX_ROWS_PER_DISPATCH`]).
    pub fn new(caps: DeviceCaps) -> Self {
        Self {
            caps,
            cache: PipelineCache::new(),
            rows_per_dispatch: crate::shaders_mobile::DEFAULT_MAX_ROWS_PER_DISPATCH,
        }
    }

    /// Build state for `caps` with a custom per-dispatch row budget.
    ///
    /// A zero budget is rejected at dispatch time by [`chunk_dispatch`], not
    /// here, so construction stays infallible.
    pub fn with_row_budget(caps: DeviceCaps, rows_per_dispatch: u64) -> Self {
        Self {
            caps,
            cache: PipelineCache::new(),
            rows_per_dispatch,
        }
    }

    /// Return the device capabilities this state was built from.
    pub fn caps(&self) -> &DeviceCaps {
        &self.caps
    }

    /// Pick the [`ShaderPath`] for `dtype` on this state's caps.
    ///
    /// Never selects cooperative-matrix unless the vendor is NVIDIA and the
    /// extension is present; Adreno/Mali always yield the WGSL fallback.
    pub fn path_for(&self, dtype: Dtype) -> ShaderPath {
        select_path(&self.caps, dtype)
    }

    /// Pick the `[x, y, z]` mobile workgroup for `dtype` on this state's caps.
    ///
    /// Always 64 or 128 total invocations in 1-D layout, never desktop `16x16`.
    pub fn workgroup_for(&self, dtype: Dtype) -> [u32; 3] {
        workgroup_for_path(self.path_for(dtype), &self.caps)
    }

    /// Build the ordered warmup plan for `model_hash` over `dtypes`.
    ///
    /// Compile one pipeline per entry while the app is still on a
    /// non-interactive screen, then [`MobilePipelineState::insert`] each
    /// result; see the `crate::shaders_mobile` module docs for the first-run
    /// compile hitch this hides.
    pub fn plan_warmup(&self, model_hash: u64, dtypes: &[Dtype]) -> Vec<WarmupEntry> {
        warmup_plan(model_hash, &self.caps, dtypes)
    }

    /// Split `total_rows` output rows into thermal-friendly dispatches.
    pub fn chunks(&self, total_rows: u64) -> Result<Vec<DispatchChunk>, MobileShaderError> {
        chunk_dispatch(total_rows, self.rows_per_dispatch)
    }

    /// Look up the cached pipeline for (`model_hash`, `dtype`) on these caps.
    pub fn lookup(&self, model_hash: u64, dtype: Dtype) -> Option<&CachedPipeline> {
        let key = PipelineCacheKey::new(model_hash, self.path_for(dtype), &self.caps);
        self.cache.lookup(&key)
    }

    /// Look up a cached pipeline by its full key.
    pub fn lookup_key(&self, key: &PipelineCacheKey) -> Option<&CachedPipeline> {
        self.cache.lookup(key)
    }

    /// Insert (or replace) a compiled pipeline entry.
    pub fn insert(&mut self, entry: CachedPipeline) {
        self.cache.insert(entry);
    }

    /// Disk-cache stub: always `None` until the persistent backing lands.
    pub fn load_from_disk(&self, key: &PipelineCacheKey) -> Option<CachedPipeline> {
        self.cache.load_from_disk(key)
    }

    /// Disk-cache stub: currently a no-op (see [`PipelineCache::store_to_disk`]).
    pub fn store_to_disk(&self, entry: &CachedPipeline) {
        self.cache.store_to_disk(entry);
    }

    /// Number of cached pipelines.
    pub fn len(&self) -> usize {
        self.cache.len()
    }

    /// Whether any pipelines are cached.
    pub fn is_empty(&self) -> bool {
        self.cache.is_empty()
    }

    /// Drop all cached pipelines while keeping this state usable.
    pub fn clear(&mut self) {
        self.cache.clear();
    }
}
