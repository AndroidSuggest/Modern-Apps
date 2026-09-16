//! Current and peak resident-set-size accounting, in bytes.
//!
//! The Vulkan layer feeds every device/host allocation through
//! [`PeakTracker::add`] and every release through [`PeakTracker::release`];
//! [`PeakTracker::peak_bytes`] is the high-water mark used to size future
//! budgets and to explain OOM kills in logs.
//!
//! Always compiled, even without the `vulkan` feature: host-side planning
//! with no GPU dependency, like [`crate::memory`].

use crate::memory::MemoryError;

/// Current and peak resident-set-size accounting, in bytes.
///
/// The Vulkan layer feeds every device/host allocation through
/// [`PeakTracker::add`] and every release through [`PeakTracker::release`];
/// [`PeakTracker::peak_bytes`] is the high-water mark used to size future
/// budgets and to explain OOM kills in logs.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct PeakTracker {
    /// Bytes currently tracked as live.
    current_bytes: u64,
    /// Maximum live bytes observed (until [`PeakTracker::reset_peak`]).
    peak_bytes: u64,
}

impl PeakTracker {
    /// Create an empty tracker.
    pub fn new() -> Self {
        Self::default()
    }

    /// Record `bytes` newly live; the peak follows the maximum. Saturates
    /// instead of wrapping on absurd inputs.
    pub fn add(&mut self, bytes: u64) {
        self.current_bytes = self.current_bytes.saturating_add(bytes);
        self.peak_bytes = self.peak_bytes.max(self.current_bytes);
    }

    /// Record `bytes` released, or [`MemoryError::UntrackedRelease`] when
    /// `bytes` exceeds what is tracked as live.
    pub fn release(&mut self, bytes: u64) -> Result<(), MemoryError> {
        if bytes > self.current_bytes {
            return Err(MemoryError::UntrackedRelease {
                release: bytes,
                current: self.current_bytes,
            });
        }
        self.current_bytes -= bytes;
        Ok(())
    }

    /// Return the currently live bytes.
    pub fn current_bytes(&self) -> u64 {
        self.current_bytes
    }

    /// Return the high-water mark in bytes.
    pub fn peak_bytes(&self) -> u64 {
        self.peak_bytes
    }

    /// Reset the high-water mark to the current level.
    pub fn reset_peak(&mut self) {
        self.peak_bytes = self.current_bytes;
    }
}

/// Unit tests for the peak-RSS tracker.
///
/// These run without the `vulkan` feature: this module has no GPU
/// dependency. Every test returns a [`Result`] and reports mismatches as
/// strings instead of using `assert!` or `unwrap`, per the workspace
/// `panic` / `unwrap_used` lints.
#[cfg(test)]
mod tests {
    use super::PeakTracker;
    use crate::memory::MemoryError;

    #[test]
    fn peak_tracks_high_water() -> Result<(), String> {
        let mut tracker = PeakTracker::new();
        tracker.add(100);
        tracker.add(50);
        tracker.release(30).map_err(|err| err.to_string())?;
        if tracker.current_bytes() != 120 {
            return Err("current bytes should be 120".to_owned());
        }
        if tracker.peak_bytes() != 150 {
            return Err("peak should hold the high-water mark".to_owned());
        }
        match tracker.release(121) {
            Err(MemoryError::UntrackedRelease {
                release: 121,
                current: 120,
            }) => {}
            Err(other) => return Err(other.to_string()),
            Ok(()) => return Err("untracked release should fail".to_owned()),
        }
        tracker.reset_peak();
        if tracker.peak_bytes() != tracker.current_bytes() {
            return Err("reset should drop the peak to current".to_owned());
        }
        Ok(())
    }
}
