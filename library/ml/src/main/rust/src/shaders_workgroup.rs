//! Mobile workgroup selection for the Vulkan backend.
//!
//! Extracted from [`crate::shaders_mobile`] (Task 6) so no single `.rs`
//! exceeds 500 lines. Host-side policy only: no GPU dependency, so it
//! compiles with and without the `vulkan` Cargo feature.
//!
//! Desktop Vulkan shaders assume wide workgroups (`16x16` = 256
//! invocations). Adreno and Mali parts can reject 256-invocation groups,
//! so this module only ever emits 64/128-invocation 1-D groups.

use crate::shaders_mobile::{DeviceCaps, MobileShaderError, ShaderPath};

/// Total invocations of the small mobile workgroup (`64x1x1`).
///
/// Default for WGSL-fallback and polyfill dispatches: fits every Adreno/Mali
/// part and stays thermally cheap.
pub const MOBILE_WORKGROUP_SMALL: u32 = 64;

/// Total invocations of the large mobile workgroup (`128x1x1`).
///
/// Used for cooperative-matrix dispatches when the device reports at least
/// 128 `maxComputeWorkGroupInvocations`. Desktop-style `16x16` (256) tiling
/// is deliberately absent: some mobile drivers reject it.
pub const MOBILE_WORKGROUP_LARGE: u32 = 128;

/// Validate a 1-D mobile workgroup total.
///
/// Returns `[total, 1, 1]` for 64 or 128 and
/// [`MobileShaderError::UnsupportedWorkgroup`] for anything else — notably
/// 256, the desktop `16x16` tiling this module never emits.
pub fn workgroup_shape(total: u32) -> Result<[u32; 3], MobileShaderError> {
    match total {
        MOBILE_WORKGROUP_SMALL | MOBILE_WORKGROUP_LARGE => Ok([total, 1, 1]),
        other => Err(MobileShaderError::UnsupportedWorkgroup(other)),
    }
}

/// Choose the `[x, y, z]` workgroup for `path` on `caps`.
///
/// Cooperative-matrix dispatches take the 128-wide group when the device
/// allows it; every other path takes the thermally cheaper 64-wide group. The
/// result is always 1-D (`[n, 1, 1]`) — never desktop `16x16` — and never
/// exceeds `min(caps.max_workgroup_invocations, 128)` in total invocations.
pub fn workgroup_for_path(path: ShaderPath, caps: &DeviceCaps) -> [u32; 3] {
    let large_allowed = caps.max_workgroup_invocations >= MOBILE_WORKGROUP_LARGE;
    let total = if large_allowed && path == ShaderPath::CoopMatrix16 {
        MOBILE_WORKGROUP_LARGE
    } else {
        MOBILE_WORKGROUP_SMALL
    };
    [total, 1, 1]
}

/// Unit tests for mobile workgroup selection.
///
/// These run without the `vulkan` feature: this module has no GPU dependency.
/// Every test returns a [`Result`] and reports mismatches as
/// [`MobileShaderError::ExpectationFailed`] instead of using `assert!` or
/// `unwrap`, per the workspace lint denies.
#[cfg(test)]
mod tests {
    use super::{DeviceCaps, MobileShaderError, ShaderPath, workgroup_for_path, workgroup_shape};
    use crate::shaders_mobile::GpuVendor;

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
    fn workgroup_sizes_are_mobile_only() -> Result<(), MobileShaderError> {
        expect(workgroup_shape(64)? == [64, 1, 1], "shape 64")?;
        expect(workgroup_shape(128)? == [128, 1, 1], "shape 128")?;
        match workgroup_shape(256) {
            Err(MobileShaderError::UnsupportedWorkgroup(256)) => Ok(()),
            Err(other) => Err(other),
            Ok(_) => Err(MobileShaderError::ExpectationFailed("256 accepted".to_owned())),
        }?;
        let caps = nvidia_caps();
        let group = workgroup_for_path(ShaderPath::WgslFallback, &caps);
        expect(group == [64, 1, 1], "fallback prefers small")?;
        let coop = workgroup_for_path(ShaderPath::CoopMatrix16, &caps);
        expect(coop == [128, 1, 1], "coop takes large when allowed")?;
        let tiny = DeviceCaps::conservative();
        let clamped = workgroup_for_path(ShaderPath::CoopMatrix16, &tiny);
        expect(clamped == [64, 1, 1], "small device clamps coop to 64")
    }
}
