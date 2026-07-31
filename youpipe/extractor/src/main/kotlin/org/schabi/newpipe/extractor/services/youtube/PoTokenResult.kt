package org.schabi.newpipe.extractor.services.youtube

import javax.annotation.Nonnull
import javax.annotation.Nullable

/**
 * The result of a supported/successful `poToken` extraction request by a [PoTokenProvider].
 */
class PoTokenResult(
    /**
     * The visitor data associated with a `poToken`.
     */
    @field:Nonnull
    @JvmField
    val visitorData: String,

    /**
     * The `poToken` of a player request, a Protobuf object encoded as a base 64 string.
     */
    @field:Nonnull
    @JvmField
    val playerRequestPoToken: String,

    /**
     * The `poToken` to be appended to streaming URLs, a Protobuf object encoded as a base 64 string.
     *
     * It may be required on some clients such as HTML5 ones and may also differ from the player
     * request `poToken`.
     */
    @field:Nullable
    @JvmField
    val streamingDataPoToken: String?
) {
    init {
        requireNotNull(visitorData)
        requireNotNull(playerRequestPoToken)
    }
}
