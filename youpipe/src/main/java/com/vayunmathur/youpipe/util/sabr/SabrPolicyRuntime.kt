package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.schabi.newpipe.extractor.services.youtube.sabr.BuiltinSabrSessionPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaHeader
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaProtocol
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicyDocument
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicyManager
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyHost
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicyTranscript
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.PublicKey

/** Single construction boundary for the bundled SABR policy set and its per-session transcripts. */
object SabrPolicyRuntime {
    enum class BenchmarkPolicyMode { AUTO, BUILTIN, CLOUD }

    // Config holder replacing org.schabi.newpipe.BuildConfig (buildConfig is disabled in youpipe).
    // Blank public key + URL keep the remote signed-policy mechanism DISABLED BY DEFAULT so that
    // playback uses BuiltinSabrSessionPolicy. Deployments that ship a signed policy set these.
    const val SABR_POLICY_PUBLIC_KEY_BASE64 = ""
    const val SABR_POLICY_URL = ""
    private const val DEBUG = false

    private const val SESSION_TRANSCRIPT_CAPACITY = 512
    private const val CACHE_MAGIC = 0x53504348
    private const val CACHE_VERSION = 1
    private const val MAX_PAYLOAD_BYTES = 512 * 1024
    private const val MAX_SIGNATURE_BYTES = 1024
    private const val CACHE_FILE = "sabr-cloud-policy.bin"
    private const val REVISION_FILE = "sabr-cloud-policy.rev"

    private val FALLBACK: SabrSessionPolicy = BuiltinSabrSessionPolicy()

    @Volatile private var cloudPolicies: SabrScriptPolicyManager? = null
    @Volatile private var policyCache: AtomicFile? = null
    @Volatile private var revisionCache: AtomicFile? = null
    @Volatile private var benchmarkPolicyMode: BenchmarkPolicyMode = BenchmarkPolicyMode.AUTO

    @JvmStatic
    fun createSessionHost(): SabrSessionPolicyHost {
        val benchmarkMode = benchmarkPolicyMode
        if (benchmarkMode == BenchmarkPolicyMode.BUILTIN) {
            return createHost(FALLBACK)
        }
        val manager = cloudPolicies
        var policy = FALLBACK
        val script = manager?.current(System.currentTimeMillis())
        if (script != null) {
            policy = try {
                FailoverPolicy(script, createScriptPolicy(script))
            } catch (ignored: SabrProtocolException) {
                FALLBACK
            }
        }
        check(!(benchmarkMode == BenchmarkPolicyMode.CLOUD && policy === FALLBACK)) {
            "No active SABR cloud policy for benchmark"
        }
        return createHost(policy)
    }

    @JvmStatic
    fun setBenchmarkPolicyMode(mode: BenchmarkPolicyMode) {
        check(DEBUG) { "SABR benchmark policy override requires debug build" }
        benchmarkPolicyMode = mode
    }

    private fun createHost(policy: SabrSessionPolicy): SabrSessionPolicyHost =
        SabrSessionPolicyHost(policy, SabrSessionPolicyTranscript(SESSION_TRANSCRIPT_CAPACITY))

    /** Configures cloud policy verification and restores the last verified cached envelope. */
    @JvmStatic
    @Synchronized
    fun initialize(context: Context, publicKey: PublicKey, minimumRevision: Long) {
        val manager = SabrScriptPolicyManager(
            publicKey, maxOf(minimumRevision, readHighestRevision(context))
        )
        initialize(context, manager)
    }

    @Synchronized
    private fun initialize(context: Context, manager: SabrScriptPolicyManager) {
        val cache = AtomicFile(File(context.applicationContext.filesDir, CACHE_FILE))
        val revisions = AtomicFile(File(context.applicationContext.filesDir, REVISION_FILE))
        try {
            val envelope = decodeEnvelope(cache.readFully())
            val verified = manager.verify(
                envelope.payload, envelope.signature, System.currentTimeMillis()
            )
            createScriptPolicy(verified).close()
            manager.activate(verified)
        } catch (ignored: IOException) {
            // Missing, expired, or invalid cache must never prevent startup or builtin playback.
        } catch (ignored: IllegalArgumentException) {
            // Missing, expired, or invalid cache must never prevent startup or builtin playback.
        } catch (ignored: SabrProtocolException) {
            // Missing, expired, or invalid cache must never prevent startup or builtin playback.
        }
        cloudPolicies = manager
        policyCache = cache
        revisionCache = revisions
    }

