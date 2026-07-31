package org.schabi.newpipe.extractor.comments

import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.ListInfo
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.utils.ExtractorHelper
import java.io.IOException

class CommentsInfo private constructor(
    serviceId: Int,
    listUrlIdHandler: ListLinkHandler,
    name: String
) : ListInfo<CommentsInfoItem>(serviceId, listUrlIdHandler, name) {

    @Transient
    private var commentsExtractor: CommentsExtractor? = null
    private var commentsDisabled: Boolean = false
    private var commentsCount: Int = 0

    fun getCommentsExtractor(): CommentsExtractor? = commentsExtractor

    fun setCommentsExtractor(commentsExtractor: CommentsExtractor?) {
        this.commentsExtractor = commentsExtractor
    }

    /**
     * @return `true` if the comments are disabled otherwise `false` (default)
     * @see CommentsExtractor.isCommentsDisabled
     */
    fun isCommentsDisabled(): Boolean = commentsDisabled

    /**
     * @param commentsDisabled `true` if the comments are disabled otherwise `false`
     */
    fun setCommentsDisabled(commentsDisabled: Boolean) {
        this.commentsDisabled = commentsDisabled
    }

    /**
     * Returns the total number of comments.
     *
     * @return the total number of comments
     */
    fun getCommentsCount(): Int = commentsCount

    /**
     * Sets the total number of comments.
     *
     * @param commentsCount the commentsCount to set.
     */
    fun setCommentsCount(commentsCount: Int) {
        this.commentsCount = commentsCount
    }

    companion object {
        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getInfo(url: String): CommentsInfo? {
            return getInfo(NewPipe.getServiceByUrl(url), url)
        }

        @JvmStatic
        @Throws(ExtractionException::class, IOException::class)
        fun getInfo(service: StreamingService, url: String): CommentsInfo? {
            return getInfo(service.getCommentsExtractor(url))
        }

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getInfo(commentsExtractor: CommentsExtractor?): CommentsInfo? {
            // for services which do not have a comments extractor
            if (commentsExtractor == null) {
                return null
            }

            commentsExtractor.fetchPage()

            val name = commentsExtractor.getName()
            val serviceId = commentsExtractor.getServiceId()
            val listUrlIdHandler = commentsExtractor.getLinkHandler()

            val commentsInfo = CommentsInfo(serviceId, listUrlIdHandler, name)
            commentsInfo.setCommentsExtractor(commentsExtractor)
            val initialCommentsPage: InfoItemsPage<CommentsInfoItem> =
                ExtractorHelper.getItemsPageOrLogError(commentsInfo, commentsExtractor)
            commentsInfo.setCommentsDisabled(commentsExtractor.isCommentsDisabled())
            commentsInfo.setRelatedItems(initialCommentsPage.getItems())
            try {
                commentsInfo.setCommentsCount(commentsExtractor.getCommentsCount())
            } catch (e: Exception) {
                commentsInfo.addError(e)
            }
            initialCommentsPage.getNextPage()?.let { commentsInfo.setNextPage(it) }

            return commentsInfo
        }

        @JvmStatic
        @Throws(ExtractionException::class, IOException::class)
        fun getMoreItems(
            commentsInfo: CommentsInfo,
            page: Page
        ): InfoItemsPage<CommentsInfoItem> {
            return getMoreItems(
                NewPipe.getService(commentsInfo.getServiceId()),
                commentsInfo.getUrl(),
                page
            )
        }

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getMoreItems(
            service: StreamingService,
            commentsInfo: CommentsInfo,
            page: Page
        ): InfoItemsPage<CommentsInfoItem> {
            return getMoreItems(service, commentsInfo.getUrl(), page)
        }

        @JvmStatic
        @Throws(IOException::class, ExtractionException::class)
        fun getMoreItems(
            service: StreamingService,
            url: String,
            page: Page
        ): InfoItemsPage<CommentsInfoItem> {
            return service.getCommentsExtractor(url)!!.getPage(page)
        }
    }
}
