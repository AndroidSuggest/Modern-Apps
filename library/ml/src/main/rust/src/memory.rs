//! Mobile memory budget helpers for Vulkan weight upload and inference.
//!
//! Pure host-side planning with no GPU dependency: chunking arithmetic, a
//! unified-memory hint for Adreno/Mali parts, and the low-memory signal that
//! routes work to the ORT CPU path. Always compiled, even without the
//! `vulkan` feature, so host checks and unit tests stay dependency-free.
//!
//! The reusable staging-buffer pool lives in [`crate::memory_pool`] and the
//! peak-RSS accounting lives in [`crate::memory_stats`]; both are
//! re-exported here so existing `crate::memory::…` paths keep working. The
//! Vulkan session layer applies these plans when the `vulkan` feature is
//! enabled; this module never touches the device itself.
//!
//! All arithmetic saturates or is checked: every fallible helper returns
//! [`MemoryError`] instead of panicking.

use thiserror::Error;

/// Reusable staging-buffer accounting for chunked uploads.
///
/// Re-exported from [`crate::memory_pool`]; see [`crate::memory_pool`]
/// for the owning documentation.
pub use crate::memory_pool::StagingPool;

/// Current and peak resident-set-size accounting, in bytes.
///
/// Re-exported from [`crate::memory_stats`]; see [`crate::memory_stats`]
/// for the owning documentation.
pub use crate::memory_stats::PeakTracker;

/// Largest single weight-upload chunk: 64 MiB.
///
/// Desktop allocators happily map whole multi-hundred-megabyte blobs; on
/// 4–6 GB phones one giant device allocation is the fastest way to an OOM
/// kill. [`plan_chunks`] splits every upload so no chunk exceeds this.
pub const MAX_CHUNK_BYTES: usize = 64 * 1024 * 1024;

/// Default number of reusable staging buffers in [`StagingPool`].
///
/// Two lets the next chunk upload while the previous one is consumed without
/// pinning much memory on low-end devices.
pub const DEFAULT_STAGING_BUFFERS: usize = 2;

/// Headroom kept free when deciding the low-memory fallback.
///
/// [`select_path`] routes to [`ExecutionPath::OrtCpu`] unless
/// `available_bytes` covers `required_bytes` plus this headroom, so a Vulkan
/// session never starts with less than 256 MiB of breathing room.
pub const LOW_MEMORY_HEADROOM_BYTES: u64 = 256 * 1024 * 1024;

/// Failures reported by the memory-budget helpers.
#[derive(Debug, Error, PartialEq, Eq)]
pub enum MemoryError {
    /// A plan was requested for zero bytes; there is nothing to upload.
    #[error("total bytes must be non-zero")]
    EmptyPlan,
    /// A chunk or staging buffer was sized at zero bytes.
    #[error("chunk length must be non-zero")]
    EmptyChunk,
    /// A chunk index falls outside the plan. [`plan_chunks`] only yields
    /// in-range indices, so this signals an internal accounting bug.
    #[error("chunk index {index} out of range for {count} chunks")]
    ChunkOutOfRange {
        /// Requested chunk index.
        index: usize,
        /// Chunks in the plan.
        count: usize,
    },
    /// Every staging buffer is already acquired.
    #[error("staging pool exhausted: {in_use}/{capacity} buffers in use")]
    PoolExhausted {
        /// Buffers currently acquired.
        in_use: usize,
        /// Maximum buffers the pool holds.
        capacity: usize,
    },
    /// A buffer was released with none acquired.
    #[error("staging pool release with no buffer in use")]
    PoolOverRelease,
    /// Bytes were released that were never tracked as live.
    #[error("release of {release} bytes exceeds tracked {current} bytes")]
    UntrackedRelease {
        /// Bytes the caller tried to release.
        release: u64,
        /// Bytes currently tracked as live.
        current: u64,
    },
}

/// One contiguous window of a larger upload, in bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Chunk {
    /// Byte offset of the window within the whole buffer.
    offset: usize,
    /// Length of the window in bytes; always non-zero.
    len: usize,
}

impl Chunk {
    /// Build a chunk from an offset and a non-zero length.
    pub fn new(offset: usize, len: usize) -> Result<Self, MemoryError> {
        if len == 0 {
            return Err(MemoryError::EmptyChunk);
        }
        Ok(Self { offset, len })
    }

    /// Return the byte offset of the window.
    pub fn offset(self) -> usize {
        self.offset
    }

    /// Return the window length in bytes.
    pub fn len(self) -> usize {
        self.len
    }

    /// Return one past the last byte; saturates instead of overflowing.
    pub fn end(self) -> usize {
        self.offset.saturating_add(self.len)
    }
}

