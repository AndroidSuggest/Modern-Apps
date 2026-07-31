package org.schabi.newpipe.extractor.stream

import java.io.Serializable

class Frameset(
    val urls: List<String>,
    val frameWidth: Int,
    val frameHeight: Int,
    val totalCount: Int,
    val durationPerFrame: Int,
    val framesPerPageX: Int,
    private val framesPerPageY: Int
) : Serializable {

    fun getFramesPerPageY(): Int = framesPerPageY

    fun getFrameBoundsAt(position: Long): IntArray {
        if (position < 0 || position > (totalCount + 1).toLong() * durationPerFrame) {
            return intArrayOf(0, 0, 0, frameWidth, frameHeight)
        }
        val framesPerStoryboard = framesPerPageX * framesPerPageY
        val absoluteFrameNumber = minOf((position / durationPerFrame).toInt(), totalCount)
        val relativeFrameNumber = absoluteFrameNumber % framesPerStoryboard
        val rowIndex = Math.floorDiv(relativeFrameNumber, framesPerPageX)
        val columnIndex = relativeFrameNumber % framesPerPageY

        return intArrayOf(
            Math.floorDiv(absoluteFrameNumber, framesPerStoryboard),
            columnIndex * frameWidth,
            rowIndex * frameHeight,
            columnIndex * frameWidth + frameWidth,
            rowIndex * frameHeight + frameHeight
        )
    }
}
