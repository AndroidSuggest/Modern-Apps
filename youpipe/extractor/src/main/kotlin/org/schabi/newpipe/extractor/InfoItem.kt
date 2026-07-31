package org.schabi.newpipe.extractor

import java.io.Serializable
import javax.annotation.Nonnull

abstract class InfoItem(
    val infoType: InfoType,
    val serviceId: Int,
    val url: String,
    val name: String
) : Serializable {

    @Nonnull
    var thumbnails: List<Image> = emptyList()
        private set

    fun getInfoType(): InfoType = infoType
    fun getServiceId(): Int = serviceId
    fun getUrl(): String = url
    fun getName(): String = name

    fun setThumbnails(@Nonnull thumbnails: List<Image>) {
        this.thumbnails = thumbnails
    }

    @Nonnull
    fun getThumbnails(): List<Image> = thumbnails

    override fun toString(): String =
        "${javaClass.simpleName}[url=\"$url\", name=\"$name\"]"

    enum class InfoType {
        STREAM,
        PLAYLIST,
        CHANNEL,
        COMMENT
    }
}
