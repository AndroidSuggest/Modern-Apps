package org.schabi.newpipe.extractor.utils

import org.schabi.newpipe.extractor.Image.ResolutionLevel
import java.io.Serializable
import java.util.Objects
import javax.annotation.Nonnull

/**
 * Serializable class representing a suffix (which may include its format extension, such as
 * `.jpg`) which needs to be added to get an image/thumbnail URL with its corresponding
 * height, width and estimated resolution level.
 *
 * This class is used to construct [org.schabi.newpipe.extractor.Image] instances from a single
 * base URL/path.
 *
 * Note that this class is not intended to be used externally and so should only be used when
 * interfacing with the extractor.
 */
class ImageSuffix : Serializable {

    @Nonnull
    private val suffix: String
    private val height: Int
    private val width: Int
    @Nonnull
    private val resolutionLevel: ResolutionLevel

    /**
     * Create a new [ImageSuffix] instance.
     *
     * @param suffix                   the suffix string
     * @param height                   the height corresponding to the image suffix
     * @param width                    the width corresponding to the image suffix
     * @param estimatedResolutionLevel the [ResolutionLevel] of the image suffix, which must
     *                                 not be null
     * @throws NullPointerException if `estimatedResolutionLevel` is `null`
     */
    constructor(
        @Nonnull suffix: String,
        height: Int,
        width: Int,
        @Nonnull estimatedResolutionLevel: ResolutionLevel
    ) {
        this.suffix = suffix
        this.height = height
        this.width = width
        this.resolutionLevel = Objects.requireNonNull(
            estimatedResolutionLevel,
            "estimatedResolutionLevel is null"
        )
    }

    @Nonnull
    fun getSuffix(): String = suffix

    fun getHeight(): Int = height

    fun getWidth(): Int = width

    @Nonnull
    fun getResolutionLevel(): ResolutionLevel = resolutionLevel

    @Nonnull
    override fun toString(): String =
        "ImageSuffix {suffix=$suffix, height=$height, width=$width, resolutionLevel=$resolutionLevel}"
}
