package com.vayunmathur.cast.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.platform.mirror.EncoderSupport
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import com.vayunmathur.cast.protocol.CodecNegotiation
import com.vayunmathur.cast.protocol.CodecSelection
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.VideoCodec

private const val TAG = "CastController"

/**
 * The codec a session will use, or why it will not run.
 *
 * A two-case result rather than a nullable codec, because the refusal carries a sentence the user has
 * to read: with no H.264 fallback, "this phone cannot encode H.265 or AV1" is the whole answer and
 * there is nothing else to try.
 */
internal sealed interface CodecOutcome {

    /** Everything the geometry and the bitrate are computed from. */
    data class Chosen(val selection: CodecSelection.Chosen) : CodecOutcome {
        val codec: VideoCodec get() = selection.codec
    }

    data class Refused(val message: String) : CodecOutcome
}

/**
 * Which codec this session will use, or the sentence explaining why there is none.
 *
 * Both ends' hardware is intersected by [CodecNegotiation], which is a pure function so the rule
 * can be unit-tested; everything device-specific is in the two lists handed to it. [width] and
 * [height] are the *unfitted* frame, because a codec is only viable if it takes the frame after the
 * TV's own envelope has scaled it - and because this phone's own sustainable frame rate is only
 * meaningful for a stated geometry, which is what [EncoderSupport.videoCodecs] needs them for.
 */
internal suspend fun chooseCodec(
    context: Context,
    device: CastDevice,
    activeClient: MirrorClient,
    width: Int,
    height: Int,
): CodecOutcome {
    val receiverId = activeClient.receiverId ?: device.id
    val demoted = MirrorPreferences.demotedCodecs(context, receiverId)
    if (demoted.isNotEmpty()) {
        Log.i(
            TAG,
            "skipping ${demoted.joinToString { it.label }} - it has already failed on this TV",
        )
    }
    val selection = CodecNegotiation.choose(
        senderCodecs = EncoderSupport.videoCodecs(width, height),
        receiver = activeClient.limits ?: DecoderLimits(),
        width = width,
        height = height,
        // The rate is a floor, not a target: a codec that cannot hold it is excluded rather than
        // accepted at whatever it manages. Resolution is what yields. Deliberately the floor and
        // not the ceiling - selecting against 60 would refuse H.265 at 4K on a phone whose
        // encoder tops out below that, leaving no codec at all rather than a 30fps session.
        frameRate = StreamConstants.VIDEO_MIN_FRAME_RATE,
        demoted = demoted,
    )
    return when (selection) {
        is CodecSelection.Chosen -> {
            Log.i(TAG, "chose ${selection.codec.label} for '${device.friendlyName}'")
            CodecOutcome.Chosen(selection)
        }
        is CodecSelection.None -> CodecOutcome.Refused(refusal(context, selection))
    }
}

/**
 * Which end was short, named.
 *
 * The reason there is no H.264 fallback is the reason this has to be specific: "mirroring failed"
 * would leave a user with a device that will never work and no way to find out why. The demotion
 * case gets its own sentence for the same reason - the two ends *do* share a codec there, and a
 * message built from the offers alone would deny it.
 */
internal fun refusal(context: Context, none: CodecSelection.None): String {
    val labels = { codecs: Collection<VideoCodec> -> codecs.joinToString(" or ") { it.label } }
    val both = labels(CodecNegotiation.PREFERENCE)
    val blockedByDemotion = none.demoted.filter {
        it in none.senderOffered && it in none.receiverOffered
    }
    return when {
        blockedByDemotion.isNotEmpty() ->
            context.getString(R.string.cast_mirror_codec_demoted, labels(blockedByDemotion))
        none.senderOffered.isEmpty() ->
            context.getString(R.string.cast_mirror_phone_no_codec, both)
        none.receiverOffered.isEmpty() ->
            context.getString(R.string.cast_mirror_tv_no_codec, both)
        else -> context.getString(
            R.string.cast_mirror_no_common_codec,
            labels(none.senderOffered),
            labels(none.receiverOffered),
        )
    }
}
