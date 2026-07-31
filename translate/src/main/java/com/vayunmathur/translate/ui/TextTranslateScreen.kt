package com.vayunmathur.translate.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconSpeak
import com.vayunmathur.library.ui.IconStop
import com.vayunmathur.library.ui.IconSwapLanguages
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.translate.util.AndroidSpeechRecognizer
import com.vayunmathur.translate.util.Languages
import com.vayunmathur.translate.util.NativeSpeech
import com.vayunmathur.translate.util.SpeechRecognizerEngine
import com.vayunmathur.translate.util.TranslateViewModel
import kotlinx.coroutines.delay

/** Debounce window for live translation as the user types (ms). */
private const val TRANSLATE_DEBOUNCE_MS = 400L

/**
 * Mic button phases. TRANSCRIBING exists because the offline recognizer keeps working for
 * a moment after the mic stops; showing it (and ignoring taps) avoids the "stuck on Stop"
 * / double-start / "recognizer busy" confusion.
 */
private enum class MicState { IDLE, LISTENING, TRANSCRIBING }

/**
 * Home screen. The SMaLL-100 model (~1.2 GB) is now auto-installed on open via
 * [com.vayunmathur.library.downloadservice.InitialModelDownloadChecker] in
 * MainActivity (like OpenAssistant), so this screen never needs to show a
 * manual Download button. By the time we get here the files are on disk and
 * [TranslateViewModel] has loaded the ncnn engine.
 */
@Composable
fun TextTranslateScreen(
    viewModel: TranslateViewModel,
    initialText: String,
    onOpenCamera: () -> Unit,
) {
    val context = LocalContext.current

    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val translationAvailable by viewModel.translationAvailable.collectAsState()

    var inputText by remember { mutableStateOf(initialText) }
    var outputText by remember { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }

    // --- Live speech-to-text engine (native offline preferred, else platform) ---
    val speech: SpeechRecognizerEngine = remember(context) {
        NativeSpeech().takeIf { it.isAvailable() } ?: AndroidSpeechRecognizer(context)
    }
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

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Translate") },
                actions = {
                    IconButton(onClick = onOpenCamera) {
                        IconCamera()
                    }
                },
            )
        },
        floatingActionButton = {
            // Idle → Mic (starts). Listening → Stop (ends capture; the recognizer still
            // delivers its last result via onFinal — it does not cancel). Transcribing →
            // a spinner that ignores taps, so the user can't double-start or hit "busy".
            FloatingActionButton(
                onClick = {
                    when (micState) {
                        MicState.LISTENING -> speech.stop()
                        MicState.TRANSCRIBING -> {} // busy finishing; ignore taps
                        MicState.IDLE -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                containerColor = when (micState) {
                    MicState.LISTENING -> MaterialTheme.colorScheme.errorContainer
                    MicState.TRANSCRIBING -> MaterialTheme.colorScheme.surfaceVariant
                    MicState.IDLE -> MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                when (micState) {
                    MicState.LISTENING -> IconStop()
                    MicState.TRANSCRIBING -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    MicState.IDLE -> IconMic()
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageBar(
                sourceCode = sourceLang,
                targetCode = targetLang,
                onSource = viewModel::setSource,
                onTarget = viewModel::setTarget,
                onSwap = viewModel::swap,
            )

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                label = { Text("Enter text") },
                minLines = 4,
            )

            val micStatus = when (micState) {
                MicState.LISTENING -> "Listening…"
                MicState.TRANSCRIBING -> "Transcribing…"
                MicState.IDLE -> null
            }
            micStatus?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            speechError?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }

            OutputCard(
                translationAvailable = translationAvailable,
                isTranslating = isTranslating,
                outputText = outputText,
                onCopy = { copyToClipboard(context, outputText) },
                onSpeak = { viewModel.speak(outputText, targetLang) },
            )
        }
    }
}

@Composable
private fun LanguageBar(
    sourceCode: String,
    targetCode: String,
    onSource: (String) -> Unit,
    onTarget: (String) -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LanguagePicker(
            selectedCode = sourceCode,
            options = Languages.SOURCES,
            onSelected = onSource,
            modifier = Modifier.width(140.dp),
        )
        IconButton(
            onClick = onSwap,
            // Nothing to swap into while the source is auto-detect.
            enabled = sourceCode != Languages.AUTO.code,
        ) {
            IconSwapLanguages()
        }
        LanguagePicker(
            selectedCode = targetCode,
            options = Languages.TARGETS,
            onSelected = onTarget,
            modifier = Modifier.width(140.dp),
        )
    }
}

@Composable
private fun OutputCard(
    translationAvailable: Boolean,
    isTranslating: Boolean,
    outputText: String,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!translationAvailable) {
                // Should never happen — the outer InitialModelDownloadChecker
                // downloads the model before any Navigation is composed, and
                // TranslateViewModel loads the engine on init. Show a loading
                // spinner if the engine is still initializing.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading translator…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Translation",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (isTranslating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = outputText.ifBlank { "Translation will appear here" },
                color = if (outputText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy, enabled = outputText.isNotBlank()) {
                    IconCopy(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                TextButton(onClick = onSpeak, enabled = outputText.isNotBlank()) {
                    IconSpeak(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Speak")
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("translation", text))
}
