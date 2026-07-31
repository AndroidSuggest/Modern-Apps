package org.schabi.newpipe.extractor.kiosk

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import javax.annotation.Nonnull

abstract class KioskExtractor<T : InfoItem>(
    streamingService: StreamingService,
    linkHandler: ListLinkHandler,
    private val kioskId: String
) : ListExtractor<T>(streamingService, linkHandler) {

    @Nonnull
    override fun getId(): String = kioskId

    /**
     * Id should be the name of the kiosk, tho Id is used for identifying it in the frontend,
     * so id should be kept in english.
     * In order to get the name of the kiosk in the desired language we have to
     * crawl if from the website.
     * @return the translated version of id
     */
    @Nonnull
    @Throws(ParsingException::class)
    abstract override fun getName(): String
}
