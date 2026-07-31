package org.schabi.newpipe.extractor.services.youtube.sabr


/** Normalized protocol state produced by a policy and applied by the bounded Host. */
class SabrResponseStatePatch private constructor(builder: Builder) {

    companion object {
        private const val MAX_FORMATS = 64
        private const val MAX_LIVE_METADATA = 16
        private const val MAX_CONTEXT_UPDATES = 128

        @JvmStatic
        fun builder(): Builder = Builder()

        @JvmStatic
        internal fun builtin(response: SabrDecodedResponse): SabrResponseStatePatch {
            val b = builder().setNextRequestPolicy(response.getNextRequestPolicy())
            for (m in response.getLiveMetadata()) b.addLiveMetadata(m)
            for (m in response.getFormatInitializationMetadata()) b.addFormatMetadata(m)
            for (h in response.getMediaHeaders()) b.addMediaHeader(h)
            for (u in response.getSabrContextUpdates()) b.addContextUpdate(u)
            b.setContextSendingPolicy(response.getSabrContextSendingPolicy())
            return b.build()
        }

        @JvmStatic
        private fun <T> immutableCopy(values: List<T>): List<T> =
            values.toList()
    }

    private val nextRequestPolicy: SabrNextRequestPolicy? = builder.nextRequestPolicy
    private val liveMetadata: List<SabrLiveMetadata> = immutableCopy(builder.liveMetadata)
    private val formatMetadata: List<SabrFormatInitializationMetadata> = immutableCopy(builder.formatMetadata)
    private val mediaHeaders: List<SabrMediaHeader> = immutableCopy(builder.mediaHeaders)
    private val contextUpdates: List<SabrContextUpdate> = immutableCopy(builder.contextUpdates)
    private val contextSendingPolicy: SabrContextSendingPolicy? = builder.contextSendingPolicy

    init {
        if (builder.liveMetadata.size > MAX_LIVE_METADATA ||
            builder.formatMetadata.size > MAX_FORMATS ||
            builder.contextUpdates.size > MAX_CONTEXT_UPDATES
        ) {
            throw IllegalArgumentException("SABR response state patch exceeded Host limit")
        }
    }

    internal fun getNextRequestPolicy(): SabrNextRequestPolicy? = nextRequestPolicy
    internal fun getLiveMetadata(): List<SabrLiveMetadata> = liveMetadata
    internal fun getFormatMetadata(): List<SabrFormatInitializationMetadata> = formatMetadata
    internal fun getMediaHeaders(): List<SabrMediaHeader> = mediaHeaders
    internal fun getContextUpdates(): List<SabrContextUpdate> = contextUpdates
    internal fun getContextSendingPolicy(): SabrContextSendingPolicy? = contextSendingPolicy

    class Builder {
        internal var nextRequestPolicy: SabrNextRequestPolicy? = null
        internal val liveMetadata: MutableList<SabrLiveMetadata> = mutableListOf()
        internal val formatMetadata: MutableList<SabrFormatInitializationMetadata> = mutableListOf()
        internal val mediaHeaders: MutableList<SabrMediaHeader> = mutableListOf()
        internal val contextUpdates: MutableList<SabrContextUpdate> = mutableListOf()
        internal var contextSendingPolicy: SabrContextSendingPolicy? = null

        fun setNextRequestPolicy(value: SabrNextRequestPolicy?): Builder {
            nextRequestPolicy = value
            return this
        }

        fun addLiveMetadata(value: SabrLiveMetadata): Builder {
            liveMetadata.add(value)
            return this
        }

        fun addFormatMetadata(value: SabrFormatInitializationMetadata): Builder {
            formatMetadata.add(value)
            return this
        }

        fun addMediaHeader(value: SabrMediaHeader): Builder {
            mediaHeaders.add(value)
            return this
        }

        fun addContextUpdate(value: SabrContextUpdate): Builder {
            contextUpdates.add(value)
            return this
        }

        fun setContextSendingPolicy(value: SabrContextSendingPolicy?): Builder {
            contextSendingPolicy = value
            return this
        }

        fun build(): SabrResponseStatePatch = SabrResponseStatePatch(this)
    }
}
