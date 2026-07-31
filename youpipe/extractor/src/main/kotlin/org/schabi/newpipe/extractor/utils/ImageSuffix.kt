package org.schabi.newpipe.extractor.utils

import org.schabi.newpipe.extractor.Image.ResolutionLevel
import java.io.Serializable
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
 *
 * @param suffix          the suffix string
 * @param height          the height corresponding to the image suffix
 * @param width           the width corresponding to the image suffix
 * @param resolutionLevel the [ResolutionLevel] of the image suffix
 */
class ImageSuffix(
    @Nonnull val suffix: String,
    val height: Int,
    val width: Int,
    @Nonnull val resolutionLevel: ResolutionLevel
) : Serializable {

    @Nonnull
    override fun toString(): String =
        "ImageSuffix {suffix=$suffix, height=$height, width=$width, resolutionLevel=$resolutionLevel}"
}
