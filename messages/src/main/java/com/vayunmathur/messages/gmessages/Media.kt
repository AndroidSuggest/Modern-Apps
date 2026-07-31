package com.vayunmathur.messages.gmessages

import android.util.Base64
import android.util.Log
import authentication.Authentication
import authentication.Authentication.AuthMessage
import client.Client.AttachmentInfo
import client.Client.DownloadAttachmentRequest
import client.Client.StartMediaUploadRequest
import client.Client.UploadMediaResponse
import conversations.Conversations.MediaContent
import com.vayunmathur.library.network.NetworkClient
import conversations.Conversations.MediaFormats

/**
 * Port of `pkg/libgm/media.go` (mautrix-gmessages).
 *
 * MMS image send flow:
 *   1. Generate a fresh 32-byte AES-GCM key.
 *   2. Encrypt the bytes with the chunked-GCM stream framing from
 *      [AesGcm.encryptData].
 *   3. POST a START-UPLOAD with `x-goog-upload-command: start` and a
 *      base64'd [StartMediaUploadRequest] envelope as body. The
 *      response Content-Type isn't used — we read the
 *      `x-goog-upload-url` header and other resumable-upload metadata.
 *   4. POST the encrypted bytes to that URL with
 *      `x-goog-upload-command: upload, finalize` and offset 0. The
 *      response body is base64'd `UploadMediaResponse` proto carrying
 *      the assigned `mediaID`.
 *   5. Return a [MediaContent] referencing that media ID + the
 *      decryption key — the caller stuffs this into the
 *      [conversations.Conversations.MessageInfo.MediaContent] field of
 *      a SendMessageRequest.
 *
 * The Messages-for-Web client decrypts on download using the
 * [MediaContent.decryptionKey] we route through the SendMessageRequest.
 */
class Media(private val authProvider: () -> AuthData) {

    fun close() = Unit

    /** End-to-end: encrypt + start + finalize, return [MediaContent]. */
    suspend fun upload(data: ByteArray, fileName: String, mime: String): MediaContent {
        val format = mimeToFormat(mime)
        val key = AesGcm.generateKey()
        val cipher = AesGcm(key)
        val encrypted = cipher.encryptData(data)
        val start = startUpload(encrypted, mime)
        val finalized = finalizeUpload(start)
        Log.i(TAG, "media upload ok mime=$mime size=${data.size} mediaID=${finalized.mediaID}")
        return MediaContent.newBuilder()
            .setFormat(format)
            .setMediaID(finalized.mediaID)
            .setMediaName(fileName)
            .setSize(data.size.toLong())
            .setDecryptionKey(com.google.protobuf.ByteString.copyFrom(key))
            .setMimeType(mime)
            .build()
    }

