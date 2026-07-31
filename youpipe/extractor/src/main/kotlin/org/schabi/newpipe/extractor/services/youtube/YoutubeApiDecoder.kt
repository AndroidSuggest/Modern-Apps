package org.schabi.newpipe.extractor.services.youtube

import java.io.IOException
import java.net.URLEncoder
import javax.annotation.Nonnull
import javax.annotation.Nullable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.utils.orEmptyArray
import org.schabi.newpipe.extractor.utils.orEmptyObject

/**
 * Decoder for YouTube signature and throttling parameters using the PipePipe API.
 *
 * This class replaces the local JavaScript-based decoding with API calls to
 * https://api.pipepipe.dev/decoder/decode
 */
object YoutubeApiDecoder {

    private const val API_BASE_URL = "https://api.pipepipe.dev/decoder/decode"
    private const val USER_AGENT = "PipePipe/4.9.0"

    @JvmStatic
    @Nonnull
    private val DECODE_CACHE: MutableMap<String, String> = HashMap()

    @Nullable
    @Volatile
    private var localDecoder: YoutubeJavaScriptDecoder? = null

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun decodeSignature(
        @Nonnull playerId: String,
        @Nonnull signature: String
    ): String {
        return decode(playerId, "sig", signature)
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun decodeThrottlingParameter(
        @Nonnull playerId: String,
        @Nonnull nParameter: String
    ): String {
        return decode(playerId, "n", nParameter)
    }

    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    private fun decode(
        @Nonnull playerId: String,
        @Nonnull paramType: String,
        @Nonnull value: String
    ): String {
        val cacheKey = "$playerId:$paramType:$value"
        DECODE_CACHE[cacheKey]?.let { return it }

        val decoder = localDecoder
        if (decoder != null) {
            try {
                val result = decoder.decodeBatch(
                    playerId,
                    if ("sig" == paramType) listOf(value) else null,
                    if ("n" == paramType) listOf(value) else null
                )
                val decodedValue = if ("sig" == paramType) {
                    result.signatures[value]
                } else {
                    result.nParameters[value]
                }
                if (decodedValue.isNullOrEmpty()) {
                    throw ParsingException("Local decoder returned empty value for: $value")
                }
                DECODE_CACHE[cacheKey] = decodedValue
                return decodedValue
            } catch (localFailure: Exception) {
                disableLocalDecoder(decoder)
            }
        }

        try {
            val encodedValue = URLEncoder.encode(value, Charsets.UTF_8.name())
            val url = "$API_BASE_URL?player=$playerId&$paramType=$encodedValue"

            val headers: MutableMap<String, List<String>> = HashMap()
            headers["User-Agent"] = listOf(USER_AGENT)

            val response = NewPipe.getDownloader().get(url, headers, Localization.DEFAULT)
            val responseBody = response.responseBody()
            val jsonResponse = JsonUtils.toJsonObject(responseBody)

            if ("result" != jsonResponse.getString("type")) {
                throw ParsingException("API returned unexpected type: ${jsonResponse.getString("type")}")
            }

            val firstResponse = jsonResponse.getArray("responses").orEmptyArray().getObject(0).orEmptyObject()
            if ("result" != firstResponse.getString("type")) {
                throw ParsingException(
                    "API response item has unexpected type: ${firstResponse.getString("type")}"
                )
            }

            val data = firstResponse.getObject("data").orEmptyObject()
            val decodedValue = data.getString(value)

            if (decodedValue.isNullOrEmpty()) {
                throw ParsingException("API returned empty decoded value for: $value")
            }

            DECODE_CACHE[cacheKey] = decodedValue
            return decodedValue
        } catch (e: IOException) {
            throw ParsingException("Failed to call decode API", e)
        } catch (e: ParsingException) {
            throw e
        } catch (e: Exception) {
            throw ParsingException("Unexpected error during decoding", e)
        }
    }

    @JvmStatic
    fun clearCache() {
        DECODE_CACHE.clear()
    }

    private fun disableLocalDecoder(decoder: YoutubeJavaScriptDecoder) {
        if (localDecoder === decoder) {
            localDecoder = null
            clearCache()
        }
    }

    @JvmStatic
    fun setLocalDecoder(@Nullable decoder: YoutubeJavaScriptDecoder?) {
        localDecoder = decoder
        clearCache()
    }

    @JvmStatic
    @Nullable
    fun getLocalDecoder(): YoutubeJavaScriptDecoder? {
        return localDecoder
    }

    @JvmStatic
    fun getCacheSize(): Int {
        return DECODE_CACHE.size
    }

    /**
     * Batch decode multiple signatures and throttling parameters in a single API call.
     */
    @JvmStatic
    @Nonnull
    @Throws(ParsingException::class)
    fun decodeBatch(
        @Nonnull playerId: String,
        @Nullable signatureParams: List<String>?,
        @Nullable nParams: List<String>?
    ): BatchDecodeResult {
        val decoder = localDecoder
        if (decoder != null) {
            try {
                return decoder.decodeBatch(playerId, signatureParams, nParams)
            } catch (localFailure: Exception) {
                disableLocalDecoder(decoder)
            }
        }

        val hasSigs = !signatureParams.isNullOrEmpty()
        val hasNs = !nParams.isNullOrEmpty()

        if (!hasSigs && !hasNs) {
            return BatchDecodeResult(HashMap(), HashMap())
        }

        val sigResults: MutableMap<String, String> = HashMap()
        val nResults: MutableMap<String, String> = HashMap()
        val uncachedSigs: MutableList<String> = ArrayList()
        val uncachedNs: MutableList<String> = ArrayList()

        if (hasSigs) {
            for (sig in signatureParams!!) {
                val cachedResult = DECODE_CACHE["$playerId:sig:$sig"]
                if (cachedResult != null) {
                    sigResults[sig] = cachedResult
                } else {
                    uncachedSigs.add(sig)
                }
            }
        }

        if (hasNs) {
            for (n in nParams!!) {
                val cachedResult = DECODE_CACHE["$playerId:n:$n"]
                if (cachedResult != null) {
                    nResults[n] = cachedResult
                } else {
                    uncachedNs.add(n)
                }
            }
        }

        if (uncachedSigs.isEmpty() && uncachedNs.isEmpty()) {
            return BatchDecodeResult(sigResults, nResults)
        }

        try {
            val urlBuilder = StringBuilder(API_BASE_URL)
            urlBuilder.append("?player=").append(playerId)

            if (uncachedNs.isNotEmpty()) {
                urlBuilder.append("&n=")
                for (i in uncachedNs.indices) {
                    if (i > 0) urlBuilder.append(',')
                    urlBuilder.append(URLEncoder.encode(uncachedNs[i], Charsets.UTF_8.name()))
                }
            }

            if (uncachedSigs.isNotEmpty()) {
                urlBuilder.append("&sig=")
                for (i in uncachedSigs.indices) {
                    if (i > 0) urlBuilder.append(',')
                    urlBuilder.append(URLEncoder.encode(uncachedSigs[i], Charsets.UTF_8.name()))
                }
            }

            val headers: MutableMap<String, List<String>> = HashMap()
            headers["User-Agent"] = listOf(USER_AGENT)

            val response = NewPipe.getDownloader().get(urlBuilder.toString(), headers, Localization.DEFAULT)
            val responseBody = response.responseBody()
            val jsonResponse = JsonUtils.toJsonObject(responseBody)

            if ("result" != jsonResponse.getString("type")) {
                throw ParsingException("API returned unexpected type: ${jsonResponse.getString("type")}")
            }

            val responses = jsonResponse.getArray("responses").orEmptyArray()

            var responseIndex = 0
            if (uncachedNs.isNotEmpty()) {
                val nResponse = responses.getObject(responseIndex++).orEmptyObject()
                if ("result" != nResponse.getString("type")) {
                    throw ParsingException(
                        "N parameter response has unexpected type: ${nResponse.getString("type")}"
                    )
                }

                val nData = nResponse.getObject("data").orEmptyObject()
                for (nParam in uncachedNs) {
                    val decodedValue = nData.getString(nParam)
                    if (decodedValue.isNullOrEmpty()) {
                        throw ParsingException(
                            "API returned empty decoded value for n parameter: $nParam"
                        )
                    }
                    nResults[nParam] = decodedValue
                    DECODE_CACHE["$playerId:n:$nParam"] = decodedValue
                }
            }

            if (uncachedSigs.isNotEmpty()) {
                val sigResponse = responses.getObject(responseIndex).orEmptyObject()
                if ("result" != sigResponse.getString("type")) {
                    throw ParsingException(
                        "Signature response has unexpected type: ${sigResponse.getString("type")}"
                    )
                }

                val sigData = sigResponse.getObject("data").orEmptyObject()
                for (sig in uncachedSigs) {
                    val decodedValue = sigData.getString(sig)
                    if (decodedValue.isNullOrEmpty()) {
                        throw ParsingException(
                            "API returned empty decoded value for signature: $sig"
                        )
                    }
                    sigResults[sig] = decodedValue
                    DECODE_CACHE["$playerId:sig:$sig"] = decodedValue
                }
            }

            return BatchDecodeResult(sigResults, nResults)
        } catch (e: IOException) {
            throw ParsingException("Failed to call batch decode API", e)
        } catch (e: ParsingException) {
            throw e
        } catch (e: Exception) {
            throw ParsingException("Unexpected error during batch decoding", e)
        }
    }

    /**
     * Result class for batch decode operations.
     */
    class BatchDecodeResult(
        @field:Nonnull
        @JvmField
        val signatures: Map<String, String> = emptyMap(),
        @field:Nonnull
        @JvmField
        val nParameters: Map<String, String> = emptyMap()
    ) {
        @Nonnull
        fun getSignatures(): Map<String, String> = signatures

        @Nonnull
        fun getNParameters(): Map<String, String> = nParameters
    }
}
