package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Collections

class SabrDecodedResponse {

    companion object {
        private const val MAX_MALFORMED_PARTS = 16
        private const val MAX_MALFORMED_MESSAGE_CHARS = 256

        @JvmStatic
        fun addPartSummary(summaries: MutableList<String>, type: Int, size: Int) {
            val value = "$type:$size"
            if (summaries.isEmpty()) {
                summaries.add(value)
                return
            }
            val lastIndex = summaries.size - 1
            val last = summaries[lastIndex]
            if (last == value) {
                summaries[lastIndex] = value + "x2"
            } else if (last.startsWith(value + 'x')) {
                val count = last.substring(value.length + 1).toInt()
                summaries[lastIndex] = value + 'x' + (count + 1)
            } else {
                summaries.add(value)
            }
        }
    }

    private val partsInternal: MutableList<UmpPart> = mutableListOf()
    private val partSummariesInternal: MutableList<String> = mutableListOf()
    private val wireFieldSummariesInternal: MutableList<String> = mutableListOf()
    private val formatInitializationMetadataInternal: MutableList<SabrFormatInitializationMetadata> = mutableListOf()
    private val mediaHeadersInternal: MutableList<SabrMediaHeader> = mutableListOf()
    private val sabrContextUpdatesInternal: MutableList<SabrContextUpdate> = mutableListOf()
    private val liveMetadataInternal: MutableList<SabrLiveMetadata> = mutableListOf()
    private val onesieHeadersInternal: MutableList<SabrOnesieHeader> = mutableListOf()
    private val onesieDataInternal: MutableList<SabrOnesieData> = mutableListOf()
    private val mediaBytesByHeaderIdInternal: MutableMap<Int, Long> = LinkedHashMap()
    private val mediaEndHeaderIdsInternal: MutableList<Int> = mutableListOf()
    private val unknownPartTypesInternal: MutableList<Int> = mutableListOf()
    private val malformedPartsInternal: MutableList<String> = mutableListOf()
    private val genericPartDescriptionsInternal: MutableMap<Int, MutableList<String>> = LinkedHashMap()

    private var redirectUrlValue: String? = null
    private var redirectValue: SabrRedirect? = null
    private var sabrSeekValue: SabrSeek? = null
    private var sabrErrorValue: String? = null
    private var sabrErrorDetailsValue: SabrError? = null
    private var reloadPlayerResponseValue: SabrReloadPlayerResponse? = null
    private var formatSelectionConfigValue: SabrFormatSelectionConfig? = null
    private var selectableFormatsValue: SabrSelectableFormats? = null
    private var nextRequestPolicyValue: SabrNextRequestPolicy? = null
    private var requestIdentifierValue: SabrRequestIdentifier? = null
    private var playbackStartPolicyValue: SabrPlaybackStartPolicy? = null
    private var sabrContextSendingPolicyValue: SabrContextSendingPolicy? = null
    private var requestCancellationPolicyValue: SabrRequestCancellationPolicy? = null
    private var streamProtectionValue: SabrStreamProtectionStatus? = null
    private var prewarmConnectionValue: SabrPrewarmConnection? = null
    private var snackbarMessageValue: SabrSnackbarMessage? = null
    private var streamProtectionStatusValue: Int = -1
    private var streamProtectionMaxRetriesValue: Int = -1
    private var backoffTimeMsValue: Int = -1
    private var reloadRequestedValue: Boolean = false

    internal fun addPart(part: UmpPart) {
        partsInternal.add(part)
        addPartSummary(partSummariesInternal, part.type, part.size)
    }

    internal fun setPartSummaries(summaries: List<String>) {
        partSummariesInternal.clear()
        partSummariesInternal.addAll(summaries)
    }

    internal fun addUnknownPartType(type: Int) {
        unknownPartTypesInternal.add(type)
    }

