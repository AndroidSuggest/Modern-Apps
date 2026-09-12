package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Probe whether the BACK lens supports true high-speed video. Runs once at startup.
 * Does not affect other modes' quality.
 */
internal suspend fun CameraViewModel.probeSloMoSupport(): Boolean {
    return try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        val cameraInfo = provider.getCameraInfo(selector)
        val caps = Recorder.getHighSpeedVideoCapabilities(cameraInfo) ?: return false
        val quals = caps.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
        if (quals.isEmpty()) return false
        // Need at least one FHD/HD HFR range
        val preview = Preview.Builder().build()
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
        )
        val recorder = Recorder.Builder().setQualitySelector(qualitySelector).build()
        val vc = VideoCapture.Builder(recorder).build()
        val tempConfig = HighSpeedVideoSessionConfig.Builder(vc)
            .setPreview(preview)
            .setSlowMotionEnabled(true)
            .build()
        val ranges = try {
            cameraInfo.getSupportedFrameRateRanges(tempConfig)
        } catch (_: Exception) { emptyList() }
        ranges.isNotEmpty() && ranges.any { it.upper >= 60 }
    } catch (e: Exception) {
        Log.w("SloMo", "Slo-Mo probe failed", e)
        false
    }
}

internal suspend fun CameraViewModel.loadThumbnail(uri: Uri?): Bitmap? = uri?.let {
    withContext(Dispatchers.IO) {
        try {
            app.contentResolver.loadThumbnail(it, Size(96, 96), null)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to load gallery thumbnail", e)
            null
        }
    }
}
