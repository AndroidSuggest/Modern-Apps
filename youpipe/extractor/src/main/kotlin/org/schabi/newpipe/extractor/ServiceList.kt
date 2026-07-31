package org.schabi.newpipe.extractor

import org.schabi.newpipe.extractor.services.youtube.YoutubeService

object ServiceList {

    @JvmField
    val YouTube: YoutubeService = YoutubeService(0)

    private val SERVICES: List<StreamingService> = listOf(YouTube)

    @JvmStatic
    fun all(): List<StreamingService> = SERVICES
}
