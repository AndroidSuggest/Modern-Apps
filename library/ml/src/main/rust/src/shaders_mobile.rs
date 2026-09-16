//! Mobile shader + pipeline fallbacks for the Vulkan backend.
//!
//! Desktop Vulkan shaders assume wide workgroups (e.g. `16x16` = 256
//! invocations), always-present cooperative-matrix extensions, and generous
//! thermal headroom. None of that holds on Android: Adreno and Mali parts can
//! reject 256-invocation workgroups, expose no cooperative-matrix extension,
//! and throttle hard when a single dispatch runs long enough to heat the SoC.
//!
//! This module is the host-side policy for those constraints. It has no GPU
//! dependency, so it compiles with and without the `vulkan` Cargo feature and
//! its unit tests run under plain `cargo test -p ml_vulkan`.
//!
//! # First-run compile hitch
//!
//! The first `vkCreateComputePipelines` call for a given (module,
//! [`ShaderPath`]) pair blocks while the driver compiles the pipeline — tens
//! to low hundreds of milliseconds on current Adreno/Mali drivers. That hitch
//! lands on the first inference unless the app warms the pipelines up front.
//! Call [`warmup_plan`] during the loading screen (or any other
//! non-interactive window) and create one pipeline per returned
//! [`WarmupEntry`]; later inferences then hit the in-memory
//! [`PipelineCache`] instead of the driver compiler.
//!
//! # Layout
//!
//! * [`DeviceCaps`] + [`select_path`] — pick a [`ShaderPath`] per device and
//!   dtype, with cooperative-matrix gated to NVIDIA + extension.
//! * [`crate::shaders_workgroup`] — mobile workgroup sizes (64/128
//!   invocations, never desktop `16x16`); re-exported here.
//! * [`crate::shaders_cache`] — in-memory cache keyed by (model hash, path,
//!   caps) with a disk-cache stub; re-exported here.
//! * [`chunk_dispatch`] — split large matmuls into thermal-friendly dispatches.

use thiserror::Error;

use crate::tensors::Dtype;

// Re-exported so existing `crate::shaders_mobile::…` paths keep working
// (notably `vulkan_session.rs`, which this split must not break).

/// Small mobile workgroup total (`64x1x1`); see [`crate::shaders_workgroup`].
pub use crate::shaders_workgroup::MOBILE_WORKGROUP_SMALL;
/// Large mobile workgroup total (`128x1x1`); see [`crate::shaders_workgroup`].
pub use crate::shaders_workgroup::MOBILE_WORKGROUP_LARGE;
/// Validate a 1-D mobile workgroup total; see [`crate::shaders_workgroup`].
pub use crate::shaders_workgroup::workgroup_shape;
/// Choose the `[x, y, z]` workgroup; see [`crate::shaders_workgroup`].
pub use crate::shaders_workgroup::workgroup_for_path;
/// One cached pipeline entry; see [`crate::shaders_cache`].
pub use crate::shaders_cache::CachedPipeline;
/// In-memory pipeline cache; see [`crate::shaders_cache`].
pub use crate::shaders_cache::PipelineCache;
/// Cache key identifying one compiled pipeline; see [`crate::shaders_cache`].
pub use crate::shaders_cache::PipelineCacheKey;

/// Failures reported by the mobile shader fallback policy.
///
/// Selection itself is infallible ([`select_path`] always returns a usable
/// path); these errors cover invalid tuning inputs and test expectations.
#[derive(Debug, Error, PartialEq, Eq)]
pub enum MobileShaderError {
    /// A workgroup total other than the mobile-supported 64/128 was requested.
    #[error("unsupported workgroup total: {0} (mobile allows 64 or 128 invocations)")]
    UnsupportedWorkgroup(u32),
    /// A chunked-dispatch row budget of zero was supplied.
    #[error("chunk row budget must be non-zero")]
    ZeroRowBudget,
    /// A unit-test expectation did not hold; carries a description.
    ///
    /// Only produced by the tests in this module, never by library logic, so
    /// tests can return `Result` instead of panicking (the workspace denies
    /// `panic` / `unwrap_used`).
    #[error("expectation failed: {0}")]
    ExpectationFailed(String),
}

