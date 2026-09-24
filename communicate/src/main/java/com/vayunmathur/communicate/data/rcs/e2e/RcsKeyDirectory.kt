package com.vayunmathur.communicate.data.rcs.e2e

import android.content.Context
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Key-package directory over the RCS transport itself (no side channel).
 *
 * Publication: our fresh key package rides an unencrypted `message/cpim`
 * with content-type [RcsE2E.CT_KEY_PACKAGE] to the peer's 1:1 thread. Peers
 * cache the latest package per sender; group creation consumes them.
 *
 * Rotation: a new package is published whenever the previous one is consumed
 * (post-Welcome) — single-use init keys mean reuse fails closed at the
 * crate layer, so rotation-on-publish keeps adds working.
 */
object RcsKeyDirectory {
    /**
     * Publish our fresh key package to [recipientE164]'s 1:1 thread
     * (unencrypted pager-mode CPIM). Returns true when the SIP leg accepted.
     */
    suspend fun publishTo(context: Context, localE164: String, recipientE164: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return@withContext false
            val kp = RcsE2E.freshKeyPackage(context, localE164) ?: return@withContext false
            // Key package bytes are binary — base64 the payload into the CPIM text.
            val b64 = android.util.Base64.encodeToString(kp, android.util.Base64.NO_WRAP)
            val body = "Content-Type: ${RcsE2E.CT_KEY_PACKAGE}\r\n\r\n$b64"
            val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
                fromUri = "sip:me@rcs",
                toUri = "sip:$recipientE164@rcs",
                callId = "${UUID.randomUUID()}@rcs-kp",
                body = body,
            )
            runCatching { RcsSipTransport.sendSipMessage(startLine, headers, content) }
                .getOrDefault(false)
        }

    /**
     * Extract a key package from an inbound key-package message body, or null.
     * The CPIM text carries `Content-Type: application/x-rcs-keypackage`
     * followed by base64 TLS bytes.
     */
    fun parsePublished(body: String): ByteArray? {
        if (!body.contains(RcsE2E.CT_KEY_PACKAGE, ignoreCase = true)) return null
        val b64 = body.substringAfter("\r\n\r\n", "").ifBlank {
            body.substringAfter("\n\n", "")
        }.trim().takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
