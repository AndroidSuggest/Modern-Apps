package org.schabi.newpipe.extractor.services.youtube

import org.schabi.newpipe.extractor.exceptions.ParsingException
import javax.annotation.Nonnull
import javax.annotation.Nullable

interface YoutubeJavaScriptDecoder {

    @Nonnull
    @Throws(ParsingException::class)
    fun getPlayerData(@Nonnull videoId: String): PlayerData

    @Nonnull
    @Throws(ParsingException::class)
    fun decodeBatch(
        @Nonnull playerId: String,
        @Nullable signatures: List<String>?,
        @Nullable throttlingParameters: List<String>?
    ): YoutubeApiDecoder.BatchDecodeResult

    class PlayerData(
        @Nonnull private val playerId: String,
        private val signatureTimestamp: Int
    ) {
        @Nonnull
        fun getPlayerId(): String = playerId

        fun getSignatureTimestamp(): Int = signatureTimestamp
    }
}
