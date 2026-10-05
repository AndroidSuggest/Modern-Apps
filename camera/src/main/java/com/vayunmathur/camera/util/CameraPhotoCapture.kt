package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.net.Uri
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

/** Shutter-timer countdown tick (1s). */
internal const val TIMER_TICK_MS = 1000L

fun CameraViewModel.takePhoto() {
    // Second tap cancels an armed timer, mirroring toggleRecording().
    if (timerCountdownMutable.value > 0) {
        cancelTimerCountdown()
        return
    }
    if (isCapturingMutable.value || burstActiveMutable.value) return
    val timer = timerDurationMutable.value
    if (timer.seconds > 0) {
        timerCountdownJob = viewModelScope.launch {
            for (i in timer.seconds downTo 1) {
                timerCountdownMutable.value = i
                kotlinx.coroutines.delay(TIMER_TICK_MS)
            }
            timerCountdownMutable.value = 0
            timerCountdownJob = null
            capturePhoto()
        }
    } else {
        capturePhoto()
    }
}

internal fun CameraViewModel.capturePhoto() {
    if (imageCapture == null || isCapturingMutable.value || burstActiveMutable.value) return
    // Claim in-flight synchronously so a second shutter tap (or a stacked timer firing)
    // can't start a concurrent capture; every path below clears it on completion.
    isCapturingMutable.value = true
    when {
        // Night Sight mode (or auto-engaged night): the preview is bound with the vendor NIGHT
        // extension, so a plain single capture through that ImageCapture lets the vendor pipeline
        // produce the multi-frame night image.
        nightPreviewActiveMutable.value -> captureSinglePhoto()
        // Multi-frame night capture only when night mode is active and exposure is fully auto.
        nightModeActive.value && isExposureAuto() -> captureNightPhoto()
        // Motion Photo for plain PHOTO captures (no warmth/shadows bake, not capturing for a
        // caller). Only at the native 4:3 ratio: the motion still is saved as raw JPEG bytes
        // (to preserve the Ultra HDR gain map + motion trailer), which can't carry CameraX's
        // crop. For other ratios fall through to the single-shot path, which saves a cropped JPEG.
        cameraModeMutable.value == CameraMode.PHOTO && !captureForResult &&
            warmthMutable.value == 0f && shadowsMutable.value == 0f &&
            aspectRatioMutable.value == AspectRatioOption.RATIO_4_3 -> captureMotionPhoto()
        else -> captureSinglePhoto()
    }
}

/**
 * Starts a press-and-hold burst: standard single-frame captures fired back-to-back (one in
 * flight at a time) with `IMG_<ts>_BURSTn` names, until [stopBurst] or [CameraViewModel.BURST_MAX]
 * is reached. Uses the plain capture path (no night/manual special-casing).
 */
fun CameraViewModel.startBurst() {
    if (burstActiveMutable.value || isCapturingMutable.value) return
    // A burst is immediate: an armed shutter timer no longer applies.
    cancelTimerCountdown()
    val capture = imageCapture ?: return
    // Mark both in-flight: a single capture must not start mid-burst and a burst must not
    // start mid-capture. Releasing the shutter always goes through finishBurst().
    isCapturingMutable.value = true
    burstActiveMutable.value = true
    burstCountMutable.value = 0
    val ts = MediaStoreSaver.timestamp()

    fun finishBurst() {
        burstActiveMutable.value = false
        isCapturingMutable.value = false
    }

    fun shootNext(n: Int) {
        if (!burstActiveMutable.value || n > CameraViewModel.BURST_MAX) {
            finishBurst()
            return
        }
        // prepareStillSave falls back to MediaStore, so null is unexpected — but a null
        // here must terminate, never retry (the old `?: run { shootNext(n + 1) }` looped
        // forever when the save target was unusable).
        val pending = prepareStillSave("IMG_${ts}_BURST${n}.jpg") ?: run {
            finishBurst()
            return
        }
        val outputOptions = pending.outputOptions

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    burstCountMutable.value = n
                    pending.closeStream()
                    pending.resolveUri(outputFileResults)?.let { setLastCaptureUri(it) }
                    shootNext(n + 1)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraViewModel", "Burst frame $n failed", exception)
                    pending.closeStream()
                    shootNext(n + 1)
                }
            }
        )
    }
    shootNext(1)
}

