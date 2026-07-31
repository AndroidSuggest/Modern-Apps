package org.schabi.newpipe.extractor.services.youtube.sabr

import java.security.PublicKey

/** Thread-safe verifier and monotonic activation boundary for signed JavaScript source. */
class SabrScriptPolicyManager {

    private val publicKey: PublicKey?
    private val rawPublicKey: ByteArray?
    @Volatile
    private var active: SabrScriptPolicy? = null
    private var highestRevision: Long

    constructor(key: PublicKey, minimumRevision: Long) {
        if (minimumRevision < 0) throw IllegalArgumentException("Invalid policy revision")
        publicKey = key
        rawPublicKey = null
        highestRevision = minimumRevision
    }

    constructor(key: ByteArray, minimumRevision: Long) {
        if (key.size != 32 || minimumRevision < 0) throw IllegalArgumentException("Invalid policy verifier")
        publicKey = null
        rawPublicKey = key.clone()
        highestRevision = minimumRevision
    }

    @Synchronized
    fun verify(payload: ByteArray, signature: ByteArray, nowMs: Long): SabrScriptPolicy {
        return if (rawPublicKey == null) {
            SabrScriptPolicy.parseVerified(payload, signature, publicKey!!, nowMs, highestRevision)
        } else {
            SabrScriptPolicy.parseVerified(payload, signature, rawPublicKey, nowMs, highestRevision)
        }
    }

    @Synchronized
    fun activate(verified: SabrScriptPolicy) {
        if (verified.getRevision() < highestRevision) throw IllegalArgumentException("SABR policy rollback rejected")
        val currentActive = active
        if (currentActive != null && currentActive.getRevision() == verified.getRevision() &&
            !currentActive.serialize().contentEquals(verified.serialize())
        ) {
            throw IllegalArgumentException("Conflicting SABR policy revision")
        }
        active = verified
        highestRevision = verified.getRevision()
    }

    @Synchronized
    fun install(payload: ByteArray, signature: ByteArray, nowMs: Long): SabrScriptPolicy {
        val verified = verify(payload, signature, nowMs)
        activate(verified)
        return verified
    }

    fun current(nowMs: Long): SabrScriptPolicy? {
        val value = active
        return if (value != null && nowMs >= value.getValidFromMs() && nowMs < value.getValidUntilMs()) value else null
    }

    @Synchronized
    fun getHighestRevision(): Long = highestRevision

    @Synchronized
    fun deactivate(expected: SabrScriptPolicy) {
        if (active === expected) active = null
    }
}
