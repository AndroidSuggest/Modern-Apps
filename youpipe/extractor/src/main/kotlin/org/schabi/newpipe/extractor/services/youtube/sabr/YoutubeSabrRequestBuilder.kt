package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Base64

internal class YoutubeSabrRequestBuilder private constructor() {

    companion object {
        const val ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO: Int = 0
        const val ENABLED_TRACK_TYPES_AUDIO_ONLY: Int = 1
        const val ENABLED_TRACK_TYPES_VIDEO_ONLY: Int = 2

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun buildFirstMediaRequest(
            info: YoutubeSabrInfo,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat
        ): ByteArray {
            return buildFirstMediaRequest(info, audioFormat, videoFormat, null)
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun buildFirstMediaRequest(
            info: YoutubeSabrInfo,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            streamState: YoutubeSabrStreamState?
        ): ByteArray {
            if (streamState != null) {
                synchronized(streamState) {
                    return buildFirstMediaRequestLocked(info, audioFormat, videoFormat, streamState)
                }
            }
            return buildFirstMediaRequestLocked(info, audioFormat, videoFormat, null)
        }

        @Throws(SabrProtocolException::class)
        private fun buildFirstMediaRequestLocked(
            info: YoutubeSabrInfo,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            streamState: YoutubeSabrStreamState?
        ): ByteArray {
            val ustreamerConfig = info.videoPlaybackUstreamerConfig
            if (ustreamerConfig.isNullOrEmpty()) {
                throw SabrProtocolException("Missing video playback ustreamer config")
            }
            val playerTimeMs = streamState?.getRequestPlayerTimeMs() ?: 0L
            val bufferedRanges = streamState?.getBufferedRanges() ?: emptyList()
            val includeInitialPlaybackState = playerTimeMs > 0 || bufferedRanges.isNotEmpty()
            val request = SabrProto.Writer()
            request.writeMessage(
                1,
                buildClientAbrState(
                    audioFormat,
                    videoFormat,
                    playerTimeMs,
                    includeInitialPlaybackState,
                    streamState?.getEnabledTrackTypesBitfield() ?: ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO,
                    streamState
                )
            )
            if (includeInitialPlaybackState && streamState != null) {
                if (streamState.shouldSelectVideoFormatBeforeAudio()
                    && streamState.shouldSelectVideoFormat()
                    && streamState.isInitialized(videoFormat)
                ) {
                    request.writeMessage(2, SabrProto.formatId(videoFormat))
                }
                if (streamState.shouldSelectAudioFormat() && streamState.isInitialized(audioFormat)) {
                    request.writeMessage(2, SabrProto.formatId(audioFormat))
                }
                if (!streamState.shouldSelectVideoFormatBeforeAudio()
                    && streamState.shouldSelectVideoFormat()
                    && streamState.isInitialized(videoFormat)
                ) {
                    request.writeMessage(2, SabrProto.formatId(videoFormat))
                }
                for (range in bufferedRanges) {
                    request.writeMessage(3, range.toProto(streamState.shouldWriteBufferedRangeTimeRange()))
                }
                if (streamState.shouldWriteTopLevelPlayerTimeMs()) {
                    request.writeUInt64(4, playerTimeMs)
                }
            }
            request.writeBytes(5, decodeBase64(ustreamerConfig))
            writePreferredFormats(request, info, audioFormat, videoFormat, streamState)
            request.writeMessage(
                19,
                if (streamState == null) buildStreamerContext(info)
                else buildStreamerContext(info, streamState)
            )
            return request.toByteArray()
        }

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun buildFollowUpMediaRequest(
            info: YoutubeSabrInfo,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            streamState: YoutubeSabrStreamState
        ): ByteArray {
            synchronized(streamState) {
                return buildFollowUpMediaRequestLocked(info, audioFormat, videoFormat, streamState)
            }
        }

        @Throws(SabrProtocolException::class)
        private fun buildFollowUpMediaRequestLocked(
            info: YoutubeSabrInfo,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            streamState: YoutubeSabrStreamState
        ): ByteArray {
            val ustreamerConfig = info.videoPlaybackUstreamerConfig
            if (ustreamerConfig.isNullOrEmpty()) {
                throw SabrProtocolException("Missing video playback ustreamer config")
            }
            val playerTimeMs = streamState.getRequestPlayerTimeMs()
            val request = SabrProto.Writer()
            request.writeMessage(
                1,
                buildClientAbrState(
                    audioFormat,
                    videoFormat,
                    playerTimeMs,
                    true,
                    streamState.getEnabledTrackTypesBitfield(),
                    streamState
                )
            )
            if (streamState.shouldSelectVideoFormatBeforeAudio()
                && streamState.shouldSelectVideoFormat()
                && streamState.isInitialized(videoFormat)
            ) {
                request.writeMessage(2, SabrProto.formatId(videoFormat))
            }
            if (streamState.shouldSelectAudioFormat() && streamState.isInitialized(audioFormat)) {
                request.writeMessage(2, SabrProto.formatId(audioFormat))
            }
            if (!streamState.shouldSelectVideoFormatBeforeAudio()
                && streamState.shouldSelectVideoFormat()
                && streamState.isInitialized(videoFormat)
            ) {
                request.writeMessage(2, SabrProto.formatId(videoFormat))
            }
            val bufferedRanges = streamState.getBufferedRanges()
            for (range in bufferedRanges) {
                request.writeMessage(3, range.toProto(streamState.shouldWriteBufferedRangeTimeRange()))
            }
            if (streamState.shouldWriteTopLevelPlayerTimeMs()) {
                request.writeUInt64(4, playerTimeMs)
            }
            request.writeBytes(5, decodeBase64(ustreamerConfig))
            writePreferredFormats(request, info, audioFormat, videoFormat, streamState)
            request.writeMessage(19, buildStreamerContext(info, streamState))
            return request.toByteArray()
        }

        private fun buildClientAbrState(
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat
        ): ByteArray = buildClientAbrState(audioFormat, videoFormat, 0, false)

        private fun buildClientAbrState(
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            playerTimeMs: Long,
            includeFollowUpState: Boolean
        ): ByteArray = buildClientAbrState(
            audioFormat, videoFormat, playerTimeMs, includeFollowUpState,
            ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO
        )

        private fun buildClientAbrState(
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            playerTimeMs: Long,
            includeFollowUpState: Boolean,
            enabledTrackTypesBitfield: Int
        ): ByteArray = buildClientAbrState(
            audioFormat, videoFormat, playerTimeMs, includeFollowUpState,
            enabledTrackTypesBitfield, null
        )

        private fun buildClientAbrState(
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            playerTimeMs: Long,
            includeFollowUpState: Boolean,
            enabledTrackTypesBitfield: Int,
            streamState: YoutubeSabrStreamState?
        ): ByteArray {
            val state = SabrProto.Writer()
            val officialWebClientAbrFields = streamState != null && streamState.shouldWriteOfficialWebClientAbrFields()

            if ((includeFollowUpState || officialWebClientAbrFields) && streamState != null
                && streamState.shouldWriteLastManualSelectedResolution()
            ) {
                state.writeInt32(16, maxOf(videoFormat.height, 360))
            }
            if (includeFollowUpState || officialWebClientAbrFields) {
                state.writeInt32(
                    18,
                    if (streamState != null && streamState.getClientViewportWidth() > 0)
                        streamState.getClientViewportWidth()
                    else maxOf(videoFormat.width, 640)
                )
                state.writeInt32(
                    19,
                    if (streamState != null && streamState.getClientViewportHeight() > 0)
                        streamState.getClientViewportHeight()
                    else maxOf(videoFormat.height, 360)
                )
            }
            val stickyResolutionOverride = streamState?.getStickyResolutionOverride()
            state.writeInt32(21, stickyResolutionOverride ?: maxOf(videoFormat.height, 360))

            if (includeFollowUpState || officialWebClientAbrFields) {
                val bandwidthEstimate: Long = if (streamState != null && streamState.getBandwidthEstimate() > 0)
                    streamState.getBandwidthEstimate()
                else if (audioFormat.bitrate > 0 && videoFormat.bitrate > 0)
                    (audioFormat.bitrate + videoFormat.bitrate) * 2L
                else -1
                if (bandwidthEstimate > 0) state.writeUInt64(23, bandwidthEstimate)
            }
            val visibility: Int? = if (streamState == null) 1 else streamState.getClientAbrVisibility()
            if (visibility != null) state.writeInt32(34, visibility)
            state.writeFloat(35, streamState?.getPlaybackRate() ?: 1.0f)

            if (enabledTrackTypesBitfield != ENABLED_TRACK_TYPES_VIDEO_AND_AUDIO) {
                state.writeInt32(40, enabledTrackTypesBitfield)
            }
            if (audioFormat.isDrc) state.writeBool(46, true)

            if (streamState?.getSabrReportRequestCancellationInfoOverride() != null) {
                state.writeInt32(54, streamState.getSabrReportRequestCancellationInfoOverride()!!)
            }
            if (officialWebClientAbrFields && streamState != null) {
                if (includeFollowUpState) {
                    state.writeUInt64(29, longOverride(streamState.getOfficialTimeSinceLastSeekOverride(), 48))
                    state.writeUInt64(36, longOverride(streamState.getOfficialElapsedWallTimeOverride(), 1406))
                    state.writeUInt64(39, longOverride(streamState.getOfficialTimeSinceLastActionOverride(), 1446))
                    state.writeUInt64(57, longOverride(streamState.getOfficialField57Override(), 59))
                } else {
                    state.writeUInt64(29, longOverride(streamState.getOfficialTimeSinceLastSeekOverride(), 9))
                    state.writeUInt64(36, longOverride(streamState.getOfficialElapsedWallTimeOverride(), 41))
                    state.writeUInt64(39, longOverride(streamState.getOfficialTimeSinceLastActionOverride(), 80))
                    val officialField57Override = streamState.getOfficialField57Override()
                    if (officialField57Override != null) state.writeUInt64(57, officialField57Override)
                }
                state.writeBool(58, false)
                state.writeInt32(59, maxOf(videoFormat.height, 1080))
                state.writeUInt64(68, longOverride(streamState.getOfficialField68Override(), 0))
                state.writeBool(71, true)
                state.writeMessage(72, buildOfficialWebQualityConstraints(maxOf(videoFormat.height, 1080)))
                state.writeInt32(76, 0)
                state.writeMessage(79, buildOfficialWebPlaybackAuthorization())
                if (!includeFollowUpState) state.writeInt32(80, 1)
            }
            state.writeUInt64(28, playerTimeMs)
            state.writeStringIfNotEmpty(69, audioFormat.audioTrackId)
            return state.toByteArray()
        }

        private fun writePreferredFormats(
            request: SabrProto.Writer,
            info: YoutubeSabrInfo,
            audioFormat: YoutubeSabrFormat,
            videoFormat: YoutubeSabrFormat,
            streamState: YoutubeSabrStreamState?
        ) {
            if (streamState != null && streamState.shouldWriteAllPreferredFormats()) {
                if (streamState.shouldWriteOfficialWebPreferredFormats()) {
                    writeOfficialWebPreferredFormats(request, info)
                    return
                }
                for (format in info.getFormats()) {
                    if (format.isAudio && streamState.shouldSelectAudioFormat()) {
                        request.writeMessage(16, SabrProto.formatId(format))
                    }
                }
                for (format in info.getFormats()) {
                    if (format.isVideo && streamState.shouldSelectVideoFormat()) {
                        request.writeMessage(17, SabrProto.formatId(format))
                    }
                }
                return
            }
            if (streamState == null || streamState.shouldSelectAudioFormat()) {
                request.writeMessage(16, SabrProto.formatId(audioFormat))
            }
            if (streamState == null || streamState.shouldSelectVideoFormat()) {
                request.writeMessage(17, SabrProto.formatId(videoFormat))
            }
        }

        private fun writeOfficialWebPreferredFormats(request: SabrProto.Writer, info: YoutubeSabrInfo) {
            writeAudioFormatByItagAndXtagsLength(request, info, 251, 12)
            writeAudioFormatByItagAndXtagsLength(request, info, 251, 14)
            writeAudioFormatByItagAndXtagsLength(request, info, 251, 0)
            writeAudioFormatByItagAndXtagsLength(request, info, 250, 14)
            writeAudioFormatByItagAndXtagsLength(request, info, 250, 0)
            writeAudioFormatByItagAndXtagsLength(request, info, 250, 12)
            writeVideoFormatByItag(request, info, 248)
            writeVideoFormatByItag(request, info, 247)
            writeVideoFormatByItag(request, info, 244)
            writeVideoFormatByItag(request, info, 243)
            writeVideoFormatByItag(request, info, 242)
            writeVideoFormatByItag(request, info, 278)
        }

        private fun writeAudioFormatByItagAndXtagsLength(
            request: SabrProto.Writer,
            info: YoutubeSabrInfo,
            itag: Int,
            xtagsLength: Int
        ) {
            for (format in info.getFormats()) {
                val currentXtagsLength = format.xtags?.length ?: 0
                if (format.isAudio && format.itag == itag && currentXtagsLength == xtagsLength) {
                    request.writeMessage(16, SabrProto.formatId(format))
                }
            }
        }

        private fun writeVideoFormatByItag(request: SabrProto.Writer, info: YoutubeSabrInfo, itag: Int) {
            for (format in info.getFormats()) {
                if (format.isVideo && format.itag == itag) {
                    request.writeMessage(17, SabrProto.formatId(format))
                    return
                }
            }
        }

        private fun buildOfficialWebQualityConstraints(height: Int): ByteArray {
            val constraints = SabrProto.Writer()
            constraints.writeInt32(1, 0)
            constraints.writeInt32(2, height)
            constraints.writeInt32(3, 0)
            constraints.writeInt32(4, 0)
            constraints.writeInt32(5, height)
            constraints.writeInt32(6, 0)
            return constraints.toByteArray()
        }

        private fun buildOfficialWebPlaybackAuthorization(): ByteArray {
            val authorization = SabrProto.Writer()
            authorization.writeMessage(1, buildAuthorizedTrack(1, false))
            authorization.writeMessage(1, buildAuthorizedTrack(2, false))
            authorization.writeMessage(1, buildAuthorizedTrack(2, true))
            return authorization.toByteArray()
        }

        private fun buildAuthorizedTrack(trackType: Int, hdr: Boolean): ByteArray {
            val track = SabrProto.Writer()
            track.writeInt32(1, trackType)
            track.writeBool(2, hdr)
            return track.toByteArray()
        }

        private fun buildStreamerContext(info: YoutubeSabrInfo): ByteArray = buildStreamerContext(info, null as ByteArray?)

        private fun buildStreamerContext(info: YoutubeSabrInfo, streamState: YoutubeSabrStreamState): ByteArray =
            buildStreamerContext(info, streamState.getRawPlaybackCookie(), streamState)

        private fun buildStreamerContext(info: YoutubeSabrInfo, playbackCookie: ByteArray?): ByteArray =
            buildStreamerContext(info, playbackCookie, null)

        private fun buildStreamerContext(
            info: YoutubeSabrInfo,
            playbackCookie: ByteArray?,
            streamState: YoutubeSabrStreamState?
        ): ByteArray {
            val context = SabrProto.Writer()
            context.writeMessage(1, buildClientInfo(info, streamState))
            val poToken = streamState?.getRawPoToken()
            if (poToken != null && poToken.isNotEmpty()) context.writeBytes(2, poToken)
            if (playbackCookie != null && playbackCookie.isNotEmpty()) context.writeBytes(3, playbackCookie)
            if (streamState != null) {
                for (cu in streamState.getActiveSabrContexts()) {
                    context.writeMessage(5, cu.toStreamerContextProto())
                }
                for (type in streamState.getUnsentSabrContextTypes()) {
                    context.writeInt32(6, type)
                }
            }
            return context.toByteArray()
        }

        private fun buildClientInfo(info: YoutubeSabrInfo): ByteArray = buildClientInfo(info, null)

        private fun buildClientInfo(info: YoutubeSabrInfo, streamState: YoutubeSabrStreamState?): ByteArray {
            val client = SabrProto.Writer()
            if (streamState != null && streamState.shouldWriteOfficialWebClientAbrFields()) {
                client.writeStringIfNotEmpty(1, "en_US")
                client.writeInt32(16, parseInt(info.profile.clientId, -1))
                client.writeStringIfNotEmpty(17, info.clientVersion)
                client.writeStringIfNotEmpty(18, "X11")
                return client.toByteArray()
            }
            client.writeInt32(16, parseInt(info.profile.clientId, -1))
            client.writeStringIfNotEmpty(17, info.clientVersion)
            client.writeStringIfNotEmpty(18, info.profile.osName)
            client.writeStringIfNotEmpty(19, info.profile.osVersion)
            client.writeStringIfNotEmpty(21, "en-US")
            client.writeStringIfNotEmpty(22, "US")
            return client.toByteArray()
        }

        @Throws(SabrProtocolException::class)
        private fun decodeBase64(value: String): ByteArray {
            try {
                return Base64.getDecoder().decode(padBase64(value))
            } catch (first: IllegalArgumentException) {
                try {
                    return Base64.getUrlDecoder().decode(padBase64(value))
                } catch (second: IllegalArgumentException) {
                    throw SabrProtocolException("Could not decode base64 ustreamer config", second)
                }
            }
        }

        private fun padBase64(value: String): String {
            val padding = (4 - value.length % 4) % 4
            return buildString {
                append(value)
                repeat(padding) { append('=') }
            }
        }

        private fun parseInt(value: String?, fallback: Int): Int {
            if (value == null) return fallback
            return try { value.toInt() } catch (ignored: NumberFormatException) { fallback }
        }

        private fun longOverride(override: Long?, fallback: Long): Long = override ?: fallback
    }
}