    internal fun addMalformedPart(type: Int, size: Int, error: SabrProtocolException) {
        if (malformedPartsInternal.size >= MAX_MALFORMED_PARTS) return
        val message = error.message.toString()
        val truncated = if (message.length > MAX_MALFORMED_MESSAGE_CHARS)
            message.substring(0, MAX_MALFORMED_MESSAGE_CHARS) else message
        malformedPartsInternal.add("$type:$size:$truncated")
    }

    internal fun addWireFieldSummary(type: Int, summary: String) {
        wireFieldSummariesInternal.add("$type={$summary}")
    }

    internal fun addGenericPartDescription(type: Int, description: String) {
        val existing = genericPartDescriptionsInternal[type]
        if (existing == null) {
            genericPartDescriptionsInternal[type] = mutableListOf(description)
        } else {
            existing.add(description)
        }
    }

    internal fun addFormatInitializationMetadata(metadata: SabrFormatInitializationMetadata) {
        formatInitializationMetadataInternal.add(metadata)
    }

    internal fun addMediaHeader(header: SabrMediaHeader) {
        mediaHeadersInternal.add(header)
    }

    internal fun addSabrContextUpdate(sabrContextUpdate: SabrContextUpdate) {
        sabrContextUpdatesInternal.add(sabrContextUpdate)
    }

    internal fun addLiveMetadata(metadata: SabrLiveMetadata) {
        liveMetadataInternal.add(metadata)
    }

    internal fun addOnesieHeader(onesieHeader: SabrOnesieHeader) {
        onesieHeadersInternal.add(onesieHeader)
    }

    internal fun addOnesieData(data: SabrOnesieData) {
        onesieDataInternal.add(data)
    }

    internal fun addMediaBytes(headerId: Int, bytes: Long) {
        val current = mediaBytesByHeaderIdInternal[headerId]
        mediaBytesByHeaderIdInternal[headerId] = if (current == null) bytes else current + bytes
    }

    internal fun addMediaEndHeaderId(headerId: Int) {
        mediaEndHeaderIdsInternal.add(headerId)
    }

    internal fun setRedirectUrl(redirectUrl: String?) {
        this.redirectUrlValue = redirectUrl
    }

    internal fun setRedirect(redirect: SabrRedirect?) {
        this.redirectValue = redirect
    }

    internal fun setSabrSeek(sabrSeek: SabrSeek?) {
        this.sabrSeekValue = sabrSeek
    }

    internal fun setSabrError(sabrError: String?) {
        this.sabrErrorValue = sabrError
    }

    internal fun setSabrErrorDetails(sabrErrorDetails: SabrError?) {
        this.sabrErrorDetailsValue = sabrErrorDetails
    }

    internal fun setReloadPlayerResponse(reloadPlayerResponse: SabrReloadPlayerResponse?) {
        this.reloadPlayerResponseValue = reloadPlayerResponse
    }

    internal fun setFormatSelectionConfig(formatSelectionConfig: SabrFormatSelectionConfig?) {
        this.formatSelectionConfigValue = formatSelectionConfig
    }

    internal fun setSelectableFormats(selectableFormats: SabrSelectableFormats?) {
        this.selectableFormatsValue = selectableFormats
    }

    internal fun setNextRequestPolicy(nextRequestPolicy: SabrNextRequestPolicy?) {
        this.nextRequestPolicyValue = nextRequestPolicy
    }

    internal fun setRequestIdentifier(requestIdentifier: SabrRequestIdentifier?) {
        this.requestIdentifierValue = requestIdentifier
    }

    internal fun setPlaybackStartPolicy(playbackStartPolicy: SabrPlaybackStartPolicy?) {
        this.playbackStartPolicyValue = playbackStartPolicy
    }

    internal fun setSabrContextSendingPolicy(sabrContextSendingPolicy: SabrContextSendingPolicy?) {
        this.sabrContextSendingPolicyValue = sabrContextSendingPolicy
    }

    internal fun setRequestCancellationPolicy(requestCancellationPolicy: SabrRequestCancellationPolicy?) {
        this.requestCancellationPolicyValue = requestCancellationPolicy
    }

    internal fun setStreamProtection(streamProtection: SabrStreamProtectionStatus?) {
        this.streamProtectionValue = streamProtection
    }

