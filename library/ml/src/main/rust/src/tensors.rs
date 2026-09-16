//! Host-side tensor packing helpers shared by every session.
//!
//! Kotlin hands flat little-endian payloads across JNI; this module turns
//! those bytes into [`HostTensor`] values (and back) without touching the
//! GPU. It only knows two [`Dtype`]s — the ones every `:library:ml` model
//! uses — so the surface stays small and every failure is a [`TensorError`].

/// Re-exported so existing `crate::tensors::TensorError` paths keep working.
pub use crate::tensors_error::TensorError;

/// Element type of a [`HostTensor`], using ONNX TensorProto codes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dtype {
    /// 32-bit float (`FLOAT = 1`).
    F32 = 1,
    /// 64-bit int (`INT64 = 7`).
    I64 = 7,
    /// Boolean (`BOOL = 9`), stored as one byte per element (0 or 1).
    Bool = 9,
}

impl Dtype {
    /// Map an ONNX dtype code to a [`Dtype`], or [`None`] when unknown.
    pub fn from_i32(value: i32) -> Option<Self> {
        match value {
            1 => Some(Self::F32),
            7 => Some(Self::I64),
            9 => Some(Self::Bool),
            _ => None,
        }
    }

    /// Return the ONNX dtype code for this [`Dtype`].
    pub fn to_i32(self) -> i32 {
        self as i32
    }

    /// Size of one element in bytes.
    pub const fn elem_size(self) -> usize {
        match self {
            Self::F32 => 4,
            Self::I64 => 8,
            Self::Bool => 1,
        }
    }
}

/// A single dense tensor in host memory with little-endian bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostTensor {
    dtype: Dtype,
    shape: Vec<i64>,
    bytes: Vec<u8>,
}

impl HostTensor {
    /// Build a tensor from raw parts, validating shape against bytes.
    pub fn new(dtype: Dtype, shape: Vec<i64>, bytes: Vec<u8>) -> Result<Self, TensorError> {
        let tensor = Self { dtype, shape, bytes };
        tensor.validate()?;
        Ok(tensor)
    }

    /// Build an `F32` tensor from shape and values.
    pub fn from_f32(shape: Vec<i64>, values: &[f32]) -> Result<Self, TensorError> {
        let bytes = encode_f32_le(values);
        Self::new(Dtype::F32, shape, bytes)
    }

    /// Build an `I64` tensor from shape and values.
    pub fn from_i64(shape: Vec<i64>, values: &[i64]) -> Result<Self, TensorError> {
        let bytes = encode_i64_le(values);
        Self::new(Dtype::I64, shape, bytes)
    }

    /// Build a `Bool` tensor from shape and values (one byte each, 0 or 1).
    pub fn from_bool(shape: Vec<i64>, values: &[bool]) -> Result<Self, TensorError> {
        let bytes = encode_bool(values);
        Self::new(Dtype::Bool, shape, bytes)
    }

    /// Return the element type.
    pub fn dtype(&self) -> Dtype {
        self.dtype
    }

    /// Return the tensor shape.
    pub fn shape(&self) -> &[i64] {
        &self.shape
    }

    /// Return the raw little-endian bytes.
    pub fn bytes(&self) -> &[u8] {
        &self.bytes
    }

    /// Return the number of elements implied by the shape.
    pub fn num_elements(&self) -> Result<usize, TensorError> {
        element_count(&self.shape)
    }

    /// Check shape and byte length for consistency.
    pub fn validate(&self) -> Result<(), TensorError> {
        let count = element_count(&self.shape)?;
        let expected = count
            .checked_mul(self.dtype.elem_size())
            .ok_or_else(|| TensorError::ShapeOverflow(format!("{:?}", self.shape)))?;
        if self.bytes.len() == expected {
            Ok(())
        } else {
            Err(TensorError::ByteLengthMismatch {
                expected,
                actual: self.bytes.len(),
            })
        }
    }

    /// Decode the bytes as `f32`, failing on dtype mismatch.
    pub fn as_f32(&self) -> Result<Vec<f32>, TensorError> {
        if self.dtype != Dtype::F32 {
            return Err(TensorError::DtypeMismatch {
                expected: Dtype::F32.to_i32(),
                actual: self.dtype.to_i32(),
            });
        }
        decode_f32_le(&self.bytes)
    }

