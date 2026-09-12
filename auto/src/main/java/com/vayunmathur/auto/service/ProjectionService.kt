package com.vayunmathur.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vayunmathur.auto.R
import com.vayunmathur.auto.network.HeadUnitServer
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.VideoSinkChannel
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalCredential
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.SessionState
import com.vayunmathur.auto.protocol.StreamTransport
import kotlin.concurrent.thread

/**
 * Runs a projection session for as long as a head unit is attached.
 *
 * Single-threaded by design: one [javax.net.ssl.SSLEngine] backs the connection and an
 * engine is not thread-safe, so reads, writes and encoder draining all happen on this
 * thread.
 */
class ProjectionService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        startForeground(NOTIFICATION_ID, notification())
        worker = thread(name = "ma-auto-projection") { serve() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun serve() {
        HeadUnitServer().use { server ->
            while (running) {
                runCatching { session(server) }
                    .onFailure { if (running) Log.e(TAG, "projection session ended", it) }
            }
        }
    }

    private fun session(server: HeadUnitServer) {
        server.accept().use { socket ->
            // A head unit is on the wire; the phone status screen leaves Disconnected.
            AutoSessionState.onSocketAccepted()
            val connection = GalConnection(
                transport = StreamTransport(socket.getInputStream(), socket.getOutputStream()),
                sslContext = GalCredential.fromAssets(assets),
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                onChannelMessage = { message -> video?.onMessage(message.type, message.payload) },
                trace = { Log.d(TAG, it) },
            )

            var setupVideo = false
            var reportedActive = false
            while (running && connection.pump()) {
                // Open every advertised service in HU-discovery wire order, one at a
                // time: the next 0x7 goes out only once the previous channel is
                // granted (openChannels) or refused (refusedChannels, or a bare
                // 0xff with no 0x8 -- DHU 2.0 answers that way). gearhead opens in
                // this same order (`jbp.i[]` follows the `xob.c` wire order).
                // Video setup additionally waits for its own channel grant.
                if (connection.session.state == SessionState.ACTIVE) {
                    if (!reportedActive) {
                        reportedActive = true
                        AutoSessionState.onActive(CAR_NAME)
                    }
                    openNext(connection)
                }
                if (!setupVideo && GalService.VIDEO_SINK.id in connection.session.openChannels) {
                    setupVideo = true
                    video?.requestSetup()
                }
                video?.pumpEncoder()
            }
            Log.i(TAG, "head unit disconnected: ${connection.session.failure ?: "cleanly"}")
            AutoSessionState.onSessionEnd(connection.session.failure)
            video?.release()
            video = null
        }
    }

    private var video: VideoSinkChannel? = null

    /**
     * Opens the next not-yet-attempted advertised service, in the HU's wire order.
     *
     * Only one open is ever in flight: while `pendingChannels` is non-empty the
     * head unit still owes us an answer, so wait.
     *
     * Service 1 IS sent, first: gearhead opens channel 0 locally and never sends
     * a 0x7 for it, but DHU 2.0 rejects a first-0x7 for service 2 with an empty
     * 0xff and no 0x8 (Run 4) -- so the HU-side may expect the wire-order
     * sequence to start at 1. If the HU 0xffs/0x8-refuses service 1 as well, it
     * lands in refusedChannels like any other refusal and the bring-up moves on
     * to service 2; the experiment then distinguishes "first-open must be 1"
     * (0x8 arrives for 1) from "video refused regardless" (both refused).
     */
    private fun openNext(connection: GalConnection) {
        val session = connection.session
        if (session.pendingChannels.isNotEmpty()) return
        val next = session.services.firstOrNull {
            it.id !in session.openChannels &&
                it.id !in session.refusedChannels
        } ?: return
        connection.send(session.openChannel(next))
        Log.i(TAG, "requesting channel open for service ${next.id}")
        if (next.id == GalService.VIDEO_SINK.id && next.hasMediaSink()) {
            video = VideoSinkChannel(this, next, connection, AutoSessionState::onVideoEvent)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.projection_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.projection_running))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "MaAuto.Service"
        private const val CHANNEL_ID = "projection"
        private const val NOTIFICATION_ID = 1

        /**
         * Interim car name until the head unit reports its own. The GAL services the DHU
         * advertises carry no human-readable name, so the status screen says where the
         * pixels are going rather than guessing a make.
         */
        private const val CAR_NAME = "Desktop Head Unit"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java))
        }
    }
}
