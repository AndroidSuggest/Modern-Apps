package com.vayunmathur.taxi.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.library.util.ensureNotificationChannel
import com.vayunmathur.taxi.MainActivity
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.ActiveRide
import com.vayunmathur.taxi.data.RideStatus

/**
 * Builds the ongoing "Live Update" ride-tracking notification.
 *
 * On Android 16+ (API 36) it uses [NotificationCompat.ProgressStyle] with segmented
 * milestones and requests promotion to an ongoing Live Update. Below that it degrades to
 * a plain ongoing notification with a determinate progress bar showing the same fraction.
 */
object RideLiveUpdate {
    const val CHANNEL_ID = "ride_tracking"
    const val NOTIFICATION_ID = 5120
    const val EXTRA_TRACK_RIDE_ID = "com.vayunmathur.taxi.EXTRA_TRACK_RIDE_ID"

    // Android 16 Live Updates arrived in API 36; below this we fall back to a plain bar.
    private const val LIVE_UPDATE_SDK = 36

    // Ordered phases the tracker advances through, each with its user-facing label.
    private enum class Milestone(val labelRes: Int) {
        FINDING(R.string.status_finding_driver),
        EN_ROUTE(R.string.status_en_route),
        ARRIVED(R.string.status_arrived),
        ON_TRIP(R.string.status_on_trip),
        COMPLETE(R.string.ride_complete),
    }

    fun ensureChannel(context: Context) {
        context.ensureNotificationChannel(
            id = CHANNEL_ID,
            name = context.getString(R.string.ride_tracking_channel),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = context.getString(R.string.ride_tracking_channel_desc),
        )
    }

    fun build(context: Context, ride: ActiveRide): Notification {
        ensureChannel(context)

        val index = milestoneIndex(ride.status)
        val title = ride.driver?.displayName?.takeIf { it.isNotBlank() }
            ?: ride.vehicle?.description?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.ride_in_progress)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(statusText(context, ride, index))
            .setContentIntent(contentIntent(context, ride.rideId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK) {
            val milestones = Milestone.entries
            val progressStyle = NotificationCompat.ProgressStyle()
                .setProgressSegments(
                    List(milestones.size - 1) { NotificationCompat.ProgressStyle.Segment(1) }
                )
                .setProgressPoints(
                    milestones.indices.map { NotificationCompat.ProgressStyle.Point(it) }
                )
                .setProgress(index)
            builder.setStyle(progressStyle)
            builder.setShortCriticalText(context.getString(milestones[index].labelRes))
            builder.setRequestPromotedOngoing(true)
        } else {
            val max = (Milestone.entries.size - 1).coerceAtLeast(1)
            builder.setProgress(max, index, false)
        }

        return builder.build()
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun contentIntent(context: Context, rideId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (rideId != null) putExtra(EXTRA_TRACK_RIDE_ID, rideId)
        }
        return PendingIntent.getActivity(
            context,
            rideId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun statusText(context: Context, ride: ActiveRide, index: Int): String {
        val label = context.getString(Milestone.entries[index].labelRes)
        val eta = ride.pickupEtaSeconds
        if (!ride.status.isTerminal && ride.status.isPrePickup && eta != null && eta > 0) {
            val minutes = (eta + 59) / 60
            return context.getString(R.string.notif_status_eta, label, minutes)
        }
        return label
    }

    private fun milestoneIndex(status: RideStatus): Int = when {
        status.isTerminal -> Milestone.COMPLETE.ordinal
        status == RideStatus.PICKED_UP -> Milestone.ON_TRIP.ordinal
        status == RideStatus.ARRIVED -> Milestone.ARRIVED.ordinal
        status == RideStatus.ACCEPTED || status == RideStatus.APPROACHING -> Milestone.EN_ROUTE.ordinal
        else -> Milestone.FINDING.ordinal
    }
}