    /// Decode the bytes as `i64`, failing on dtype mismatch.
    pub fn as_i64(&self) -> Result<Vec<i64>, TensorError> {
        if self.dtype != Dtype::I64 {
            return Err(TensorError::DtypeMismatch {
                expected: Dtype::I64.to_i32(),
                actual: self.dtype.to_i32(),
            });
        }
        decode_i64_le(&self.bytes)
    }

    /// Decode the bytes as `bool`, failing on dtype mismatch.
    pub fn as_bool(&self) -> Result<Vec<bool>, TensorError> {
        if self.dtype != Dtype::Bool {
            return Err(TensorError::DtypeMismatch {
                expected: Dtype::Bool.to_i32(),
                actual: self.dtype.to_i32(),
            });
        }
        Ok(decode_bool(&self.bytes))
    }
}

/// Number of elements for a shape; the empty shape is a scalar of one.
fn element_count(shape: &[i64]) -> Result<usize, TensorError> {
    let mut count: usize = 1;
    for dim in shape {
        if *dim < 0 {
            return Err(TensorError::InvalidShape(format!("{shape:?}")));
        }
        let dim_usize: usize = usize::try_from(*dim)
            .map_err(|_| TensorError::InvalidShape(format!("{shape:?}")))?;
        count = count
            .checked_mul(dim_usize)
            .ok_or_else(|| TensorError::ShapeOverflow(format!("{shape:?}")))?;
    }
    Ok(count)
}

