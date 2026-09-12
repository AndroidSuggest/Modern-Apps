package com.vayunmathur.auto.protocol

/**
 * GAL message types. Every channel payload is `[2-byte big-endian type][protobuf]`.
 *
 * The control channel uses small integers; every service channel uses 0x8000-based ones,
 * with 0x0000/0x0001 reserved for bulk media data.
 *
 * These are **wire** values. Reading them out of gearhead is error-prone in one specific
 * way: `jdk` and `jdi` dispatch on `wub.o(id)`, which maps a wire id to a 1-based enum
 * index and so is `id + 1` over this range, while the `k(id, …)` send calls are already
 * raw. Taking those comparisons at face value yields every received media type one too
 * high. The values below are the raw wire ids, and they agree with the public
 * aasdk/openauto tables.
 */
object GalMessage {

    /** Control channel, channel id 0. */
    object Control {
        const val VERSION_REQUEST = 1
        const val VERSION_RESPONSE = 2

        /** Raw TLS handshake bytes, wrapped and unwrapped by the SSL engine. */
        const val SSL_HANDSHAKE = 3
        const val AUTH_COMPLETE = 4

        /** The phone asks; the head unit answers with its service list. */
        const val SERVICE_DISCOVERY_REQUEST = 5
        const val SERVICE_DISCOVERY_RESPONSE = 6

        const val CHANNEL_OPEN_REQUEST = 7
        const val CHANNEL_OPEN_RESPONSE = 8

        const val PING_REQUEST = 11
        const val PING_RESPONSE = 12

        const val NAVIGATION_FOCUS_NOTIFICATION = 14

        const val BYEBYE_REQUEST = 15
        const val BYEBYE_RESPONSE = 16

        const val AUDIO_FOCUS_REQUEST = 18
        const val AUDIO_FOCUS_NOTIFICATION = 19

        const val CALL_AVAILABILITY_STATUS = 24

        /** Hot-add or replace a single service after the initial discovery. */
        const val SERVICE_DISCOVERY_UPDATE = 26

        const val MESSAGE_ERROR = 255
        const val FRAMING_ERROR = 0xFFFF
    }

    /** Shared by the video sink, the three audio sinks and the microphone source. */
    object Media {
        /** Bulk payload prefixed with an 8-byte timestamp. */
        const val DATA_WITH_TIMESTAMP = 0x0000

        /** Bulk payload with no timestamp. */
        const val DATA = 0x0001

        const val SETUP_REQUEST = 0x8000
        const val START_REQUEST = 0x8001
        const val STOP_REQUEST = 0x8002
        const val CONFIG = 0x8003
        const val ACK = 0x8004
    }

    /** Video sink, on top of [Media]. */
    object Video {
        const val FOCUS_REQUEST = 0x8007
        const val FOCUS_INDICATION = 0x8008
        const val UPDATE_UI_CONFIG_REQUEST = 0x800A
    }

    /** Microphone source. The car owns the microphone, so the phone receives and acks. */
    object Microphone {
        const val REQUEST = 0x8006
    }

    /** Input source: touch, keys and rotary, all reported by the head unit. */
    object Input {
        const val REPORT = 0x8001
        const val KEY_BINDING_REQUEST = 0x8002
        const val KEY_BINDING_RESPONSE = 0x8003
    }

    /** Sensor source. [SENSOR_REQUEST] is synchronous with a 2 s timeout. */
    object Sensor {
        const val SENSOR_REQUEST = 0x8001
        const val SENSOR_RESPONSE = 0x8002
        const val SENSOR_BATCH = 0x8003
        const val SENSOR_ERROR = 0x8004

        /** A [SENSOR_REQUEST] with this update period unsubscribes instead. */
        const val UNSUBSCRIBE_PERIOD = -1L
    }