/// How many `<= MAX_CHUNK_BYTES` chunks cover `total_bytes`.
///
/// Returns [`MemoryError::EmptyPlan`] for zero input.
pub fn chunk_count(total_bytes: usize) -> Result<usize, MemoryError> {
    if total_bytes == 0 {
        return Err(MemoryError::EmptyPlan);
    }
    let full = total_bytes / MAX_CHUNK_BYTES;
    let extra = if total_bytes % MAX_CHUNK_BYTES == 0 {
        0
    } else {
        1
    };
    Ok(full.saturating_add(extra))
}

/// Offset and length of chunk `index` within `total_bytes`.
///
/// Returns [`None`] for zero totals or out-of-range indices.
pub fn chunk_range(total_bytes: usize, index: usize) -> Option<(usize, usize)> {
    if total_bytes == 0 {
        return None;
    }
    let count = chunk_count(total_bytes).ok()?;
    if index >= count {
        return None;
    }
    let offset = index.saturating_mul(MAX_CHUNK_BYTES);
    let len = total_bytes.saturating_sub(offset).min(MAX_CHUNK_BYTES);
    if len == 0 {
        return None;
    }
    Some((offset, len))
}

/// Split `total_bytes` into contiguous `<= MAX_CHUNK_BYTES` chunks.
///
/// The chunks tile the range exactly: each chunk starts where the previous
/// one ends and the lengths sum to `total_bytes`. Callers pass real buffer
/// lengths; absurd inputs are rejected ([`MemoryError::EmptyPlan`]) or
/// reported ([`MemoryError::ChunkOutOfRange`]), never panicked on.
pub fn plan_chunks(total_bytes: usize) -> Result<Vec<Chunk>, MemoryError> {
    let count = chunk_count(total_bytes)?;
    let mut out = Vec::new();
    for index in 0..count {
        match chunk_range(total_bytes, index) {
            Some((offset, len)) => out.push(Chunk { offset, len }),
            None => {
                return Err(MemoryError::ChunkOutOfRange { index, count });
            }
        }
    }
    Ok(out)
}

/// What kind of memory the GPU exposes, from its advertised device name.
///
/// Adreno (Qualcomm) and Mali (ARM) parts share one physical pool between
/// CPU and GPU, so uploads can prefer host-visible memory and skip a staging
/// copy; discrete GPUs still need the staging path.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GpuMemoryKind {
    /// No usable name was supplied, or nothing matched a known part.
    /// Callers take the conservative (discrete) path.
    Unknown,
    /// Qualcomm Adreno part with CPU/GPU unified memory.
    AdrenoUnified,
    /// ARM Mali part with CPU/GPU unified memory.
    MaliUnified,
    /// Any other device; assumed to need staged, device-local uploads.
    Discrete,
}

/// Check whether lowercased `haystack` contains lowercased `needle`.
fn contains_bytes(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() {
        return false;
    }
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

/// Classify an advertised Vulkan device name, case-insensitively.
///
/// Matches the substrings `adreno` and `mali`; empty names yield
/// [`GpuMemoryKind::Unknown`] and anything else
/// [`GpuMemoryKind::Discrete`].
pub fn hint_for_device_name(name: &str) -> GpuMemoryKind {
    if name.is_empty() {
        return GpuMemoryKind::Unknown;
    }
    let lowered: Vec<u8> = name.bytes().map(|b| b.to_ascii_lowercase()).collect();
    if contains_bytes(&lowered, b"adreno") {
        GpuMemoryKind::AdrenoUnified
    } else if contains_bytes(&lowered, b"mali") {
        GpuMemoryKind::MaliUnified
    } else {
        GpuMemoryKind::Discrete
    }
}

/// Whether uploads to `kind` should prefer host-visible memory.
///
/// True for [`GpuMemoryKind::AdrenoUnified`] and
/// [`GpuMemoryKind::MaliUnified`]; false for [`GpuMemoryKind::Unknown`] and
/// [`GpuMemoryKind::Discrete`], which keep the staging-copy path.
pub fn prefers_host_visible(kind: GpuMemoryKind) -> bool {
    match kind {
        GpuMemoryKind::AdrenoUnified | GpuMemoryKind::MaliUnified => true,
        GpuMemoryKind::Unknown | GpuMemoryKind::Discrete => false,
    }
}

/// Execution path selected from a memory budget.
///
/// The low-memory signal: Vulkan when the budget fits, otherwise the ORT CPU
/// fallback the Kotlin layer already knows how to drive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExecutionPath {
    /// Enough memory for the upload plus headroom; run on Vulkan.
    Vulkan,
    /// Too little memory; route to the ORT CPU fallback.
    OrtCpu,
}