    /**
     * Download and decrypt incoming media. Port of Go's `Client.DownloadMedia`.
     *
     * Sends a GET to the upload URL with an `x-goog-download-metadata` header
     * containing a base64-encoded [DownloadAttachmentRequest] proto. The
     * response body is AES-GCM encrypted bytes which we decrypt with [key].
     */
    suspend fun download(mediaId: String, key: ByteArray): ByteArray {
        val auth = authProvider()
        val downloadReq = DownloadAttachmentRequest.newBuilder()
            .setInfo(
                AttachmentInfo.newBuilder()
                    .setAttachmentID(mediaId)
                    .setEncrypted(true)
            )
            .setAuthData(
                AuthMessage.newBuilder()
                    .setRequestID(java.util.UUID.randomUUID().toString())
                    .setTachyonAuthToken(
                        com.google.protobuf.ByteString.copyFrom(
                            auth.tachyonToken()
                                ?: error("tachyon token is null — not paired or token expired")
                        )
                    )
                    .setNetwork(auth.authNetwork())
                    .setConfigVersion(PairFlow.ConfigVersion)
            )
            .build()
        val metadata = Base64.encodeToString(downloadReq.toByteArray(), Base64.NO_WRAP)
        val resp = NetworkClient.execute(
            url = Endpoints.UploadMediaUrl,
            method = "GET",
            headers = downloadHeaders(metadata),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (!resp.isSuccess) {
            error("media download HTTP ${resp.status}")
        }
        val encryptedBytes = resp.bytes
        val cipher = AesGcm(key)
        return cipher.decryptData(encryptedBytes)
    }

    /** Encode + base64 a StartMediaUploadRequest envelope. */
    private fun buildStartPayload(): String {
        val auth = authProvider()
        val req = StartMediaUploadRequest.newBuilder()
            .setAttachmentType(1)
            .setAuthData(
                AuthMessage.newBuilder()
                    .setRequestID(java.util.UUID.randomUUID().toString())
                    .setTachyonAuthToken(
                        com.google.protobuf.ByteString.copyFrom(
                            auth.tachyonToken()
                                ?: error("tachyon token is null — not paired or token expired")
                        )
                    )
                    .setNetwork(auth.authNetwork())
                    .setConfigVersion(PairFlow.ConfigVersion)
            )
        auth.mobile()?.let { req.setMobile(it) }
        return Base64.encodeToString(req.build().toByteArray(), Base64.NO_WRAP)
    }

    /** POST the START upload request. Returns the resumable URL + meta. */
    private suspend fun startUpload(encrypted: ByteArray, mime: String): StartedUpload {
        val payload = buildStartPayload()
        val sizeStr = encrypted.size.toString()
        val resp = NetworkClient.execute(
            url = Endpoints.UploadMediaUrl,
            method = "POST",
            headers = uploadHeaders(
                contentLength = sizeStr,
                command = "start",
                offset = null,
                contentMime = mime,
                protocol = "resumable",
            ),
            body = payload.toByteArray(Charsets.UTF_8),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (!resp.isSuccess) {
            error("start-upload HTTP ${resp.status}")
        }
        val uploadUrl = resp.header("x-goog-upload-url")
            ?: error("start-upload: missing x-goog-upload-url")
        return StartedUpload(
            uploadUrl = uploadUrl,
            chunkGranularity = resp.header("x-goog-upload-chunk-granularity")?.toLongOrNull() ?: 0L,
            mime = mime,
            encrypted = encrypted,
        )
    }

    /** POST the encrypted bytes to the resumable URL and decode the response. */
    private suspend fun finalizeUpload(start: StartedUpload): FinalizedUpload {
        val resp = NetworkClient.execute(
            url = start.uploadUrl,
            method = "POST",
            headers = uploadHeaders(
                contentLength = start.encrypted.size.toString(),
                command = "upload, finalize",
                offset = "0",
                contentMime = start.mime,
                protocol = null,
            ),
            body = start.encrypted,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (!resp.isSuccess) {
            error("finalize-upload HTTP ${resp.status}")
        }
        var bodyBytes = resp.bytes
        val parsed = try {
            UploadMediaResponse.parseFrom(bodyBytes)
        } catch (_: Throwable) {
            bodyBytes = Base64.decode(bodyBytes, Base64.NO_WRAP)
            UploadMediaResponse.parseFrom(bodyBytes)
        }
        return FinalizedUpload(
            mediaID = parsed.media.mediaID,
            mediaNumber = parsed.media.mediaNumber,
        )
    }

    /**
     * Apply the upload header bundle (port of util/func.go.NewMediaUploadHeaders).
     * The relay's anti-abuse layer inspects these alongside the URL +
     * Origin to decide whether the request looks like a real browser.
     */
    private fun uploadHeaders(
        contentLength: String,
        command: String?,
        offset: String?,
        contentMime: String?,
        protocol: String?,
    ): Map<String, String> = buildMap {
        put("sec-ch-ua", Endpoints.SecUA)
        if (protocol != null) put("x-goog-upload-protocol", protocol)
        put("x-goog-upload-header-content-length", contentLength)
        put("sec-ch-ua-mobile", Endpoints.SecUAMobile)
        put("user-agent", Endpoints.UserAgent)
        if (contentMime != null) put("x-goog-upload-header-content-type", contentMime)
        put("content-type", "application/x-www-form-urlencoded;charset=UTF-8")
        if (command != null) put("x-goog-upload-command", command)
        if (offset != null) put("x-goog-upload-offset", offset)
        put("sec-ch-ua-platform", "\"${Endpoints.UAPlatform}\"")
        put("accept", "*/*")
        put("origin", "https://messages.google.com")
        put("sec-fetch-site", "cross-site")
        put("sec-fetch-mode", "cors")
        put("sec-fetch-dest", "empty")
        put("referer", "https://messages.google.com/")
        put("accept-encoding", "gzip, deflate, br")
        put("accept-language", "en-US,en;q=0.9")
    }

    /** Port of util/func.go.BuildUploadHeaders — used for media download. */
    private fun downloadHeaders(metadata: String): Map<String, String> = linkedMapOf(
        "x-goog-download-metadata" to metadata,
        "sec-ch-ua" to Endpoints.SecUA,
        "sec-ch-ua-mobile" to Endpoints.SecUAMobile,
        "user-agent" to Endpoints.UserAgent,
        "sec-ch-ua-platform" to "\"${Endpoints.UAPlatform}\"",
        "accept" to "*/*",
        "origin" to "https://messages.google.com",
        "sec-fetch-site" to "cross-site",
        "sec-fetch-mode" to "cors",
        "sec-fetch-dest" to "empty",
        "referer" to "https://messages.google.com/",
        "accept-encoding" to "gzip, deflate, br",
        "accept-language" to "en-US,en;q=0.9",
    )

    /**
     * MIME-string → wire-enum mapping. Mirrors libgm's `MimeToMediaType`
     * map; only the formats we'll plausibly emit from the Compose
     * picker are listed. Falls back to the *_UNSPECIFIED bucket for the
     * top-level type so the relay still accepts the upload.
     */
    private fun mimeToFormat(mime: String): MediaFormats = when (mime.lowercase()) {
        "image/jpeg" -> MediaFormats.IMAGE_JPEG
        "image/jpg" -> MediaFormats.IMAGE_JPG
        "image/png" -> MediaFormats.IMAGE_PNG
        "image/gif" -> MediaFormats.IMAGE_GIF
        "image/wbmp" -> MediaFormats.IMAGE_WBMP
        "image/bmp", "image/x-ms-bmp" -> MediaFormats.IMAGE_X_MS_BMP
        "video/mp4" -> MediaFormats.VIDEO_MP4
        "video/3gpp" -> MediaFormats.VIDEO_3GPP
        "video/3gpp2" -> MediaFormats.VIDEO_3G2
        "video/webm" -> MediaFormats.VIDEO_WEBM
        "video/x-matroska" -> MediaFormats.VIDEO_MKV
        "audio/aac" -> MediaFormats.AUDIO_AAC
        "audio/amr" -> MediaFormats.AUDIO_AMR
        "audio/mp3" -> MediaFormats.AUDIO_MP3
        "audio/mpeg" -> MediaFormats.AUDIO_MPEG
        "audio/mpg" -> MediaFormats.AUDIO_MPG
        "audio/mp4" -> MediaFormats.AUDIO_MP4
        "audio/mp4-latm" -> MediaFormats.AUDIO_MP4_LATM
        "audio/3gpp" -> MediaFormats.AUDIO_3GPP
        "audio/ogg" -> MediaFormats.AUDIO_OGG
        "text/vcard" -> MediaFormats.TEXT_VCARD
        "application/pdf" -> MediaFormats.APP_PDF
        "text/plain" -> MediaFormats.APP_TXT
        "text/html" -> MediaFormats.APP_HTML
        "application/msword" -> MediaFormats.APP_DOC
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> MediaFormats.APP_DOCX
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> MediaFormats.APP_PPTX
        "application/vnd.ms-powerpoint" -> MediaFormats.APP_PPT
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> MediaFormats.APP_XLSX
        "application/vnd.ms-excel" -> MediaFormats.APP_XLS
        "application/vnd.android.package-archive" -> MediaFormats.APP_APK
        "application/zip" -> MediaFormats.APP_ZIP
        "application/java-archive" -> MediaFormats.APP_JAR
        "text/x-calendar" -> MediaFormats.CAL_TEXT_VCALENDAR
        "text/calendar" -> MediaFormats.CAL_TEXT_CALENDAR
        else -> when (mime.substringBefore('/').lowercase()) {
            "image" -> MediaFormats.IMAGE_UNSPECIFIED
            "video" -> MediaFormats.VIDEO_UNSPECIFIED
            "audio" -> MediaFormats.AUDIO_UNSPECIFIED
            "text" -> MediaFormats.APP_TXT
            else -> MediaFormats.APP_UNSPECIFIED
        }
    }

    private data class StartedUpload(
        val uploadUrl: String,
        val chunkGranularity: Long,
        val mime: String,
        val encrypted: ByteArray,
    )

    private data class FinalizedUpload(
        val mediaID: String,
        val mediaNumber: Long,
    )

    companion object {
        private const val TAG = "GMessages/Media"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 120_000L
    }
}
