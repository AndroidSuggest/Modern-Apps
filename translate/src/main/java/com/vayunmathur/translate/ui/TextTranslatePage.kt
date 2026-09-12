package com.vayunmathur.translate.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.translate.R
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.AndroidSpeechRecognizer
import com.vayunmathur.translate.platform.MicState
import com.vayunmathur.translate.platform.SpeechRecognizerEngine
import com.vayunmathur.translate.platform.TextTranslateActions
import com.vayunmathur.translate.platform.TextTranslateUiState
import com.vayunmathur.translate.platform.TranslateViewModel
import kotlinx.coroutines.delay

/** Debounce window for live translation as the user types (ms). */
private const val TRANSLATE_DEBOUNCE_MS = 400L

/**
 * Binds [TranslateViewModel] to the stateless [TextTranslateScreen].
 *
 * Everything a `@Preview` cannot supply lives here: the speech recognizer, the microphone
 * permission launcher, the clipboard, and the debounced call into the translation engine.
 *
 * The NLLB-200 model is auto-installed on open via
 * [com.vayunmathur.library.downloadservice.InitialModelDownloadChecker] in MainActivity
 * (like OpenAssistant), so this screen never needs to show a manual Download button. By the
 * time we get here the files are on disk and [TranslateViewModel] has loaded the engine.
 */
@Composable
fun TextTranslatePage(
    viewModel: TranslateViewModel,
    initialText: String,
    onOpenCamera: () -> Unit,
    onOpenLanguagePicker: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()

    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val translationAvailable by viewModel.translationAvailable.collectAsState()

    var inputText by remember { mutableStateOf(initialText) }
    var outputText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }

    // --- Live speech-to-text engine ---
    // The platform SpeechRecognizer, which routes to whichever RecognitionService the user
    // has selected. Installing the Speech app makes that fully offline (Whisper) without
    // this app needing its own model — see the <queries> entry in the manifest, which is
    // what lets us see an installed recognizer at all on Android 11+.
    val speech: SpeechRecognizerEngine = remember(context) { AndroidSpeechRecognizer(context) }
    var micState by remember { mutableStateOf(MicState.IDLE) }
    var speechError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(speech) { onDispose { speech.destroy() } }

    fun startListening() {
        speechError = null
        micState = MicState.LISTENING
        speech.start(
            languageCode = sourceLang,
            onPartial = { inputText = it },
            onFinal = {
                if (it.isNotBlank()) inputText = it
                micState = MicState.IDLE
            },
            onError = {
                speechError = it
                micState = MicState.IDLE
            },
            // Mic closed; the model is now transcribing. Only advance from LISTENING so a
            // late callback can't resurrect the button after we're already idle.
            onEndOfSpeech = { if (micState == MicState.LISTENING) micState = MicState.TRANSCRIBING },
        )
    }

    val micPermission = rememberPermissionRequest(
        Manifest.permission.RECORD_AUDIO
    ) { granted ->
        if (granted) startListening() else speechError = "Microphone permission is required"
    }

    // Live, debounced translation as input / language selection changes.
    LaunchedEffect(inputText, sourceLang, targetLang, translationAvailable) {
        if (inputText.isBlank()) {
            outputText = ""
            isTranslating = false
            return@LaunchedEffect
        }
        if (!translationAvailable) return@LaunchedEffect
        delay(TRANSLATE_DEBOUNCE_MS)
        isTranslating = true
        outputText = viewModel.translate(inputText).orEmpty()
        isTranslating = false
    }

    // Read here, not in the callback below: stringResource is @Composable.
    val missingVoiceMessage = stringResource(
        R.string.no_voice_installed,
        Languages.byCode(targetLang).englishName,
    )
    val installLabel = stringResource(R.string.install_voice)

    TextTranslateScreen(
        state = TextTranslateUiState(
            sourceLang = sourceLang,
            targetLang = targetLang,
            translationAvailable = translationAvailable,
            inputText = inputText,
            outputText = outputText,
            isTranslating = isTranslating,
            micState = micState,
            speechError = speechError,
        ),
        actions = object : TextTranslateActions {
            override fun setSource(code: String) = viewModel.setSource(code)
            override fun setTarget(code: String) = viewModel.setTarget(code)
            // Swap the text along with the languages, so the translation becomes the
            // thing being translated. The debounced effect below then re-translates it.
            override fun swap() {
                if (sourceLang == Languages.AUTO.code) return
                val previousInput = inputText
                inputText = outputText
                outputText = previousInput
                viewModel.swap()
            }
            override fun setInput(text: String) {
                inputText = text
            }

            // Idle → start. Listening → end capture; the recognizer still delivers its last
            // result via onFinal, it does not cancel. Transcribing → busy finishing.
            override fun toggleMic() {
                when (micState) {
                    MicState.LISTENING -> speech.stop()
                    MicState.TRANSCRIBING -> Unit
                    MicState.IDLE -> micPermission()
                }
            }

            override fun copyOutput() = copyToClipboard(context, outputText)

            // Say so rather than let the engine read the translation out in the device's
            // language, and offer the engine's own voice-download screen as the fix.
            override fun speakOutput() {
                viewModel.speak(outputText, targetLang) {
                    messenger.show(
                        message = missingVoiceMessage,
                        actionLabel = installLabel,
                        onAction = { installVoiceData(context) },
                    )
                }
            }

            override fun openCamera() = onOpenCamera()

            override fun openLanguagePicker(forSource: Boolean) =
                onOpenLanguagePicker(forSource)
        },
    )
}

/**
 * Open the TTS engine's voice-download screen. Not every engine declares the activity, so
 * a missing handler is just a no-op rather than a crash.
 */
private fun installVoiceData(context: Context) {
    val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (t: Throwable) {
        Log.w("TextTranslate", "no activity for ACTION_INSTALL_TTS_DATA", t)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("translation", text))
}
