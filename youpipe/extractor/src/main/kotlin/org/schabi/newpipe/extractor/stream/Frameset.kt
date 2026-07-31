package org.schabi.newpipe.extractor.stream

import java.io.Serializable

class Frameset(
    private val urls: List<String>,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val totalCount: Int,
    private val durationPerFrame: Int,
    private val framesPerPageX: Int,
    private val framesPerPageY: Int
) : Serializable {

    fun getUrls(): List<String> = urls
    fun getTotalCount(): Int = totalCount
    fun getFramesPerPageX(): Int = framesPerPageX
    fun getFramesPerPageY(): Int = framesPerPageY
    fun getFrameWidth(): Int = frameWidth
    fun getFrameHeight(): Int = frameHeight
    fun getDurationPerFrame(): Int = durationPerFrame

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
