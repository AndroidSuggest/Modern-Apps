package com.vayunmathur.speech

import com.vayunmathur.speech.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.speech.service.WhisperRecognitionService
import com.vayunmathur.speech.util.PiperModel
import com.vayunmathur.speech.util.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                SetupScreen()
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // Recompute status whenever [refresh] changes (bumped when returning from settings).
    val isDefault = remember(refresh) {
        val current = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        val mine = ComponentName(context, WhisperRecognitionService::class.java).flattenToString()
        current != null && ComponentName.unflattenFromString(current) ==
            ComponentName.unflattenFromString(mine)
    }
    val hasMic = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val ds = remember { DataStoreUtils.getInstance(context) }
    val modelReady = remember(refresh) { WhisperModel.isReady(context) }
    val ttsModelReady = remember(refresh) { PiperModel.isReady(context) }
    val isTtsDefault = remember(refresh) {
        Settings.Secure.getString(context.contentResolver, "tts_default_synth") == context.packageName
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.recognition_service_label)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.offline_on_device_speech_whisper_recogni) +
                    "the whole system via MA Speech. Models download once, then everything runs with no internet, " +
                    "no Google — works on GrapheneOS.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 1) Download the offline recognition model
            StepCard(
                index = 1,
                title = "Speech recognition model",
                done = modelReady,
                body = if (modelReady) {
                    "The Whisper model is installed — recognition runs fully offline."
                } else {
                    "Download the multilingual Whisper model (~113 MB) once. Runs offline afterward."
                },
            ) {
                if (!modelReady) {
                    ModelDownloadButton(
                        label = stringResource(R.string.download_model_113_mb),
                        progressOf = { WhisperModel.progress(ds) },
                        download = { WhisperModel.download(context, ds) },
                        onDone = { refresh++ },
                    )
                }
            }

            // 2) Microphone permission
            StepCard(
                index = 2,
                title = "Microphone access",
                done = hasMic,
                body = "Needed to record your voice for transcription.",
            ) {
                if (!hasMic) {
                    Button(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text(stringResource(R.string.grant_microphone))
                    }
                }
            }

            // 3) Set as the device's recognizer
            StepCard(
                index = 3,
                title = "Set as speech recognizer",
                done = isDefault,
                body = if (isDefault) {
                    "This app is your device's speech recognizer. Other apps' voice input now runs offline."
                } else {
                    "Open voice-input settings and choose \"MA Speech\" as the on-device / " +
                        "voice-input service so other apps (like Translate) use it."
                },
            ) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        .onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                    refresh++
                }) { Text(stringResource(R.string.open_voice_input_settings)) }
            }

            // 3) Try it
            TestSection(enabled = hasMic)

            // 4) Download the offline TTS voice
            StepCard(
                index = 4,
                title = "Text-to-speech voice",
                done = ttsModelReady,
                body = if (ttsModelReady) {
                    "The offline Piper voice is installed — apps can speak fully offline."
                } else {
                    "Download the Piper voice (~64 MB) once. Runs offline afterward."
                },
            ) {
                if (!ttsModelReady) {
                    ModelDownloadButton(
                        label = stringResource(R.string.download_voice_64_mb),
                        progressOf = { PiperModel.progress(ds) },
                        download = {
                            PiperModel.download(context, ds)
                            // Unzip the voice off the main thread before marking done.
                            withContext(Dispatchers.IO) { PiperModel.installIfNeeded(context) }
                        },
                        onDone = { refresh++ },
                    )
                }
            }

            // 5) Set as the device's TTS engine
            StepCard(
                index = 5,
                title = "Set as text-to-speech engine",
                done = isTtsDefault,
                body = if (isTtsDefault) {
                    "This app is your device's text-to-speech engine. Other apps' read-aloud now runs offline."
                } else {
                    "Open text-to-speech settings and choose \"MA Speech\" as the preferred engine."
                },
            ) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        .onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                    refresh++
                }) { Text(stringResource(R.string.open_text_to_speech_settings)) }
            }

            TtsTestSection(enabled = ttsModelReady)
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    done: Boolean,
    body: String,
    action: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$index. $title" + if (done) "  ✓" else "",
                fontWeight = FontWeight.Bold,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

/**
 * A button that runs a suspending model [download] (progress polled from DataStore via
 * [progressOf]) and calls [onDone] when finished so the caller can refresh its "installed"
 * status. Disabled while downloading.
 */
@Composable
private fun ModelDownloadButton(
    label: String,
    progressOf: () -> Float,
    download: suspend () -> Unit,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pct by remember { mutableIntStateOf(0) }
    LaunchedEffect(busy) {
        while (busy) {
            pct = (progressOf() * 100f).toInt().coerceIn(0, 100)
            delay(500)
        }
    }
    Button(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                runCatching { download() }
                busy = false
                onDone()
            }
        },
    ) { Text(if (busy) "Downloading… $pct%" else label) }
}

@Composable
private fun TestSection(enabled: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_it), fontWeight = FontWeight.Bold)
            Button(
                enabled = enabled,
                onClick = {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        status = "No recognizer selected yet (finish step 2)."
                        return@Button
                    }
                    result = ""
                    status = "Listening…"
                    val sr = SpeechRecognizer.createSpeechRecognizer(context)
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { status = "Transcribing…" }
                        override fun onError(error: Int) { status = "Error ($error)"; sr.destroy() }
                        override fun onResults(results: Bundle?) {
                            result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty()
                            status = ""
                            sr.destroy()
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    }
                    sr.startListening(intent)
                },
            ) { Text(stringResource(R.string.test_microphone)) }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            if (result.isNotBlank()) Text(stringResource(R.string.heard, result))
        }
    }
}

@Composable
private fun TtsTestSection(enabled: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    // Hold the engine across recompositions and release it when leaving the screen.
    val engine = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        onDispose { engine.value?.shutdown(); engine.value = null }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_the_voice), fontWeight = FontWeight.Bold)
            Button(
                enabled = enabled,
                onClick = {
                    status = "Loading…"
                    engine.value?.shutdown()
                    // Force OUR engine (by package) so this tests Piper, not the system default.
                    var tts: TextToSpeech? = null
                    tts = TextToSpeech(
                        context,
                        { st ->
                            if (st == TextToSpeech.SUCCESS) {
                                tts?.setLanguage(java.util.Locale.US)
                                tts?.speak(
                                    "Hello, this is the offline Piper voice.",
                                    TextToSpeech.QUEUE_FLUSH, null, "sample",
                                )
                                status = ""
                            } else {
                                status = "Engine failed to start."
                            }
                        },
                        context.packageName,
                    )
                    engine.value = tts
                },
            ) { Text(stringResource(R.string.speak_sample)) }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        }
    }
}
