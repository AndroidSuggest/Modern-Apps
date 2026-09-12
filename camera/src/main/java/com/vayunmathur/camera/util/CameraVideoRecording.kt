package com.vayunmathur.camera.util

import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.toggleHighSpeedRecording() {
    // Cancel countdown if active
    if (_timerCountdown.value > 0) {
        timerCountdownJob?.cancel()
        timerCountdownJob = null
        _timerCountdown.value = 0
        return
    }

    if (_isRecording.value) {
        highSpeedRecording?.stop()
        highSpeedRecording = null
        stopRecordingTimer()
        return
    }

    val timer = _timerDuration.value
    if (timer.seconds > 0) {
        // Start countdown before recording
        timerCountdownJob = viewModelScope.launch {
            for (i in timer.seconds downTo 1) {
                _timerCountdown.value = i
                delay(1000)
            }
            _timerCountdown.value = 0
            timerCountdownJob = null
            startHighSpeedRecording()
        }
        return
    }

    startHighSpeedRecording()
}

@android.annotation.SuppressLint("MissingPermission")
internal fun CameraViewModel.startHighSpeedRecording() {
    val videoCapture = highSpeedVideoCapture ?: return

    val contentValues = MediaStoreSaver.videoValues("SLOMO_${MediaStoreSaver.timestamp()}")

    val outputOptions = MediaStoreOutputOptions.Builder(
        app.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValues).build()

    startRecordingTimer()

    Log.d("SloMo", "Starting high-speed recording at ${sloMoFps}fps")

    highSpeedRecording = videoCapture.output
        .prepareRecording(app, outputOptions)
        .start(ContextCompat.getMainExecutor(app)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                stopRecordingTimer()
                if (event.hasError()) {
                    Log.e("SloMo", "Recording error: ${event.error} - ${event.cause?.message}")
                } else {
                    Log.d("SloMo", "High-speed recording saved: ${event.outputResults.outputUri}")
                    setLastCaptureUri(event.outputResults.outputUri)
                }
            }
        }
}

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.toggleRecording() {
    // Cancel countdown if active
    if (_timerCountdown.value > 0) {
        timerCountdownJob?.cancel()
        timerCountdownJob = null
        _timerCountdown.value = 0
        return
    }

    if (_isRecording.value) {
        currentRecording?.stop()
        currentRecording = null
        stopRecordingTimer()
        return
    }

    val timer = _timerDuration.value
    if (timer.seconds > 0) {
        // Start countdown before recording
        timerCountdownJob = viewModelScope.launch {
            for (i in timer.seconds downTo 1) {
                _timerCountdown.value = i
                delay(1000)
            }
            _timerCountdown.value = 0
            timerCountdownJob = null
            startRecording()
        }
        return
    }

    startRecording()
}

