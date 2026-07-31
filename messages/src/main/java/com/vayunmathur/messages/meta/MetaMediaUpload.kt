package com.vayunmathur.messages.meta

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.vayunmathur.library.network.NetworkClient
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * Real media upload (#14). Replaces the base64-in-text hack with the actual
 * upload endpoints used by messagix:
 *
 *  - Messenger: multipart POST to /ajax/mercury/upload.php (mercury.go) which
 *    returns file metadata containing the attachment fbid.
 *  - Instagram: a resumable "rupload" octet-stream POST to
 *    rupload.facebook.com/messenger_image/ (instagram.go EditGroupAvatar
 *    pattern) which returns an RUploadResponse media_id.
 *
 * The returned id is used as attachment_fbids in the SendMediaTask.
 *
 * // UNVERIFIED: requires live cookies + bootstrap tokens (lsd/fb_dtsg) and a
 * // real network round-trip; the request shape mirrors the Go reference.
 */
object MetaMediaUpload {
    private const val TAG = "MetaMediaUpload"
    private const val IG_RUPLOAD_URL = "https://rupload.facebook.com/messenger_image/"
    private const val ANTI_JS_PREFIX = "for (;;);"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Uploads [bytes] and returns the attachment fbid to reference in a send
     * task, or null on failure.
     */
    suspend fun upload(
        authData: MetaAuthData,
        config: MetaConfig,
        threadId: Long,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ): Long? = withContext(Dispatchers.IO) {
        try {
            when (authData.platform) {
                MetaAuthData.Platform.MESSENGER ->
                    uploadMercury(authData, config, threadId, bytes, mimeType, fileName)
                MetaAuthData.Platform.INSTAGRAM ->
                    uploadRupload(authData, config, bytes, mimeType)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Media upload failed for ${authData.platform}", e)
            null
        }
    }

    private suspend fun uploadMercury(
        authData: MetaAuthData,
        config: MetaConfig,
        threadId: Long,
        bytes: ByteArray,
        mimeType: String,
        fileName: String?,
    ): Long? {
        val boundary = "----WebKitFormBoundary" + randomAlphaNum(16)
        val body = multipartFormData(boundary, "farr", fileName ?: "attachment", mimeType, bytes)

        val query = buildMercuryQuery(authData, config)
        val url = MetaProtocol.MESSENGER_BASE_URL + "/ajax/mercury/upload.php?" + query
        val referer = MetaProtocol.MESSENGER_BASE_URL + "/t/" + threadId

        val headers = buildMap {
            put("Cookie", authData.toCookieHeader())
            put("User-Agent", MetaProtocol.USER_AGENT)
            put("Accept", "*/*")
            put("Content-Type", "multipart/form-data; boundary=$boundary")
            put("Origin", MetaProtocol.MESSENGER_BASE_URL)
            put("Referer", referer)
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
            if (config.lsdToken.isNotEmpty()) put("x-fb-lsd", config.lsdToken)
        }

        val resp = NetworkClient.execute(url, "POST", headers, body)
        if (!resp.isSuccess) {
            Log.w(TAG, "Mercury upload returned ${resp.status}")
            return null
        }

        val jsonStr = resp.text.removePrefix(ANTI_JS_PREFIX)
        val root = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: return null
        val payload = root["payload"]?.let { it as? JsonObject } ?: return null
        val metadata = payload["metadata"] ?: return null
        return extractFbid(metadata)
    }

    private suspend fun uploadRupload(
        authData: MetaAuthData,
        config: MetaConfig,
        bytes: ByteArray,
        mimeType: String,
    ): Long? {
        val userId = authData.userId
        val entityId = "${userId}_0_${MetaProtocol.generateEpochId()}"
        val entityType = if (mimeType == "image/png") "image/png" else "image/jpeg"
        val url = IG_RUPLOAD_URL + entityId

        val headers = buildMap {
            put("Cookie", authData.toCookieHeader())
            put("User-Agent", MetaProtocol.USER_AGENT)
            put("content-type", "application/octet-stream")
            put("image_type", "FILE_ATTACHMENT")
            put("x-entity-name", entityId)
            put("x-entity-length", bytes.size.toString())
            put("x-entity-type", entityType)
            put("offset", "0")
            put("priority", "u=6, i")
            authData.cookies["csrftoken"]?.let { put("x-csrftoken", it) }
            authData.cookies["mid"]?.let { put("x-mid", it) }
            if (config.appId != 0L) put("x-ig-app-id", config.appId.toString())
        }

        val resp = NetworkClient.execute(url, "POST", headers, bytes)
        if (!resp.isSuccess) {
            Log.w(TAG, "rupload returned ${resp.status}")
            return null
        }
        val respBody = resp.text

        val root = runCatching { json.parseToJsonElement(respBody).jsonObject }.getOrNull() ?: return null
        val mediaId = root["media_id"]?.jsonPrimitive?.longOrNull
            ?: root["upload_id"]?.jsonPrimitive?.content?.toLongOrNull()
        if (mediaId == null || mediaId == 0L) {
            Log.w(TAG, "rupload response had no media id: $respBody")
            return null
        }
        return mediaId
    }

    // Mercury metadata is an array (images) or object keyed by "0" (videos),
    // each entry carrying *_id fields. Ref types/mercury.go GetFbId.
    private fun extractFbid(metadata: kotlinx.serialization.json.JsonElement): Long? {
        val entry: JsonObject? = when (metadata) {
            is JsonArray -> metadata.firstOrNull()?.let { it as? JsonObject }
            is JsonObject -> metadata["0"]?.let { it as? JsonObject }
            else -> null
        }
        if (entry == null) return null
        val keys = listOf("video_id", "audio_id", "image_id", "gif_id", "file_id", "fbid")
        for (k in keys) {
            val v = entry[k]?.jsonPrimitive ?: continue
            val id = v.longOrNull ?: v.content.toLongOrNull()
            if (id != null && id != 0L) return id
        }
        return null
    }

    /**
     * Encodes a single-file `multipart/form-data` body, replacing okhttp's
     * MultipartBody.Builder. Mirrors what it emitted: CRLF line endings, a
     * Content-Disposition naming the form field and filename, a Content-Type
     * for the part, and a closing `--boundary--` delimiter. The caller sets the
     * matching `Content-Type: multipart/form-data; boundary=...` header.
     */
    private fun multipartFormData(
        boundary: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val header = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"").append(fieldName)
                .append("\"; filename=\"").append(escapeQuotes(fileName)).append("\"\r\n")
            if (mimeType.isNotEmpty()) append("Content-Type: ").append(mimeType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /** okhttp percent-escapes quotes/newlines in part filenames; do the same. */
    private fun escapeQuotes(s: String): String =
        s.replace("\n", "%0A").replace("\r", "%0D").replace("\"", "%22")

    private fun buildMercuryQuery(authData: MetaAuthData, config: MetaConfig): String {
        val params = LinkedHashMap<String, String>()
        params["__a"] = "1"
        if (authData.userId.isNotEmpty()) params["__user"] = authData.userId
        if (config.lsdToken.isNotEmpty()) params["lsd"] = config.lsdToken
        if (config.fbDtsg.isNotEmpty()) params["fb_dtsg"] = config.fbDtsg
        if (config.jazoest.isNotEmpty()) params["jazoest"] = config.jazoest
        return params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun randomAlphaNum(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return buildString(length) {
            repeat(length) { append(chars[rnd.nextInt(chars.length)]) }
        }
    }
}
