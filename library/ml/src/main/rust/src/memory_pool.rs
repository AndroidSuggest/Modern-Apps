//! Reusable staging-buffer accounting for chunked weight uploads.
//!
//! [`StagingPool`] tracks how many upload-sized buffers are live so at most
//! `capacity` chunks are in flight at once. It owns no memory itself: the
//! Vulkan layer allocates the real buffers and pairs each allocation with
//! [`StagingPool::acquire`] and each recycle with [`StagingPool::release`].
//!
//! Always compiled, even without the `vulkan` feature: host-side planning
//! with no GPU dependency, like [`crate::memory`].

use crate::memory::{DEFAULT_STAGING_BUFFERS, MAX_CHUNK_BYTES, MemoryError};

/// Reusable staging-buffer accounting for chunked uploads.
///
/// The pool tracks how many upload-sized buffers are live so at most
/// `capacity` chunks are in flight at once. It owns no memory itself: the
/// Vulkan layer allocates the real buffers and pairs each allocation with
/// [`StagingPool::acquire`] and each recycle with [`StagingPool::release`].
#[derive(Debug)]
pub struct StagingPool {
    /// Bytes per staging buffer.
    chunk_bytes: usize,
    /// Maximum simultaneously acquired buffers.
    capacity: usize,
    /// Buffers currently acquired.
    in_use: usize,
}

impl StagingPool {
    /// Build a pool of `capacity` buffers of `chunk_bytes` bytes.
    ///
    /// A zero `capacity` is legal and simply makes every
    /// [`StagingPool::acquire`] fail with [`MemoryError::PoolExhausted`];
    /// a zero `chunk_bytes` is rejected with [`MemoryError::EmptyChunk`].
    pub fn new(chunk_bytes: usize, capacity: usize) -> Result<Self, MemoryError> {
        if chunk_bytes == 0 {
            return Err(MemoryError::EmptyChunk);
        }
        Ok(Self {
            chunk_bytes,
            capacity,
            in_use: 0,
        })
    }

    /// Build the default pool: 64 MiB buffers, [`DEFAULT_STAGING_BUFFERS`].
    pub fn with_defaults() -> Self {
        Self {
            chunk_bytes: MAX_CHUNK_BYTES,
            capacity: DEFAULT_STAGING_BUFFERS,
            in_use: 0,
        }
    }

    /// Acquire one buffer, or [`MemoryError::PoolExhausted`] when full.
    pub fn acquire(&mut self) -> Result<(), MemoryError> {
        if self.in_use >= self.capacity {
            return Err(MemoryError::PoolExhausted {
                in_use: self.in_use,
                capacity: self.capacity,
            });
        }
        self.in_use = self.in_use.saturating_add(1);
        Ok(())
    }

    /// Release one buffer, or [`MemoryError::PoolOverRelease`] when idle.
    pub fn release(&mut self) -> Result<(), MemoryError> {
        if self.in_use == 0 {
            return Err(MemoryError::PoolOverRelease);
        }
        self.in_use -= 1;
        Ok(())
    }

    /// Return the bytes per staging buffer.
    pub fn chunk_bytes(&self) -> usize {
        self.chunk_bytes
    }

    /// Return the maximum simultaneously acquired buffers.
    pub fn capacity(&self) -> usize {
        self.capacity
    }

    /// Return the currently acquired buffers.
    pub fn in_use(&self) -> usize {
        self.in_use
    }

    /// Return the currently free buffers; saturates at zero.
    pub fn free(&self) -> usize {
        self.capacity.saturating_sub(self.in_use)
    }
}

/// Unit tests for the staging-buffer pool.
///
/// These run without the `vulkan` feature: this module has no GPU
/// dependency. Every test returns a [`Result`] and reports mismatches as
/// strings instead of using `assert!` or `unwrap`, per the workspace
/// `panic` / `unwrap_used` lints.
#[cfg(test)]
mod tests {
    use super::StagingPool;
    use crate::memory::{DEFAULT_STAGING_BUFFERS, MemoryError};

    #[test]
    fn pool_cycles_and_reports_limits() -> Result<(), String> {
        let mut pool = StagingPool::with_defaults();
        if pool.capacity() != DEFAULT_STAGING_BUFFERS {
            return Err("default pool has the wrong capacity".to_owned());
        }
        pool.acquire().map_err(|err| err.to_string())?;
        pool.acquire().map_err(|err| err.to_string())?;
        if pool.free() != 0 {
            return Err("full pool should report no free buffers".to_owned());
        }
        match pool.acquire() {
            Err(MemoryError::PoolExhausted { in_use, capacity }) => {
                if in_use != pool.in_use() || capacity != pool.capacity() {
                    return Err("exhaustion report disagrees with pool".to_owned());
                }
            }
            Err(other) => return Err(other.to_string()),
            Ok(()) => return Err("over-acquire should fail".to_owned()),
        }
        pool.release().map_err(|err| err.to_string())?;
        pool.release().map_err(|err| err.to_string())?;
        match pool.release() {
            Err(MemoryError::PoolOverRelease) => Ok(()),
            Err(other) => Err(other.to_string()),
            Ok(()) => Err("over-release should fail".to_owned()),
        }
    }
}
