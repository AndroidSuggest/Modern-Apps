package com.vayunmathur.camera.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object VideoProcessor {
    fun adjustSpeed(inputFile: File, outputFile: File, speedFactor: Float) {
        val retriever = MediaMetadataRetriever()
        val rotation = try {
            retriever.setDataSource(inputFile.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 } finally { retriever.release() }

        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.path)

        val muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)

        var videoTrackIndex = -1
        var muxerTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                muxerTrackIndex = muxer.addTrack(format)
                break
            }
        }

        if (videoTrackIndex < 0) {
            extractor.release()
            muxer.release()
            return
        }

        extractor.selectTrack(videoTrackIndex)
        muxer.start()

        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()

        // newPts = (pts / totalDuration) * (totalDuration / speedFactor) == pts / speedFactor,
        // so timestamps can be remapped per-sample without buffering the whole stream.
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.presentationTimeUs = (extractor.sampleTime.toDouble() / speedFactor).toLong()
            muxer.writeSampleData(muxerTrackIndex, buffer, info)
            extractor.advance()
        }

        muxer.stop()
        muxer.release()
        extractor.release()
    }
}
