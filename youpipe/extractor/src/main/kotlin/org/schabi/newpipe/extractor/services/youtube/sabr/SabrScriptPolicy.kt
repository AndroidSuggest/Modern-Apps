package org.schabi.newpipe.extractor.services.youtube.sabr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature

/** Signed metadata and ordinary JavaScript source. This is a container, not a policy language. */
class SabrScriptPolicy {

    companion object {
        private const val MAGIC = 0x534A5331
        private const val VERSION = 1
        private const val MAX_SOURCE_BYTES = 512 * 1024

        @JvmStatic
        fun parseVerified(
            payload: ByteArray,
            signature: ByteArray,
            key: PublicKey,
            nowMs: Long,
            minimumRevision: Long
        ): SabrScriptPolicy {
            try {
                val verifier = Signature.getInstance("Ed25519")
                verifier.initVerify(key)
                verifier.update(payload)
                if (!verifier.verify(signature)) throw IllegalArgumentException("Invalid SABR JavaScript policy signature")
            } catch (error: GeneralSecurityException) {
                throw IllegalArgumentException("Could not verify SABR JavaScript policy", error)
            }
            return parse(payload, nowMs, minimumRevision)
        }

        @JvmStatic
        fun parseVerified(
            payload: ByteArray,
            signature: ByteArray,
            rawKey: ByteArray,
            nowMs: Long,
            minimumRevision: Long
        ): SabrScriptPolicy {
            if (rawKey.size != 32) throw IllegalArgumentException("Invalid Ed25519 public key")
            if (!Ed25519Verify.verify(signature, payload, rawKey)) {
                throw IllegalArgumentException("Invalid SABR JavaScript policy signature")
            }
            return parse(payload, nowMs, minimumRevision)
        }

        private fun parse(payload: ByteArray, nowMs: Long, minimumRevision: Long): SabrScriptPolicy {
            if (payload.isEmpty() || payload.size > MAX_SOURCE_BYTES + 64) {
                throw IllegalArgumentException("Invalid SABR JavaScript policy size")
            }
            try {
                val input = DataInputStream(ByteArrayInputStream(payload))
                if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                    throw IllegalArgumentException("Unsupported SABR JavaScript policy")
                }
                val revision = input.readLong()
                val from = input.readLong()
                val until = input.readLong()
                val size = input.readInt()
                if (revision < minimumRevision || from < 0 || until <= from || size <= 0 || size > MAX_SOURCE_BYTES) {
                    throw IllegalArgumentException("Invalid SABR JavaScript policy metadata")
                }
                val source = ByteArray(size)
                input.readFully(source)
                if (input.available() != 0) throw IllegalArgumentException("Trailing SABR JavaScript policy bytes")
                if (nowMs < from || nowMs >= until) throw IllegalArgumentException("SABR JavaScript policy is not currently valid")
                return SabrScriptPolicy(
                    revision, from, until,
                    String(source, Charsets.UTF_8), payload.clone()
                )
            } catch (error: IOException) {
                throw IllegalArgumentException("Malformed SABR JavaScript policy", error)
            }
        }

        private fun encode(revision: Long, from: Long, until: Long, source: String): ByteArray {
            val script = source.toByteArray(Charsets.UTF_8)
            if (script.isEmpty() || script.size > MAX_SOURCE_BYTES) {
                throw IllegalArgumentException("Invalid SABR JavaScript source size")
            }
            return try {
                val bytes = ByteArrayOutputStream()
                val output = DataOutputStream(bytes)
                output.writeInt(MAGIC)
                output.writeByte(VERSION)
                output.writeLong(revision)
                output.writeLong(from)
                output.writeLong(until)
                output.writeInt(script.size)
                output.write(script)
                output.flush()
                bytes.toByteArray()
            } catch (impossible: IOException) {
                throw IllegalStateException(impossible)
            }
        }
    }

    private val revision: Long
    private val validFromMs: Long
    private val validUntilMs: Long
    private val source: String
    private val payload: ByteArray

    constructor(revision: Long, validFromMs: Long, validUntilMs: Long, source: String) {
        if (revision < 0 || validFromMs < 0 || validUntilMs <= validFromMs || source.isEmpty()) {
            throw IllegalArgumentException("Invalid SABR JavaScript policy")
        }
        this.revision = revision
        this.validFromMs = validFromMs
        this.validUntilMs = validUntilMs
        this.source = source
        this.payload = encode(revision, validFromMs, validUntilMs, source)
    }

    private constructor(revision: Long, validFromMs: Long, validUntilMs: Long, source: String, payload: ByteArray) {
        this.revision = revision
        this.validFromMs = validFromMs
        this.validUntilMs = validUntilMs
        this.source = source
        this.payload = payload
    }

    fun getRevision(): Long = revision
    fun getValidFromMs(): Long = validFromMs
    fun getValidUntilMs(): Long = validUntilMs
    fun getSource(): String = source
    fun serialize(): ByteArray = payload.clone()
}