/// Select the execution path from `available_bytes` vs `required_bytes`.
///
/// Vulkan wins only when `available_bytes` covers `required_bytes` plus
/// [`LOW_MEMORY_HEADROOM_BYTES`]; the addition is checked, so gigantic
/// requirements that would overflow safely resolve to
/// [`ExecutionPath::OrtCpu`].
pub fn select_path(available_bytes: u64, required_bytes: u64) -> ExecutionPath {
    match required_bytes.checked_add(LOW_MEMORY_HEADROOM_BYTES) {
        Some(need) if available_bytes >= need => ExecutionPath::Vulkan,
        _ => ExecutionPath::OrtCpu,
    }
}

/// True when the budget says to route to the ORT CPU fallback.
pub fn should_fallback_to_ort(available_bytes: u64, required_bytes: u64) -> bool {
    select_path(available_bytes, required_bytes) == ExecutionPath::OrtCpu
}

/// Largest model file the Vulkan path attempts without consulting free memory.
///
/// Above this size [`path_for_model_file`] routes to
/// [`ExecutionPath::OrtCpu`] unconditionally: a 728 MiB NLLB decoder plus its
/// transient protobuf/IR copies cannot fit a 4–6 GB phone's budget alongside
/// the [`LOW_MEMORY_HEADROOM_BYTES`] headroom.
pub const VULKAN_MODEL_FILE_CAP_BYTES: u64 = 512 * 1024 * 1024;

/// Estimate of currently free host memory in bytes.
///
/// Std-only: parses `MemAvailable` from `/proc/meminfo` (present on Android
/// and desktop Linux). Returns [`None`] where the file is absent or
/// unparsable so callers fall back to the conservative
/// [`VULKAN_MODEL_FILE_CAP_BYTES`] cap instead of guessing.
pub fn available_bytes() -> Option<u64> {
    let text = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in text.lines() {
        let mut parts = line.split_whitespace();
        if parts.next() == Some("MemAvailable:") {
            let kilobytes: u64 = parts.next()?.parse().ok()?;
            return kilobytes.checked_mul(1024);
        }
    }
    None
}

/// Execution path for a model file of `file_len_bytes` on-disk bytes.
///
/// Over-cap files always route to [`ExecutionPath::OrtCpu`]; under-cap files
/// defer to [`select_path`] when `available` is known and attempt Vulkan when
/// it is not (the cap already bounds that risk). The caller maps `OrtCpu` to
/// a fallback error, never an abort.
pub fn path_for_model_file(file_len_bytes: u64, available: Option<u64>) -> ExecutionPath {
    if file_len_bytes > VULKAN_MODEL_FILE_CAP_BYTES {
        return ExecutionPath::OrtCpu;
    }
    match available {
        Some(avail) => select_path(avail, file_len_bytes),
        None => ExecutionPath::Vulkan,
    }
}

/// Unit tests for the memory-budget helpers.
///
/// These run without the `vulkan` feature: this module has no GPU
/// dependency. Like `tensors`, every test returns a [`Result`] and reports
/// mismatches as strings instead of using `assert!` or `unwrap`, per the
/// workspace `panic` / `unwrap_used` lints.
///
/// Pool accounting is tested in [`crate::memory_pool`] and peak-RSS
/// accounting in [`crate::memory_stats`].
#[cfg(test)]
mod tests {
    use super::{
        Chunk, ExecutionPath, GpuMemoryKind, MemoryError, chunk_count, chunk_range,
        hint_for_device_name, path_for_model_file, plan_chunks, prefers_host_visible,
        select_path, should_fallback_to_ort,
    };
    use super::{LOW_MEMORY_HEADROOM_BYTES, MAX_CHUNK_BYTES, VULKAN_MODEL_FILE_CAP_BYTES};

    #[test]
    fn count_rejects_zero() -> Result<(), String> {
        match chunk_count(0) {
            Err(MemoryError::EmptyPlan) => Ok(()),
            Err(other) => Err(other.to_string()),
            Ok(count) => Err(format!("expected EmptyPlan, got {count}")),
        }
    }

    #[test]
    fn count_splits_at_boundary() -> Result<(), String> {
        let exact = chunk_count(MAX_CHUNK_BYTES).map_err(|err| err.to_string())?;
        if exact != 1 {
            return Err(format!("expected 1 chunk for exactly MAX, got {exact}"));
        }
        let over = MAX_CHUNK_BYTES.saturating_add(1);
        let split = chunk_count(over).map_err(|err| err.to_string())?;
        if split != 2 {
            return Err(format!("expected 2 chunks for MAX+1, got {split}"));
        }
        Ok(())
    }

    #[test]
    fn plan_covers_total_without_gaps() -> Result<(), String> {
        let total = MAX_CHUNK_BYTES.saturating_mul(2).saturating_add(7);
        let chunks = plan_chunks(total).map_err(|err| err.to_string())?;
        let mut covered: usize = 0;
        let mut count: usize = 0;
        for chunk in &chunks {
            if chunk.len() > MAX_CHUNK_BYTES {
                return Err("chunk exceeds MAX_CHUNK_BYTES".to_owned());
            }
            if chunk.offset() != covered {
                return Err("gap or overlap between chunks".to_owned());
            }
            covered = covered.saturating_add(chunk.len());
            count = count.saturating_add(1);
        }
        if covered != total {
            return Err("chunk lengths do not sum to total".to_owned());
        }
        if count != 3 {
            return Err(format!("expected 3 chunks, got {count}"));
        }
        Ok(())
    }

