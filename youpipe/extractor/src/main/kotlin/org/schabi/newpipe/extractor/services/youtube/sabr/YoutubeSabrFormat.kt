package org.schabi.newpipe.extractor.services.youtube.sabr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.Serializable
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder

class YoutubeSabrFormat private constructor(
    @JvmField val itag: Int,
    @JvmField val lastModified: Long,
    @JvmField val xtags: String?,
    @JvmField val mimeType: String?,
    @JvmField val audioTrackId: String?,
    @JvmField val audioTrackDisplayName: String?,
    private val audioIsDefaultInternal: Boolean,
    @JvmField val qualityLabel: String?,
    @JvmField val audioQuality: String?,
    private val drcInternal: Boolean,
    @JvmField val width: Int,
    @JvmField val height: Int,
    @JvmField val bitrate: Int,
    @JvmField val contentLength: Long,
    @JvmField val approxDurationMs: Long,
    @JvmField var initializationUrl: String?,
    @JvmField val initializationUrlTemplate: String?,
    @JvmField val obfuscatedSignature: String?,
    @JvmField val signatureParameter: String?,
    @JvmField val obfuscatedNParameter: String?,
    @JvmField val initRangeStart: Long,
    @JvmField val initRangeEnd: Long
) : Serializable {

    val isAudio: Boolean
        get() = mimeType != null && mimeType.contains("audio")

    val isVideo: Boolean
        get() = mimeType != null && mimeType.contains("video")

    val isAudioDefault: Boolean
        get() = audioIsDefaultInternal

    val isDrc: Boolean
        get() = drcInternal

    val isOriginalAudio: Boolean
        get() = audioTrackDisplayName != null &&
            (audioTrackDisplayName.contains("original") || audioTrackDisplayName.contains("yokuqala"))

    companion object {
        private const val serialVersionUID = 1L

        @JvmStatic
        internal fun fromAdaptiveFormats(
            videoId: String,
            formats: JsonArray?
        ): List<YoutubeSabrFormat> {
            val result = parseAdaptiveFormats(formats)
            return resolveInitializationUrls(videoId, result)
        }

        @JvmStatic
        internal fun parseAdaptiveFormats(formats: JsonArray?): List<YoutubeSabrFormat> {
            val result = mutableListOf<YoutubeSabrFormat>()
            if (formats == null) return result
            for (i in 0 until formats.size) {
                val element = formats[i]
                if (element !is JsonObject) continue
                if (!element.containsKey("itag")) continue
                result.add(fromJson(element))
            }
            return result
        }

        private fun fromJson(format: JsonObject): YoutubeSabrFormat {
            val audioTrack = format.getObject("audioTrack")
            val initRange = format.getObject("initRange")
            val indexRange = format.getObject("indexRange")
            val initRangeStart = if (initRange == null) -1L else parseLong(initRange["start"])
            var initRangeEnd = if (initRange == null) -1L else parseLong(initRange["end"])
            if (indexRange != null) {
                val indexRangeEnd = parseLong(indexRange["end"])
                if (indexRangeEnd > initRangeEnd) {
                    initRangeEnd = indexRangeEnd
                }
            }
            val urlParts = StreamingUrlParts.fromJson(format)
            return YoutubeSabrFormat(
                itag = format.getIntCompat("itag"),
                lastModified = parseLong(format["lastModified"]),
                xtags = format.getString("xtags"),
                mimeType = format.getString("mimeType"),
                audioTrackId = audioTrack?.getString("id"),
                audioTrackDisplayName = audioTrack?.getString("displayName"),
                audioIsDefaultInternal = audioTrack?.getBoolean("audioIsDefault", false) ?: false,
                qualityLabel = format.getString("qualityLabel"),
                audioQuality = format.getString("audioQuality"),
                drcInternal = format.getBoolean("isDrc", false) ?: false,
                width = format.getIntCompat("width", -1),
                height = format.getIntCompat("height", -1),
                bitrate = format.getIntCompat("bitrate", -1),
                contentLength = parseLong(format["contentLength"]),
                approxDurationMs = parseLong(format["approxDurationMs"]),
                initializationUrl = if (urlParts.isResolved()) urlParts.url else null,
                initializationUrlTemplate = urlParts.url,
                obfuscatedSignature = urlParts.signature,
                signatureParameter = urlParts.signatureParameter,
                obfuscatedNParameter = urlParts.nParameter,
                initRangeStart = initRangeStart,
                initRangeEnd = initRangeEnd
            )
        }

        @JvmStatic
        internal fun resolveInitializationUrls(
            videoId: String,
            formats: List<YoutubeSabrFormat>
        ): List<YoutubeSabrFormat> {
            val signatures = LinkedHashSet<String>()
            val nParameters = LinkedHashSet<String>()
            collectDecodeParameters(formats, signatures, nParameters)
            if (signatures.isEmpty() && nParameters.isEmpty()) {
                return formats
            }
            val decoded = YoutubeJavaScriptPlayerManager.deobfuscateBatch(
                videoId,
                ArrayList(signatures),
                ArrayList(nParameters)
            )
            resolveInitializationUrls(formats, decoded)
            return formats
        }

        @JvmStatic
        internal fun collectDecodeParameters(
            formats: Collection<YoutubeSabrFormat>,
            signatures: MutableSet<String>,
            nParameters: MutableSet<String>
        ) {
            for (format in formats) {
                if (format.obfuscatedSignature != null) {
                    signatures.add(format.obfuscatedSignature)
                }
                if (format.obfuscatedNParameter != null) {
                    nParameters.add(format.obfuscatedNParameter)
                }
            }
        }

        @JvmStatic
        internal fun resolveInitializationUrls(
            formats: Collection<YoutubeSabrFormat>,
            decoded: YoutubeApiDecoder.BatchDecodeResult
        ) {
            for (format in formats) {
                format.resolveInitializationUrl(decoded)
            }
        }

        @JvmStatic
        internal fun resolveNParameter(
            url: String?,
            decoded: YoutubeApiDecoder.BatchDecodeResult
        ): String? {
            if (url == null || url.isEmpty()) return url
            val encryptedN = extractNParameter(url) ?: return url
            val decryptedN = decoded.nParameters[encryptedN] ?: return url
            val queryMatch = Regex("([?&])n=([^&]+)").find(url)
            if (queryMatch != null) {
                val g2 = queryMatch.groups[2] ?: return url
                return url.substring(0, g2.range.first) + urlEncode(decryptedN) +
                    url.substring(g2.range.last + 1)
            }
            val pathMatch = Regex("/n/([^/?#]+)").find(url) ?: return url
            val g1 = pathMatch.groups[1] ?: return url
            return url.substring(0, g1.range.first) + decryptedN + url.substring(g1.range.last + 1)
        }

        @JvmStatic
        internal fun extractNParameter(url: String?): String? {
            if (url == null || url.isEmpty()) return null
            val queryMatch = Regex("([?&])n=([^&]+)").find(url)
            if (queryMatch != null) {
                val raw = queryMatch.groups[2]?.value ?: return null
                return urlDecode(raw)
            }
            val pathMatch = Regex("/n/([^/?#]+)").find(url)
            return pathMatch?.groups?.get(1)?.value
        }

        internal fun parseQuery(value: String?): Map<String, String> {
            val params = HashMap<String, String>()
            if (value == null || value.isEmpty()) return params
            for (part in value.split("&")) {
                val equals = part.indexOf('=')
                if (equals <= 0) continue
                params[urlDecode(part.substring(0, equals))] =
                    urlDecode(part.substring(equals + 1))
            }
            return params
        }

        internal fun urlEncode(value: String): String {
            try {
                return URLEncoder.encode(value, Charsets.UTF_8.name())
            } catch (e: UnsupportedEncodingException) {
                throw ParsingException("Could not encode SABR signature cipher", e)
            }
        }

        internal fun urlDecode(value: String): String {
            try {
                return URLDecoder.decode(value, Charsets.UTF_8.name())
            } catch (e: UnsupportedEncodingException) {
                throw ParsingException("Could not decode SABR signature cipher", e)
            }
        }

        internal fun parseLong(value: JsonElement?): Long {
            if (value == null) return -1
            if (value is JsonPrimitive) {
                value.longOrNull?.let { return it }
                val content = value.content
                try {
                    return content.toLong()
                } catch (ignored: NumberFormatException) {
                    return -1
                }
            }
            return -1
        }

        internal fun JsonObject.getIntCompat(key: String): Int {
            val el = this[key] as? JsonPrimitive ?: return -1
            return el.content.toIntOrNull() ?: el.longOrNull?.toInt() ?: -1
        }

        internal fun JsonObject.getIntCompat(key: String, default: Int): Int {
            val el = this[key] ?: return default
            if (el !is JsonPrimitive) return default
            return el.content.toIntOrNull() ?: el.longOrNull?.toInt() ?: default
        }
    }

    private fun resolveInitializationUrl(decoded: YoutubeApiDecoder.BatchDecodeResult) {
        var url = initializationUrlTemplate
        if (url == null || url.isEmpty()) {
            initializationUrl = url
            return
        }
        if (obfuscatedSignature != null) {
            val signature = decoded.signatures[obfuscatedSignature]
            if (signature == null) {
                initializationUrl = null
                return
            }
            val separator = if (url.contains("?")) "&" else "?"
            url = url + separator + urlEncode(signatureParameter ?: "signature") + '=' + urlEncode(signature)
        }
        initializationUrl = resolveNParameter(url, decoded)
    }

    private class StreamingUrlParts(
        val url: String?,
        val signature: String?,
        val signatureParameter: String?,
        val nParameter: String?
    ) {
        companion object {
            fun fromJson(format: JsonObject): StreamingUrlParts {
                var url: String? = format.getString("url")
                var signature: String? = null
                var signatureParameter: String? = null
                val cipherValue = when {
                    format.containsKey("signatureCipher") -> format.getString("signatureCipher")
                    else -> format.getString("cipher")
                }
                if ((url == null || url.isEmpty()) && !cipherValue.isNullOrEmpty()) {
                    val cipher = parseQuery(cipherValue)
                    url = cipher["url"]
                    signature = cipher["s"]
                    signatureParameter = cipher.getOrDefault("sp", "signature")
                }
                val nParam = extractNParameter(url)
                return StreamingUrlParts(url, signature, signatureParameter, nParam)
            }
        }

        fun isResolved(): Boolean = signature == null && nParameter == null
    }
}
