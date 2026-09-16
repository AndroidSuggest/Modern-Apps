//! Self-describing `MLV1` tensor wire format shared with Kotlin.
//!
//! A single `byte[]` carries every tensor with its own header, so the JNI
//! boundary needs no side-channel dtype/shape/offset arrays. Kotlin
//! (`VulkanWire`) encodes the same layout when handing inputs in and decodes
//! it when reading outputs back.
//!
//! Layout (all integers little-endian):
//!   [u32 magic = 0x4D4C5631 "MLV1"]
//!   [u32 count]
//!   repeat count:
//!     [i32 dtype_code]   (ONNX TensorProto code: 1=f32, 7=i64)
//!     [u32 rank]
//!     [i64 dim] * rank
//!     [u64 byte_len]
//!     [u8]  * byte_len
//!
//! Kept in its own module so [`crate::tensors`] stays under the repo's
//! 500-line-per-file limit; re-exported there as `crate::tensors::{encode_payload,
//! decode_payload, PAYLOAD_MAGIC}` for existing call sites.

use crate::tensors::{Dtype, HostTensor};
use crate::tensors_error::TensorError;

/// Magic tag identifying a v1 self-describing tensor payload ("MLV1").
pub const PAYLOAD_MAGIC: u32 = 0x4D4C_5631;

/// Read a little-endian `u32` at `cursor`, advancing it, or fail cleanly.
fn read_u32_le(bytes: &[u8], cursor: &mut usize) -> Result<u32, TensorError> {
    let start = *cursor;
    let end = start
        .checked_add(4)
        .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: 4, total: bytes.len() })?;
    let window = bytes
        .get(start..end)
        .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: 4, total: bytes.len() })?;
    let raw: [u8; 4] = window
        .try_into()
        .map_err(|_| TensorError::InvalidByteLength { len: bytes.len(), elem: 4 })?;
    *cursor = end;
    Ok(u32::from_le_bytes(raw))
}

/// Read a little-endian `i32` at `cursor`, advancing it, or fail cleanly.
fn read_i32_le(bytes: &[u8], cursor: &mut usize) -> Result<i32, TensorError> {
    Ok(read_u32_le(bytes, cursor)? as i32)
}

/// Read a little-endian `i64` at `cursor`, advancing it, or fail cleanly.
fn read_i64_le(bytes: &[u8], cursor: &mut usize) -> Result<i64, TensorError> {
    let start = *cursor;
    let end = start
        .checked_add(8)
        .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: 8, total: bytes.len() })?;
    let window = bytes
        .get(start..end)
        .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: 8, total: bytes.len() })?;
    let raw: [u8; 8] = window
        .try_into()
        .map_err(|_| TensorError::InvalidByteLength { len: bytes.len(), elem: 8 })?;
    *cursor = end;
    Ok(i64::from_le_bytes(raw))
}

/// Read a little-endian `u64` at `cursor`, advancing it, or fail cleanly.
fn read_u64_le(bytes: &[u8], cursor: &mut usize) -> Result<u64, TensorError> {
    let start = *cursor;
    let end = start
        .checked_add(8)
        .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: 8, total: bytes.len() })?;
    let window = bytes
        .get(start..end)
        .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: 8, total: bytes.len() })?;
    let raw: [u8; 8] = window
        .try_into()
        .map_err(|_| TensorError::InvalidByteLength { len: bytes.len(), elem: 8 })?;
    *cursor = end;
    Ok(u64::from_le_bytes(raw))
}

/// Encode tensors into the self-describing v1 wire payload.
///
/// The result is a single buffer Kotlin can decode with the matching reader;
/// every tensor carries its own dtype, shape, and byte length so no external
/// descriptor arrays are needed.
pub fn encode_payload(tensors: &[HostTensor]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&PAYLOAD_MAGIC.to_le_bytes());
    out.extend_from_slice(&(tensors.len() as u32).to_le_bytes());
    for tensor in tensors {
        out.extend_from_slice(&tensor.dtype().to_i32().to_le_bytes());
        out.extend_from_slice(&(tensor.shape().len() as u32).to_le_bytes());
        for dim in tensor.shape() {
            out.extend_from_slice(&dim.to_le_bytes());
        }
        out.extend_from_slice(&(tensor.bytes().len() as u64).to_le_bytes());
        out.extend_from_slice(tensor.bytes());
    }
    out
}

