package com.vayunmathur.camera.util

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Rational
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.annotation.StringRes
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import com.vayunmathur.camera.R
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.SessionConfig
import androidx.camera.core.UseCase
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionSessionConfig
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.video.AudioSpec
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import androidx.camera.lifecycle.awaitInstance
import java.io.ByteArrayInputStream
import kotlin.math.atan2
import kotlin.math.roundToInt

enum class CameraMode { PHOTO, PORTRAIT, PANORAMA, PHOTOSPHERE, VIDEO, SLOW_MO, TIMELAPSE, CINEMATIC }
enum class FlashMode { ON, OFF, AUTO }
enum class TimerDuration(val seconds: Int) { NONE(0), THREE(3), FIVE(5), TEN(10) }
enum class AspectRatioOption(val label: String) { RATIO_16_9("16:9"), RATIO_4_3("4:3"), RATIO_1_1("1:1") }
enum class VideoCodec(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    AVC(R.string.codec_avc_label, R.string.codec_avc_description),
    HEVC(R.string.codec_hevc_label, R.string.codec_hevc_description),
    AV1(R.string.codec_av1_label, R.string.codec_av1_description),
}
enum class AudioInputSource(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val specValue: Int,
) {
    CAMCORDER(R.string.audio_source_camcorder_label, R.string.audio_source_camcorder_description, AudioSpec.SOURCE_CAMCORDER),
    MIC(R.string.audio_source_mic_label, R.string.audio_source_mic_description, AudioSpec.SOURCE_MIC),
    VOICE_COMMUNICATION(R.string.audio_source_voice_communication_label, R.string.audio_source_voice_communication_description, AudioSpec.SOURCE_VOICE_COMMUNICATION),
    UNPROCESSED(R.string.audio_source_unprocessed_label, R.string.audio_source_unprocessed_description, AudioSpec.SOURCE_UNPROCESSED),
}

/** Formats a zoom ratio for the zoom bar: ".5", "1x", "2x", or "1.5x". */
fun formatZoomLabel(ratio: Float): String = when {
    ratio < 1f -> ".${(ratio * 10).roundToInt()}"
    else -> {
        val rounded = (ratio * 10f).roundToInt() / 10f
        if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.05f) "${rounded.roundToInt()}x"
        else "%.1fx".format(rounded)
    }
}

data class ExposureTimeStop(val label: String, val nanos: Long?)

/**
 * Builds the warmth/shadows color matrix shared by the live preview and the
 * saved capture, so a photo looks the same as what the viewfinder showed.
 */
fun buildColorAdjustmentMatrix(warmth: Float, shadows: Float): ColorMatrix = ColorMatrix(
    floatArrayOf(
        1f + warmth * 0.15f, 0f, 0f, 0f, shadows * 40f,
        0f, 1f + warmth * 0.05f, 0f, 0f, shadows * 40f,
        0f, 0f, 1f - warmth * 0.15f, 0f, shadows * 40f,
        0f, 0f, 0f, 1f, 0f,
    )
)

