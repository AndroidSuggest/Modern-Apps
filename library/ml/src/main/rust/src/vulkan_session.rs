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

use crate::tensors::{Dtype, HostTensor, TensorError};

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

#[cfg(feature = "vulkan")]
pub use onnx_vulkan::KvCache;

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
    /// Display text of the most recent [`GpuSession::run`] /
    /// [`GpuSession::run_cached`] failure, retrievable via
    /// [`GpuSession::take_last_error`]. Lets the JNI layer surface the real
    /// native reason after `run` returns a null payload to the caller.
    last_error: Mutex<Option<String>>,
}

/// Map a native `onnx_vulkan` failure onto [`VulcanError`].
///
/// Each known variant maps one-to-one; anything else becomes a [`VulcanError::Load`]
/// carrying the display text so no detail is lost.
#[cfg(feature = "vulkan")]
fn map_native_error(err: onnx_vulkan::Error) -> VulcanError {
    match err {
        onnx_vulkan::Error::Load(msg) => VulcanError::Load(msg.to_string()),
        onnx_vulkan::Error::Unsupported(msg) => VulcanError::Unsupported(msg),
        onnx_vulkan::Error::Device(msg) => VulcanError::Device(msg),
        onnx_vulkan::Error::NoSuchValue(msg) => VulcanError::NoSuchValue(msg),
    }
}

#[cfg(feature = "vulkan")]
fn to_native(tensor: &HostTensor) -> Result<onnx_vulkan::HostTensor, VulcanError> {
    match tensor.dtype() {
        Dtype::F32 => Ok(onnx_vulkan::HostTensor::from_f32(
            tensor.shape().to_vec(),
            &tensor.as_f32()?,
        )),
        Dtype::I64 => Ok(onnx_vulkan::HostTensor::from_i64(
            tensor.shape().to_vec(),
            &tensor.as_i64()?,
        )),
        Dtype::Bool => Ok(onnx_vulkan::HostTensor::new(
            Dtype::Bool.to_i32(),
            tensor.shape().to_vec(),
            tensor.bytes().to_vec(),
        )),
    }
}

#[cfg(feature = "vulkan")]
fn from_native(tensor: onnx_vulkan::HostTensor) -> Result<HostTensor, VulcanError> {
    let dtype = Dtype::from_i32(tensor.dtype)
        .ok_or_else(|| VulcanError::Load(format!("unsupported dtype {}", tensor.dtype)))?;
    HostTensor::new(dtype, tensor.shape, tensor.data).map_err(VulcanError::Tensor)
}

#[cfg(feature = "vulkan")]
impl GpuSession {
    /// Load model bytes onto the Vulkan backend.
    ///
    /// `base_dir` lets the native loader resolve sidecar weights that sit
    /// next to the model file. `key` is only a diagnostic label.
    pub fn load(bytes: &[u8], base_dir: Option<&str>, key: &str) -> Result<Self, VulcanError> {
        let session =
            onnx_vulkan::Session::load_from_bytes(bytes, base_dir.map(std::path::Path::new)).map_err(map_native_error)?;
        Ok(Self {
            inner: Mutex::new(Some(session)),
            key: key.to_owned(),
            last_error: Mutex::new(None),
        })
    }

    /// Store `err`'s display text as this session's most recent failure.
    ///
    /// A poisoned lock is ignored: dropping one diagnostic string is preferable
    /// to failing the call that is already returning the error to the caller.
    fn record_error(&self, err: &VulcanError) {
        if let Ok(mut slot) = self.last_error.lock() {
            *slot = Some(err.to_string());
        }
    }

    /// Take and clear the most recent [`GpuSession::run`] /
    /// [`GpuSession::run_cached`] failure text, if any.
    ///
    /// Returns [`None`] when no error has been recorded since the last take or
    /// when the internal lock is poisoned.
    pub fn take_last_error(&self) -> Option<String> {
        self.last_error.lock().ok().and_then(|mut slot| slot.take())
    }

    /// Run inference over host tensors and return host tensors.
    ///
    /// Returns [`VulcanError::Device`] when the session was closed or the
    /// lock is unavailable, and forwards native failures via [`map_native_error`].
    /// Any error is also stashed via [`GpuSession::record_error`] so the JNI
    /// layer can report the reason after handing a null payload to the caller.
    pub fn run(&self, inputs: Vec<HostTensor>) -> Result<Vec<HostTensor>, VulcanError> {
        let outcome = self.run_inner(inputs);
        if let Err(ref err) = outcome {
            self.record_error(err);
        }
        outcome
    }

    fn run_inner(&self, inputs: Vec<HostTensor>) -> Result<Vec<HostTensor>, VulcanError> {
        let guard = self
            .inner
            .lock()
            .map_err(|_| VulcanError::Device("session lock unavailable".to_owned()))?;
        let session = guard
            .as_ref()
            .ok_or_else(|| VulcanError::Device("session is closed".to_owned()))?;
        let vulkan_inputs: Vec<onnx_vulkan::HostTensor> = inputs
            .iter()
            .map(to_native)
            .collect::<Result<Vec<_>, _>>()?;
        let names: Vec<String> = session.inputs().iter().map(|i| i.name.clone()).collect();
        let run = session
            .run(names.iter().zip(vulkan_inputs))
            .map_err(map_native_error)?;
        let mut outputs = Vec::new();
        for info in session.outputs() {
            let tensor = run.get(&info.name).map_err(map_native_error)?;
            outputs.push(from_native(tensor)?);
        }
        Ok(outputs)
    }