    /** Initializes from a deployment-provided raw 32-byte Ed25519 key. Empty means builtin only. */
    @JvmStatic
    @Synchronized
    fun initialize(context: Context, publicKeyBase64: String?, minimumRevision: Long) {
        if (publicKeyBase64.isNullOrEmpty()) {
            cloudPolicies = null
            policyCache = null
            revisionCache = null
            return
        }
        try {
            val key = Base64.decode(publicKeyBase64, Base64.DEFAULT)
            val manager = SabrScriptPolicyManager(
                key, maxOf(minimumRevision, readHighestRevision(context))
            )
            initialize(context, manager)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid SABR cloud policy public key", error)
        }
    }

    /** Verifies, activates, and atomically persists a downloaded policy envelope. */
    @JvmStatic
    @Synchronized
    @Throws(IOException::class)
    fun install(payload: ByteArray, signature: ByteArray, nowMs: Long) {
        val manager = cloudPolicies
        val cache = policyCache
        val revisions = revisionCache
        if (manager == null || cache == null || revisions == null) {
            throw IllegalStateException("SABR cloud policy runtime is not initialized")
        }
        val verified = manager.verify(payload, signature, nowMs)
        try {
            createScriptPolicy(verified).close()
        } catch (error: SabrProtocolException) {
            throw IOException("Invalid SABR JavaScript policy", error)
        }
        writeRevision(revisions, verified.getRevision())
        try {
            writeEnvelope(cache, encodeEnvelope(payload, signature))
        } catch (error: IOException) {
            cache.delete()
            throw error
        }
        manager.activate(verified)
    }

    /** Installs a signed, human-readable JSON policy document received from remote delivery. */
    @JvmStatic
    @Throws(IOException::class)
    fun installDocument(encoded: ByteArray, nowMs: Long) {
        val document: SabrScriptPolicyDocument.Parsed = try {
            SabrScriptPolicyDocument.decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid SABR cloud policy document", error)
        }
        install(document.getPayload(), document.getSignature(), nowMs)
    }

    @Throws(SabrProtocolException::class)
    private fun createScriptPolicy(script: SabrScriptPolicy): SabrSessionPolicy =
        QuickJsSabrSessionPolicy(script)

    @Synchronized
    private fun disable(script: SabrScriptPolicy) {
        cloudPolicies?.deactivate(script)
        policyCache?.delete()
    }

    private class FailoverPolicy(
        private val script: SabrScriptPolicy,
        private val primary: SabrSessionPolicy
    ) : SabrSessionPolicy {
        private var failed = false
        private val mediaProtocol: SabrMediaProtocol

        init {
            val primaryMedia = primary.getMediaProtocol()
            mediaProtocol = object : SabrMediaProtocol {
                override fun getHeaderPartType(): Int =
                    currentMediaProtocol(primaryMedia).getHeaderPartType()

                override fun getMediaPartType(): Int =
                    currentMediaProtocol(primaryMedia).getMediaPartType()

                override fun getEndPartType(): Int =
                    currentMediaProtocol(primaryMedia).getEndPartType()

                @Throws(SabrProtocolException::class)
                override fun decodeHeader(payload: ByteArray): SabrMediaHeader {
                    if (failed) return SabrMediaProtocol.builtin().decodeHeader(payload)
                    return try {
                        primaryMedia.decodeHeader(payload)
                    } catch (error: RuntimeException) {
                        fail()
                        SabrMediaProtocol.builtin().decodeHeader(payload)
                    } catch (error: SabrProtocolException) {
                        fail()
                        SabrMediaProtocol.builtin().decodeHeader(payload)
                    }
                }
            }
        }

        override fun getMediaProtocol(): SabrMediaProtocol = mediaProtocol

        @Throws(SabrProtocolException::class)
        override fun evaluate(
            state: SabrSessionPolicy.State,
            event: SabrSessionPolicy.Event
        ): SabrSessionPolicy.Result {
            if (failed) return FALLBACK.evaluate(state, event)
            return try {
                primary.evaluate(state, event)
            } catch (error: RuntimeException) {
                fail()
                FALLBACK.evaluate(state, event)
            } catch (error: SabrProtocolException) {
                fail()
                FALLBACK.evaluate(state, event)
            }
        }

        @Throws(SabrProtocolException::class)
        override fun evaluateDemandRoute(
            event: SabrSessionPolicy.DemandRouteEvent
        ): SabrSessionPolicy.DemandRoute {
            if (failed) return FALLBACK.evaluateDemandRoute(event)
            return try {
                primary.evaluateDemandRoute(event)
            } catch (error: RuntimeException) {
                fail()
                FALLBACK.evaluateDemandRoute(event)
            } catch (error: SabrProtocolException) {
                fail()
                FALLBACK.evaluateDemandRoute(event)
            }
        }

        @Throws(SabrProtocolException::class)
        override fun evaluateDemandResponse(
            event: SabrSessionPolicy.DemandResponseEvent
        ): SabrSessionPolicy.DemandResponseDecision {
            if (failed) return FALLBACK.evaluateDemandResponse(event)
            return try {
                primary.evaluateDemandResponse(event)
            } catch (error: RuntimeException) {
                fail()
                FALLBACK.evaluateDemandResponse(event)
            } catch (error: SabrProtocolException) {
                fail()
                FALLBACK.evaluateDemandResponse(event)
            }
        }

        private fun currentMediaProtocol(primaryMedia: SabrMediaProtocol): SabrMediaProtocol =
            if (failed) SabrMediaProtocol.builtin() else primaryMedia

        private fun fail() {
            if (!failed) {
                failed = true
                disable(script)
                try {
                    primary.close()
                } catch (ignored: RuntimeException) {
                }
            }
        }

        override fun close() {
            primary.close()
        }
    }