class CameraViewModel(internal val app: Application) : AndroidViewModel(app) {
    companion object {
        // Companions are public so same-module extension files resolve these constants.

        // Once the vendor NIGHT extension fails to bind on this device, don't try again for this
        // long (persisted). Re-probes after it expires in case a system update fixes the extender.
        const val NIGHT_EXT_FAILURE_TTL_MS = 7L * 24 * 60 * 60 * 1000

        // Night-mode auto-detection tuning. Engage once average Y stays below ENGAGE for a few
        // frames; disengage once it climbs above DISENGAGE for a few frames. The gap between the
        // two thresholds is the hysteresis band that stops the moon button from flickering.
        const val NIGHT_ENGAGE_LUMA = 40f
        const val NIGHT_DISENGAGE_LUMA = 55f
        const val NIGHT_DEBOUNCE_FRAMES = 4

        // Target night exposure/ISO used when night mode fires on an Auto exposure stop. The
        // single-frame emulation (fallback) uses the long ~1/4s target; the multi-frame burst uses
        // a shorter per-frame exposure so each frame has less motion blur and the merge recovers SNR.
        const val NIGHT_TARGET_EXPOSURE_NANOS = 250_000_000L // ~1/4s
        const val NIGHT_BURST_PER_FRAME_NANOS = 100_000_000L // ~1/10s, in the 1/15–1/8s range
        const val NIGHT_ISO_FRACTION = 0.75f

        /** Safety cap on frames captured during a single press-and-hold burst. */
        const val BURST_MAX = 30

        // Motion-Photo ring buffer: keep ~1.5s of analysis frames, capped by count to bound memory
        // (analysis frames can be high-res, so this count is deliberately conservative).
        const val MOTION_WINDOW_NANOS = 1_500_000_000L
        const val MOTION_MAX_FRAMES = 12

        val EXPOSURE_TIME_STOPS = listOf(
            ExposureTimeStop("Auto", null),
            ExposureTimeStop("1/4000s", 250_000L),
            ExposureTimeStop("1/2000s", 500_000L),
            ExposureTimeStop("1/1000s", 1_000_000L),
            ExposureTimeStop("1/500s", 2_000_000L),
            ExposureTimeStop("1/250s", 4_000_000L),
            ExposureTimeStop("1/125s", 8_000_000L),
            ExposureTimeStop("1/60s", 16_666_667L),
            ExposureTimeStop("1/30s", 33_333_333L),
            ExposureTimeStop("1/15s", 66_666_667L),
            ExposureTimeStop("1/8s", 125_000_000L),
            ExposureTimeStop("1/4s", 250_000_000L),
            ExposureTimeStop("1/2s", 500_000_000L),
            ExposureTimeStop("1s", 1_000_000_000L),
            ExposureTimeStop("2s", 2_000_000_000L),
            ExposureTimeStop("4s", 4_000_000_000L),
        )

        /**
         * EXIF tags copied from the original frame onto an adjusted capture. Orientation and GPS
         * are set separately (see writeCaptureExif); dimension tags are omitted so they aren't
         * left inconsistent with the re-encoded JPEG.
         */
        val EXIF_TAGS_TO_COPY = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_MAX_APERTURE_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_LIGHT_SOURCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_SCENE_CAPTURE_TYPE,
            ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        )
    }

    internal val ds = DataStoreUtils.getInstance(app)

    internal val _cameraMode = MutableStateFlow(CameraMode.PHOTO)
    val cameraMode = _cameraMode.asStateFlow()

    internal val _lensFacing = MutableStateFlow(CameraSelector.LENS_FACING_BACK)
    val lensFacing = _lensFacing.asStateFlow()

    internal val _availableLenses = MutableStateFlow<List<com.vayunmathur.camera.domain.PhysicalLens>>(emptyList())
    val availableLenses = _availableLenses.asStateFlow()

    internal val _selectedLens = MutableStateFlow<com.vayunmathur.camera.domain.PhysicalLens?>(null)
    val selectedLens = _selectedLens.asStateFlow()

    internal val _lensCapabilities =
        MutableStateFlow<com.vayunmathur.camera.platform.LensCapabilities?>(null)
    val lensCapabilities = _lensCapabilities.asStateFlow()

    internal val _hasFlashUnit = MutableStateFlow(true)
    val hasFlashUnit = _hasFlashUnit.asStateFlow()

    internal val _exposureCompRange = MutableStateFlow<ClosedRange<Float>?>(null)
    val exposureCompRange = _exposureCompRange.asStateFlow()

    internal val _flashMode = MutableStateFlow(FlashMode.OFF)
    val flashMode = _flashMode.asStateFlow()

    internal val _torchEnabled = MutableStateFlow(false)
    val torchEnabled = _torchEnabled.asStateFlow()

    internal val _timerDuration = MutableStateFlow(TimerDuration.NONE)
    val timerDuration = _timerDuration.asStateFlow()

    internal val _aspectRatio = MutableStateFlow(AspectRatioOption.RATIO_4_3)
    val aspectRatio = _aspectRatio.asStateFlow()

    internal val _videoCodec = MutableStateFlow(
        when {
            CodecSupport.isHardwareAv1EncoderAvailable -> VideoCodec.AV1
            CodecSupport.isHevcEncoderAvailable -> VideoCodec.HEVC
            else -> VideoCodec.AVC
        }
    )
    val videoCodec = _videoCodec.asStateFlow()

    internal val _audioInputSource = MutableStateFlow(AudioInputSource.CAMCORDER)
    val audioInputSource = _audioInputSource.asStateFlow()

    internal val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    internal val _recordingDurationSec = MutableStateFlow(0L)
    val recordingDurationSec = _recordingDurationSec.asStateFlow()
    internal var recordingTimerJob: kotlinx.coroutines.Job? = null

    internal val _timerCountdown = MutableStateFlow(0)
    val timerCountdown = _timerCountdown.asStateFlow()
    internal var timerCountdownJob: kotlinx.coroutines.Job? = null

    internal val _qrResult = MutableStateFlow<String?>(null)
    val qrResult = _qrResult.asStateFlow()

    internal val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio = _zoomRatio.asStateFlow()

    internal val _availableZoomLevels = MutableStateFlow(listOf("1x" to 1f))
    val availableZoomLevels = _availableZoomLevels.asStateFlow()

    // Whether the front-camera preview and saved selfie are horizontally mirrored (issue #632).
    // Default true preserves the long-standing mirror-like selfie behavior.
    internal val _mirrorFront = MutableStateFlow(true)
    val mirrorFront = _mirrorFront.asStateFlow()

    internal val _isCapturing = MutableStateFlow(false)
    val isCapturing = _isCapturing.asStateFlow()

    internal val _locationEnabled = MutableStateFlow(false)
    val locationEnabled = _locationEnabled.asStateFlow()

    internal val _lastCaptureUri = MutableStateFlow<Uri?>(null)
    val lastCaptureUri = _lastCaptureUri.asStateFlow()

    private val _galleryThumbnail = MutableStateFlow<Bitmap?>(null)
    val galleryThumbnail = _galleryThumbnail.asStateFlow()

    internal val _gridEnabled = MutableStateFlow(false)
    val gridEnabled = _gridEnabled.asStateFlow()

    // Horizon level indicator: a device-roll angle (degrees) read off the accelerometer/gravity
    // sensor, exposed only while the level overlay is enabled. Registered lazily so the sensor
    // isn't running when the overlay is off.
    internal val _levelEnabled = MutableStateFlow(false)
    val levelEnabled = _levelEnabled.asStateFlow()

    private val _roll = MutableStateFlow(0f)
    val roll = _roll.asStateFlow()

    internal val sensorManager by lazy { app.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    // Dedicated single thread for portrait segmentation so main thread stays free for preview rendering.
    internal val bokehExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Same for the photo stream. [PhotoAnalyzer] copies the Y plane, runs a ZXing decode and (in
    // PHOTO) a toBitmap() per frame; on the main executor that only ran between UI frames, so
    // KEEP_ONLY_LATEST dropped most of them and QR codes decoded only when main happened to be idle.
    internal val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Bakes the same bokeh into the saved still. Separate from the preview's BokehAnalyzer, which is
    // owned by the composable and torn down with it; this one loads its model on the first capture.
    internal val stillBokeh = StillBokehRenderer(app)
    internal val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            // 0° when the device is held upright in portrait; positive as the top edge tilts right.
            _roll.value = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    internal var levelSensorRegistered = false

    // Portrait blur strength (0..1 UI → ~0.4..1.8 shader blurScale). Scales the bokeh shader's
    // tap offsets so the user can dial the background blur up/down. Default 0.5 ≈ 1.0 blurScale.
    internal val _blurStrength = MutableStateFlow(0.5f)
    val blurStrength = _blurStrength.asStateFlow()

    internal val _exposureCompensation = MutableStateFlow(0f)
    val exposureCompensation = _exposureCompensation.asStateFlow()

    internal val _warmth = MutableStateFlow(0f)
    val warmth = _warmth.asStateFlow()

    internal val _shadows = MutableStateFlow(0f)
    val shadows = _shadows.asStateFlow()

    internal val _exposureTimeIndex = MutableStateFlow(0)
    val exposureTimeIndex = _exposureTimeIndex.asStateFlow()

    // --- Manual pro controls (ISO). Index 0 == Auto. ---

    // ISO: index 0 == Auto; otherwise an index into [_isoStops] (+1). Stops are derived from the
    // sensor's SENSOR_INFO_SENSITIVITY_RANGE when the session binds.
    internal val _manualIsoIndex = MutableStateFlow(0)
    val manualIsoIndex = _manualIsoIndex.asStateFlow()

    internal val _isoStops = MutableStateFlow<List<Int>>(emptyList())
    val isoStops = _isoStops.asStateFlow()

    // Last auto-converged AE ISO / exposure, snapshotted off the preview's capture results so a
    // half-manual exposure (only ISO or only shutter set) can seed the other from the auto value.
    @Volatile internal var lastAeIso: Int? = null
    @Volatile internal var lastAeExposureNanos: Long? = null

    internal val aeSnapshotCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: android.hardware.camera2.CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { lastAeIso = it }
            result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastAeExposureNanos = it }
        }
    }

    // Night mode is fully automatic: brightness detection engages it, and the moon button lets
    // the user override it off for the current dark scene. nightModeActive drives the capture path.
    internal val _lowLightDetected = MutableStateFlow(false)
    val lowLightDetected = _lowLightDetected.asStateFlow()

    internal val _nightModeOverriddenOff = MutableStateFlow(false)
    val nightModeOverriddenOff = _nightModeOverriddenOff.asStateFlow()

    val nightModeActive = combine(_lowLightDetected, _nightModeOverriddenOff) { low, off -> low && !off }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Whether to offer the vendor NIGHT extension. We optimistically probe with
    // isSessionConfigSupported(), but the authoritative signal is the actual bind: if
    // setupNightPreviewSession() ever fails on this device, we remember that (persisted, with a
    // ~1-week TTL) and stop offering night for a week — so a broken extender (GrapheneOS/Pixel)
    // makes at most one failed attempt per week instead of engaging/failing in a loop.
    internal val _nightExtensionUsable = MutableStateFlow(false)
    val nightExtensionUsable = _nightExtensionUsable.asStateFlow()

    // Night-mode detection lives in CameraNightMode.kt as extensions.
    // Debounce counters for the luminance hysteresis filter (accessed from those extensions).
    @Volatile internal var lowLumaFrames = 0
    @Volatile internal var highLumaFrames = 0

    // Preferred night detection: CameraX's getNightModeIndicator() (1.7.0-alpha02) — the OS/vendor
    // reports when the scene is dark enough that night mode is RECOMMENDED. We observe the bound
    // camera's indicator LiveData and drive _lowLightDetected from it. Where the device doesn't
    // support the indicator, onLuminance()'s luminance heuristic is used as a fallback instead.
    internal var nightIndicatorLiveData: androidx.lifecycle.LiveData<Int>? = null
    var nightIndicatorSupported = false
        internal set
    internal val nightIndicatorObserver = androidx.lifecycle.Observer<Int> { state ->
        // Don't override a manual "off" for the current dark scene; the moon button owns that.
        if (_nightModeOverriddenOff.value) return@Observer
        // RECOMMENDED engages; anything else (NOT_RECOMMENDED / UNKNOWN) disengages, so night turns
        // off again when the scene brightens. (The toggle loop this used to cause on unusable devices
        // is now prevented by the daily failure cache in nightExtensionUsable instead.)
        _lowLightDetected.value = state == androidx.camera.core.NightModeIndicator.RECOMMENDED
    }

    // Live NIGHT-extension processing strength (0..100) from CameraExtensionsInfo.getExtensionStrength(),
    // for a "night processing" indicator in the UI. Null when unavailable / not in an extension session.
    internal val _extensionStrength = MutableStateFlow<Int?>(null)
    val extensionStrength = _extensionStrength.asStateFlow()

    // The vendor NIGHT extension can bind "successfully" (bindToLifecycle returns) yet fail
    // asynchronously when camera-pipe configures the ExtensionCaptureSession (GrapheneOS/Pixel:
    // ERROR_STREAM_CONFIG). That never throws from setupNightPreviewSession, so we watch the bound
    // extension camera's CameraState and treat any error as a real failure → cache it for a week.
    internal var extensionStrengthLiveData: androidx.lifecycle.LiveData<Int>? = null
    internal val extensionStrengthObserver = androidx.lifecycle.Observer<Int> { s -> _extensionStrength.value = s }
    internal var extensionCameraStateLiveData: androidx.lifecycle.LiveData<androidx.camera.core.CameraState>? = null
    internal val extensionCameraStateObserver = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { st ->
        val err = st.error ?: return@Observer
        Log.w("NightPreview", "night extension camera error type=${st.type} code=${err.code} – disabling night extension (cached)")
        recordNightExtensionFailure()
    }

    internal val _longExposureProgress = MutableStateFlow(0f)
    val longExposureProgress = _longExposureProgress.asStateFlow()

    internal val _longExposureRemaining = MutableStateFlow("")
    val longExposureRemaining = _longExposureRemaining.asStateFlow()

    internal var longExposureTimerJob: kotlinx.coroutines.Job? = null

    internal var lastLocation: Location? = null
    internal var currentRecording: Recording? = null
    internal var sloMoFps: Int = 30

    // IMAGE_CAPTURE (system "take a photo") intent state. When capturing for a caller, we either
    // write the full-res JPEG to their EXTRA_OUTPUT Uri, or hand back a downscaled thumbnail.
    var captureForResult: Boolean = false
        private set
    internal var resultOutputUri: Uri? = null

    /** Enters capture-for-result mode; [outputUri] is the caller's EXTRA_OUTPUT (may be null). */
    fun enableCaptureForResult(outputUri: Uri?) {
        captureForResult = true
        resultOutputUri = outputUri
    }

    internal val panoramaEngine = PanoramaEngine(app)

    // Guards panorama stitching so a second stop request can't start a concurrent stitch.
    // Written from CameraVideoRecording.kt's finishPanoramaSweep() extension.
    @Volatile internal var isFinishingPano = false

    // Unified manual session state (all modes bind through one CameraXViewfinder).
    internal val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = _surfaceRequest.asStateFlow()

    internal var cameraProvider: ProcessCameraProvider? = null
    // Cached per ProcessCameraProvider instance; ExtensionsManager must be tied to the same provider.
    internal var extensionsManager: ExtensionsManager? = null
    internal var sessionLifecycleOwner: ManualLifecycleOwner? = null
    internal var boundCamera: Camera? = null
    internal var imageCapture: ImageCapture? = null
    internal var imageAnalysis: ImageAnalysis? = null

    // The analyzer the UI wants running, kept across teardown/rebind. Every session bind re-attaches
    // it via [attachDesiredAnalyzer], so a new ImageAnalysis is never left bare: the UI's attach
    // effect is keyed on photoSessionActive, and that StateFlow conflates, so a false->true rebind
    // inside one frame used to leave the analyzer detached until the next mode switch.
    internal var desiredAnalyzer: ImageAnalysis.Analyzer? = null
    internal var desiredAnalyzerExecutor: Executor? = null

    /** True once the photo session's use cases (incl. ImageAnalysis) are bound. */
    internal val _photoSessionActive = MutableStateFlow(false)
    val photoSessionActive = _photoSessionActive.asStateFlow()

    /**
     * Whether the bound session actually carries an ImageAnalysis stream. False when the vendor
     * NIGHT extension can't host concurrent analysis, which silently stops QR scanning, luminance
     * sampling and Motion Photo; the viewfinder shows a notice so it isn't a mystery.
     */
    internal val _analysisStreamActive = MutableStateFlow(false)
    val analysisStreamActive = _analysisStreamActive.asStateFlow()

    /**
     * True while the live preview is bound with the CameraX NIGHT extension (plain
     * PHOTO mode, low light). When set, capture goes straight through the already
     * extension-enabled session instead of the momentary rebind path.
     */
    internal val _nightPreviewActive = MutableStateFlow(false)
    val nightPreviewActive = _nightPreviewActive.asStateFlow()

    // AE/AF lock (long-press-to-lock on the preview).
    internal val _focusLocked = MutableStateFlow(false)
    val focusLocked = _focusLocked.asStateFlow()

    // Video recording paused (between pause() and resume()).
    internal val _recordingPaused = MutableStateFlow(false)
    val recordingPaused = _recordingPaused.asStateFlow()

    // Mic mute for video (persisted). Applied live to the active recording.
    internal val _micMuted = MutableStateFlow(false)
    val micMuted = _micMuted.asStateFlow()

    // True when an ImageCapture is bound alongside the video session (in-recording snapshots).
    internal val _videoSnapshotSupported = MutableStateFlow(false)
    val videoSnapshotSupported = _videoSnapshotSupported.asStateFlow()

    // Hardware-shutter (volume key) events, collected by the UI to run the same capture action.
    internal val _shutterEvents = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutterEvents = _shutterEvents.asSharedFlow()

    fun triggerShutter() {
        _shutterEvents.tryEmit(Unit)
    }

    // Burst mode (press-and-hold shutter): fires single-frame captures back-to-back with only one
    // in flight at a time, up to BURST_MAX, until the user releases (stopBurst).
    internal val _burstActive = MutableStateFlow(false)
    val burstActive = _burstActive.asStateFlow()

    internal val _burstCount = MutableStateFlow(0)
    val burstCount = _burstCount.asStateFlow()

    // Motion-Photo ring buffer: the last ~MOTION_WINDOW of analysis frames (RGB copies), fed by the
    // PhotoAnalyzer off the shared analysis stream and drained when a Motion Photo is captured.
    class MotionFrame(val bitmap: Bitmap, val timestampNanos: Long, val rotationDegrees: Int)
    internal val motionFrames = ArrayDeque<MotionFrame>()
    internal val motionLock = Any()

    // High-speed session state — Slo-Mo-only (back camera, true HFR, no fallbacks)
    internal var highSpeedVideoCapture: VideoCapture<Recorder>? = null
    internal var highSpeedRecording: Recording? = null

    internal val _highSpeedActive = MutableStateFlow(false)
    val highSpeedActive = _highSpeedActive.asStateFlow()

    /** Whether the back camera supports true HFR slo-mo on this device. */
    internal val _sloMoSupported = MutableStateFlow<Boolean?>(null)
    val sloMoSupported = _sloMoSupported.asStateFlow()

    // Manual video session state (VIDEO / TIMELAPSE modes)
    internal var videoCapture: VideoCapture<Recorder>? = null

    internal val _videoSessionActive = MutableStateFlow(false)
    val videoSessionActive = _videoSessionActive.asStateFlow()

    /** True when the active video session is encoding AV1 (vs. the default codec). */
    var recordingWithAv1: Boolean = false
        internal set

    /** True when the active video session is encoding HEVC/H.265. */
    var recordingWithHevc: Boolean = false
        internal set

    fun setSloMoFps(fps: Int) {
        sloMoFps = fps
    }

    init {
        loadSettings()
        panoramaEngine.onSweepComplete = { finishPanoramaSweep() }
        viewModelScope.launch {
            _lastCaptureUri.collect { uri -> _galleryThumbnail.value = loadThumbnail(uri) }
        }
        // Probe Slo-Mo capability once (back camera only). UI hides Slo-Mo if unsupported.
        viewModelScope.launch {
            _sloMoSupported.value = probeSloMoSupport()
        }
        // DEBUG: Night mode resolution question – extension uses default resolution vs max-res, may be lower so can't take full quality?
        viewModelScope.launch {
            var last: List<Pair<String, Float>> = emptyList()
            availableZoomLevels.collect { levels ->
                if (levels != last) {
                    Log.d("NightPreview", "CameraViewModel availableZoomLevels FLOW emitted=$levels previous=$last nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} zoomRatio=${_zoomRatio.value} – if only [1x], zoom bar appears disappeared")
                    last = levels
                }
            }
        }
        viewModelScope.launch {
            var lastRes: android.util.Size? = null
            surfaceRequest.collect { req ->
                val res = req?.resolution
                if (res != lastRes) {
                    Log.d("NightPreview", "CameraViewModel surfaceRequest FLOW emitted res=$res previous=$lastRes nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} – null->val during extension bind, black if stuck null")
                    lastRes = res
                }
            }
        }
        viewModelScope.launch {
            nightModeActive.collect { active ->
                Log.d("NightPreview", "CameraViewModel nightModeActive FLOW=$active lowLight=${_lowLightDetected.value} overriddenOff=${_nightModeOverriddenOff.value} – button toggle drives this, triggers session rebind via useNightPreview in UI")
            }
        }
    }

    // Slo-Mo probe + thumbnail loading live in CameraStartup.kt as extensions.

    // Capture settings live in CameraSettings.kt as extensions.

    /**
     * Still-capture crop aspect ratio, expressed width:height in the portrait UI
     * orientation (the activity is portrait-locked) so it matches the on-screen
     * preview box. CameraX crops OutputFileOptions saves to this and sets the
     * ImageProxy cropRect for in-memory captures.
     */
    internal fun currentCropAspectRatio(): android.util.Rational = when (_aspectRatio.value) {
        AspectRatioOption.RATIO_1_1 -> Rational(1, 1)
        AspectRatioOption.RATIO_4_3 -> Rational(3, 4)
        AspectRatioOption.RATIO_16_9 -> Rational(9, 16)
    }

    // Bitmap/EXIF processing helpers live in CameraCaptureProcessing.kt as extensions.
    // (long-exposure countdown lives in CameraNightCapture.kt.)

    // Capture settings live in CameraSettings.kt as extensions.

    internal fun registerLevelSensor() {
        if (levelSensorRegistered) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(levelListener, sensor, SensorManager.SENSOR_DELAY_UI)
        levelSensorRegistered = true
    }

    internal fun unregisterLevelSensor() {
        if (!levelSensorRegistered) return
        sensorManager.unregisterListener(levelListener)
        levelSensorRegistered = false
    }

    /** Cycles the aspect ratio 4:3 → 16:9 → 1:1 → 4:3 (top-bar icon). */
    fun cycleAspectRatio() {
        val order = listOf(
            AspectRatioOption.RATIO_4_3,
            AspectRatioOption.RATIO_16_9,
            AspectRatioOption.RATIO_1_1
        )
        val next = order[(order.indexOf(_aspectRatio.value) + 1) % order.size]
        setAspectRatio(next)
    }

    // Night luminance/override handling lives in CameraNightMode.kt as extensions.
    // Last-capture persistence lives in CameraSettings.kt as an extension.

    fun switchCameraMode(newMode: CameraMode) {
        // A new mode starts with a clean night-detection slate (teardown no longer
        // resets it, since it also runs on night<->normal preview rebinds).
        if (newMode != _cameraMode.value) resetNightModeDetection()
        // Slo-Mo is back-camera only; enforce it when entering Slo-Mo.
        if (newMode == CameraMode.SLOW_MO) {
            if (_lensFacing.value != CameraSelector.LENS_FACING_BACK) {
                _lensFacing.value = CameraSelector.LENS_FACING_BACK
                resetNightModeDetection()
            }
        }
        _cameraMode.value = newMode
    }

    fun flipCamera() {
        // Disallow flipping while in Slo-Mo — it's back-camera only, true HFR.
        if (_cameraMode.value == CameraMode.SLOW_MO) return
        _lensFacing.value = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        // Preserve the lens family across the flip: e.g. UW back -> front lens,
        // tele back -> front lens (front has a single family, anchored at its default).
        val facing = if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
            com.vayunmathur.camera.domain.LensFacing.BACK
        else com.vayunmathur.camera.domain.LensFacing.FRONT
        _selectedLens.value =
            com.vayunmathur.camera.domain.LensSelectionLogic.filterByFacing(
                _availableLenses.value, facing
            ).minByOrNull { it.fallbackPriority }
        resetNightModeDetection()
    }

    /**
     * Selects a physical lens within the current facing family. The session
     * rebind effect is keyed on [selectedLens], so this triggers a rebind with
     * the filtered selector plus a capability refresh. Unknown lenses are
     * ignored so a stale UI entry can never crash a bind.
     */
    fun selectLens(lens: com.vayunmathur.camera.domain.PhysicalLens) {
        if (lens.facing != (
            if (_lensFacing.value == CameraSelector.LENS_FACING_BACK)
                com.vayunmathur.camera.domain.LensFacing.BACK
            else com.vayunmathur.camera.domain.LensFacing.FRONT
            )
        ) {
            return
        }
        if (lens !in _availableLenses.value) return
        _selectedLens.value = lens
        resetNightModeDetection()
    }

    // (night reset helper lives in CameraNightMode.kt.)

    /**
     * Whether captures should be horizontally mirrored to match the preview. CameraX mirrors the
     * front-camera preview but saves un-mirrored by default, so selfies otherwise come out flipped.
     * Controlled by the user-facing "mirror selfie" setting (issue #632); only ever applies to the
     * front lens.
     */
    internal val mirrorCaptures: Boolean
        get() = _lensFacing.value == CameraSelector.LENS_FACING_FRONT && _mirrorFront.value

    // Zoom/focus/analyzer controls live in CameraControls.kt as extensions.

    // Location refresh lives in CameraSettings.kt as an extension.

    // Slo-Mo session setup lives in CameraSloMoSession.kt as an extension.

    // bindSession lives in CameraSessions.kt as an extension.

    // Photo/night/pano/portrait session setups live in CameraPhotoSessions.kt as extensions.

    // Manual-control range reading lives in CameraManualControls.kt as an extension.

    // Video session setup lives in CameraVideoSession.kt as an extension.

    // Video capability helpers live in CameraSessions.kt as extensions.

    // Video tuning helpers (fps range, stabilization, capture options) also live there.

    // Session teardown lives in CameraSessions.kt as an extension.

    // --- Unified manual session control wiring (targets the single bound camera) ---
    // Implementations live in CameraControls.kt as extensions.
    // Flash-mode mapping lives in CameraSessions.kt as an extension.

    // Recording timers live in CameraControls.kt as extensions.

    // Slo-mo + standard recording live in CameraVideoRecording.kt as extensions.

    // --- Standard capture routing, burst and Motion Photo live in CameraPhotoCapture.kt. ---
    // Single-shot + IMAGE_CAPTURE paths live in CameraSingleCapture.kt.

    // Night-shutter routing lives in CameraPhotoCapture.kt; capture paths live in CameraNightCapture.kt.
    // IMAGE_CAPTURE paths live in CameraSingleCapture.kt.

    // (bitmap/EXIF helpers live in CameraCaptureProcessing.kt.)

    // (long-exposure countdown lives in CameraNightCapture.kt.)

    // Video recording + panorama live in CameraVideoRecording.kt as extensions.

    override fun onCleared() {
        unregisterLevelSensor()
        try { bokehExecutor.shutdown() } catch (_: Exception) {}
        try { analysisExecutor.shutdown() } catch (_: Exception) {}
        try { stillBokeh.close() } catch (_: Exception) {}
    }
}

internal class ManualLifecycleOwner : LifecycleOwner {
    private val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

    fun start() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
    }
}