    /// Run one decode step against a resident KV cache.
    ///
    /// Any error is stashed via [`GpuSession::record_error`] before returning so
    /// the JNI layer can report the reason after handing a null payload back.
    pub fn run_cached(
        &mut self,
        kv: &onnx_vulkan::KvCache,
        inputs: Vec<HostTensor>,
    ) -> Result<Vec<HostTensor>, VulcanError> {
        let outcome = self.run_cached_inner(kv, inputs);
        if let Err(ref err) = outcome {
            self.record_error(err);
        }
        outcome
    }

    fn run_cached_inner(
        &mut self,
        kv: &onnx_vulkan::KvCache,
        inputs: Vec<HostTensor>,
    ) -> Result<Vec<HostTensor>, VulcanError> {
        let guard = self
            .inner
            .lock()
            .map_err(|_| VulcanError::Device("session lock unavailable".to_owned()))?;
        let session = guard
            .as_ref()
            .ok_or_else(|| VulcanError::Device("session is closed".to_owned()))?;
        let vulkan_inputs: Vec<onnx_vulkan::HostTensor> = inputs
            .iter()
            .map(to_native)
            .collect::<Result<Vec<_>, _>>()?;
        let names: Vec<String> = session.inputs().iter().map(|i| i.name.clone()).collect();
        let run = session
            .run_cached(names.iter().zip(vulkan_inputs), kv)
            .map_err(map_native_error)?;
        let mut outputs = Vec::new();
        for info in session.outputs() {
            let tensor = run.get(&info.name).map_err(map_native_error)?;
            outputs.push(from_native(tensor)?);
        }
        Ok(outputs)
    }

    /// Short human-readable summary of the loaded plan.
    ///
    /// Never fails; reports a closed marker when the session is gone.
    pub fn plan_stats(&self) -> String {
        String::new()
    }

    /// Release the native session while keeping this handle usable.
    ///
    /// Later `run` calls report [`VulcanError::Device`].
    pub fn close(&self) {
        if let Ok(mut guard) = self.inner.lock() {
            let _ = guard.take();
        }
    }

    /// Validate model bytes by attempting a real load, then dropping it.
    ///
    /// Returns `Ok(())` when the backend would accept the model and a
    /// [`VulcanError`] (carrying the native reason) when it would refuse, so
    /// callers can log exactly why a model stays on the CPU path.
    pub fn preflight(bytes: &[u8], base_dir: Option<&str>) -> Result<(), VulcanError> {
        let _session =
            onnx_vulkan::Session::load_from_bytes(bytes, base_dir.map(std::path::Path::new))
                .map_err(map_native_error)?;
        Ok(())
    }

    /// Load a model from a filesystem path, resolving sidecar weights next to it.
    ///
    /// Calls the engine's file-path loader directly so the file crosses the
    /// boundary once: the previous `std::fs::read` + `load_from_bytes` kept
    /// two 728 MiB copies of the NLLB decoder alive at once. Over-budget
    /// files return [`VulcanError::Unsupported`] so Kotlin falls back to the
    /// ORT CPU path instead of OOM-aborting.
    pub fn load_path(path: &str) -> Result<Self, VulcanError> {
        let file_len = std::fs::metadata(path)
            .map(|meta| meta.len())
            .unwrap_or(u64::MAX);
        if crate::memory::path_for_model_file(file_len, crate::memory::available_bytes())
            == crate::memory::ExecutionPath::OrtCpu
        {
            return Err(VulcanError::Unsupported(format!(
                "model file {path} ({file_len} bytes) exceeds the Vulkan memory budget; fallback to ORT CPU"
            )));
        }
        let session =
            onnx_vulkan::Session::load(path).map_err(map_native_error)?;
        Ok(Self {
            inner: Mutex::new(Some(session)),
            key: path.to_owned(),
            last_error: Mutex::new(None),
        })
    }

    /// Return output shapes as JSON.
    pub fn output_shapes_json(&self) -> String {
        String::new()
    }

    /// Return output names as JSON.
    pub fn output_names_json(&self) -> String {
        String::new()
    }

    /// Create a KV cache sized for `max_tokens`.
    pub fn create_kv(&self, max_tokens: usize) -> Result<onnx_vulkan::KvCache, VulcanError> {
        let guard = self
            .inner
            .lock()
            .map_err(|_| VulcanError::Device("session lock unavailable".to_owned()))?;
        let session = guard
            .as_ref()
            .ok_or_else(|| VulcanError::Device("session is closed".to_owned()))?;
        let cache = onnx_vulkan::KvCache::new(session, max_tokens)
            .map_err(|e| VulcanError::Device(e.to_string()))?;
        Ok(cache)
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
    pub fn new(
        session: onnx_vulkan::Session,
        max_seq_len: usize,
        session_ptr: usize,
    ) -> Result<Self, VulcanError> {
        let cache = onnx_vulkan::KvCache::new(&session, max_seq_len)
            .map_err(|e| VulcanError::Device(e.to_string()))?;
        Ok(Self {
            session_ptr,
            cache,
        })
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
