package com.vayunmathur.auto.platform

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The microphone permission gate.
 *
 * Receiving the car's mic audio and acking it needs no permission, but
 * retaining a turn for transcription does: without an explicit grant no voice
 * data is buffered ([MicSourceChannel] acks and counts only). The grant also
 * covers the phone-side fallback a future transcriber may need.
 */
object MicPermission {
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
}
