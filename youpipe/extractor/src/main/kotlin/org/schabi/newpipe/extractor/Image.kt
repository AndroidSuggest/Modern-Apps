package org.schabi.newpipe.extractor

import java.io.Serializable
import javax.annotation.Nonnull

class Image(
    @field:Nonnull @get:Nonnull val url: String,
    @get:JvmName("getHeight") val height: Int,
    @get:JvmName("getWidth") val width: Int,
    @field:Nonnull @get:Nonnull val estimatedResolutionLevel: ResolutionLevel
) : Serializable {





    @Nonnull
    override fun toString(): String =
        "Image {url=$url, height=$height, width=$width, estimatedResolutionLevel=$estimatedResolutionLevel}"

    enum class ResolutionLevel {
        HIGH,
        MEDIUM,
        LOW,
        UNKNOWN;

        companion object {
            @JvmStatic
            fun fromHeight(heightPx: Int): ResolutionLevel {
                if (heightPx <= 0) return UNKNOWN
                if (heightPx < 175) return LOW
                if (heightPx < 720) return MEDIUM
                return HIGH
            }
        }
    }

    companion object {
        const val HEIGHT_UNKNOWN: Int = -1
        const val WIDTH_UNKNOWN: Int = -1
    }
}
