package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

fun CameraViewModel.takePhoto() {
    val timer = _timerDuration.value
    if (timer.seconds > 0) {
        viewModelScope.launch {
            for (i in timer.seconds downTo 1) {
                _timerCountdown.value = i
                kotlinx.coroutines.delay(1000)
            }
            _timerCountdown.value = 0
            capturePhoto()
        }
    } else {
        capturePhoto()
    }
}

internal fun CameraViewModel.capturePhoto() {
    if (imageCapture == null) return
    when {
        // Night Sight mode (or auto-engaged night): the preview is bound with the vendor NIGHT
        // extension, so a plain single capture through that ImageCapture lets the vendor pipeline
        // produce the multi-frame night image.
        _nightPreviewActive.value -> captureSinglePhoto()
        // Multi-frame night capture only when night mode is active and exposure is fully auto.
        nightModeActive.value && isExposureAuto() -> captureNightPhoto()
        // Motion Photo for plain PHOTO captures (no warmth/shadows bake, not capturing for a
        // caller). Only at the native 4:3 ratio: the motion still is saved as raw JPEG bytes
        // (to preserve the Ultra HDR gain map + motion trailer), which can't carry CameraX's
        // crop. For 1:1/16:9 fall through to the single-shot path, which saves a cropped JPEG.
        _cameraMode.value == CameraMode.PHOTO && !captureForResult &&
            _warmth.value == 0f && _shadows.value == 0f &&
            _aspectRatio.value == AspectRatioOption.RATIO_4_3 -> captureMotionPhoto()
        else -> captureSinglePhoto()
    }
}

/**
 * Starts a press-and-hold burst: standard single-frame captures fired back-to-back (one in
 * flight at a time) with `IMG_<ts>_BURSTn` names, until [stopBurst] or [CameraViewModel.BURST_MAX]
 * is reached. Uses the plain capture path (no night/manual special-casing).
 */
fun CameraViewModel.startBurst() {
    if (_burstActive.value) return
    val capture = imageCapture ?: return
    _burstActive.value = true
    _burstCount.value = 0
    val ts = MediaStoreSaver.timestamp()

    fun shootNext(n: Int) {
        if (!_burstActive.value || n > BURST_MAX) {
            _burstActive.value = false
            return
        }
        val values = MediaStoreSaver.imageValues("IMG_${ts}_BURST${n}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            if (_locationEnabled.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).setMetadata(metadata).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    _burstCount.value = n
                    outputFileResults.savedUri?.let { setLastCaptureUri(it) }
                    shootNext(n + 1)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraViewModel", "Burst frame $n failed", exception)
                    shootNext(n + 1)
                }
            }
        )
    }
    shootNext(1)
}

fun CameraViewModel.stopBurst() {
    _burstActive.value = false
}

/** Appends an analysis frame to the Motion-Photo ring buffer, trimming by age then count. */
fun CameraViewModel.addMotionFrame(bitmap: Bitmap, timestampNanos: Long, rotationDegrees: Int) {
    synchronized(motionLock) {
        motionFrames.addLast(MotionFrame(bitmap, timestampNanos, rotationDegrees))
        val cutoff = timestampNanos - MOTION_WINDOW_NANOS
        while (motionFrames.size > 1 && motionFrames.first().timestampNanos < cutoff) {
            motionFrames.removeFirst().bitmap.recycle()
        }
        while (motionFrames.size > MOTION_MAX_FRAMES) {
            motionFrames.removeFirst().bitmap.recycle()
        }
    }
}

internal fun CameraViewModel.drainMotionFrames(): List<MotionFrame> = synchronized(motionLock) {
    val list = motionFrames.toList()
    motionFrames.clear()
    list
}

/**
 * Captures a Motion Photo: the still (captured in-memory so its bytes can carry the trailer) plus
 * the buffered ring-buffer frames encoded to a short MP4 and appended as a Google Motion Photo
 * trailer. Falls back to saving the plain still if no frames are buffered or encoding fails.
 * The still may be Ultra HDR (gain map preserved); the appended clip is SDR at analysis resolution.
 */
internal fun CameraViewModel.captureMotionPhoto() {
    val capture = imageCapture ?: return
    _isCapturing.value = true
    capture.takePicture(
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val degrees = image.imageInfo.rotationDegrees
                val jpegBytes = try {
                    image.planes[0].buffer.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } finally {
                    image.close()
                }
                val frames = drainMotionFrames()
                viewModelScope.launch {
                    val uri = withContext(Dispatchers.Default) {
                        assembleAndSaveMotionPhoto(jpegBytes, frames, degrees)
                    }
                    frames.forEach { it.bitmap.recycle() }
                    _isCapturing.value = false
                    if (uri != null) setLastCaptureUri(uri)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraViewModel", "Motion Photo capture failed; falling back to still", exception)
                _isCapturing.value = false
                captureSinglePhoto()
            }
        }
    )
}

internal fun CameraViewModel.assembleAndSaveMotionPhoto(
    jpegBytes: ByteArray,
    frames: List<MotionFrame>,
    degrees: Int
): Uri? {
    val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
    // Only frames matching the newest frame's dimensions are encoded (a rebind can change size).
    val sized = frames.takeIf { it.isNotEmpty() }?.let { list ->
        val w = list.last().bitmap.width
        val h = list.last().bitmap.height
        list.filter { it.bitmap.width == w && it.bitmap.height == h }
    }.orEmpty()

    val bytes = if (sized.size >= 2) {
        val tmp = java.io.File(app.cacheDir, "motion_${System.currentTimeMillis()}.mp4")
        val spanNs = (sized.last().timestampNanos - sized.first().timestampNanos).coerceAtLeast(1)
        val fps = (sized.size * 1_000_000_000.0 / spanNs).roundToInt().coerceIn(5, 30)
        val ok = MotionPhotoEncoder.encode(
            sized.map { it.bitmap }, tmp, sized.last().rotationDegrees, fps
        )
        if (ok && tmp.exists()) {
            val mp4 = tmp.readBytes()
            tmp.delete()
            MotionPhotoWriter.assemble(jpegBytes, mp4)
        } else {
            tmp.delete()
            jpegBytes
        }
    } else {
        jpegBytes
    }
    return MediaStoreSaver.saveJpegBytes(app.contentResolver, values, bytes)
}

/**
 * Routes the night shutter: prefer CameraX Extensions NIGHT (vendor multi-frame processing)
 * when it's usable on this device, otherwise fall back to the custom burst + Rust merge. Uses
 * nightExtensionUsable (the gated/failure-cached value) so a device where the extension can't
 * bind (GrapheneOS/Pixel) goes straight to the working custom path instead of failing.
 */
internal fun CameraViewModel.captureNightPhoto() {
    viewModelScope.launch {
        if (_nightExtensionUsable.value) {
            captureNightPhotoExtension()
        } else {
            captureNightPhotoCustom()
        }
    }
}
