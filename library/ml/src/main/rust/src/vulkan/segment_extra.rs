use super::context::Limits;
use super::segment::{Segments, Segment, ALIGNMENT, MAX_SEGMENTS};

impl Segments {
    /// Window `total` bytes of weights so that every op's tensors land inside one descriptor.
    ///
    /// Depends only on the file's length and the device, never on the plan. See the module docs.
    pub fn plan(total: u64, limits: &Limits) -> Result<Segments, String> {
        if total > limits.max_memory_allocation_size {
            return Err(format!(
                "{total} bytes of weights against a maxMemoryAllocationSize of {}. The spec \
                 guarantees at least 1 GiB, so this device is unusually constrained and the model \
                 is refused rather than loaded wrongly.",
                limits.max_memory_allocation_size
            ));
        }
        let granularity = limits.min_storage_buffer_offset_alignment.max(ALIGNMENT);
        let range = limits.max_storage_buffer_range;
        if range < granularity {
            return Err(format!(
                "maxStorageBufferRange {range} is below minStorageBufferOffsetAlignment \
                 {granularity}"
            ));
        }
        if total <= range {
            // The overwhelmingly common case, and the only one before SMaLL-100: one descriptor
            // over the whole file, `base` zero, so `rebase` is the identity and `record` emits
            // exactly the commands it always did.
            return Ok(Segments { segments: vec![Segment { base: 0, len: total }], stride: 0, reach: range });
        }
        // A quarter of the range, rounded down to something both views can address.
        let stride = ((range / 4) / granularity) * granularity;
        if stride == 0 {
            return Err(format!("maxStorageBufferRange {range} is too small to window"));
        }
        let count = usize::try_from(total.div_ceil(stride)).map_err(|_| "too many segments")?;
        if count > MAX_SEGMENTS {
            return Err(format!(
                "{total} bytes of weights need {count} descriptor windows at a \
                 maxStorageBufferRange of {range}, past the {MAX_SEGMENTS} this allows"
            ));
        }
        let segments = (0..count)
            .map(|index| {
                let base = index as u64 * stride;
                Segment { base, len: range.min(total - base) }
            })
            .collect();
        Ok(Segments { segments, stride, reach: range - stride })
    }
}
