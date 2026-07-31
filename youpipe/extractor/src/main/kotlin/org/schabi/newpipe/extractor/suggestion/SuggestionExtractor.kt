package org.schabi.newpipe.extractor.suggestion

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException

abstract class SuggestionExtractor(
    private val service: StreamingService
) {
    private var forcedLocalization: Localization? = null
    private var forcedContentCountry: ContentCountry? = null

    @Throws(IOException::class, ExtractionException::class)
    abstract fun suggestionList(query: String): List<String>

    fun getServiceId(): Int = service.serviceId

    fun getService(): StreamingService = service

    // TODO: Create a more general Extractor class

    fun forceLocalization(localization: Localization?) {
        this.forcedLocalization = localization
    }

    fun forceContentCountry(contentCountry: ContentCountry?) {
        this.forcedContentCountry = contentCountry
    }

    fun getExtractorLocalization(): Localization =
        forcedLocalization ?: service.getLocalization()

    fun getExtractorContentCountry(): ContentCountry =
        forcedContentCountry ?: service.getContentCountry()
}
