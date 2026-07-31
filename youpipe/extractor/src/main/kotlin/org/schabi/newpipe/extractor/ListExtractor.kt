package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import java.io.IOException
import java.util.Collections
import javax.annotation.Nonnull
import javax.annotation.Nullable

abstract class ListExtractor<R : InfoItem>(
    service: StreamingService,
    linkHandler: ListLinkHandler
) : Extractor(service, linkHandler) {

    companion object {
        const val ITEM_COUNT_UNKNOWN: Long = -1
        const val ITEM_COUNT_INFINITE: Long = -2
        const val ITEM_COUNT_MORE_THAN_100: Long = -3
    }

    @Nonnull
    @Throws(IOException::class, ExtractionException::class)
    abstract fun getInitialPage(): InfoItemsPage<R>

    @Nonnull
    @Throws(IOException::class, ExtractionException::class)
    abstract fun getPage(page: Page): InfoItemsPage<R>

    @Nonnull
    override fun getLinkHandler(): ListLinkHandler = super.getLinkHandler() as ListLinkHandler

    class InfoItemsPage<T : InfoItem> {

        val itemsList: List<T>
        @Nullable
        val nextPage: Page?
        val errors: List<Throwable>

        constructor(collector: InfoItemsCollector<T, *>, @Nullable nextPage: Page?) :
            this(collector.items, nextPage, collector.errors)

        constructor(itemsList: List<T>, @Nullable nextPage: Page?, errors: List<Throwable>) {
            this.itemsList = itemsList
            this.nextPage = nextPage
            this.errors = errors
        }

        fun hasNextPage(): Boolean = Page.isValid(nextPage)
        fun getItems(): List<T> = itemsList

        @Nullable
        fun getNextPage(): Page? = nextPage

        fun getErrors(): List<Throwable> = errors

        companion object {
            private val EMPTY: InfoItemsPage<InfoItem> = InfoItemsPage(
                Collections.emptyList(),
                null,
                Collections.emptyList()
            )

            @JvmStatic
            fun <T : InfoItem> emptyPage(): InfoItemsPage<T> {
                @Suppress("UNCHECKED_CAST")
                return EMPTY as InfoItemsPage<T>
            }
        }
    }
}
