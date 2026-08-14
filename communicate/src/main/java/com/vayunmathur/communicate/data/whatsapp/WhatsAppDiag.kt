package com.vayunmathur.communicate.data.whatsapp

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app diagnostics sink. The dev build strips Log output from logcat, so during pairing
 * we mirror every handshake step, server response and error into an observable list that the
 * login screen renders on-screen.
 */
object WhatsAppDiag {
    private const val MAX_ENTRIES = 300
    private val timeFmt = LocalTime.Format {
        hour(); char(':'); minute(); char(':'); second(); char('.'); secondFraction(3)
    }

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time.format(timeFmt)} $tag  $msg"
        _log.value = (_log.value + line).takeLast(MAX_ENTRIES)
        Log.i(tag, msg)
    }

    @Synchronized
    fun clear() {
        _log.value = emptyList()
    }

    /** Short hex preview of a byte array for inspecting raw frames. */
    fun preview(data: ByteArray, max: Int = 24): String {
        val n = minOf(data.size, max)
        val hex = StringBuilder()
        for (i in 0 until n) {
            hex.append(String.format("%02x", data[i]))
        }
        return "${data.size}B[${hex}${if (data.size > max) "…" else ""}]"
    }

    /**
     * Group sender-key (`skmsg`) self-loopback diagnostic (Phase A 1d). Exercises the exact Rust
     * sender-key wire that [WhatsAppE2E.encryptGroup]/[buildEncryptedGroupMessageNode] produce:
     *
     *   1. sender: create sender key → (state, SKDM)
     *   2. receiver: processSenderKey(SKDM) → receiver state
     *   3. sender: encryptGroup(state, padded) → skmsg ciphertext
     *   4. receiver: decryptGroup(receiverState, ciphertext) → plaintext
     *   5. assert plaintext == padded
     *
     * This validates the SKDM + skmsg round-trips through [RustWhatsAppCrypto] end-to-end on-device
     * (it requires the native `libcommunicate_signal` .so, so it is a dev diagnostic, NOT a JVM
     * unit test). Live peer interop still can't be validated because the test number is banned.
     * Returns true on a successful round-trip.
     */
    fun verifyGroupSenderKeyRoundTrip(): Boolean {
        return try {
            val plaintext = WhatsAppProtocol.padMessage("skmsg loopback probe".toByteArray(Charsets.UTF_8))
            val created = com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto.createSenderKeySplit()
            val receiverState = com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto.processSenderKey(created.skdm)
                ?: throw RuntimeException("processSenderKey returned null")
            val enc = com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto.encryptGroupSplit(created.state, plaintext)
            val dec = com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto.decryptGroupSplit(receiverState, enc.data)
            val ok = dec.data.contentEquals(plaintext)
            log("WA-SKMSG", "sender-key loopback ${if (ok) "PASS" else "FAIL"} skdm=${preview(created.skdm)} ct=${preview(enc.data)}")
            ok
        } catch (e: Exception) {
            log("WA-SKMSG", "sender-key loopback ERROR ${e.message}")
            false
        }
    }
}
