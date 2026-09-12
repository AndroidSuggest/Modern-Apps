package com.vayunmathur.cast.platform

import android.os.ParcelFileDescriptor
import android.view.Surface

/** What [CastController.startContentSession] managed. */
sealed interface ContentSessionResult {

    /**
     * Live. [surface] is the encoder's input surface and [audioWriteEnd] the PCM pipe, both of which
     * are the caller's to hand to the SDK client - and [audioWriteEnd] is the caller's to close once it
     * has been sent.
     *
     * The geometry is what the TV and this phone's encoder actually agreed, not what was asked for.
     */
    class Started(
        val surface: Surface,
        val audioWriteEnd: ParcelFileDescriptor?,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val receiverName: String,
    ) : ContentSessionResult

    /** [reason] is one of `CastContract`'s `REASON_` values, ready to send straight back. */
    class Failed(val reason: Int) : ContentSessionResult

    /**
     * Live, and served rather than encoded.
     *
     * No surface and no pipe, because nothing is being encoded: the TV fetches byte ranges of the
     * app's own media from the proxy and decodes them itself. No geometry either - the TV plays the
     * media at its own size, which is what stops this phone having to choose a frame it can encode.
     */
    class Serving(
        val receiverName: String,
        val hasVideo: Boolean,
    ) : ContentSessionResult
}
