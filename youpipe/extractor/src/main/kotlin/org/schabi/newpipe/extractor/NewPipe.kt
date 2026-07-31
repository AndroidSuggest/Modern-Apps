package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeSessionPoTokenProvider
import org.schabi.newpipe.extractor.utils.ExtractorLogger
import javax.annotation.Nonnull
import javax.annotation.Nullable

object NewPipe {

    private const val TAG = "NewPipe"

    private var downloaderInstance: Downloader? = null

    private var preferredLocalization: Localization? = null
    private var preferredContentCountry: ContentCountry? = null

    @Nullable
    private var youtubeSessionPoTokenProvider: YoutubeSessionPoTokenProvider? = null

    @JvmStatic
    fun init(d: Downloader) {
        ExtractorLogger.d(TAG, "Default init called")
        init(d, Localization.DEFAULT)
    }

    @JvmStatic
    fun init(d: Downloader, l: Localization) {
        ExtractorLogger.d(TAG, "Default init called with localization={}")
        init(
            d, l,
            if (l.getCountryCode().isEmpty()) ContentCountry.DEFAULT else ContentCountry(l.getCountryCode())
        )
    }

    @JvmStatic
    fun init(d: Downloader, l: Localization, c: ContentCountry) {
        ExtractorLogger.d(TAG, "Initializing with downloader={}, localization={}, country={}", d, l, c)
        downloaderInstance = d
        preferredLocalization = l
        preferredContentCountry = c
    }

    @JvmStatic
    fun getDownloader(): Downloader = downloaderInstance!!

    @JvmStatic
    fun setYoutubeSessionPoTokenProvider(
        @Nullable provider: YoutubeSessionPoTokenProvider?
    ) {
        youtubeSessionPoTokenProvider = provider
    }

    @JvmStatic
    @Nullable
    fun getYoutubeSessionPoTokenProvider(): YoutubeSessionPoTokenProvider? =
        youtubeSessionPoTokenProvider

    @JvmStatic
    fun getServices(): List<StreamingService> = ServiceList.all()

    @JvmStatic
    @Throws(ExtractionException::class)
    fun getService(serviceId: Int): StreamingService =
        ServiceList.all().firstOrNull { it.serviceId == serviceId }
            ?: throw ExtractionException("There's no service with the id = \"$serviceId\"")

    @JvmStatic
    @Throws(ExtractionException::class)
    fun getService(serviceName: String): StreamingService =
        ServiceList.all().firstOrNull { it.serviceInfo.name == serviceName }
            ?: throw ExtractionException("There's no service with the name = \"$serviceName\"")

    @JvmStatic
    @Throws(ExtractionException::class)
    fun getServiceByUrl(url: String): StreamingService {
        for (service in ServiceList.all()) {
            if (service.getLinkTypeByUrl(url) != StreamingService.LinkType.NONE) {
                return service
            }
        }
        throw ExtractionException("No service can handle the url = \"$url\"")
    }

    @JvmStatic
    fun setupLocalization(thePreferredLocalization: Localization) {
        setupLocalization(thePreferredLocalization, null)
    }

    @JvmStatic
    fun setupLocalization(
        thePreferredLocalization: Localization,
        @Nullable thePreferredContentCountry: ContentCountry?
    ) {
        preferredLocalization = thePreferredLocalization
        preferredContentCountry = if (thePreferredContentCountry != null) {
            thePreferredContentCountry
        } else {
            if (thePreferredLocalization.getCountryCode().isEmpty()) ContentCountry.DEFAULT
            else ContentCountry(thePreferredLocalization.getCountryCode())
        }
    }

    @JvmStatic
    @Nonnull
    fun getPreferredLocalization(): Localization = preferredLocalization ?: Localization.DEFAULT

    @JvmStatic
    fun setPreferredLocalization(preferredLocalization: Localization) {
        this.preferredLocalization = preferredLocalization
    }

    @JvmStatic
    @Nonnull
    fun getPreferredContentCountry(): ContentCountry =
        preferredContentCountry ?: ContentCountry.DEFAULT

    @JvmStatic
    fun setPreferredContentCountry(preferredContentCountry: ContentCountry) {
        this.preferredContentCountry = preferredContentCountry
    }
}
