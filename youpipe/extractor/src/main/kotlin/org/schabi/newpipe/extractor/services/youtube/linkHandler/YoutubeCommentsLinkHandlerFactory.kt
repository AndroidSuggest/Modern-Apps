package org.schabi.newpipe.extractor.services.youtube.linkHandler

import org.schabi.newpipe.extractor.exceptions.FoundAdException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory

class YoutubeCommentsLinkHandlerFactory private constructor() : ListLinkHandlerFactory() {

    companion object {
        private val INSTANCE = YoutubeCommentsLinkHandlerFactory()

        @JvmStatic
        fun getInstance(): YoutubeCommentsLinkHandlerFactory = INSTANCE
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String): String {
        return "https://www.youtube.com/watch?v=$id"
    }

    @Throws(ParsingException::class)
    override fun getId(url: String): String {
        return YoutubeStreamLinkHandlerFactory.getInstance().getId(url)
    }

    @Throws(FoundAdException::class)
    override fun onAcceptUrl(url: String): Boolean {
        return try {
            getId(url)
            true
        } catch (fe: FoundAdException) {
            throw fe
        } catch (e: ParsingException) {
            false
        }
    }

    @Throws(ParsingException::class)
    override fun getUrl(id: String, contentFilter: List<String>, sortFilter: String): String {
        return getUrl(id)
    }
}
