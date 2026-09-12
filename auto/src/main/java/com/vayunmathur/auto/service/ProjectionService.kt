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
            val connection = GalConnection(
                transport = StreamTransport(socket.getInputStream(), socket.getOutputStream()),
                sslContext = GalCredential.fromAssets(assets),
                deviceName = Build.MODEL,
                deviceBrand = Build.MANUFACTURER,
                onChannelMessage = { message -> video?.onMessage(message.type, message.payload) },
                trace = { Log.d(TAG, it) },
            )

            var openedVideo = false
            while (running && connection.pump()) {
                // Opening the video channel is the first thing to do once discovery lands.
                if (!openedVideo && connection.session.state == SessionState.ACTIVE) {
                    openedVideo = openVideo(connection)
                }
                video?.pumpEncoder()
            }
            Log.i(TAG, "head unit disconnected: ${connection.session.failure ?: "cleanly"}")
            video?.release()
            video = null
        }
    }

    private var video: VideoSinkChannel? = null

    /** @return true once the request is away, so it is only sent once. */
    private fun openVideo(connection: GalConnection): Boolean {
        val service = connection.session.services
            .firstOrNull { it.id == GalService.VIDEO_SINK.id && it.hasMediaSink() }
        if (service == null) {
            Log.w(TAG, "head unit advertised no video sink; nothing to project onto")
            return true
        }
        connection.send(connection.session.openChannel(service))
        video = VideoSinkChannel(this, service, connection).also { it.requestSetup() }
        return true
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ProjectionService::class.java))
        }
    }
}