/// GPU vendor classes relevant to mobile shader selection.
///
/// Derived from the Vulkan `vendorID` via [`GpuVendor::from_vendor_id`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum GpuVendor {
    /// NVIDIA (`vendorID == 0x10DE`); the only vendor eligible for
    /// [`ShaderPath::CoopMatrix16`].
    Nvidia,
    /// Qualcomm Adreno (`vendorID == 0x5143`); always [`ShaderPath::WgslFallback`].
    QualcommAdreno,
    /// ARM Mali (`vendorID == 0x13B5`); always [`ShaderPath::WgslFallback`].
    ArmMali,
    /// Imagination PowerVR (`vendorID == 0x1010`).
    ImaginationPowerVr,
    /// Any other or unrecognized vendor; never gets cooperative-matrix.
    Unknown,
}

impl GpuVendor {
    /// PCI-SIG / Khronos vendor ID for NVIDIA.
    pub const NVIDIA_ID: u32 = 0x10DE;
    /// Khronos vendor ID for Qualcomm (Adreno).
    pub const QUALCOMM_ID: u32 = 0x5143;
    /// Khronos vendor ID for ARM (Mali).
    pub const ARM_ID: u32 = 0x13B5;
    /// Khronos vendor ID for Imagination Technologies (PowerVR).
    pub const IMAGINATION_ID: u32 = 0x1010;

    /// Classify a Vulkan `vendorID` without panicking.
    ///
    /// Unrecognized IDs map to [`GpuVendor::Unknown`], which takes the same
    /// conservative path as Adreno/Mali (never cooperative-matrix).
    pub const fn from_vendor_id(vendor_id: u32) -> Self {
        match vendor_id {
            Self::NVIDIA_ID => Self::Nvidia,
            Self::QUALCOMM_ID => Self::QualcommAdreno,
            Self::ARM_ID => Self::ArmMali,
            Self::IMAGINATION_ID => Self::ImaginationPowerVr,
            _ => Self::Unknown,
        }
    }
}

/// Host-side snapshot of the device capabilities that drive shader selection.
///
/// Built once at session setup from the Vulkan physical-device properties and
/// extension list, then threaded through [`select_path`],
/// [`PipelineCacheKey::new`], and [`warmup_plan`].
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct DeviceCaps {
    /// Classified vendor; gates cooperative-matrix eligibility.
    pub vendor: GpuVendor,
    /// Raw Vulkan `vendorID` the classification came from.
    pub vendor_id: u32,
    /// Whether the 16-bit cooperative-matrix extension is present.
    ///
    /// Required *in addition to* [`GpuVendor::Nvidia`] before
    /// [`ShaderPath::CoopMatrix16`] may be selected.
    pub coop_matrix16_ext_present: bool,
    /// Whether the device natively supports integer dot-product accumulates.
    ///
    /// Without it, integer matmuls take [`ShaderPath::PolyfillIntDot`] (except
    /// on Adreno/Mali, where the WGSL fallback covers integer math itself).
    pub supports_int_dot: bool,
    /// Whether the device supports native fp16 arithmetic.
    ///
    /// Informational for now: it feeds the caps fingerprint so future fp16
    /// pipeline variants invalidate stale cache entries.
    pub supports_fp16_arithmetic: bool,
    /// `maxComputeWorkGroupInvocations` reported by the device.
    ///
    /// Clamped down to the mobile set (64/128) by
    /// [`crate::shaders_workgroup::workgroup_for_path`]; values above 128
    /// never produce desktop-style 256-invocation groups.
    pub max_workgroup_invocations: u32,
}

impl DeviceCaps {
    /// Build capabilities from probed properties; never panics.
    ///
    /// `vendor` is classified from `vendor_id` via [`GpuVendor::from_vendor_id`].
    pub const fn new(
        vendor_id: u32,
        coop_matrix16_ext_present: bool,
        supports_int_dot: bool,
        supports_fp16_arithmetic: bool,
        max_workgroup_invocations: u32,
    ) -> Self {
        Self {
            vendor: GpuVendor::from_vendor_id(vendor_id),
            vendor_id,
            coop_matrix16_ext_present,
            supports_int_dot,
            supports_fp16_arithmetic,
            max_workgroup_invocations,
        }
    }