    @JvmStatic
    fun encodeEnvelope(payload: ByteArray, signature: ByteArray): ByteArray {
        validateLengths(payload.size, signature.size)
        return try {
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(bytes)
            output.writeInt(CACHE_MAGIC)
            output.writeByte(CACHE_VERSION)
            output.writeInt(payload.size)
            output.writeInt(signature.size)
            output.write(payload)
            output.write(signature)
            output.flush()
            bytes.toByteArray()
        } catch (impossible: IOException) {
            throw IllegalStateException(impossible)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decodeEnvelope(encoded: ByteArray): Envelope {
        val input = DataInputStream(ByteArrayInputStream(encoded))
        if (input.readInt() != CACHE_MAGIC || input.readUnsignedByte() != CACHE_VERSION) {
            throw IOException("Unsupported SABR policy cache")
        }
        val payloadLength = input.readInt()
        val signatureLength = input.readInt()
        validateLengths(payloadLength, signatureLength)
        val payload = ByteArray(payloadLength)
        val signature = ByteArray(signatureLength)
        input.readFully(payload)
        input.readFully(signature)
        if (input.available() != 0) throw IOException("Trailing SABR policy cache bytes")
        return Envelope(payload, signature)
    }

    private fun validateLengths(payloadLength: Int, signatureLength: Int) {
        require(
            payloadLength > 0 && payloadLength <= MAX_PAYLOAD_BYTES &&
                signatureLength > 0 && signatureLength <= MAX_SIGNATURE_BYTES
        ) { "Invalid SABR policy cache size" }
    }

    @Throws(IOException::class)
    private fun writeEnvelope(cache: AtomicFile, encoded: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = cache.startWrite()
            output.write(encoded)
            output.flush()
            cache.finishWrite(output)
        } catch (error: IOException) {
            if (output != null) cache.failWrite(output)
            throw error
        }
    }

    private fun readHighestRevision(context: Context): Long {
        val file = AtomicFile(File(context.applicationContext.filesDir, REVISION_FILE))
        return try {
            val input = DataInputStream(ByteArrayInputStream(file.readFully()))
            val revision = input.readLong()
            if (input.available() == 0 && revision >= 0) revision else 0
        } catch (ignored: IOException) {
            0
        }
    }

    @Throws(IOException::class)
    private fun writeRevision(file: AtomicFile, revision: Long) {
        val bytes = ByteArrayOutputStream(java.lang.Long.BYTES)
        val output = DataOutputStream(bytes)
        output.writeLong(revision)
        output.flush()
        writeEnvelope(file, bytes.toByteArray())
    }

    class Envelope internal constructor(
        @JvmField val payload: ByteArray,
        @JvmField val signature: ByteArray
    )
}
