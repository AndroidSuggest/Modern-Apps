package com.vayunmathur.findfamily.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
enum class RequestStatus {
    MUTUAL_CONNECTION,
    AWAITING_REQUEST,
    AWAITING_RESPONSE
}

@Serializable
@Entity
data class User(
    val name: String,
    val photo: String?,
    var locationName: String,
    val sendingEnabled: Boolean,
    val requestStatus: RequestStatus,
    val lastLocationChangeTime: Instant = Clock.System.now(),
    val encryptionKey: String? = null,

    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val lastWaypointId: Long? = null,
    /** Peer device platform (`"android"` or `"ios"`), learned from heartbeat payloads. Null until first heartbeat after both sides upgrade. */
    val platform: String? = null,
    /** When to auto-toggle sharing (flip sendingEnabled). Null means Never / disabled. Single field. */
    val sharingAutoToggleAt: Instant? = null,
    /** Peer post-quantum public bundle (base64: [4B kemLen][kemPubDer][dsaPubDer]), nullable for backward compat. */
    val pqcEncryptionKey: String? = null
): DatabaseItem {
    companion object {
        val EMPTY = User(" ", null, "Unnamed Location", true, RequestStatus.MUTUAL_CONNECTION, Clock.System.now(), null)
    }
}
