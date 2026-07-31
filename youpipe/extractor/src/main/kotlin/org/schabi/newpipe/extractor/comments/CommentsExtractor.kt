package org.schabi.newpipe.extractor.comments

import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import javax.annotation.Nonnull

abstract class CommentsExtractor(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : ListExtractor<CommentsInfoItem>(service, linkHandler) {

    /**
     * @apiNote Warning: This method is experimental and may get removed in a future release.
     * @return `true` if the comments are disabled otherwise `false` (default)
     */
    @Throws(ExtractionException::class)
    open fun isCommentsDisabled(): Boolean = false

    /**
     * @return the total number of comments
     */
    @Throws(ExtractionException::class)
    open fun getCommentsCount(): Int = -1

    @Nonnull
    @Throws(ParsingException::class)
    override fun getName(): String = "Comments"
}
