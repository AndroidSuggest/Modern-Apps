package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.stream.Description
import java.io.Serializable
import java.net.URL
import javax.annotation.Nonnull

class MetaInfo : Serializable {

    @Nonnull
    var title: String = ""
        private set

    @Nonnull
    var content: Description? = null
        private set

    @Nonnull
    var urls: MutableList<URL> = ArrayList()
        private set

    @Nonnull
    var urlTexts: MutableList<String> = ArrayList()
        private set

    constructor(
        @Nonnull title: String,
        @Nonnull content: Description,
        @Nonnull urls: List<URL>,
        @Nonnull urlTexts: List<String>
    ) {
        this.title = title
        this.content = content
        this.urls = urls.toMutableList()
        this.urlTexts = urlTexts.toMutableList()
    }

    constructor()

    @Nonnull
    fun getTitle(): String = title

    fun setTitle(@Nonnull title: String) {
        this.title = title
    }

    @Nonnull
    fun getContent(): Description = content!!

    fun setContent(@Nonnull content: Description) {
        this.content = content
    }

    @Nonnull
    fun getUrls(): List<URL> = urls

    fun setUrls(@Nonnull urls: List<URL>) {
        this.urls = urls.toMutableList()
    }

    fun addUrl(@Nonnull url: URL) {
        urls.add(url)
    }

    @Nonnull
    fun getUrlTexts(): List<String> = urlTexts

    fun setUrlTexts(@Nonnull urlTexts: List<String>) {
        this.urlTexts = urlTexts.toMutableList()
    }

    fun addUrlText(@Nonnull urlText: String) {
        urlTexts.add(urlText)
    }
}
