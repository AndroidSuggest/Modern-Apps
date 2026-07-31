package org.schabi.newpipe.extractor.stream

/**
 * Availability of the stream.
 *
 * A stream may be available to all, restricted to a certain user group or time.
 */
enum class ContentAvailability {
    /**
     * The availability of the stream is unknown (but clients may assume that it's available).
     */
    UNKNOWN,

    /**
     * The stream is available to all users.
     */
    AVAILABLE,

    /**
     * The stream is available to users with a membership.
     */
    MEMBERSHIP,

    /**
     * The stream is behind a paywall.
     */
    PAID,

    /**
     * The stream is only available in the future.
     */
    UPCOMING
}
