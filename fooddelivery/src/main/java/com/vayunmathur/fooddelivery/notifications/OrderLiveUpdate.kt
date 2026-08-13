package com.vayunmathur.fooddelivery.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vayunmathur.fooddelivery.MainActivity
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.data.Order
import com.vayunmathur.fooddelivery.data.OrderStage
import com.vayunmathur.library.util.ensureNotificationChannel

/**
 * Builds the ongoing "Live Update" order-tracking notification.
 *
 * On Android 16+ (API 36) it uses [NotificationCompat.ProgressStyle] with segmented
 * milestones and requests promotion to an ongoing Live Update. Below that it degrades to
 * a plain ongoing notification with a determinate progress bar showing the same fraction.
 */
object OrderLiveUpdate {
    const val CHANNEL_ID = "order_tracking"
    const val NOTIFICATION_ID = 4711
    const val EXTRA_TRACK_ORDER_ID = "com.vayunmathur.fooddelivery.EXTRA_TRACK_ORDER_ID"

    // Android 16 Live Updates arrived in API 36; below this we fall back to a plain bar.
    private const val LIVE_UPDATE_SDK = 36

    // Ordered milestones the tracker advances through. PREPARING_SOON collapses onto
    // PREPARING, and PICKED_UP maps onto DRIVING for delivery (it means "on the way").
    private val DELIVERY_MILESTONES = listOf(
        OrderStage.PREPARING,
        OrderStage.READY,
        OrderStage.DRIVING,
        OrderStage.ARRIVING,
        OrderStage.DELIVERED,
    )
    private val PICKUP_MILESTONES = listOf(
        OrderStage.PREPARING,
        OrderStage.READY,
        OrderStage.PICKED_UP,
    )

    fun ensureChannel(context: Context) {
        context.ensureNotificationChannel(
            id = CHANNEL_ID,
            name = context.getString(R.string.order_tracking_channel),
            importance = NotificationManager.IMPORTANCE_LOW,
            description = context.getString(R.string.order_tracking_channel_desc),
        )
    }

    fun build(context: Context, order: Order): Notification {
        ensureChannel(context)

        val milestones = if (order.isDelivery) DELIVERY_MILESTONES else PICKUP_MILESTONES
        val index = milestoneIndex(order, milestones)
        val title = order.merchant?.name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.tracking)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(statusText(context, order))
            .setContentIntent(contentIntent(context, order.id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (Build.VERSION.SDK_INT >= LIVE_UPDATE_SDK) {
            val progressStyle = NotificationCompat.ProgressStyle()
                .setProgressSegments(
                    List(milestones.size - 1) { NotificationCompat.ProgressStyle.Segment(1) }
                )
                .setProgressPoints(
                    milestones.indices.map { NotificationCompat.ProgressStyle.Point(it) }
                )
                .setProgress(index)
            builder.setStyle(progressStyle)
            builder.setShortCriticalText(order.stage.label)
            builder.setRequestPromotedOngoing(true)
        } else {
            val max = (milestones.size - 1).coerceAtLeast(1)
            builder.setProgress(max, index, false)
        }

        return builder.build()
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun contentIntent(context: Context, orderId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRACK_ORDER_ID, orderId)
        }
        return PendingIntent.getActivity(
            context,
            orderId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun statusText(context: Context, order: Order): String {
        val status = order.displayStatus
        val eta = order.etaMillis
        if (order.stage != OrderStage.DELIVERED && eta != null) {
            val minutes = ((eta - System.currentTimeMillis()) / 60_000L).toInt()
            if (minutes > 0) return context.getString(R.string.notif_status_eta, status, minutes)
        }
        return status
    }

    private fun milestoneIndex(order: Order, milestones: List<OrderStage>): Int {
        val mapped = when (order.stage) {
            OrderStage.PREPARING_SOON -> OrderStage.PREPARING
            OrderStage.PICKED_UP -> if (order.isDelivery) OrderStage.DRIVING else OrderStage.PICKED_UP
            else -> order.stage
        }
        return milestones.indexOf(mapped).coerceAtLeast(0)
    }
}
