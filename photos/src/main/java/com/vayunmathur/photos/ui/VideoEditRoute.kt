package com.vayunmathur.photos.ui

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface VideoEditRoute : NavKey {
    @Serializable
    data class EditVideo(val id: Long, val uri: String? = null) : VideoEditRoute
}