/// Encode `f32` values as little-endian bytes.
pub fn encode_f32_le(values: &[f32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(values.len().saturating_mul(4));
    for value in values {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out
}

/// Decode little-endian bytes into `f32` values.
pub fn decode_f32_le(bytes: &[u8]) -> Result<Vec<f32>, TensorError> {
    if !bytes.len().is_multiple_of(4) {
        return Err(TensorError::InvalidByteLength {
            len: bytes.len(),
            elem: 4,
        });
    }
    let mut out = Vec::with_capacity(bytes.len() / 4);
    for chunk in bytes.chunks_exact(4) {
        let raw: [u8; 4] = chunk
            .try_into()
            .map_err(|_| TensorError::InvalidByteLength {
                len: bytes.len(),
                elem: 4,
            })?;
        out.push(f32::from_le_bytes(raw));
    }
    Ok(out)
}

/// Encode `i64` values as little-endian bytes.
pub fn encode_i64_le(values: &[i64]) -> Vec<u8> {
    let mut out = Vec::with_capacity(values.len().saturating_mul(8));
    for value in values {
        out.extend_from_slice(&value.to_le_bytes());
    }
    out
}

/// Decode little-endian bytes into `i64` values.
pub fn decode_i64_le(bytes: &[u8]) -> Result<Vec<i64>, TensorError> {
    if !bytes.len().is_multiple_of(8) {
        return Err(TensorError::InvalidByteLength {
            len: bytes.len(),
            elem: 8,
        });
    }
    let mut out = Vec::with_capacity(bytes.len() / 8);
    for chunk in bytes.chunks_exact(8) {
        let raw: [u8; 8] = chunk
            .try_into()
            .map_err(|_| TensorError::InvalidByteLength {
                len: bytes.len(),
                elem: 8,
            })?;
        out.push(i64::from_le_bytes(raw));
    }
    Ok(out)
}

/// Encode `bool` values as one byte each (0 or 1).
pub fn encode_bool(values: &[bool]) -> Vec<u8> {
    values.iter().map(|&v| u8::from(v)).collect()
}

/// Decode bytes into `bool` values, treating any non-zero byte as `true`.
pub fn decode_bool(bytes: &[u8]) -> Vec<bool> {
    bytes.iter().map(|&b| b != 0).collect()
}

/// Index of the maximum value in the last row of little-endian `f32` logits.
///
/// `cols` is the row width; the row count is derived from the byte length.
/// Returns [`None`] when `cols` is zero, the buffer is empty, or the length
/// is not a whole number of rows.
pub fn argmax_last_row_f32(bytes: &[u8], cols: usize) -> Option<usize> {
    if cols == 0 || bytes.is_empty() {
        return None;
    }
    let row_bytes = cols.checked_mul(4)?;
    if !bytes.len().is_multiple_of(row_bytes) {
        return None;
    }
    let rows = bytes.len() / row_bytes;
    let last_start = rows.checked_sub(1)?.checked_mul(row_bytes)?;
    let mut best_index: usize = 0;
    let mut best_value = f32::NEG_INFINITY;
    for col in 0..cols {
        let start = last_start.checked_add(col.checked_mul(4)?)?;
        let end = start.checked_add(4)?;
        let window = bytes.get(start..end)?;
        let raw: [u8; 4] = window.try_into().ok()?;
        let value = f32::from_le_bytes(raw);
        if value > best_value {
            best_value = value;
            best_index = col;
        }
    }
    Some(best_index)
}

/// Split a concatenated payload into one [`HostTensor`] per input.
///
/// Each tensor `i` spans `offsets[i] .. offsets[i] + len[i]`, where `len[i]`
/// is derived from `dtypes[i]` and `shapes[i]`; lengths must agree.
pub fn split_payload(
    payload: &[u8],
    dtypes: &[i32],
    shapes: &[Vec<i64>],
    offsets: &[usize],
) -> Result<Vec<HostTensor>, TensorError> {
    if dtypes.len() != shapes.len() || dtypes.len() != offsets.len() {
        return Err(TensorError::MismatchedLengths {
            dtypes: dtypes.len(),
            shapes: shapes.len(),
            offsets: offsets.len(),
        });
    }
    let mut out = Vec::with_capacity(dtypes.len());
    for ((dtype_code, shape), offset) in dtypes.iter().zip(shapes.iter()).zip(offsets.iter()) {
        let dtype = Dtype::from_i32(*dtype_code).ok_or(TensorError::UnknownDtype(*dtype_code))?;
        let count = element_count(shape)?;
        let len = count
            .checked_mul(dtype.elem_size())
            .ok_or_else(|| TensorError::ShapeOverflow(format!("{shape:?}")))?;
        let end = offset
            .checked_add(len)
            .ok_or(TensorError::PayloadOutOfBounds {
                offset: *offset,
                len,
                total: payload.len(),
            })?;
        let window = payload.get(*offset..end).ok_or(TensorError::PayloadOutOfBounds {
            offset: *offset,
            len,
            total: payload.len(),
        })?;
        out.push(HostTensor::new(dtype, shape.clone(), window.to_vec())?);
    }
    Ok(out)
}

/// Concatenate output tensors' raw bytes in order into one payload.
///
/// Pair with [`split_payload`] on the receiving side using the known dtypes,
/// shapes, and offsets.
pub fn join_outputs(outputs: Vec<HostTensor>) -> Vec<u8> {
    let total: usize = outputs.iter().map(|tensor| tensor.bytes.len()).sum();
    let mut out = Vec::with_capacity(total);
    for tensor in &outputs {
        out.extend_from_slice(&tensor.bytes);
    }
    out
}

// ---------------------------------------------------------------------------
// Self-describing wire format (v1)
// ---------------------------------------------------------------------------
// The `MLV1` encoder/decoder lives in [`crate::tensors_wire`] to keep this file
// under the repo's 500-line-per-file limit; re-exported here so existing
// `crate::tensors::{encode_payload, decode_payload, PAYLOAD_MAGIC}` call sites
// (jni_bridge, tests) keep resolving.
pub use crate::tensors_wire::{decode_payload, encode_payload, PAYLOAD_MAGIC};

/// Unit tests for host-side tensor packing.
///
/// These run without the `vulkan` feature: this module has no GPU
/// dependency, so `cargo test -p ml_vulkan` exercises them directly.
/// The workspace denies `unwrap_used` and `panic`, so every test returns a
/// [`Result`] and reports mismatches as [`TensorError`] instead of using
/// `assert!` or `unwrap`.
#[cfg(test)]
mod tests {
    use super::{
        Dtype, HostTensor, TensorError, argmax_last_row_f32, decode_f32_le, decode_i64_le,
        encode_f32_le, encode_i64_le, join_outputs, split_payload,
    };

    fn check_f32_bits(actual: &[f32], expected: &[f32]) -> Result<(), TensorError> {
        if actual.len() != expected.len() {
            return Err(TensorError::ByteLengthMismatch {
                expected: expected.len(),
                actual: actual.len(),
            });
        }
        for (got, want) in actual.iter().zip(expected.iter()) {
            if got.to_bits() != want.to_bits() {
                return Err(TensorError::ByteLengthMismatch {
                    expected: want.to_bits() as usize,
                    actual: got.to_bits() as usize,
                });
            }
        }
        Ok(())
    }

    #[test]
    fn f32_encode_decode_round_trip() -> Result<(), TensorError> {
        let values = [
            0.0,
            -1.5,
            3.25,
            f32::MAX,
            f32::MIN,
            f32::INFINITY,
            f32::NEG_INFINITY,
            f32::NAN,
        ];
        let back = decode_f32_le(&encode_f32_le(&values))?;
        check_f32_bits(&back, &values)
    }

    #[test]
    fn i64_encode_decode_round_trip() -> Result<(), TensorError> {
        let values = [0_i64, -1, 1, 42, i64::MIN, i64::MAX];
        let back = decode_i64_le(&encode_i64_le(&values))?;
        if back == values {
            Ok(())
        } else {
            Err(TensorError::InvalidByteLength {
                len: back.len(),
                elem: values.len(),
            })
        }
    }

    #[test]
    fn host_tensor_f32_round_trip() -> Result<(), TensorError> {
        let values = [1.0_f32, 2.0, 3.0, 4.0];
        let tensor = HostTensor::from_f32(vec![2, 2], &values)?;
        if tensor.dtype() != Dtype::F32 {
            return Err(TensorError::DtypeMismatch {
                expected: Dtype::F32.to_i32(),
                actual: tensor.dtype().to_i32(),
            });
        }
        if tensor.shape() != [2_i64, 2] {
            return Err(TensorError::InvalidShape(format!("{:?}", tensor.shape())));
        }
        if tensor.num_elements()? != 4 {
            return Err(TensorError::InvalidShape("expected 4 elements".to_owned()));
        }
        check_f32_bits(&tensor.as_f32()?, &values)
    }

    #[test]
    fn host_tensor_i64_round_trip() -> Result<(), TensorError> {
        let values = [7_i64, -3, 0];
        let tensor = HostTensor::from_i64(vec![3], &values)?;
        let back = tensor.as_i64()?;
        if back == values {
            Ok(())
        } else {
            Err(TensorError::InvalidByteLength {
                len: back.len(),
                elem: values.len(),
            })
        }
    }

    #[test]
    fn host_tensor_rejects_bad_byte_length() -> Result<(), TensorError> {
        match HostTensor::new(Dtype::F32, vec![2], vec![0u8; 4]) {
            Err(TensorError::ByteLengthMismatch { expected: 8, actual: 4 }) => Ok(()),
            Err(other) => Err(other),
            Ok(_) => Err(TensorError::ByteLengthMismatch {
                expected: 8,
                actual: 4,
            }),
        }
    }

    #[test]
    fn host_tensor_rejects_unknown_dtype_code() -> Result<(), TensorError> {
        if Dtype::from_i32(2).is_none() {
            Ok(())
        } else {
            Err(TensorError::UnknownDtype(2))
        }
    }

    #[test]
    fn split_join_round_trip() -> Result<(), TensorError> {
        let first = HostTensor::from_f32(vec![2], &[1.0, 2.0])?;
        let second = HostTensor::from_i64(vec![3], &[10, 20, 30])?;
        let second_offset = first.bytes().len();
        let payload = join_outputs(vec![first, second]);
        let parts = split_payload(
            &payload,
            &[Dtype::F32.to_i32(), Dtype::I64.to_i32()],
            &[vec![2], vec![3]],
            &[0, second_offset],
        )?;
        let mut iter = parts.into_iter();
        match (iter.next(), iter.next(), iter.next()) {
            (Some(a), Some(b), None) => {
                check_f32_bits(&a.as_f32()?, &[1.0, 2.0])?;
                let back = b.as_i64()?;
                if back == [10_i64, 20, 30] {
                    Ok(())
                } else {
                    Err(TensorError::InvalidByteLength {
                        len: back.len(),
                        elem: 3,
                    })
                }
            }
            _ => Err(TensorError::MismatchedLengths {
                dtypes: 2,
                shapes: 2,
                offsets: 2,
            }),
        }
    }

    #[test]
    fn argmax_reads_last_row() -> Result<(), TensorError> {
        let logits = encode_f32_le(&[1.0, 2.0, 3.0, 0.5, 9.0, -1.0]);
        if argmax_last_row_f32(&logits, 3) != Some(1) {
            return Err(TensorError::InvalidByteLength {
                len: logits.len(),
                elem: 3,
            });
        }
        if argmax_last_row_f32(&logits, 0).is_some() {
            return Err(TensorError::InvalidByteLength { len: 0, elem: 0 });
        }
        if argmax_last_row_f32(&[], 3).is_some() {
            return Err(TensorError::InvalidByteLength { len: 0, elem: 3 });
        }
        Ok(())
    }
}
