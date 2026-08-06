package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.content.SharedPreferences
import android.os.Debug
import androidx.core.content.edit
import java.util.Locale

object SabrPlaybackDiagnostics {
    private const val PREFS = "sabr_playback_diagnostics"
    private const val KEY_LAST_SNAPSHOT = "last_snapshot"

    @JvmStatic
    fun record(context: Context, holder: SabrSessionStore.Holder, event: String) {
        val runtime = Runtime.getRuntime()
        val maxHeap = runtime.maxMemory()
        val totalHeap = runtime.totalMemory()
        val freeHeap = runtime.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val pssKb = Debug.getPss()
        val snapshot = String.format(
            Locale.US,
            "event=%s\n" +
                "timeMs=%d\n" +
                "videoId=%s\n" +
                "playerTimeMs=%d\n" +
                "readerHeadMs=%d\n" +
                "readerTailMs=%d\n" +
                "videoItag=%d\n" +
                "videoHeight=%d\n" +
                "videoBitrate=%d\n" +
                "audioItag=%d\n" +
                "audioBitrate=%d\n" +
                "heapUsedBytes=%d\n" +
                "heapFreeBytes=%d\n" +
                "heapTotalBytes=%d\n" +
                "heapMaxBytes=%d\n" +
                "pssKb=%d\n" +
                "sabr=%s\n",
            event,
            System.currentTimeMillis(),
            holder.videoId,
            holder.getPlayerTimeMs(),
            holder.getReaderHeadMs(),
            holder.getReaderTailMs(),
            holder.videoFormat.itag,
            holder.videoFormat.height,
            holder.videoFormat.bitrate,
            holder.audioFormat.itag,
            holder.audioFormat.bitrate,
            usedHeap,
            freeHeap,
            totalHeap,
            maxHeap,
            pssKb,
            holder.session.getMemoryDiagnosticSummary()
        )
        preferences(context).edit { putString(KEY_LAST_SNAPSHOT, snapshot) }
    }

    @JvmStatic
    fun getLastSnapshot(context: Context): String =
        preferences(context).getString(KEY_LAST_SNAPSHOT, "") ?: ""

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
