package org.schabi.newpipe.extractor.feed

import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * This class helps to extract items from lightweight feeds that the services may provide.
 *
 * YouTube is an example of a service that has this alternative available.
 */
abstract class FeedExtractor(
    service: StreamingService,
    listLinkHandler: ListLinkHandler
) : ListExtractor<StreamInfoItem>(service, listLinkHandler)
