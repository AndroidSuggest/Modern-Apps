package com.vayunmathur.findfamily.data

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A watch for an expected arrival that never happened (no-show alert).
 *
 * The scheduler (a later step) periodically asks for rows whose deadline —
 * [expectedAt] plus [grace] — has passed without the watched user appearing,
 * then notifies and marks them fired. Time columns reuse the database-wide
 * converters: [Instant] is stored as epoch seconds, [Duration] as whole
 * milliseconds, booleans as 0/1 integers.
 */
@Serializable
@Entity(indices = [Index("watchedUserId")])
data class NoShowAlert(
    /** Row id of the watched [User]. */
    val watchedUserId: Long,
    /** Waypoint the user was expected at. Null means any location update counts as arrival. */
    val waypointId: Long?,
    /** When the user was expected. */
    val expectedAt: Instant,
    /** Extra wait after [expectedAt] before the alert becomes due. */
    val grace: Duration,
    /** Whether this alert has already fired. Set atomically via `markFired`. */
    val fired: Boolean = false,
    /** When true the row is deleted after firing; when false it stays and can be re-armed. */
    val oneShot: Boolean = true,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
) : DatabaseItem
