//! Vulkan-backed ONNX session wrapper.
//!
//! This module isolates every direct dependency on the `onnx_vulkan` crate
//! behind the `vulkan` Cargo feature so the rest of the crate keeps building
//! when Vulkan support is disabled.
//!
//! With `vulkan` enabled, [`GpuSession`] owns a live `onnx_vulkan::Session`
//! behind a [`Mutex`]. Without `vulkan`, the same public names exist but
//! every constructor and runner returns [`VulcanError::Unsupported`].

use std::sync::Mutex;

use thiserror::Error;

use crate::tensors::{HostTensor, TensorError};

/// Error type for Vulkan session work.
///
/// Keeps load, capability, device, and lookup failures distinct so callers
/// can decide whether to fall back to CPU execution or surface a message.
#[derive(Debug, Error)]
pub enum VulcanError {
    /// Model bytes or the base directory could not be loaded.
    #[error("vulkan load failed: {0}")]
    Load(String),
    /// The operation or model shape is not supported on this device.
    #[error("unsupported on vulkan backend: {0}")]
    Unsupported(String),
    /// The Vulkan device or queue reported a failure.
    #[error("vulkan device error: {0}")]
    Device(String),
    /// A named value requested from the session was not present.
    #[error("no such value: {0}")]
    NoSuchValue(String),
    /// Conversion to or from [`HostTensor`] failed.
    #[error(transparent)]
    Tensor(#[from] TensorError),
}

/// Canonical spelling alias for [`VulcanError`].
pub type VulkanError = VulcanError;

// ---------------------------------------------------------------------------
// Vulkan-enabled implementation.
// ---------------------------------------------------------------------------

/// GPU-backed ONNX session.
///
/// Holds the native session inside a [`Mutex`] so `run` can borrow it
/// through a shared reference while `close` can release it.
#[cfg(feature = "vulkan")]
pub struct GpuSession {
    /// Live session, or [`None`] after [`GpuSession::close`].
    inner: Mutex<Option<onnx_vulkan::Session>>,
    /// Caller-supplied label used in diagnostics and stats.
    key: String,
}

/// Map a native `onnx_vulkan` failure onto [`VulcanError`].
///
/// Each known variant maps one-to-one; anything else becomes a [`VulcanError::Load`]
/// carrying the display text so no detail is lost.
#[cfg(feature = "vulkan")]
fn map_native_error(err: onnx_vulkan::Error) -> VulcanError {
    match err {
        onnx_vulkan::Error::Load(msg) => VulcanError::Load(msg),
        onnx_vulkan::Error::Unsupported(msg) => VulcanError::Unsupported(msg),
        onnx_vulkan::Error::Device(msg) => VulcanError::Device(msg),
        onnx_vulkan::Error::NoSuchValue(msg) => VulcanError::NoSuchValue(msg),
        other => VulcanError::Load(other.to_string()),
    }
}

#[cfg(feature = "vulkan")]
impl GpuSession {
    /// Load model bytes onto the Vulkan backend.
    ///
    /// `base_dir` lets the native loader resolve sidecar weights that sit
    /// next to the model file. `key` is only a diagnostic label.
    pub fn load(bytes: &[u8], base_dir: Option<&str>, key: &str) -> Result<Self, VulcanError> {
        let session = onnx_vulkan::Session::from_bytes(bytes, base_dir).map_err(map_native_error)?;
        Ok(Self {
            inner: Mutex::new(Some(session)),
            key: key.to_owned(),
        })
    }

    /// Run inference over host tensors and return host tensors.
    ///
    /// Returns [`VulcanError::Device`] when the session was closed or the
    /// lock is unavailable, and forwards native failures via [`map_native_error`].
    pub fn run(&self, inputs: Vec<HostTensor>) -> Result<Vec<HostTensor>, VulcanError> {
        let guard = self
            .inner
            .lock()
            .map_err(|_| VulcanError::Device("session lock unavailable".to_owned()))?;
        let session = guard
            .as_ref()
            .ok_or_else(|| VulcanError::Device("session is closed".to_owned()))?;
        let gpu_inputs: Vec<onnx_vulkan::GpuTensor> =
            inputs.into_iter().map(onnx_vulkan::GpuTensor::from).collect();
        let gpu_outputs = session.run(gpu_inputs).map_err(map_native_error)?;
        gpu_outputs
            .into_iter()
            .map(|tensor| HostTensor::try_from(tensor).map_err(VulcanError::from))
            .collect()
    }

