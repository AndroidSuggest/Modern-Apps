package org.schabi.newpipe.extractor.channel

import org.schabi.newpipe.extractor.InfoItem

open class ChannelInfoItem(
    serviceId: Int,
    url: String,
    name: String
) : InfoItem(InfoType.CHANNEL, serviceId, url, name) {

    private var description: String? = null
    private var subscriberCount: Long = -1
    private var streamCount: Long = -1
    private var verified: Boolean = false

    fun getDescription(): String? = description
    fun setDescription(description: String?) {
        this.description = description
    }

    fun getSubscriberCount(): Long = subscriberCount
    fun setSubscriberCount(subscriberCount: Long) {
        this.subscriberCount = subscriberCount
    }

    fun getStreamCount(): Long = streamCount
    fun setStreamCount(streamCount: Long) {
        this.streamCount = streamCount
    }

    fun isVerified(): Boolean = verified
    fun setVerified(verified: Boolean) {
        this.verified = verified
    }
}
