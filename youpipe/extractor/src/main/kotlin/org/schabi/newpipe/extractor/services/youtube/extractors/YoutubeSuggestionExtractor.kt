package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.Utils
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getString

class YoutubeSuggestionExtractor(service: StreamingService) : SuggestionExtractor(service) {

    @Throws(java.io.IOException::class, ExtractionException::class)
    override fun suggestionList(query: String): List<String> {
        val url = "https://suggestqueries-clients6.youtube.com/complete/search" +
            "?client=youtube" +
            "&ds=yt" +
            "&gl=" + Utils.encodeUrlUtf8(getExtractorContentCountry().countryCode) +
            "&q=" + Utils.encodeUrlUtf8(query) +
            "&xhr=t"

        val headers: MutableMap<String, List<String>> = HashMap()
        headers["Origin"] = listOf("https://www.youtube.com")
        headers["Referer"] = listOf("https://www.youtube.com")

        val response = NewPipe.getDownloader()
            .get(url, headers, getExtractorLocalization())

        val contentTypeHeader = response.getHeader("Content-Type")
        if (isNullOrEmpty(contentTypeHeader) || !contentTypeHeader.contains("application/json")) {
            throw ExtractionException(
                "Invalid response type (got \"$contentTypeHeader\", excepted a JSON response) (response code ${response.responseCode()})"
            )
        }

        val responseBody = response.responseBody()

        if (responseBody.isEmpty()) {
            throw ExtractionException("Empty response received")
        }

        try {
            val suggestions = JsonUtils.toJsonArray(responseBody)
                .getArray(1) // 0: search query, 1: search suggestions, 2: tracking data?
            return suggestions!!.filterIsInstance<JsonArray>()
                .map { it.getString(0) ?: "" }
                .filter { !Utils.isBlank(it) }
        } catch (e: Exception) {
            throw ParsingException("Could not parse JSON response", e)
        }
    }
}
