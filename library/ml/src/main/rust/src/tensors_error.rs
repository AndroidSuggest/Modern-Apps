//! Failure type for host-side tensor packing helpers.
//!
//! [`TensorError`] is the single error surface for [`crate::tensors`]:
//! unknown dtype codes, bad shapes, and byte-length mismatches. It lives in
//! its own module so `tensors.rs` stays within the 500-line advisory limit.

use thiserror::Error;

/// Failures while packing, unpacking, or validating host tensors.
#[derive(Debug, Error, PartialEq, Eq)]
pub enum TensorError {
    /// The `i32` dtype code matches no [`crate::tensors::Dtype`].
    #[error("unknown dtype code: {0}")]
    UnknownDtype(i32),
    /// A shape dimension is negative or otherwise unusable.
    #[error("invalid shape: {0}")]
    InvalidShape(String),
    /// The byte buffer length does not match dtype and shape.
    #[error("byte length mismatch: expected {expected}, got {actual}")]
    ByteLengthMismatch {
        /// Required length in bytes.
        expected: usize,
        /// Length actually supplied.
        actual: usize,
    },
    /// The parallel input slices have different lengths.
    #[error("mismatched lengths: dtypes {dtypes}, shapes {shapes}, offsets {offsets}")]
    MismatchedLengths {
        /// Length of the dtypes slice.
        dtypes: usize,
        /// Length of the shapes slice.
        shapes: usize,
        /// Length of the offsets slice.
        offsets: usize,
    },
    /// An offset window runs past the end of the payload.
    #[error("payload out of bounds: offset {offset} with len {len} exceeds {total}")]
    PayloadOutOfBounds {
        /// Requested start offset.
        offset: usize,
        /// Requested window length.
        len: usize,
        /// Total payload length.
        total: usize,
    },
    /// Element-count arithmetic overflowed.
    #[error("shape overflows address space: {0}")]
    ShapeOverflow(String),
    /// A byte buffer is not a multiple of the element size.
    #[error("invalid byte length {len} for element size {elem}")]
    InvalidByteLength {
        /// Buffer length in bytes.
        len: usize,
        /// Required element size in bytes.
        elem: usize,
    },
    /// The tensor holds a different dtype than the accessor expected.
    #[error("dtype mismatch: expected {expected}, got {actual}")]
    DtypeMismatch {
        /// Requested dtype code.
        expected: i32,
        /// Tensor's actual dtype code.
        actual: i32,
    },
    /// A self-describing payload did not begin with the expected magic tag.
    #[error("bad payload magic: 0x{0:08X}")]
    BadMagic(u32),
}
