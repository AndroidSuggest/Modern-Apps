package org.schabi.newpipe.extractor.stream

import java.io.Serializable

open class StreamSegment(
    private var title: String,
    private var startTimeSeconds: Int
) : Serializable {

    private var channelName: String? = null
    var url: String? = null
    private var previewUrl: String? = null

    fun getTitle(): String = title
    fun setTitle(title: String) {
        this.title = title
    }

    fun getStartTimeSeconds(): Int = startTimeSeconds
    fun setStartTimeSeconds(startTimeSeconds: Int) {
        this.startTimeSeconds = startTimeSeconds
    }

    fun getChannelName(): String? = channelName
    fun setChannelName(channelName: String?) {
        this.channelName = channelName
    }


    fun getPreviewUrl(): String? = previewUrl
    fun setPreviewUrl(previewUrl: String?) {
        this.previewUrl = previewUrl
    }
}