    /// Short human-readable summary of the loaded plan.
    ///
    /// Never fails; reports a closed marker when the session is gone.
    pub fn plan_stats(&self) -> String {
        match self.inner.lock() {
            Ok(guard) => match guard.as_ref() {
                Some(session) => format!("key={} plan={}", self.key, session.plan_stats()),
                None => format!("key={} plan=closed", self.key),
            },
            Err(_) => format!("key={} plan=unavailable", self.key),
        }
    }

    /// Release the native session while keeping this handle usable.
    ///
    /// Later `run` calls report [`VulcanError::Device`].
    pub fn close(&self) {
        if let Ok(mut guard) = self.inner.lock() {
            guard.take();
        }
    }
}

/// Minimal key-value cache handle tied to a session address.
///
/// `session_ptr` records the originating [`GpuSession`] as a raw address so
/// the cache can be matched back without holding a borrow. The native cache
/// owns all GPU-side state.
#[cfg(feature = "vulkan")]
pub struct KvState {
    /// Address of the owning session, stored as an integer.
    session_ptr: usize,
    /// Native key-value cache storage.
    cache: onnx_vulkan::KvCache,
}

#[cfg(feature = "vulkan")]
impl KvState {
    /// Create an empty cache for the session at `session_ptr`.
    pub fn new(session_ptr: usize) -> Self {
        Self {
            session_ptr,
            cache: onnx_vulkan::KvCache::new(),
        }
    }

    /// Return the owning session address.
    pub fn session_ptr(&self) -> usize {
        self.session_ptr
    }

    /// Borrow the native cache.
    pub fn cache(&self) -> &onnx_vulkan::KvCache {
        &self.cache
    }

    /// Borrow the native cache mutably.
    pub fn cache_mut(&mut self) -> &mut onnx_vulkan::KvCache {
        &mut self.cache
    }

    /// Drop all cached entries while keeping the handle usable.
    pub fn clear(&mut self) {
        self.cache.clear();
    }
}

// ---------------------------------------------------------------------------
// No-Vulkan stubs: keep the crate compiling without the native dependency.
// ---------------------------------------------------------------------------

/// GPU-backed ONNX session placeholder used when `vulkan` is disabled.
///
/// Every operation reports [`VulcanError::Unsupported`].
#[cfg(not(feature = "vulkan"))]
pub struct GpuSession {
    /// Diagnostic label retained so `plan_stats` stays useful.
    key: String,
}

#[cfg(not(feature = "vulkan"))]
impl GpuSession {
    /// Stub loader that always reports unsupported.
    pub fn load(_bytes: &[u8], _base_dir: Option<&str>, key: &str) -> Result<Self, VulcanError> {
        Err(VulcanError::Unsupported(format!(
            "vulkan backend disabled (key={key})"
        )))
    }

    /// Stub runner that always reports unsupported.
    pub fn run(&self, _inputs: Vec<HostTensor>) -> Result<Vec<HostTensor>, VulcanError> {
        Err(VulcanError::Unsupported(format!(
            "vulkan backend disabled (key={})",
            self.key
        )))
    }

    /// Stub stats string for the disabled backend.
    pub fn plan_stats(&self) -> String {
        format!("key={} plan=vulkan-disabled", self.key)
    }

    /// Stub close; nothing to release.
    pub fn close(&self) {}
}

/// Minimal key-value cache placeholder used when `vulkan` is disabled.
#[cfg(not(feature = "vulkan"))]
pub struct KvState {
    /// Address of the owning session, stored as an integer.
    session_ptr: usize,
}

#[cfg(not(feature = "vulkan"))]
impl KvState {
    /// Create an empty placeholder for the session at `session_ptr`.
    pub fn new(session_ptr: usize) -> Self {
        Self { session_ptr }
    }

    /// Return the owning session address.
    pub fn session_ptr(&self) -> usize {
        self.session_ptr
    }

    /// Discard cached entries; a no-op while disabled.
    pub fn clear(&mut self) {}
}

// ---------------------------------------------------------------------------
// Mobile shader fallback wiring (Task 4, owner: shader-dev).
// ---------------------------------------------------------------------------
// Additive-only section: re-uses `crate::shaders_mobile` (host-side policy
// with no GPU dependency) to give sessions a thermal-aware pipeline cache and
// a warmup plan that hides the first-run compile hitch. Nothing above this
// marker is modified.

use crate::shaders_mobile::{
    CachedPipeline, DeviceCaps, DispatchChunk, MobileShaderError, PipelineCache,
    PipelineCacheKey, ShaderPath, WarmupEntry, chunk_dispatch, select_path, warmup_plan,
    workgroup_for_path,
};
use crate::tensors::Dtype;

/// Mobile fallback state kept alongside a [`GpuSession`].
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