    /// Conservative capabilities for an unknown mobile part.
    ///
    /// No extensions, no integer dot product, and a 64-invocation ceiling, so
    /// selection degrades to the WGSL fallback with the small workgroup.
    pub const fn conservative() -> Self {
        Self {
            vendor: GpuVendor::Unknown,
            vendor_id: 0,
            coop_matrix16_ext_present: false,
            supports_int_dot: false,
            supports_fp16_arithmetic: false,
            max_workgroup_invocations: MOBILE_WORKGROUP_SMALL,
        }
    }

    /// Deterministic 64-bit fingerprint of these capabilities.
    ///
    /// Used as the `caps` leg of [`PipelineCacheKey`] so a driver update or a
    /// device change never collides with a stale cached pipeline. Implemented
    /// with inline FNV-1a (wrapping arithmetic, no panics) rather than
    /// `DefaultHasher` so the value is stable across processes and runs.
    pub fn fingerprint(&self) -> u64 {
        let discriminant: u8 = match self.vendor {
            GpuVendor::Nvidia => 1,
            GpuVendor::QualcommAdreno => 2,
            GpuVendor::ArmMali => 3,
            GpuVendor::ImaginationPowerVr => 4,
            GpuVendor::Unknown => 0,
        };
        let mut hash: u64 = 0xcbf29ce484222325;
        let mut mix = |byte: u8| {
            hash ^= u64::from(byte);
            hash = hash.wrapping_mul(0x100000001b3);
        };
        mix(discriminant);
        for byte in self.vendor_id.to_le_bytes() {
            mix(byte);
        }
        mix(self.coop_matrix16_ext_present as u8);
        mix(self.supports_int_dot as u8);
        mix(self.supports_fp16_arithmetic as u8);
        for byte in self.max_workgroup_invocations.to_le_bytes() {
            mix(byte);
        }
        hash
    }
}

/// Which compiled shader variant backs a dispatch.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ShaderPath {
    /// 16-bit cooperative-matrix kernels.
    ///
    /// Only selected for NVIDIA devices with the extension present (see
    /// [`select_path`]); never on mobile Adreno/Mali parts.
    CoopMatrix16,
    /// Portable WGSL compute fallback.
    ///
    /// The only path ever taken on Adreno/Mali. It covers both float and
    /// integer matmuls internally, so no separate integer polyfill is needed
    /// on those vendors.
    WgslFallback,
    /// Integer dot-product polyfill for non-Adreno/Mali devices that lack
    /// native integer-dot support.
    PolyfillIntDot,
}

/// Pick the shader path for `dtype` on `caps`.
///
/// * Adreno and Mali always return [`ShaderPath::WgslFallback`], regardless of
///   dtype or extension flags.
/// * [`ShaderPath::CoopMatrix16`] requires *both* [`GpuVendor::Nvidia`] and
///   [`DeviceCaps::coop_matrix16_ext_present`]; every other vendor falls
///   through even when the flag is set.
/// * Integer (`[`Dtype::I64`]`) matmuls without native integer-dot support
///   return [`ShaderPath::PolyfillIntDot`] on non-Adreno/Mali devices.
/// * Everything else returns [`ShaderPath::WgslFallback`].
///
/// Infallible by design: there is always a safe fallback, so callers never
/// need to handle a "no path" case.
pub fn select_path(caps: &DeviceCaps, dtype: Dtype) -> ShaderPath {
    match caps.vendor {
        GpuVendor::QualcommAdreno | GpuVendor::ArmMali => ShaderPath::WgslFallback,
        _ => {
            if caps.vendor == GpuVendor::Nvidia && caps.coop_matrix16_ext_present {
                match dtype {
                    Dtype::F32 => ShaderPath::CoopMatrix16,
                    Dtype::I64 => {
                        if caps.supports_int_dot {
                            ShaderPath::CoopMatrix16
                        } else {
                            ShaderPath::PolyfillIntDot
                        }
                    }
                    Dtype::Bool => ShaderPath::WgslFallback,
                }
            } else if dtype == Dtype::I64 && !caps.supports_int_dot {
                ShaderPath::PolyfillIntDot
            } else {
                ShaderPath::WgslFallback
            }
        }
    }
}

/// One chunk of a split matmul dispatch: `row_count` rows at `row_offset`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DispatchChunk {
    /// First output row covered by this dispatch.
    pub row_offset: u64,
    /// Number of output rows covered by this dispatch.
    pub row_count: u64,
}

