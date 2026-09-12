package com.vayunmathur.auto.platform

/**
 * Something a sensor, guidance or navigation-status channel observed,
 * forwarded to [AutoSessionState] for the phone UI.
 *
 * Like [VideoEvent], [MessagingEvent] and [InputEvent], these are
 * fire-and-forget observations: the bring-up never waits on them, and the
 * service forwards them without gating any protocol step on the UI.
 */
sealed interface SensorEvent {
    /** One stub SensorRequest went out on ch7; [type] is the sensor name. */
    data class Subscribed(val type: String) : SensorEvent

    /** The head unit answered a subscription; [status] 0 means subscribed. */
    data class SubscriptionAnswered(val type: String, val status: Int) : SensorEvent

    /** One SensorBatch arrived on ch7; [count] is events in it. */
    data class BatchReceived(val count: Int) : SensorEvent

    /** The head unit reported a sensor failure on ch7 (0x8004 SensorError). */
    data class SensorError(val type: String, val status: Int) : SensorEvent

    /** Guidance sink (ch3) setup went out on its grant. */
    data object GuidanceSetup : SensorEvent

    /** The head unit answered guidance setup (0x8003 CONFIG). */
    data class GuidanceConfigured(val accepted: Boolean) : SensorEvent

    /** The inactive nav-status post went out on the ch10 grant. */
    data object NavStatusPosted : SensorEvent
}
