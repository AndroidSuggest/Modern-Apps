package com.vayunmathur.auto.protocol

import java.nio.ByteBuffer

/** A GAL protocol version pair. */
data class GalVersion(val major: Int, val minor: Int) : Comparable<GalVersion> {

    override fun compareTo(other: GalVersion): Int =
        compareValuesBy(this, other, GalVersion::major, GalVersion::minor)

    override fun toString(): String = "$major.$minor"
}

/**
 * Version negotiation, control messages 1 and 2.
 *
 * Two things here are unlike the rest of the protocol and are easy to get wrong.
 *
 *  1. **These are not protobuf.** Every other control message carries a protobuf payload;
 *     these carry raw big-endian uint16s. Gearhead writes them a short at a time
 *     (`jcn.v((short) …)`) rather than through a generated builder.
 *  2. **The head unit asks and the phone answers.** The log line in the control endpoint is
 *     "Car requests protocol version %s" — the phone does not open with a version request,
 *     it waits for one. A sender that speaks first will sit unanswered.
 */
object VersionNegotiation {

    /**
     * The highest version this implementation speaks.
     *
     * Gearhead 17.5 advertises 1.7 and treats 1.6 as the floor for service discovery, so
     * anything below that will not get a discovery request out of a real head unit.
     */
    val SUPPORTED: GalVersion = GalVersion(1, 7)

    /** Below this the head unit is not expected to support service discovery. */
    val MINIMUM_FOR_DISCOVERY: GalVersion = GalVersion(1, 6)

    private const val REQUEST_SIZE = 4
    private const val RESPONSE_SIZE = 6

    /** Parses control message 1, sent by the head unit. */
    fun parseRequest(payload: ByteArray): GalVersion {
        require(payload.size >= REQUEST_SIZE) {
            "version request is ${payload.size} bytes, expected at least $REQUEST_SIZE"
        }
        val buffer = ByteBuffer.wrap(payload)
        return GalVersion(
            major = buffer.short.toInt() and 0xFFFF,
            minor = buffer.short.toInt() and 0xFFFF,
        )
    }

    /**
     * Builds the payload of control message 2.
     *
     * [status] is a [com.vayunmathur.auto.protocol.gal.MessageStatus] value written as a
     * short, so the negative error codes wrap — `STATUS_NO_COMPATIBLE_VERSION` (-1) goes out
     * as `0xFFFF`. That is what gearhead does and what a head unit expects.
     */
    fun buildResponse(version: GalVersion, status: Int): ByteArray =
        ByteBuffer.allocate(RESPONSE_SIZE)
            .putShort(version.major.toShort())
            .putShort(version.minor.toShort())
            .putShort(status.toShort())
            .array()

    /** Parses control message 2, for testing against a head unit implementation. */
    fun parseResponse(payload: ByteArray): Triple<GalVersion, Int, Int> {
        require(payload.size >= RESPONSE_SIZE) {
            "version response is ${payload.size} bytes, expected at least $RESPONSE_SIZE"
        }
        val buffer = ByteBuffer.wrap(payload)
        val version = GalVersion(
            major = buffer.short.toInt() and 0xFFFF,
            minor = buffer.short.toInt() and 0xFFFF,
        )
        // Sign-extended: the status is a signed enum value on the wire.
        return Triple(version, buffer.short.toInt(), 0)
    }

    /**
     * Picks the version to run at, or null if the head unit asks for more than we speak.
     *
     * Gearhead refuses to negotiate *down* from what the car asked for beyond its own
     * maximum: if the car requests something newer than [SUPPORTED] it fails the session
     * rather than silently answering with an older version.
     */
    fun negotiate(requested: GalVersion, supported: GalVersion = SUPPORTED): GalVersion? =
        if (requested > supported) null else requested
}