/// Upper bound on output rows per dispatch used for thermal control.
///
/// Large single dispatches pin mobile GPU clocks and heat the SoC until the
/// governor throttles mid-inference; staying at or under this many rows per
/// dispatch keeps each submission short. Tune per device family as telemetry
/// lands — the chunking math in [`chunk_dispatch`] is budget-agnostic.
pub const DEFAULT_MAX_ROWS_PER_DISPATCH: u64 = 1024;

/// Split `total_rows` output rows into dispatches of at most
/// `rows_per_dispatch` rows.
///
/// Returns an empty `Vec` for a zero-row matmul and
/// [`MobileShaderError::ZeroRowBudget`] for a zero budget. Offsets use checked
/// (`saturating`) arithmetic so hostile inputs degrade to a short plan rather
/// than wrapping. Consecutive chunks tile `[0, total_rows)` with no gaps or
/// overlaps, keeping every dispatch under the thermal budget implied by
/// [`DEFAULT_MAX_ROWS_PER_DISPATCH`].
pub fn chunk_dispatch(
    total_rows: u64,
    rows_per_dispatch: u64,
) -> Result<Vec<DispatchChunk>, MobileShaderError> {
    if rows_per_dispatch == 0 {
        return Err(MobileShaderError::ZeroRowBudget);
    }
    let mut chunks = Vec::new();
    let mut offset: u64 = 0;
    while offset < total_rows {
        let remaining = total_rows.saturating_sub(offset);
        let count = remaining.min(rows_per_dispatch);
        if count == 0 {
            break;
        }
        chunks.push(DispatchChunk {
            row_offset: offset,
            row_count: count,
        });
        offset = offset.saturating_add(count);
    }
    Ok(chunks)
}

/// One pipeline to pre-create during warmup: its cache key plus workgroup.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WarmupEntry {
    /// Key the compiled pipeline will be stored under.
    pub key: PipelineCacheKey,
    /// Workgroup to create the pipeline with.
    pub workgroup: [u32; 3],
}

/// Build the ordered warmup plan that hides the first-run compile hitch.
///
/// Returns one [`WarmupEntry`] per distinct [`ShaderPath`] selected for
/// `dtypes` on `caps` (duplicates removed, first-seen order kept). The app
/// should create one pipeline per entry — during the loading screen or another
/// non-interactive window — and [`PipelineCache::insert`] the results, so the
/// first real inference finds every pipeline already compiled.
///
/// Returns an empty `Vec` when `dtypes` is empty; never panics.
pub fn warmup_plan(model_hash: u64, caps: &DeviceCaps, dtypes: &[Dtype]) -> Vec<WarmupEntry> {
    let mut entries = Vec::new();
    for dtype in dtypes {
        let path = select_path(caps, *dtype);
        if entries.iter().any(|entry: &WarmupEntry| entry.key.path == path) {
            continue;
        }
        let key = PipelineCacheKey::new(model_hash, path, caps);
        entries.push(WarmupEntry {
            key,
            workgroup: workgroup_for_path(path, caps),
        });
    }
    entries
}

/// Unit tests for the mobile shader fallback policy.
///
/// These run without the `vulkan` feature: this module has no GPU dependency.
/// Every test returns a [`Result`] and reports mismatches as
/// [`MobileShaderError::ExpectationFailed`] instead of using `assert!` or
/// `unwrap`, per the workspace lint denies.
#[cfg(test)]
mod tests {
    use super::{
        DeviceCaps, DispatchChunk, GpuVendor, MobileShaderError, ShaderPath, chunk_dispatch,
        select_path, warmup_plan,
    };
    use crate::tensors::Dtype;

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
    fn adreno_always_uses_wgsl_fallback() -> Result<(), MobileShaderError> {
        let caps = DeviceCaps::new(GpuVendor::QUALCOMM_ID, true, true, true, 256);
        expect(select_path(&caps, Dtype::F32) == ShaderPath::WgslFallback, "adreno f32")?;
        expect(select_path(&caps, Dtype::I64) == ShaderPath::WgslFallback, "adreno i64")
    }