/// Decode a self-describing v1 wire payload into [`HostTensor`] values.
///
/// Validates the magic tag, per-tensor dtype, rank, and byte length; any
/// truncation or unknown dtype yields a [`TensorError`] rather than a panic.
pub fn decode_payload(bytes: &[u8]) -> Result<Vec<HostTensor>, TensorError> {
    let mut cursor = 0usize;
    let magic = read_u32_le(bytes, &mut cursor)?;
    if magic != PAYLOAD_MAGIC {
        return Err(TensorError::BadMagic(magic));
    }
    let count = read_u32_le(bytes, &mut cursor)? as usize;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let dtype_code = read_i32_le(bytes, &mut cursor)?;
        let dtype = Dtype::from_i32(dtype_code).ok_or(TensorError::UnknownDtype(dtype_code))?;
        let rank = read_u32_le(bytes, &mut cursor)? as usize;
        let mut shape = Vec::with_capacity(rank);
        for _ in 0..rank {
            shape.push(read_i64_le(bytes, &mut cursor)?);
        }
        let byte_len = usize::try_from(read_u64_le(bytes, &mut cursor)?)
            .map_err(|_| TensorError::InvalidByteLength { len: bytes.len(), elem: 1 })?;
        let start = cursor;
        let end = start
            .checked_add(byte_len)
            .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: byte_len, total: bytes.len() })?;
        let window = bytes
            .get(start..end)
            .ok_or(TensorError::PayloadOutOfBounds { offset: start, len: byte_len, total: bytes.len() })?;
        cursor = end;
        out.push(HostTensor::new(dtype, shape, window.to_vec())?);
    }
    Ok(out)
}

/// Unit tests for the self-describing wire format.
#[cfg(test)]
mod tests {
    use super::{decode_payload, encode_payload, PAYLOAD_MAGIC};
    use crate::tensors::HostTensor;
    use crate::tensors_error::TensorError;

    #[test]
    fn payload_round_trip() -> Result<(), TensorError> {
        let tensors = vec![
            HostTensor::from_f32(vec![2, 2], &[1.0, 2.0, 3.0, 4.0])?,
            HostTensor::from_i64(vec![3], &[10, 20, 30])?,
        ];
        let encoded = encode_payload(&tensors);
        let decoded = decode_payload(&encoded)?;
        if decoded == tensors {
            Ok(())
        } else {
            Err(TensorError::MismatchedLengths { dtypes: tensors.len(), shapes: decoded.len(), offsets: 0 })
        }
    }

    #[test]
    fn payload_rejects_bad_magic() -> Result<(), TensorError> {
        let mut bytes = encode_payload(&[]);
        bytes[0] ^= 0xFF;
        match decode_payload(&bytes) {
            Err(TensorError::BadMagic(_)) => Ok(()),
            Err(other) => Err(other),
            Ok(_) => Err(TensorError::BadMagic(PAYLOAD_MAGIC)),
        }
    }

    #[test]
    fn payload_rejects_truncation() -> Result<(), TensorError> {
        let tensors = vec![HostTensor::from_f32(vec![4], &[1.0, 2.0, 3.0, 4.0])?];
        let encoded = encode_payload(&tensors);
        let truncated = &encoded[..encoded.len() - 4];
        match decode_payload(truncated) {
            Err(TensorError::PayloadOutOfBounds { .. }) => Ok(()),
            Err(other) => Err(other),
            Ok(_) => Err(TensorError::InvalidByteLength { len: truncated.len(), elem: 1 }),
        }
    }
}