    internal fun setPrewarmConnection(prewarmConnection: SabrPrewarmConnection?) {
        this.prewarmConnectionValue = prewarmConnection
    }

    internal fun setSnackbarMessage(snackbarMessage: SabrSnackbarMessage?) {
        this.snackbarMessageValue = snackbarMessage
    }

    internal fun setStreamProtectionStatus(streamProtectionStatus: Int) {
        this.streamProtectionStatusValue = streamProtectionStatus
    }

    internal fun setStreamProtectionMaxRetries(streamProtectionMaxRetries: Int) {
        this.streamProtectionMaxRetriesValue = streamProtectionMaxRetries
    }

    internal fun setBackoffTimeMs(backoffTimeMs: Int) {
        this.backoffTimeMsValue = backoffTimeMs
    }

    internal fun setReloadRequested(reloadRequested: Boolean) {
        this.reloadRequestedValue = reloadRequested
    }

    fun getParts(): List<UmpPart> = Collections.unmodifiableList(partsInternal)

    fun getFormatInitializationMetadata(): List<SabrFormatInitializationMetadata> =
        Collections.unmodifiableList(formatInitializationMetadataInternal)

    fun getMediaHeaders(): List<SabrMediaHeader> =
        Collections.unmodifiableList(mediaHeadersInternal)

    fun getSabrContextUpdates(): List<SabrContextUpdate> =
        Collections.unmodifiableList(sabrContextUpdatesInternal)

    fun getLiveMetadata(): List<SabrLiveMetadata> =
        Collections.unmodifiableList(liveMetadataInternal)

    fun getOnesieHeaders(): List<SabrOnesieHeader> =
        Collections.unmodifiableList(onesieHeadersInternal)

    fun getOnesieData(): List<SabrOnesieData> =
        Collections.unmodifiableList(onesieDataInternal)

    fun getMediaBytesByHeaderId(): Map<Int, Long> =
        Collections.unmodifiableMap(mediaBytesByHeaderIdInternal)

    fun getMediaEndHeaderIds(): List<Int> =
        Collections.unmodifiableList(mediaEndHeaderIdsInternal)

    fun getIntegrityIssues(): List<String> {
        val issues = mutableListOf<String>()
        val mediaHeaderIds = mutableListOf<Int>()
        for (header in mediaHeadersInternal) {
            if (mediaHeaderIds.contains(header.headerId)) {
                issues.add("duplicate-media-header:" + header.headerId)
            }
            mediaHeaderIds.add(header.headerId)
            val mediaBytes = mediaBytesByHeaderIdInternal[header.headerId]
            if (mediaBytes == null) {
                issues.add("missing-media:" + header.headerId)
            } else if (header.contentLength >= 0 && mediaBytes != header.contentLength) {
                issues.add(
                    "length-mismatch:" + header.headerId +
                        ":expected=" + header.contentLength +
                        ":actual=" + mediaBytes
                )
            }
            if (!mediaEndHeaderIdsInternal.contains(header.headerId)) {
                issues.add("missing-media-end:" + header.headerId)
            }
        }
        for (headerId in mediaBytesByHeaderIdInternal.keys) {
            if (!mediaHeaderIds.contains(headerId)) {
                issues.add("media-without-header:$headerId")
            }
        }
        for (headerId in mediaEndHeaderIdsInternal) {
            if (!mediaHeaderIds.contains(headerId)) {
                issues.add("media-end-without-header:$headerId")
            }
        }
        return issues
    }

    fun getUnknownPartTypes(): List<Int> = Collections.unmodifiableList(unknownPartTypesInternal)

    fun getMalformedParts(): List<String> = Collections.unmodifiableList(malformedPartsInternal)

    fun getGenericPartDescriptions(): Map<Int, List<String>> {
        val copy = LinkedHashMap<Int, List<String>>()
        for ((k, v) in genericPartDescriptionsInternal) {
            copy[k] = v.toList()
        }
        return Collections.unmodifiableMap(copy)
    }

