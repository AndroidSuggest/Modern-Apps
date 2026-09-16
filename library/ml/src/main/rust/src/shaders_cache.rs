//! In-memory pipeline cache for the mobile Vulkan backend.
//!
//! Extracted from [`crate::shaders_mobile`] (Task 6) so no single `.rs`
//! exceeds 500 lines. Host-side policy only: no GPU dependency, so it
//! compiles with and without the `vulkan` Cargo feature.
//!
//! The cache absorbs the first-run compile hitch: after
//! [`crate::shaders_mobile::warmup_plan`] pre-populates it, steady-state
//! inference hits [`PipelineCache::lookup`] instead of the driver compiler.

use std::collections::HashMap;

use crate::shaders_mobile::{DeviceCaps, ShaderPath};

/// Cache key identifying one compiled pipeline.
///
/// The triple `(model_hash, path, caps_fingerprint)` means a model update, a
/// shader-path change, or a device/driver change each maps to a distinct entry
/// instead of colliding with a stale pipeline.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct PipelineCacheKey {
    /// Hash of the model bytes the pipeline was compiled for.
    pub model_hash: u64,
    /// Shader variant the pipeline was compiled from.
    pub path: ShaderPath,
    /// [`DeviceCaps::fingerprint`] of the device it was compiled for.
    pub caps_fingerprint: u64,
}

impl PipelineCacheKey {
    /// Build a key from its three legs, fingerprinting `caps` inline.
    pub fn new(model_hash: u64, path: ShaderPath, caps: &DeviceCaps) -> Self {
        Self {
            model_hash,
            path,
            caps_fingerprint: caps.fingerprint(),
        }
    }
}

/// One cached pipeline entry: its key plus the tuning it was built with.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CachedPipeline {
    /// Key this entry is stored under.
    pub key: PipelineCacheKey,
    /// Workgroup the pipeline was created with (see
    /// [`crate::shaders_workgroup::workgroup_for_path`]).
    pub workgroup: [u32; 3],
    /// Row budget per dispatch used with this pipeline (see
    /// [`crate::shaders_mobile::chunk_dispatch`]).
    pub chunk_rows: u64,
}

impl CachedPipeline {
    /// Build an entry; never panics.
    pub const fn new(key: PipelineCacheKey, workgroup: [u32; 3], chunk_rows: u64) -> Self {
        Self {
            key,
            workgroup,
            chunk_rows,
        }
    }
}

/// In-memory pipeline cache keyed by ([`PipelineCacheKey`]).
///
/// Lives for the duration of the process (or the owning session) and absorbs
/// the first-run compile hitch after
/// [`crate::shaders_mobile::warmup_plan`] pre-populates it: steady state
/// inference should always hit [`PipelineCache::lookup`].
#[derive(Debug, Default)]
pub struct PipelineCache {
    /// Compiled entries by key.
    entries: HashMap<PipelineCacheKey, CachedPipeline>,
}

impl PipelineCache {
    /// Create an empty cache.
    pub fn new() -> Self {
        Self {
            entries: HashMap::new(),
        }
    }

    /// Look up the entry for `key`, or `None` on a miss.
    ///
    /// A miss on first run is expected (the driver has not compiled this
    /// (model, path, caps) triple yet); warm the cache via
    /// [`crate::shaders_mobile::warmup_plan`].
    pub fn lookup(&self, key: &PipelineCacheKey) -> Option<&CachedPipeline> {
        self.entries.get(key)
    }

    /// Insert or replace the entry for its own key.
    pub fn insert(&mut self, entry: CachedPipeline) {
        let _ = self.entries.insert(entry.key.clone(), entry);
    }

    /// Number of entries currently held.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// Whether the cache holds no entries.
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Drop all entries while keeping the handle usable.
    pub fn clear(&mut self) {
        self.entries.clear();
    }

    /// Disk-cache stub: pretend to fetch `key` from persistent storage.
    ///
    /// Always returns `None` for now. This is the seam where a future change
    /// will read `vkPipelineCache` blobs (or compiled WGSL artifacts) from
    /// the app cache dir; the key already carries everything needed to
    /// namespace the file (`model_hash` / `path` / `caps_fingerprint`).
    pub fn load_from_disk(&self, _key: &PipelineCacheKey) -> Option<CachedPipeline> {
        None
    }

    /// Disk-cache stub: pretend to persist `entry` to disk.
    ///
    /// Currently a no-op accepted for API stability so callers can be written
    /// against the persistent-cache flow before the filesystem backing lands.
    /// It performs no I/O and never fails.
    pub fn store_to_disk(&self, _entry: &CachedPipeline) {}
}

/// Unit tests for the pipeline cache.
///
/// These run without the `vulkan` feature: this module has no GPU dependency.
/// Every test returns a [`Result`] and reports mismatches as
/// [`crate::shaders_mobile::MobileShaderError::ExpectationFailed`] instead of
/// using `assert!` or `unwrap`, per the workspace lint denies.
#[cfg(test)]
mod tests {
    use super::{CachedPipeline, PipelineCache, PipelineCacheKey};
    use crate::shaders_mobile::{DeviceCaps, GpuVendor, MobileShaderError, ShaderPath};

    fn expect(condition: bool, what: &str) -> Result<(), MobileShaderError> {
        if condition {
            Ok(())
        } else {
            Err(MobileShaderError::ExpectationFailed(what.to_owned()))
        }
    }

    fn nvidia_caps() -> DeviceCaps {
        DeviceCaps::new(GpuVendor::NVIDIA_ID, true, true, true, 256)
    }

    #[test]
    fn cache_key_covers_model_path_and_caps() -> Result<(), MobileShaderError> {
        let caps = nvidia_caps();
        let hit = PipelineCacheKey::new(0xA11CE, ShaderPath::CoopMatrix16, &caps);
        let mut cache = PipelineCache::new();
        expect(cache.is_empty(), "starts empty")?;
        cache.insert(CachedPipeline::new(hit.clone(), [128, 1, 1], 1024));
        expect(cache.len() == 1, "insert grows cache")?;
        expect(cache.lookup(&hit).is_some(), "same triple hits")?;
        let other_model = PipelineCacheKey::new(0xBEEF, ShaderPath::CoopMatrix16, &caps);
        expect(cache.lookup(&other_model).is_none(), "model hash splits")?;
        let other_path = PipelineCacheKey::new(0xA11CE, ShaderPath::WgslFallback, &caps);
        expect(cache.lookup(&other_path).is_none(), "path splits")?;
        let other_caps = PipelineCacheKey::new(
            0xA11CE,
            ShaderPath::CoopMatrix16,
            &DeviceCaps::conservative(),
        );
        expect(cache.lookup(&other_caps).is_none(), "caps split")?;
        expect(cache.load_from_disk(&hit).is_none(), "disk stub misses")?;
        cache.clear();
        expect(cache.is_empty(), "clear empties cache")
    }
}
