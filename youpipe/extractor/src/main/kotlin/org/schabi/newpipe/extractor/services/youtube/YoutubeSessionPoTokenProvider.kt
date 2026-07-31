package org.schabi.newpipe.extractor.services.youtube

import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import javax.annotation.Nonnull
import javax.annotation.Nullable

/**
 * Supplies the visitor-bound token used by YouTube `/player` requests.
 *
 * Implementations must be thread-safe. Returning `null` lets extraction continue without
 * a token, which is required as a compatibility fallback when the local attestation runtime is not
 * available.
 */
interface YoutubeSessionPoTokenProvider {

    @Nullable
    @Throws(IOException::class, ExtractionException::class)
    fun getSessionPoToken(
        @Nonnull clientName: String,
        @Nonnull localization: Localization,
        @Nonnull contentCountry: ContentCountry,
        loggedIn: Boolean
    ): YoutubeSessionPoToken?
}