    fun getRedirectUrl(): String? = redirectUrlValue

    fun getRedirect(): SabrRedirect? = redirectValue

    fun getSabrSeek(): SabrSeek? = sabrSeekValue

    fun getSabrError(): String? = sabrErrorValue

    fun getSabrErrorDetails(): SabrError? = sabrErrorDetailsValue

    fun getReloadPlayerResponse(): SabrReloadPlayerResponse? = reloadPlayerResponseValue

    fun getFormatSelectionConfig(): SabrFormatSelectionConfig? = formatSelectionConfigValue

    fun getSelectableFormats(): SabrSelectableFormats? = selectableFormatsValue

    fun getNextRequestPolicy(): SabrNextRequestPolicy? = nextRequestPolicyValue

    fun getRequestIdentifier(): SabrRequestIdentifier? = requestIdentifierValue

    fun getPlaybackStartPolicy(): SabrPlaybackStartPolicy? = playbackStartPolicyValue

    fun getSabrContextSendingPolicy(): SabrContextSendingPolicy? = sabrContextSendingPolicyValue

    fun getRequestCancellationPolicy(): SabrRequestCancellationPolicy? = requestCancellationPolicyValue

    fun getStreamProtection(): SabrStreamProtectionStatus? = streamProtectionValue

    fun getPrewarmConnection(): SabrPrewarmConnection? = prewarmConnectionValue

    fun getSnackbarMessage(): SabrSnackbarMessage? = snackbarMessageValue

    fun getStreamProtectionStatus(): Int = streamProtectionStatusValue

    fun getStreamProtectionMaxRetries(): Int = streamProtectionMaxRetriesValue

    fun getBackoffTimeMs(): Int = backoffTimeMsValue

    fun isReloadRequested(): Boolean = reloadRequestedValue

    fun hasMedia(): Boolean = mediaHeadersInternal.isNotEmpty() || mediaBytesByHeaderIdInternal.isNotEmpty()

    fun isNoMediaResponse(): Boolean = !hasMedia()

    fun isPolicyOnlyResponse(): Boolean = isNoMediaResponse() && nextRequestPolicyValue != null

    fun isProtectedNoMediaResponse(): Boolean = isNoMediaResponse() && streamProtectionStatusValue >= 3

    fun isProtectionBoundaryNoMediaResponse(): Boolean = isNoMediaResponse() && streamProtectionStatusValue >= 2

    fun summarizeForDiagnostics(): String {
        val initialization = mutableListOf<String>()
        for (metadata in formatInitializationMetadataInternal) {
            initialization.add(metadata.summarize())
        }
        val headers = mutableListOf<String>()
        for (header in mediaHeadersInternal) {
            headers.add(header.summarize())
        }
        return "parts=" + partSummariesInternal +
            ", wireFields=" + wireFieldSummariesInternal +
            ", controls=" + genericPartDescriptionsInternal +
            ", initialization=" + initialization +
            ", mediaHeaders=" + headers +
            ", mediaBytes=" + mediaBytesByHeaderIdInternal +
            ", mediaEnds=" + mediaEndHeaderIdsInternal +
            ", integrity=" + getIntegrityIssues() +
            ", malformedParts=" + malformedPartsInternal +
            ", unknownParts=" + unknownPartTypesInternal +
            ", protection=" + streamProtectionStatusValue + '/' + streamProtectionMaxRetriesValue +
            ", backoffMs=" + backoffTimeMsValue +
            ", reload=" + reloadRequestedValue
    }

    fun summarizeNoMediaResponse(): String {
        return "parts=" + partsInternal.size +
            ", status=" + streamProtectionStatusValue +
            ", maxRetries=" + streamProtectionMaxRetriesValue +
            ", backoffMs=" + backoffTimeMsValue +
            ", policy=" + (nextRequestPolicyValue != null) +
            ", reload=" + reloadRequestedValue +
            ", redirect=" + (redirectUrlValue != null && redirectUrlValue!!.isNotEmpty()) +
            ", error=" + (sabrErrorValue ?: "null")
    }
}
