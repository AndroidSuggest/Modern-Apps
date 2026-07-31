package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.utils.ExtractorLogger
import java.io.IOException
import java.util.Objects
import javax.annotation.Nonnull
import javax.annotation.Nullable

abstract class Extractor(
    @get:JvmName("getService") val service: StreamingService,
    linkHandler: LinkHandler
) {

    private val TAG = "${javaClass.simpleName}@${hashCode()}"

    private val linkHandler: LinkHandler =
        Objects.requireNonNull(linkHandler, "LinkHandler is null")

    @Nullable
    private var forcedLocalization: Localization? = null

    @Nullable
    private var forcedContentCountry: ContentCountry? = null

    private var pageFetched = false

    @get:JvmName("getDownloader")
    val downloader: Downloader =
        Objects.requireNonNull(NewPipe.getDownloader(), "downloader is null")

    init {
        Objects.requireNonNull(service, "service is null")
    }

    @Nonnull
    open fun getLinkHandler(): LinkHandler = linkHandler

    @Throws(IOException::class, ExtractionException::class)
    fun fetchPage() {
        ExtractorLogger.d(TAG, "base fetchPage called")
        if (pageFetched) {
            ExtractorLogger.d(TAG, "Page already fetched; returning")
            return
        }
        onFetchPage(downloader)
        pageFetched = true
    }

    protected fun assertPageFetched() {
        if (!pageFetched) {
            throw IllegalStateException("Page is not fetched. Make sure you call fetchPage()")
        }
    }

    protected fun isPageFetched(): Boolean = pageFetched

    @Throws(IOException::class, ExtractionException::class)
    abstract fun onFetchPage(@Nonnull downloader: Downloader)

    @Nonnull
    @Throws(ParsingException::class)
    open fun getId(): String = linkHandler.id

    @Nonnull
    @Throws(ParsingException::class)
    abstract fun getName(): String

    @Nonnull
    @Throws(ParsingException::class)
    open fun getOriginalUrl(): String = linkHandler.originalUrl

    @Nonnull
    @Throws(ParsingException::class)
    open fun getUrl(): String = linkHandler.url

    @Nonnull
    @Throws(ParsingException::class)
    open fun getBaseUrl(): String = linkHandler.getBaseUrl()

    @Nonnull
    open fun getService(): StreamingService = service

    open fun getServiceId(): Int = service.serviceId
    open fun getDownloader(): Downloader = downloader

    open fun forceLocalization(localization: Localization) {
        this.forcedLocalization = localization
    }

    open fun forceContentCountry(contentCountry: ContentCountry) {
        this.forcedContentCountry = contentCountry
    }

    @Nonnull
    open fun getExtractorLocalization(): Localization =
        forcedLocalization ?: service.getLocalization()

    @Nonnull
    open fun getExtractorContentCountry(): ContentCountry =
        forcedContentCountry ?: service.getContentCountry()

    @Nonnull
    open fun getTimeAgoParser(): TimeAgoParser =
        service.getTimeAgoParser(getExtractorLocalization())

    override fun toString(): String = javaClass.simpleName
}
