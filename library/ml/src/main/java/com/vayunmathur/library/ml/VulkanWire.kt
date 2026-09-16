// PACKAGE STRUCTURE EXCEPTION (JNI)
package com.vayunmathur.library.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A single dense tensor crossing the JNI boundary: ONNX dtype code, shape, and
 * little-endian bytes. Mirrors `crate::tensors::HostTensor` on the Rust side.
 */
internal data class VulkanTensor(
    val dtype: Int,
    val shape: LongArray,
    val bytes: ByteArray,
) {
    /** Decode the payload as float32 values (dtype must be [VulkanWire.DTYPE_F32]). */
    fun asFloats(): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        buf.asFloatBuffer().get(out)
        return out
    }

    /** Decode the payload as int64 values (dtype must be [VulkanWire.DTYPE_I64]). */
    fun asLongs(): LongArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = LongArray(bytes.size / 8)
        buf.asLongBuffer().get(out)
        return out
    }

    /** Decode the payload as boolean values (dtype must be [VulkanWire.DTYPE_BOOL]). */
    fun asBools(): BooleanArray = BooleanArray(bytes.size) { bytes[it].toInt() != 0 }

    override fun equals(other: Any?): Boolean =
        other is VulkanTensor &&
            dtype == other.dtype &&
            shape.contentEquals(other.shape) &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        (dtype * 31 + shape.contentHashCode()) * 31 + bytes.contentHashCode()
}

/**
 * Encoder/decoder for the self-describing `MLV1` tensor payload shared with
 * `crate::tensors::{encode_payload, decode_payload}`.
 *
 * Layout (all little-endian): `[u32 magic][u32 count]` then per tensor
 * `[i32 dtype][u32 rank][i64 dim...][u64 byteLen][bytes...]`.
 */
internal object VulkanWire {
    /** ONNX TensorProto FLOAT. */
    const val DTYPE_F32: Int = 1

    /** ONNX TensorProto INT64. */
    const val DTYPE_I64: Int = 7

    /** ONNX TensorProto BOOL (one byte per element, 0 or 1). */
    const val DTYPE_BOOL: Int = 9

    /** Magic tag "MLV1" identifying a v1 payload. */
    private const val MAGIC: Int = 0x4D4C5631

    /** Build an f32 tensor from a shape and values. */
    fun floats(shape: LongArray, values: FloatArray): VulkanTensor {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(values)
        return VulkanTensor(DTYPE_F32, shape, buf.array())
    }

    /** Build an i64 tensor from a shape and values. */
    fun longs(shape: LongArray, values: LongArray): VulkanTensor {
        val buf = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.asLongBuffer().put(values)
        return VulkanTensor(DTYPE_I64, shape, buf.array())
    }

    /** Build a bool tensor from a shape and values (one byte each, 0 or 1). */
    fun bools(shape: LongArray, values: BooleanArray): VulkanTensor {
        val bytes = ByteArray(values.size) { if (values[it]) 1 else 0 }
        return VulkanTensor(DTYPE_BOOL, shape, bytes)
    }

    /** Encode tensors into an MLV1 payload for [VulkanBridge.run]. */
    fun encode(tensors: List<VulkanTensor>): ByteArray {
        var size = 8 // magic + count
        for (t in tensors) {
            size += 4 + 4 + t.shape.size * 8 + 8 + t.bytes.size
        }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putInt(tensors.size)
        for (t in tensors) {
            buf.putInt(t.dtype)
            buf.putInt(t.shape.size)
            for (dim in t.shape) buf.putLong(dim)
            buf.putLong(t.bytes.size.toLong())
            buf.put(t.bytes)
        }
        return buf.array()
    }

    /** Decode an MLV1 payload from [VulkanBridge.run], or null if malformed. */
    fun decode(payload: ByteArray): List<VulkanTensor>? {
        return try {
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.remaining() < 8 || buf.int != MAGIC) return null
            val count = buf.int
            if (count < 0) return null
            val out = ArrayList<VulkanTensor>(count)
            repeat(count) {
                val dtype = buf.int
                val rank = buf.int
                if (rank < 0) return null
                val shape = LongArray(rank) { buf.long }
                val byteLen = buf.long
                if (byteLen < 0 || byteLen > buf.remaining()) return null
                val bytes = ByteArray(byteLen.toInt())
                buf.get(bytes)
                out.add(VulkanTensor(dtype, shape, bytes))
            }
            out
        } catch (_: Throwable) {
            null
        }
    }
}
