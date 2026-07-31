package org.schabi.newpipe.extractor.kiosk

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.utils.Utils
import java.io.IOException
import javax.annotation.Nullable

class KioskList(
    private val service: StreamingService
) {

    fun interface KioskExtractorFactory {
        @Throws(ExtractionException::class, IOException::class)
        fun createNewKiosk(
            streamingService: StreamingService,
            url: String,
            kioskId: String
        ): KioskExtractor<*>
    }

    // Keep Java-compatible internal field name kioskList as per original
    private val kioskList: HashMap<String, KioskEntry> = HashMap()
    private var defaultKiosk: String? = null

    @Nullable
    private var forcedLocalization: Localization? = null

    @Nullable
    private var forcedContentCountry: ContentCountry? = null

    private data class KioskEntry(
        val extractorFactory: KioskExtractorFactory,
        val handlerFactory: ListLinkHandlerFactory
    )

    @Throws(Exception::class)
    fun addKioskEntry(
        extractorFactory: KioskExtractorFactory,
        handlerFactory: ListLinkHandlerFactory,
        id: String
    ) {
        if (kioskList[id] != null) {
            throw Exception("Kiosk with type $id already exists.")
        }
        kioskList[id] = KioskEntry(extractorFactory, handlerFactory)
    }

    fun setDefaultKiosk(kioskType: String) {
        defaultKiosk = kioskType
    }

    @Throws(ExtractionException::class, IOException::class)
    fun getDefaultKioskExtractor(): KioskExtractor<*>? {
        return getDefaultKioskExtractor(null)
    }

    @Throws(ExtractionException::class, IOException::class)
    fun getDefaultKioskExtractor(nextPage: Page?): KioskExtractor<*>? {
        return getDefaultKioskExtractor(nextPage, NewPipe.getPreferredLocalization())
    }

    @Throws(ExtractionException::class, IOException::class)
    fun getDefaultKioskExtractor(
        nextPage: Page?,
        localization: Localization
    ): KioskExtractor<*>? {
        return if (!Utils.isNullOrEmpty(defaultKiosk)) {
            getExtractorById(defaultKiosk!!, nextPage, localization)
        } else {
            val first = kioskList.keys.firstOrNull()
            if (first != null) {
                // if not set get any entry
                getExtractorById(first, nextPage, localization)
            } else {
                null
            }
        }
    }

    fun getDefaultKioskId(): String? {
        return defaultKiosk
    }

    @Throws(ExtractionException::class, IOException::class)
    fun getExtractorById(kioskId: String, nextPage: Page?): KioskExtractor<*> {
        return getExtractorById(kioskId, nextPage, NewPipe.getPreferredLocalization())
    }

    @Throws(ExtractionException::class, IOException::class)
    fun getExtractorById(
        kioskId: String,
        nextPage: Page?,
        localization: Localization
    ): KioskExtractor<*> {
        val ke = kioskList[kioskId]
            ?: throw ExtractionException("No kiosk found with the type: $kioskId")

        val kioskExtractor = ke.extractorFactory.createNewKiosk(
            service,
            ke.handlerFactory.fromId(kioskId).url,
            kioskId
        )

        if (forcedLocalization != null) {
            kioskExtractor.forceLocalization(forcedLocalization!!)
        }
        if (forcedContentCountry != null) {
            kioskExtractor.forceContentCountry(forcedContentCountry!!)
        }

        return kioskExtractor
    }

    fun getAvailableKiosks(): Set<String> {
        return kioskList.keys
    }

    @Throws(ExtractionException::class, IOException::class)
    fun getExtractorByUrl(url: String, nextPage: Page?): KioskExtractor<*> {
        return getExtractorByUrl(url, nextPage, NewPipe.getPreferredLocalization())
    }

    @Throws(ExtractionException::class, IOException::class)
    fun getExtractorByUrl(
        url: String,
        nextPage: Page?,
        localization: Localization
    ): KioskExtractor<*> {
        for (e in kioskList.entries) {
            val ke = e.value
            if (ke.handlerFactory.acceptUrl(url)) {
                return getExtractorById(ke.handlerFactory.getId(url), nextPage, localization)
            }
        }
        throw ExtractionException("Could not find a kiosk that fits to the url: $url")
    }

    fun getListLinkHandlerFactoryByType(type: String): ListLinkHandlerFactory {
        return kioskList[type]!!.handlerFactory
    }

    fun forceLocalization(@Nullable localization: Localization?) {
        this.forcedLocalization = localization
    }

    fun forceContentCountry(@Nullable contentCountry: ContentCountry?) {
        this.forcedContentCountry = contentCountry
    }
}