    /**
     * Navigation-status channel, service 10.
     *
     * MA sender-defined types: the teardown recovered the service descriptor
     * slot but never the channel message IDs, so STATUS frames the Phase 5
     * stub (one well-formed "no guidance" post on the grant, live turn
     * updates with Phase 6 maps-dev). A head unit that does not speak it
     * answers with a bare MessageError (0xff) on ch10, which is observed and
     * non-fatal -- the bring-up and video carry on. Payloads are the
     * `gal/navigation.proto` DTOs.
     *
     * Like [Sensor], this channel aliases the 0x8000 range (STATUS shares its
     * value with the sensor request and others), so owners must scope every
     * parse by channel id -- the 0x04 CONTROL frame flag never enters routing
     * (HANDOFF.md section 9).
     */
    object NavigationStatus {
        /** Phone -> HU: turn-guidance state (gal.NavigationStatus proto). */
        const val STATUS = 0x8001
    }

    /**
     * Notification (messaging) channel, service 14.
     *
     * MA sender-defined types: the teardown recovered the service descriptor
     * slot but never the xjm channel message IDs, so these frame the Phase 7
     * set (threads, body, reply, mark-read) as a DHU-tolerated stub. A head
     * unit that does not speak them answers with a bare MessageError (0xff)
     * on ch14, which is observed and non-fatal -- the bring-up and video
     * carry on. Payloads are the `gal/notification.proto` DTOs.
     */
    object Notification {
        /** Phone -> HU: thread-list snapshot ([MessagingThreads]). */
        const val THREADS = 0x8001

        /** Phone -> HU: one message body posted ([MessagingMessage]). */
        const val MESSAGE = 0x8002

        /** Phone -> HU: thread dismissed or read elsewhere ([MessagingDismiss]). */
        const val DISMISS = 0x8003

        /** HU -> phone: user action -- reply, mark-read, voice-reply ([MessagingAction]). */
        const val ACTION = 0x8004
    }

    /**
     * Audio sink sync pulse (services 4/5). The head unit answers setup/start
     * with a no-payload 0x800B from the shared `jdk` sink table; presumed
     * stream sync, observed and counted but never answered.
     */
    object Audio {
        const val SYNC = 0x800B
    }
}

/**
 * Service ids as advertised in the head unit's discovery response, which double as the
 * channel ids. Mirrors gearhead's internal `rro` enum.
 */
enum class GalService(val id: Int) {
    CONTROL(1),
    VIDEO_SINK(2),
    AUDIO_SINK_GUIDANCE(3),
    AUDIO_SINK_SYSTEM(4),
    AUDIO_SINK_MEDIA(5),
    AUDIO_SOURCE(6),
    SENSOR_SOURCE(7),
    INPUT_SOURCE(8),
    BLUETOOTH(9),
    NAVIGATION_STATUS(10),
    MEDIA_PLAYBACK_STATUS(11),
    MEDIA_BROWSER(12),
    PHONE_STATUS(13),
    NOTIFICATION(14),
    RADIO(15),
    VENDOR_EXTENSION(16),
    WIFI_PROJECTION(17),
    WIFI_DISCOVERY(18),
    CAR_CONTROL(19),
    CAR_LOCAL_MEDIA(20),
    BUFFERED_MEDIA_SINK(21),
    CAR_INTENT(22),
    ;

    companion object {
        private val byId = entries.associateBy(GalService::id)

        fun fromId(id: Int): GalService? = byId[id]
    }
}

/**
 * GAL 11/12 gap (Phase 4, MA Auto sender): the teardown recovered the
 * `MEDIA_PLAYBACK_STATUS` (`xkn`, control.proto field 9) and `MEDIA_BROWSER`
 * (`xki`, field 11) descriptor fields but never their channel message IDs, so
 * MA Auto neither opens these channels' semantics nor answers them. Now-playing
 * is served from the on-device media session and rendered into the ch2 video
 * stream instead; inbound 11/12 traffic is NOT_SUPPORTED: observed and ignored.
 */
fun isMediaBrowserChannel(channelId: Int): Boolean =
    channelId == GalService.MEDIA_PLAYBACK_STATUS.id || channelId == GalService.MEDIA_BROWSER.id
