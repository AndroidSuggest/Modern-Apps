package org.schabi.newpipe.extractor.services.youtube.extractors

import org.schabi.newpipe.extractor.services.youtube.ItagItem
import java.io.Serializable
import javax.annotation.Nonnull

/**
 * Class to build easier [org.schabi.newpipe.extractor.stream.Stream]s for
 * [YoutubeStreamExtractor].
 *
 * It stores, per stream:
 * - its content (the URL/the base URL of streams);
 * - whether its content is the URL the content itself or the base URL;
 * - its associated [ItagItem].
 */
internal class ItagInfo(
    @field:Nonnull
    private val content: String,
    @field:Nonnull
    private val itagItem: ItagItem
) : Serializable {

    private var isUrl: Boolean = false

    /**
     * Sets whether the stream is a URL.
     *
     * @param isUrl whether the content is a URL
     */
    fun setIsUrl(isUrl: Boolean) {
        this.isUrl = isUrl
    }

    /**
     * Gets the content stored in this [ItagInfo] instance, which is either the URL to the
     * content itself or the base URL.
     *
     * @return the content stored in this [ItagInfo] instance
     */
    @Nonnull
    fun getContent(): String = content

    /**
     * Gets the [ItagItem] associated with this [ItagInfo] instance.
     *
     * @return the [ItagItem] associated with this [ItagInfo] instance, which is not null
     */
    @Nonnull
    fun getItagItem(): ItagItem = itagItem

    /**
     * Gets whether the content stored is the URL to the content itself or the base URL of it.
     *
     * @return whether the content stored is the URL to the content itself or the base URL of it
     * @see getContent for more details
     */
    fun getIsUrl(): Boolean = isUrl
}
