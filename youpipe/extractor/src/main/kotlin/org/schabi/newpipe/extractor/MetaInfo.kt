package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.stream.Description
import java.io.Serializable
import java.net.URL
import javax.annotation.Nonnull

class MetaInfo : Serializable {

    @Nonnull
    var title: String = ""

    @Nonnull
    var content: Description? = null

    @Nonnull
    var urls: MutableList<URL> = ArrayList()

    @Nonnull
    var urlTexts: MutableList<String> = ArrayList()

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

    fun addUrl(@Nonnull url: URL) {
        urls.add(url)
    }

    fun addUrlText(@Nonnull urlText: String) {
        urlTexts.add(urlText)
    }
}