@android.annotation.SuppressLint("MissingPermission")
internal fun CameraViewModel.startRecording() {
    val capture = videoCapture ?: return

    val timestamp = MediaStoreSaver.timestamp()
    val prefix = when (_cameraMode.value) {
        CameraMode.TIMELAPSE -> "TL"
        CameraMode.CINEMATIC -> "CINE"
        else -> "VID"
    }
    val contentValues = MediaStoreSaver.videoValues("${prefix}_$timestamp")

    val cacheFile = java.io.File(app.cacheDir, "VID_$timestamp.mp4")
    val outputOptions = FileOutputOptions.Builder(cacheFile).build()
    val recordingMode = _cameraMode.value

    startRecordingTimer()

    var pending = capture.output.prepareRecording(app, outputOptions)
    val audioEnabled = recordingMode == CameraMode.VIDEO || recordingMode == CameraMode.CINEMATIC
    if (audioEnabled) {
        pending = pending.withAudioEnabled()
    }
    currentRecording = pending.start(ContextCompat.getMainExecutor(app)) { event ->
        if (event is VideoRecordEvent.Finalize) {
            stopRecordingTimer()
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (!cacheFile.exists()) return@launch
                val fileToSave = when (recordingMode) {
                    CameraMode.TIMELAPSE -> {
                        val processed = java.io.File(app.cacheDir, "TL_$timestamp.mp4")
                        try {
                            VideoProcessor.adjustSpeed(cacheFile, processed, 8f)
                            cacheFile.delete()
                            processed
                        } catch (e: Exception) {
                            // MediaMuxer can reject an av01 track (or an oversized keyframe)
                            // on some devices; keep the raw recording rather than crash.
                            Log.e("CameraViewModel", "Timelapse remux failed; saving unprocessed", e)
                            processed.delete()
                            cacheFile
                        }
                    }
                    else -> cacheFile
                }
                MediaStoreSaver.saveVideoFile(app.contentResolver, contentValues, fileToSave)?.let {
                    setLastCaptureUri(it)
                }
                fileToSave.delete()
            }
        }
    }
    // Apply the current mic-mute state to the freshly-started recording.
    if (audioEnabled) {
        try {
            currentRecording?.mute(_micMuted.value)
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to apply initial mic mute", e)
        }
    }
}

/** Pauses or resumes the active recording (video/cinematic). No-op if not recording. */
fun CameraViewModel.togglePauseRecording() {
    val recording = currentRecording ?: return
    try {
        if (_recordingPaused.value) {
            recording.resume()
            _recordingPaused.value = false
        } else {
            recording.pause()
            _recordingPaused.value = true
        }
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Failed to pause/resume recording", e)
    }
}

/** Toggles mic mute; applies live to the active recording. Persisted for the next session. */
fun CameraViewModel.toggleMicMuted() {
    _micMuted.value = !_micMuted.value
    try {
        currentRecording?.mute(_micMuted.value)
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Failed to toggle mic mute", e)
    }
    viewModelScope.launch { ds.setString("camera_mic_muted", _micMuted.value.toString()) }
}

/**
 * Captures a still while recording (video snapshot), using the ImageCapture bound alongside the
 * video session. No-op if the device couldn't bind the extra ImageCapture use case.
 */
fun CameraViewModel.captureVideoSnapshot() {
    val capture = imageCapture ?: return
    val contentValues = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
    val metadata = ImageCapture.Metadata().apply {
        if (_locationEnabled.value) location = lastLocation
        isReversedHorizontal = mirrorCaptures
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        app.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).setMetadata(metadata).build()
    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                outputFileResults.savedUri?.let { setLastCaptureUri(it) }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraViewModel", "Video snapshot failed", exception)
            }
        }
    )
}

fun CameraViewModel.startPanorama() = panoramaEngine.startSweep()

fun CameraViewModel.stopPanorama() = finishPanoramaSweep()

fun CameraViewModel.startPhotosphere() = panoramaEngine.startSweep(fullSphere = true)

fun CameraViewModel.stopPhotosphere() = finishPanoramaSweep()

internal fun CameraViewModel.finishPanoramaSweep() {
    if (isFinishingPano) return
    isFinishingPano = true
    panoramaEngine.stopSweep()
    viewModelScope.launch {
        val result = panoramaEngine.stitch()
        if (result == null) {
            android.util.Log.e("CameraViewModel", "Panorama stitch failed – no output (check registration)")
        } else {
            val (jpeg, info) = result
            android.util.Log.i("CameraViewModel", "Panorama stitched ${jpeg.size} bytes, saving")
            val uri = panoramaEngine.saveToMediaStore(jpeg, info)
            if (uri != null) {
                android.util.Log.i("CameraViewModel", "Panorama saved uri=$uri")
                setLastCaptureUri(uri)
            } else {
                android.util.Log.e("CameraViewModel", "Panorama MediaStore save returned null")
            }
        }
        panoramaEngine.reset()
        isFinishingPano = false
    }
}
