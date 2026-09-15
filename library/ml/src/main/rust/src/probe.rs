//! Vulkan capability probe.
//!
//! Returns a JNI-compatible bitmask of device capabilities without ever
//! panicking. The heavy lifting lives in [`crate::device`]: the `vulkan`
//! build probes the loader plus the best compute device (instance and
//! enumeration only, no logical device), while the no-`vulkan` build keeps
//! the same symbol returning `0`. A missing loader yields `0` plus a
//! `log::info` line.

/// Bit set when the Vulkan loader (`vulkan-1.dll` / `libvulkan.so`) loads.
pub const CAP_LOADER: i32 = 1;
/// Bit set when a compute-capable physical device is found.
pub const CAP_COMPUTE: i32 = 2;
/// Bit set when 16-bit cooperative-matrix types are supported.
pub const CAP_COOPMAT16: i32 = 4;
/// Bit set when Vulkan 1.2 (or newer) is available.
pub const CAP_VULKAN12: i32 = 8;

/// Probe the Vulkan loader and best compute device, returning the
/// capability bitmask.
///
/// Enumerates physical devices on a throwaway Vulkan 1.2 instance and sets
/// [`CAP_COMPUTE`], [`CAP_COOPMAT16`] and [`CAP_VULKAN12`] from the winner;
/// no logical device is created, so this stays cheap enough for the JNI
/// probe path. Never panics; a missing loader or instance is reported via
/// `log::info` with the lower bits set accordingly.
#[cfg(feature = "vulkan")]
pub fn probe_device() -> i32 {
    crate::device::probe_bitmask()
}

/// Probe the Vulkan loader and return a capability bitmask.
///
/// The `vulkan` cargo feature is disabled, so no loader is attempted
/// and `0` is returned. Never panics.
#[cfg(not(feature = "vulkan"))]
pub fn probe_device() -> i32 {
    log::info!("vulkan feature disabled; probe returns 0");
    0
}