    #[test]
    fn range_rejects_out_of_bounds() -> Result<(), String> {
        if chunk_range(0, 0).is_some() {
            return Err("zero total should yield no range".to_owned());
        }
        if chunk_range(10, 5).is_some() {
            return Err("out-of-range index should yield no range".to_owned());
        }
        match chunk_range(10, 0) {
            Some((0, 10)) => Ok(()),
            Some((offset, len)) => Err(format!("wrong range {offset}..{len}")),
            None => Err("valid index should yield a range".to_owned()),
        }
    }

    #[test]
    fn chunk_rejects_empty_and_reports_end() -> Result<(), String> {
        match Chunk::new(8, 0) {
            Err(MemoryError::EmptyChunk) => {}
            Err(other) => return Err(other.to_string()),
            Ok(_) => return Err("zero-length chunk should fail".to_owned()),
        }
        let chunk = Chunk::new(8, 4).map_err(|err| err.to_string())?;
        if chunk.end() != 12 {
            return Err("chunk end should be offset + len".to_owned());
        }
        Ok(())
    }

    #[test]
    fn hints_classify_device_names() -> Result<(), String> {
        if hint_for_device_name("Qualcomm Adreno 740") != GpuMemoryKind::AdrenoUnified {
            return Err("adreno name should map to AdrenoUnified".to_owned());
        }
        if hint_for_device_name("ARM Mali-G710") != GpuMemoryKind::MaliUnified {
            return Err("mali name should map to MaliUnified".to_owned());
        }
        if hint_for_device_name("") != GpuMemoryKind::Unknown {
            return Err("empty name should map to Unknown".to_owned());
        }
        if hint_for_device_name("NVIDIA GeForce RTX 4090") != GpuMemoryKind::Discrete {
            return Err("unknown part should map to Discrete".to_owned());
        }
        if !prefers_host_visible(GpuMemoryKind::AdrenoUnified)
            || !prefers_host_visible(GpuMemoryKind::MaliUnified)
        {
            return Err("unified parts should prefer host-visible memory".to_owned());
        }
        if prefers_host_visible(GpuMemoryKind::Unknown)
            || prefers_host_visible(GpuMemoryKind::Discrete)
        {
            return Err("unknown/discrete parts should keep the staging path".to_owned());
        }
        Ok(())
    }

    #[test]
    fn select_path_falls_back_when_tight() -> Result<(), String> {
        if select_path(u64::MAX, 1) != ExecutionPath::Vulkan {
            return Err("ample memory should select Vulkan".to_owned());
        }
        let tight = LOW_MEMORY_HEADROOM_BYTES
            .saturating_add(100)
            .saturating_sub(1);
        if !should_fallback_to_ort(tight, 100) {
            return Err("sub-headroom budget should fall back to ORT".to_owned());
        }
        if should_fallback_to_ort(u64::MAX, u64::MAX) != true {
            return Err("gigantic requirement should fall back to ORT".to_owned());
        }
        Ok(())
    }

    #[test]
    fn model_file_gate_refuses_over_cap() -> Result<(), String> {
        // 728 MiB NLLB decoder: over the 512 MiB cap, refused outright.
        let decoder = 728 * 1024 * 1024;
        if decoder <= VULKAN_MODEL_FILE_CAP_BYTES {
            return Err("test assumes a 728 MiB model exceeds the cap".to_owned());
        }
        if path_for_model_file(decoder, Some(u64::MAX)) != ExecutionPath::OrtCpu {
            return Err("over-cap model should route to ORT even with free memory".to_owned());
        }
        // Under-cap file with ample memory stays on Vulkan.
        let small: u64 = 100 * 1024 * 1024;
        let ample = small
            .saturating_add(LOW_MEMORY_HEADROOM_BYTES)
            .saturating_add(1);
        if path_for_model_file(small, Some(ample)) != ExecutionPath::Vulkan {
            return Err("small model with ample memory should stay on Vulkan".to_owned());
        }
        // Tight memory still routes the small file to ORT.
        if path_for_model_file(small, Some(small)) != ExecutionPath::OrtCpu {
            return Err("small model with tight memory should route to ORT".to_owned());
        }
        // Unknown free memory: cap alone decides.
        if path_for_model_file(small, None) != ExecutionPath::Vulkan {
            return Err("unknown memory with an under-cap file should attempt Vulkan".to_owned());
        }
        Ok(())
    }
}