    #[test]
    fn mali_always_uses_wgsl_fallback() -> Result<(), MobileShaderError> {
        let caps = DeviceCaps::new(GpuVendor::ARM_ID, true, false, false, 128);
        expect(select_path(&caps, Dtype::F32) == ShaderPath::WgslFallback, "mali f32")?;
        expect(select_path(&caps, Dtype::I64) == ShaderPath::WgslFallback, "mali i64")
    }

    #[test]
    fn nvidia_with_ext_uses_coop_matrix_for_f32() -> Result<(), MobileShaderError> {
        let caps = nvidia_caps();
        expect(
            select_path(&caps, Dtype::F32) == ShaderPath::CoopMatrix16,
            "nvidia+ext f32",
        )
    }

    #[test]
    fn coop_matrix_requires_both_nvidia_and_ext() -> Result<(), MobileShaderError> {
        let no_ext = DeviceCaps::new(GpuVendor::NVIDIA_ID, false, true, true, 256);
        expect(
            select_path(&no_ext, Dtype::F32) == ShaderPath::WgslFallback,
            "nvidia without ext",
        )?;
        let impostor = DeviceCaps::new(0x1234, true, true, true, 256);
        expect(
            select_path(&impostor, Dtype::F32) == ShaderPath::WgslFallback,
            "non-nvidia with ext flag",
        )?;
        expect(
            DeviceCaps::conservative().vendor == GpuVendor::Unknown,
            "conservative vendor",
        )?;
        expect(
            select_path(&DeviceCaps::conservative(), Dtype::F32) == ShaderPath::WgslFallback,
            "conservative path",
        )
    }

    #[test]
    fn int_without_dot_uses_polyfill_off_adreno_mali() -> Result<(), MobileShaderError> {
        let caps = DeviceCaps::new(GpuVendor::IMAGINATION_ID, false, false, false, 128);
        expect(
            select_path(&caps, Dtype::I64) == ShaderPath::PolyfillIntDot,
            "int without dot",
        )?;
        expect(
            select_path(&caps, Dtype::F32) == ShaderPath::WgslFallback,
            "float without dot",
        )
    }

    #[test]
    fn chunking_tiles_rows_without_gaps() -> Result<(), MobileShaderError> {
        let chunks = chunk_dispatch(3000, 1024)?;
        expect(
            chunks
                == [
                    DispatchChunk {
                        row_offset: 0,
                        row_count: 1024,
                    },
                    DispatchChunk {
                        row_offset: 1024,
                        row_count: 1024,
                    },
                    DispatchChunk {
                        row_offset: 2048,
                        row_count: 952,
                    },
                ],
            "3000/1024 tiling",
        )?;
        expect(chunk_dispatch(0, 1024)?.is_empty(), "zero rows chunk-free")?;
        match chunk_dispatch(100, 0) {
            Err(MobileShaderError::ZeroRowBudget) => Ok(()),
            Err(other) => Err(other),
            Ok(_) => Err(MobileShaderError::ExpectationFailed("zero budget ok".to_owned())),
        }
    }

    #[test]
    fn warmup_dedups_paths_in_first_seen_order() -> Result<(), MobileShaderError> {
        let caps = nvidia_caps();
        let plan = warmup_plan(0xA11CE, &caps, &[Dtype::F32, Dtype::F32, Dtype::I64]);
        expect(plan.len() == 1, "nvidia f32+i64 dedups to coop only")?;
        match plan.as_slice() {
            [only] if only.key.path == ShaderPath::CoopMatrix16 => Ok(()),
            _ => Err(MobileShaderError::ExpectationFailed("warmup path".to_owned())),
        }?;
        let mixed = DeviceCaps::new(GpuVendor::IMAGINATION_ID, false, false, false, 128);
        let mixed_plan = warmup_plan(0xA11CE, &mixed, &[Dtype::F32, Dtype::I64]);
        expect(mixed_plan.len() == 2, "float+int needs two paths")?;
        match mixed_plan.as_slice() {
            [first, second]
                if first.key.path == ShaderPath::WgslFallback
                    && second.key.path == ShaderPath::PolyfillIntDot =>
            {
                Ok(())
            }
            _ => Err(MobileShaderError::ExpectationFailed("warmup order".to_owned())),
        }?;
        expect(warmup_plan(0xA11CE, &caps, &[]).is_empty(), "empty dtypes")
    }
}
