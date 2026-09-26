package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.cacheOutgoingRcsFile
import com.vayunmathur.library.network.NetworkClient
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FT-over-HTTP (RCC.07 Annex: file transfer via the carrier content server).
 *
 * Upload flow mirrors TestRcsApp's `FileUploadController`, rebuilt on this
 * repo's `NetworkClient` (no Apache HttpClient / Guava): empty POST →
 * 401 digest challenge → authenticated multipart POST (`tid` + `File` parts)
 * → server returns the download URL. The URL + metadata ride a CPIM
 * file-transfer message so the far end can fetch; inbound FT messages expose
 * [RcsFtInfo] for the attachment renderer + a [download] helper.
 *
 * The content-server URI comes from provisioning (RCS config XML
 * `ftHTTPCSURI`); without it, FT degrades to the v1 envelope path.
 */
object RcsFileTransferHttp {
    private const val TAG = "RcsFtHttp"

    /** Parsed inbound file-transfer descriptor. */
    data class RcsFtInfo(
        val url: String,
        val mime: String,
        val fileName: String?,
        val fileSize: Long?,
    )

    /** Content-server base URI from provisioning, or null when unknown. */
    @Volatile
    var contentServerUri: String? = null

    /**
     * Upload [attachment] bytes to the content server. Returns the download
     * URL on success, null otherwise. Never throws.
     */
    suspend fun upload(
        context: Context,
        attachment: CommunicateAttachment,
    ): String? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        val server = contentServerUri?.takeIf { it.isNotBlank() } ?: return@withContext null
        runCatching {
            val bytes = context.contentResolver.openInputStream(attachment.contentUri.toUri())
                ?.use { it.readBytes() } ?: return@runCatching null
            // 1. Empty POST to harvest the digest challenge.
            val probe = NetworkClient.performRequest(server, "POST", useSystemTrust = true)
            val challenge = probe.headers.entries
                .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                ?.value?.firstOrNull()
                ?: return@runCatching null
            // 2. Authenticated multipart POST (tid + File). GBA-shaped creds
            // when a privileged build injected them, else the plain-digest
            // fallback (empty password). Live GBA bootstrapping is unreachable
            // from a non-privileged app (hidden API blocklist — see RcsGbaAuth).
            val boundary = "rcsft${UUID.randomUUID().toString().replace("-", "").take(16)}"
            val tid = UUID.randomUUID().toString()
            val multipart = buildMultipart(
                boundary = boundary,
                tid = tid,
                fileName = attachment.fileName ?: "file",
                mime = attachment.mimeType,
                bytes = bytes,
            )
            val gba = RcsGbaAuth.injected
            val auth = digestAuthHeader(
                challenge = challenge,
                method = "POST",
                uri = server,
                username = gba?.btId ?: "rcs",
                password = gba?.let {
                    android.util.Base64.encodeToString(it.key, android.util.Base64.NO_WRAP)
                }.orEmpty(),
            )
            val response = NetworkClient.performRequest(
                url = server,
                method = "POST",
                headers = mapOf(
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                    "Authorization" to auth,
                    "User-Agent" to "Communicate-RCS/1.0",
                ),
                body = multipart,
                useSystemTrust = true,
            )
            if (!response.isSuccess) return@runCatching null
            // Server answers with the file URL (plain or XML-wrapped).
            Regex("https?://[^\\s\"'<>]+").find(response.body)?.value
        }.getOrElse {
            Log.w(TAG, "FT upload failed", it)
            null
        }
    }

    /**
     * Download [url] to the app cache and return the file. Never throws.
     */
    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || url.isBlank()) return@withContext null
        runCatching {
            val (status, bytes) = NetworkClient.performRequestBytes(url, useSystemTrust = true)
            if (status !in 200..299 || bytes.isEmpty()) return@runCatching null
            val dir = File(context.cacheDir, "rcs-ft").apply { mkdirs() }
            val file = File(dir, "dl-${UUID.randomUUID()}")
            file.writeBytes(bytes)
            file
        }.getOrElse {
            Log.w(TAG, "FT download failed", it)
            null
        }
    }

    /**
     * Send a file to [recipient]: upload, then a CPIM FT message carrying the
     * URL + metadata, then cache the outgoing row. Returns true when the SIP
     * leg was accepted.
     */
    suspend fun sendFile(
        context: Context,
        repository: CommunicateRepository,
        recipient: String,
        attachment: CommunicateAttachment,
        caption: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return@withContext false
        val url = upload(context, attachment) ?: return@withContext false
        val ftCpim = buildString {
            append("File-URL: $url\r\n")
            append("File-MIME: ${attachment.mimeType}\r\n")
            attachment.fileName?.let { append("File-Name: $it\r\n") }
            if (caption.isNotBlank()) append("\r\n$caption")
        }
        val (startLine, headers, content) = RcsSipTransport.buildChatMessage(
            fromUri = "sip:me@rcs",
            toUri = "sip:$recipient@rcs",
            callId = "${UUID.randomUUID()}@rcs-ft",
            body = ftCpim,
        )
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (ok) {
            repository.cacheOutgoingRcsFile(
                context = context,
                conversationId = recipient,
                body = caption.ifBlank { attachment.fileName ?: "[file]" },
                messageId = "local-ft-${UUID.randomUUID()}",
                ftUrl = url,
                ftMime = attachment.mimeType,
            )
        }
        ok
    }

    /** Parse an inbound FT CPIM body into [RcsFtInfo], or null. */
    fun parseFtBody(body: String): RcsFtInfo? {
        val url = Regex("File-URL:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(body)
            ?.groupValues?.getOrNull(1)?.trim() ?: return null
        val mime = Regex("File-MIME:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(body)
            ?.groupValues?.getOrNull(1)?.trim() ?: "application/octet-stream"
        val name = Regex("File-Name:\\s*(.+)", RegexOption.IGNORE_CASE).find(body)
            ?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val size = Regex("File-Size:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(body)
            ?.groupValues?.getOrNull(1)?.toLongOrNull()
        return RcsFtInfo(url = url, mime = mime, fileName = name, fileSize = size)
    }

    private fun buildMultipart(
        boundary: String,
        tid: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun part(headers: String, content: ByteArray) {
            out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write(content)
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        part(
            "Content-Disposition: form-data; name=\"tid\"\r\nContent-Type: text/plain",
            tid.toByteArray(Charsets.UTF_8),
        )
        part(
            "Content-Disposition: form-data; name=\"File\"; filename=\"$fileName\"\r\nContent-Type: $mime",
            bytes,
        )
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * Digest Authorization header from a WWW-Authenticate challenge. [password]
     * is the GBA-derived base64 key when bootstrapped, empty otherwise (the
     * plain-digest fallback).
     */
    private fun digestAuthHeader(
        challenge: String,
        method: String,
        uri: String,
        username: String,
        password: String = "",
    ): String {
        if (!challenge.contains("Digest", ignoreCase = true)) return ""
        fun param(name: String): String =
            Regex("$name=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(challenge)
                ?.groupValues?.getOrNull(1).orEmpty()
        val realm = param("realm")
        val nonce = param("nonce")
        if (realm.isBlank() || nonce.isBlank()) return ""
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val response = RcsGbaAuth.digestResponse(username, password, realm, nonce, method, uri, cnonce)
        return "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", " +
            "response=\"$response\", qop=auth, nc=00000001, cnonce=\"$cnonce\""
    }

    /** Pull the FT content-server URI out of RCS config XML (ftHTTPCSURI). */
    fun parseContentServer(configXml: ByteArray): String? {
        val xml = configXml.toString(Charsets.UTF_8)
        return Regex("<ftHTTPCSURI>([^<]+)</ftHTTPCSURI>", RegexOption.IGNORE_CASE).find(xml)
            ?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Expose the uploader URI type for tests without leaking Uri internals. */
    @Suppress("unused")
    private fun uriOf(s: String): Uri = s.toUri()
}
