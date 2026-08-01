package com.vayunmathur.speech.util

/**
 * The UI contract for the setup screen.
 *
 * The screen takes a state value plus an actions interface rather than reading the device
 * directly, so it can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. It lives in `util` so the dependency runs one way: the UI depends on
 * `util`, never the reverse.
 *
 * There is no ViewModel here. Every field below is a fact about the device (a granted
 * permission, a Settings.Secure entry, a downloaded model) that the binder re-reads when
 * the user returns from a settings screen, so the state is assembled at the call site.
 */

/** Which of the five setup steps are already done. */
data class SpeechSetupUiState(
    /** Whisper recognition model present on disk. */
    val modelReady: Boolean = false,
    val hasMic: Boolean = false,
    /** This app is the device's `voice_recognition_service`. */
    val isRecognizerDefault: Boolean = false,
    /** Piper TTS voice present on disk. */
    val ttsModelReady: Boolean = false,
    /** This app is the device's `tts_default_synth`. */
    val isTtsDefault: Boolean = false,
)

/**
 * Setup screen callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * The two downloads are `suspend` because the button drives them directly and polls
 * [recognitionProgress]/[voiceProgress] while they run.
 */
interface SpeechSetupActions {
    fun requestMicPermission() {}
    fun openVoiceInputSettings() {}
    fun openTtsSettings() {}

    /** Re-read the device state after a step completes. */
    fun refresh() {}

    fun recognitionProgress(): Float = 0f
    suspend fun downloadRecognitionModel() {}

    fun voiceProgress(): Float = 0f
    suspend fun downloadVoice() {}

    companion object {
        val Noop: SpeechSetupActions = object : SpeechSetupActions {}
    }
}
