package org.schabi.newpipe.extractor.channel

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import java.io.IOException

open class ChannelInfo(
    serviceId: Int,
    id: String,
    url: String,
    originalUrl: String,
    name: String
) : Info(serviceId, id, url, originalUrl, name) {

    private var parentChannelName: String? = null
    private var parentChannelUrl: String? = null
    private var feedUrl: String? = null
    private var subscriberCount: Long = -1
    private var description: String? = null
    private var donationLinks: Array<String>? = null
    private var avatars: List<Image> = emptyList()
    private var banners: List<Image> = emptyList()
    private var parentChannelAvatars: List<Image> = emptyList()
    private var verified: Boolean = false
    private var tabs: List<ListLinkHandler> = emptyList()
    private var tags: List<String> = emptyList()

    fun getParentChannelName(): String? = parentChannelName
    fun setParentChannelName(parentChannelName: String?) {
        this.parentChannelName = parentChannelName
    }

    fun getParentChannelUrl(): String? = parentChannelUrl
    fun setParentChannelUrl(parentChannelUrl: String?) {
        this.parentChannelUrl = parentChannelUrl
    }

    fun getParentChannelAvatars(): List<Image> = parentChannelAvatars
    fun setParentChannelAvatars(parentChannelAvatars: List<Image>) {
        this.parentChannelAvatars = parentChannelAvatars
    }

    fun getAvatars(): List<Image> = avatars
    fun setAvatars(avatars: List<Image>) {
        this.avatars = avatars
    }

    fun getBanners(): List<Image> = banners
    fun setBanners(banners: List<Image>) {
        this.banners = banners
    }

    fun getFeedUrl(): String? = feedUrl
    fun setFeedUrl(feedUrl: String?) {
        this.feedUrl = feedUrl
    }

    fun getSubscriberCount(): Long = subscriberCount
    fun setSubscriberCount(subscriberCount: Long) {
        this.subscriberCount = subscriberCount
    }

    fun getDescription(): String? = description
    fun setDescription(description: String?) {
        this.description = description
    }

    fun getDonationLinks(): Array<String>? = donationLinks
    fun setDonationLinks(donationLinks: Array<String>?) {
        this.donationLinks = donationLinks
    }

    fun isVerified(): Boolean = verified
    fun setVerified(verified: Boolean) {
        this.verified = verified
    }

    fun getTabs(): List<ListLinkHandler> = tabs
    fun setTabs(tabs: List<ListLinkHandler>) {
        this.tabs = tabs
    }

    fun getTags(): List<String> = tags
    fun setTags(tags: List<String>) {
        this.tags = tags
    }

    companion object {
        @Throws(IOException::class, ExtractionException::class)
        @JvmStatic
        fun getInfo(url: String): ChannelInfo {
            return getInfo(NewPipe.getServiceByUrl(url), url)
        }

        @Throws(IOException::class, ExtractionException::class)
        @JvmStatic
        fun getInfo(service: StreamingService, url: String): ChannelInfo {
            val extractor = service.getChannelExtractor(url)
            extractor.fetchPage()
            return getInfo(extractor)
        }

        @Throws(IOException::class, ExtractionException::class)
        @JvmStatic
        fun getInfo(extractor: ChannelExtractor): ChannelInfo {
            val serviceId = extractor.getServiceId()
            val id = extractor.getId()
            val url = extractor.getUrl()
            val originalUrl = extractor.getOriginalUrl()
            val name = extractor.getName()

            val info = ChannelInfo(serviceId, id, url, originalUrl, name)

            try {
                info.setAvatars(extractor.getAvatars())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setBanners(extractor.getBanners())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setFeedUrl(extractor.getFeedUrl())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setSubscriberCount(extractor.getSubscriberCount())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setDescription(extractor.getDescription())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setParentChannelName(extractor.getParentChannelName())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setParentChannelUrl(extractor.getParentChannelUrl())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setParentChannelAvatars(extractor.getParentChannelAvatars())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setVerified(extractor.isVerified())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setTabs(extractor.getTabs())
            } catch (e: Exception) {
                info.addError(e)
            }

            try {
                info.setTags(extractor.getTags())
            } catch (e: Exception) {
                info.addError(e)
            }

            return info
        }
    }
}