fun CameraViewModel.stopBurst() {
    // Release path for the press-and-hold gesture: only clears the loop flag. The in-flight
    // frame's save callback finishes the sequence (finishBurst) so a release between frames
    // still lands the shot already exposing.
    burstActiveMutable.value = false
}

/** Appends an analysis frame to the Motion-Photo ring buffer, trimming by age then count. */
fun CameraViewModel.addMotionFrame(bitmap: Bitmap, timestampNanos: Long, rotationDegrees: Int) {
    synchronized(motionLock) {
        motionFrames.addLast(CameraViewModel.MotionFrame(bitmap, timestampNanos, rotationDegrees))
        val cutoff = timestampNanos - CameraViewModel.MOTION_WINDOW_NANOS
        while (motionFrames.size > 1 && motionFrames.first().timestampNanos < cutoff) {
            motionFrames.removeFirst().bitmap.recycle()
        }
        while (motionFrames.size > CameraViewModel.MOTION_MAX_FRAMES) {
            motionFrames.removeFirst().bitmap.recycle()
        }
    }
}

internal fun CameraViewModel.drainMotionFrames(): List<CameraViewModel.MotionFrame> = synchronized(motionLock) {
    val list = motionFrames.toList()
    motionFrames.clear()
    list
}

internal fun CameraViewModel.clearMotionFrames() = synchronized(motionLock) {
    motionFrames.forEach { it.bitmap.recycle() }
    motionFrames.clear()
}

/**
 * Captures a Motion Photo: the still (captured in-memory so its bytes can carry the trailer) plus
 * the buffered ring-buffer frames encoded to a short MP4 and appended as a Google Motion Photo
 * trailer. Falls back to saving the plain still if no frames are buffered or encoding fails.
 * The still may be Ultra HDR (gain map preserved); the appended clip is SDR at analysis resolution.
 */
internal fun CameraViewModel.captureMotionPhoto() {
    val capture = imageCapture ?: return
    isCapturingMutable.value = true
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
                    isCapturingMutable.value = false
                    if (uri != null) setLastCaptureUri(uri)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraViewModel", "Motion Photo capture failed; falling back to still", exception)
                isCapturingMutable.value = false
                captureSinglePhoto()
            }
        }
    )
}

internal fun CameraViewModel.assembleAndSaveMotionPhoto(
    jpegBytes: ByteArray,
    frames: List<CameraViewModel.MotionFrame>,
    degrees: Int
): Uri? {
    val name = "IMG_${MediaStoreSaver.timestamp()}.jpg"
    // In-memory captures carry no ImageCapture.Metadata, so stamp GPS/orientation into the
    // still bytes here (issue #731). Must happen BEFORE the MP4 trailer is appended —
    // ExifInterface only understands a pure JPEG.
    val stillBytes = stampStillBytes(jpegBytes, degrees, mirrorCaptures)
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
            MotionPhotoWriter.assemble(stillBytes, mp4)
        } else {
            tmp.delete()
            stillBytes
        }
    } else {
        stillBytes
    }
    return saveStillBytes(name, bytes)
}

/**
 * Routes the night shutter: prefer CameraX Extensions NIGHT (vendor multi-frame processing)
 * when it's usable on this device, otherwise fall back to the custom burst + Rust merge. Uses
 * nightExtensionUsable (the gated/failure-cached value) so a device where the extension can't
 * bind (GrapheneOS/Pixel) goes straight to the working custom path instead of failing.
 */
internal fun CameraViewModel.captureNightPhoto() {
    viewModelScope.launch {
        if (nightExtensionUsableMutable.value) {
            captureNightPhotoExtension()
        } else {
            captureNightPhotoCustom()
        }
    }
}
