package com.vayunmathur.library.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.vayunmathur.library.log.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Repackages a WebM/Opus stream as an Ogg/Opus file without re-encoding.
 *
 * YouTube serves its highest-quality audio as Opus inside a WebM container, but a `.opus`
 * file is Opus inside Ogg. The audio itself is identical, so this only rewrites the
 * container: the Opus packets are demuxed with [MediaExtractor] and stream-copied into an
 * Ogg/Opus file with [MediaMuxer]. There is no transcode, so no quality is lost.
 *
 * This mirrors the framework-only remux the YouPipe app uses for SABR downloads, chosen so
 * the repo can stay ffmpeg-free and F-Droid publishable.
 */
object OpusRemuxer {

    private const val TAG = "OpusRemuxer"
    private const val INITIAL_BUFFER_SIZE = 1 * 1024 * 1024

    private data class AudioTrack(val index: Int, val format: MediaFormat)

    /**
     * Returns the Ogg/Opus bytes, or null if the input has no readable Opus track or the
     * platform muxer refuses it. Callers keep the original bytes on null so a download is
     * never lost to a remux failure.
     */
    fun remux(context: Context, webmOpus: ByteArray): ByteArray? {
        val input = File.createTempFile("mb-remux-in", ".webm", context.cacheDir)
        val output = File.createTempFile("mb-remux-out", ".ogg", context.cacheDir)
        // MediaMuxer insists on creating its own output file.
        output.delete()
        return try {
            input.writeBytes(webmOpus)
            if (muxToOgg(input, output)) {
                output.readBytes().takeIf { it.isNotEmpty() }
                    .also { Log.status(TAG, "remux ok: in=${webmOpus.size} out=${it?.size ?: 0}") }
            } else {
                Log.status(TAG, "remux failed: muxToOgg returned false (in=${webmOpus.size})")
                null
            }
        } catch (expected: IOException) {
            Log.status(TAG, "remux threw: ${expected.javaClass.simpleName}: ${expected.message}", expected)
            null
        } finally {
            input.delete()
            output.delete()
        }
    }

    private fun muxToOgg(input: File, output: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(input.absolutePath) }

            val track = findAudioTrack(extractor) ?: run {
                Log.status(TAG, "remux: no audio track found in ${extractor.trackCount} tracks")
                return false
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            val muxerTrack = muxer.addTrack(track.format)
            muxer.start()
            extractor.selectTrack(track.index)

            val samples = copySamples(extractor, muxer, muxerTrack)
            Log.status(TAG, "remux: copied $samples samples")
            return true
        } catch (expected: IOException) {
            Log.status(TAG, "remux muxToOgg threw: ${expected.javaClass.simpleName}: ${expected.message}", expected)
            return false
        } finally {
            runCatching { extractor?.release() }
            muxer?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): AudioTrack? {
        val candidates = (0 until extractor.trackCount).map { i ->
            i to extractor.getTrackFormat(i)
        }
        for ((i, candidate) in candidates) {
            val mime = mimeOf(candidate) ?: continue
            Log.status(TAG, "remux track $i mime=$mime")
            if (isAudioMime(mime)) return AudioTrack(i, candidate)
        }
        return null
    }

    private fun mimeOf(format: MediaFormat): String? =
        format.getString(MediaFormat.KEY_MIME)

    private fun isAudioMime(mime: String): Boolean = mime.startsWith("audio/")

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, muxerTrack: Int): Int {
        val info = MediaCodec.BufferInfo()
        val state = CopyState(ByteBuffer.allocate(INITIAL_BUFFER_SIZE))
        while (state.advance(extractor, muxer, muxerTrack, info)) {
            // advance() returns false at end of stream.
        }
        return state.samples
    }

    private class CopyState(var buffer: ByteBuffer) {
        var samples = 0
        private var pendingSize: Int? = null

        fun advance(
            extractor: MediaExtractor,
            muxer: MediaMuxer,
            muxerTrack: Int,
            info: MediaCodec.BufferInfo,
        ): Boolean {
            val size = pendingSize ?: readSample(extractor, buffer) ?: return false
            if (pendingSize == null && size > buffer.capacity()) {
                buffer = ByteBuffer.allocate(size)
                pendingSize = size
                return true
            }
            pendingSize = null
            val actual = readSample(extractor, buffer) ?: return false
            info.set(0, actual, extractor.sampleTime, bufferFlagsFor(extractor.sampleFlags))
            muxer.writeSampleData(muxerTrack, buffer, info)
            extractor.advance()
            samples++
            return true
        }
    }

    private fun readSample(extractor: MediaExtractor, buffer: ByteBuffer): Int? {
        buffer.clear()
        val sampleSize = extractor.readSampleData(buffer, 0)
        return if (sampleSize < 0) null else sampleSize
    }

    // MediaExtractor.SAMPLE_FLAG_* and MediaCodec.BUFFER_FLAG_* are distinct constant spaces.
    private fun bufferFlagsFor(sampleFlags: Int): Int =
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
}
