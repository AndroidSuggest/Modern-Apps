package com.vayunmathur.euicc.data

import kotlinx.serialization.Serializable

/** One pending eUICC notification, deserialized from the native ListNotification JSON. */
@Serializable
data class Notification(
    val seqNumber: Int = -1,
    /** "install", "enable", "disable", "delete", or "unknown". */
    val operation: String = "unknown",
    /** SM-DP+ / SM-DS address the notification is destined for. */
    val address: String = "",
    val iccidDisplay: String = "",
)
