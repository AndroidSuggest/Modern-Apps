package org.schabi.newpipe.extractor.services.youtube.sabr

class SabrResponseDecoder private constructor() {

    companion object {
        const val ONESIE_HEADER = 10
        const val ONESIE_DATA = 11
        const val ONESIE_ENCRYPTED_MEDIA = 12
        const val MEDIA_HEADER = 20
        const val MEDIA = 21
        const val MEDIA_END = 22
        const val CONFIG = 30
        const val LIVE_METADATA = 31
        const val HOSTNAME_CHANGE_HINT_DEPRECATED = 32
        const val LIVE_METADATA_PROMISE = 33
        const val LIVE_METADATA_PROMISE_CANCELLATION = 34
        const val NEXT_REQUEST_POLICY = 35
        const val USTREAMER_VIDEO_AND_FORMAT_METADATA = 36
        const val FORMAT_SELECTION_CONFIG = 37
        const val USTREAMER_SELECTED_MEDIA_STREAM = 38
        const val FORMAT_INITIALIZATION_METADATA = 42
        const val SABR_REDIRECT = 43
        const val SABR_ERROR = 44
        const val SABR_SEEK = 45
        const val RELOAD_PLAYER_RESPONSE = 46
        const val PLAYBACK_START_POLICY = 47
        const val ALLOWED_CACHED_FORMATS = 48
        const val START_BW_SAMPLING_HINT = 49
        const val PAUSE_BW_SAMPLING_HINT = 50
        const val SELECTABLE_FORMATS = 51
        const val REQUEST_IDENTIFIER = 52
        const val REQUEST_CANCELLATION_POLICY = 53
        const val ONESIE_PREFETCH_REJECTION = 54
        const val TIMELINE_CONTEXT = 55
        const val REQUEST_PIPELINING = 56
        const val SABR_CONTEXT_UPDATE = 57
        const val STREAM_PROTECTION_STATUS = 58
        const val SABR_CONTEXT_SENDING_POLICY = 59
        const val LAWNMOWER_POLICY = 60
        const val SABR_ACK = 61
        const val END_OF_TRACK = 62
        const val CACHE_LOAD_POLICY = 63
        const val LAWNMOWER_MESSAGING_POLICY = 64
        const val PREWARM_CONNECTION = 65
        const val PLAYBACK_DEBUG_INFO = 66
        const val SNACKBAR_MESSAGE = 67

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decode(data: ByteArray): SabrDecodedResponse = decodeParts(UmpReader.readAll(data))

        @JvmStatic
        @Throws(SabrProtocolException::class)
        fun decodeParts(parts: List<UmpPart>): SabrDecodedResponse =
            decodeParts(parts, SabrMediaProtocol.builtin())

        @JvmStatic
        @Throws(SabrProtocolException::class)
        internal fun decodeParts(parts: List<UmpPart>, mediaProtocol: SabrMediaProtocol): SabrDecodedResponse {
            val decoded = SabrDecodedResponse()
            var currentOnesieHeader: SabrOnesieHeader? = null
            for (part in parts) {
                val partData = part.rawData
                decoded.addPart(part)
                if (part.type != mediaProtocol.getMediaPartType() && part.type != mediaProtocol.getEndPartType()) {
                    try {
                        decoded.addWireFieldSummary(part.type, SabrProto.summarizeFields(partData))
                    } catch (ignored: SabrProtocolException) {
                        decoded.addWireFieldSummary(part.type, "opaqueBytes=" + partData.size)
                    }
                }
                try {
                    if (part.type == mediaProtocol.getHeaderPartType()) {
                        decoded.addMediaHeader(mediaProtocol.decodeHeader(partData))
                        continue
                    }
                    if (part.type == mediaProtocol.getMediaPartType()) {
                        if (partData.isNotEmpty()) decoded.addMediaBytes(partData[0].toInt() and 0xff, partData.size - 1L)
                        continue
                    }
                    if (part.type == mediaProtocol.getEndPartType()) {
                        if (partData.isNotEmpty()) decoded.addMediaEndHeaderId(partData[0].toInt() and 0xff)
                        continue
                    }
                    when (part.type) {
                        ONESIE_HEADER -> {
                            val onesieHeader = SabrOnesieHeader.decode(partData)
                            currentOnesieHeader = onesieHeader
                            decoded.addOnesieHeader(onesieHeader)
                            decoded.addGenericPartDescription(part.type, onesieHeader.summarize())
                        }
                        ONESIE_DATA, ONESIE_ENCRYPTED_MEDIA -> {
                            val onesieData = SabrOnesieData.fromPart(
                                partData, part.type == ONESIE_ENCRYPTED_MEDIA, currentOnesieHeader
                            )
                            decoded.addOnesieData(onesieData)
                            decoded.addGenericPartDescription(part.type, onesieData.summarize())
                        }
                        FORMAT_INITIALIZATION_METADATA -> {
                            val metadata = SabrFormatInitializationMetadata.decode(partData)
                            decoded.addFormatInitializationMetadata(metadata)
                            decoded.addGenericPartDescription(part.type, metadata.summarize())
                        }
                        MEDIA_HEADER -> decoded.addMediaHeader(SabrMediaHeader.decode(partData))
                        MEDIA -> if (partData.isNotEmpty()) decoded.addMediaBytes(partData[0].toInt() and 0xff, partData.size - 1L)
                        MEDIA_END -> if (partData.isNotEmpty()) decoded.addMediaEndHeaderId(partData[0].toInt() and 0xff)
                        LIVE_METADATA -> {
                            val liveMetadata = SabrLiveMetadata.decode(partData)
                            decoded.addLiveMetadata(liveMetadata)
                            decoded.addGenericPartDescription(part.type, liveMetadata.summarize())
                        }
                        NEXT_REQUEST_POLICY -> {
                            val nextRequestPolicy = decodeNextRequestPolicy(partData, decoded)
                            decoded.addGenericPartDescription(part.type, nextRequestPolicy.summarize())
                        }
                        SABR_REDIRECT -> {
                            val redirect = SabrRedirect.decode(partData)
                            decoded.setRedirect(redirect)
                            decoded.setRedirectUrl(redirect.getUrl())
                            decoded.addGenericPartDescription(part.type, redirect.summarize())
                        }
                        SABR_SEEK -> {
                            val sabrSeek = SabrSeek.decode(partData)
                            decoded.setSabrSeek(sabrSeek)
                            decoded.addGenericPartDescription(part.type, sabrSeek.summarize())
                        }
                        SABR_ERROR -> {
                            val sabrError = SabrError.decode(partData)
                            decoded.setSabrErrorDetails(sabrError)
                            decoded.setSabrError(sabrError.summarize())
                            decoded.addGenericPartDescription(part.type, sabrError.summarize())
                        }
                        RELOAD_PLAYER_RESPONSE -> {
                            val reloadPlayerResponse = SabrReloadPlayerResponse.decode(partData)
                            decoded.setReloadRequested(true)
                            decoded.setReloadPlayerResponse(reloadPlayerResponse)
                            decoded.addGenericPartDescription(part.type, reloadPlayerResponse.summarize())
                        }
                        STREAM_PROTECTION_STATUS -> {
                            val streamProtection = SabrStreamProtectionStatus.decode(partData)
                            decoded.setStreamProtection(streamProtection)
                            decoded.setStreamProtectionStatus(streamProtection.getStatus())
                            decoded.setStreamProtectionMaxRetries(streamProtection.getMaxRetries())
                            decoded.addGenericPartDescription(part.type, streamProtection.summarize())
                        }
                        PLAYBACK_START_POLICY -> {
                            val playbackStartPolicy = SabrPlaybackStartPolicy.decode(partData)
                            decoded.setPlaybackStartPolicy(playbackStartPolicy)
                            decoded.addGenericPartDescription(part.type, playbackStartPolicy.summarize())
                        }
                        SABR_CONTEXT_UPDATE -> {
                            val sabrContextUpdate = SabrContextUpdate.decode(partData)
                            decoded.addSabrContextUpdate(sabrContextUpdate)
                            decoded.addGenericPartDescription(part.type, sabrContextUpdate.summarize())
                        }
                        SABR_CONTEXT_SENDING_POLICY -> {
                            val sabrContextSendingPolicy = SabrContextSendingPolicy.decode(partData)
                            decoded.setSabrContextSendingPolicy(sabrContextSendingPolicy)
                            decoded.addGenericPartDescription(part.type, sabrContextSendingPolicy.summarize())
                        }
                        SNACKBAR_MESSAGE -> {
                            val snackbarMessage = SabrSnackbarMessage.decode(partData)
                            decoded.setSnackbarMessage(snackbarMessage)
                            decoded.addGenericPartDescription(part.type, snackbarMessage.summarize())
                        }
                        FORMAT_SELECTION_CONFIG -> {
                            val formatSelectionConfig = SabrFormatSelectionConfig.decode(partData)
                            decoded.setFormatSelectionConfig(formatSelectionConfig)
                            decoded.addGenericPartDescription(part.type, formatSelectionConfig.summarize())
                        }
                        PREWARM_CONNECTION -> {
                            val prewarmConnection = SabrPrewarmConnection.decode(partData)
                            decoded.setPrewarmConnection(prewarmConnection)
                            decoded.addGenericPartDescription(part.type, prewarmConnection.summarize())
                        }
                        START_BW_SAMPLING_HINT, CONFIG, HOSTNAME_CHANGE_HINT_DEPRECATED,
                        LIVE_METADATA_PROMISE, LIVE_METADATA_PROMISE_CANCELLATION,
                        USTREAMER_VIDEO_AND_FORMAT_METADATA, USTREAMER_SELECTED_MEDIA_STREAM,
                        ALLOWED_CACHED_FORMATS, PAUSE_BW_SAMPLING_HINT, ONESIE_PREFETCH_REJECTION,
                        TIMELINE_CONTEXT, REQUEST_PIPELINING, LAWNMOWER_POLICY, SABR_ACK,
                        END_OF_TRACK, CACHE_LOAD_POLICY, LAWNMOWER_MESSAGING_POLICY,
                        PLAYBACK_DEBUG_INFO -> decoded.addGenericPartDescription(part.type, describeGenericMessage(partData))
                        REQUEST_IDENTIFIER -> {
                            val requestIdentifier = SabrRequestIdentifier.decode(partData)
                            decoded.setRequestIdentifier(requestIdentifier)
                            decoded.addGenericPartDescription(part.type, requestIdentifier.summarize())
                        }
                        REQUEST_CANCELLATION_POLICY -> {
                            val requestCancellationPolicy = SabrRequestCancellationPolicy.decode(partData)
                            decoded.setRequestCancellationPolicy(requestCancellationPolicy)
                            decoded.addGenericPartDescription(part.type, requestCancellationPolicy.summarize())
                        }
                        SELECTABLE_FORMATS -> {
                            val selectableFormats = SabrSelectableFormats.decode(partData)
                            decoded.setSelectableFormats(selectableFormats)
                            decoded.addGenericPartDescription(part.type, selectableFormats.summarize())
                        }
                        else -> {
                            decoded.addUnknownPartType(part.type)
                            decoded.addGenericPartDescription(part.type, describeGenericMessage(partData))
                        }
                    }
                } catch (e: SabrProtocolException) {
                    decoded.addMalformedPart(part.type, part.getSize(), e)
                }
            }
            return decoded
        }

        @Throws(SabrProtocolException::class)
        private fun decodeNextRequestPolicy(data: ByteArray, decoded: SabrDecodedResponse): SabrNextRequestPolicy {
            val policy = SabrNextRequestPolicy.decode(data)
            decoded.setNextRequestPolicy(policy)
            decoded.setBackoffTimeMs(policy.getBackoffTimeMs())
            for (field in SabrProto.readFields(data)) {
                if (field.getNumber() == 4 && field.getWireType() == SabrProto.WIRE_VARINT) {
                    decoded.setBackoffTimeMs(field.getVarint().toInt())
                }
            }
            return policy
        }

        private fun describeGenericMessage(data: ByteArray): String = try {
            SabrProto.summarizeFields(data)
        } catch (e: Exception) {
            "undecodable(" + data.size + " bytes)"
        }
    }
}
