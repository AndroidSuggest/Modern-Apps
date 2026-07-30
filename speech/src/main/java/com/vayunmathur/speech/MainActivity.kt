package com.vayunmathur.speech

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import com.vayunmathur.speech.service.WhisperRecognitionService
import com.vayunmathur.speech.util.WhisperModel

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
    val modelReady = WhisperModel.isReady(context)

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    Scaffold(topBar = { TopAppBar(title = { Text("Speech Recognizer") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Offline, on-device speech recognition (Whisper, ~99 languages) for the whole " +
                    "system. No internet, no Google — works on GrapheneOS.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 1) Bundled offline model
            StepCard(
                index = 1,
                title = "Offline model",
                done = modelReady,
                body = if (modelReady) {
                    "The Whisper model is bundled with the app and ready — no download, runs fully offline."
                } else {
                    "Model missing from this build. Run scripts/speech/fetch_whisper_model.sh and reinstall."
                },
            ) {}

            // 2) Microphone permission
            StepCard(
                index = 2,
                title = "Microphone access",
                done = hasMic,
                body = "Needed to record your voice for transcription.",
            ) {
                if (!hasMic) {
                    Button(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text("Grant microphone")
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
                    "Open voice-input settings and choose \"Speech Recognizer\" as the on-device / " +
                        "voice-input service so other apps (like Translate) use it."
                },
            ) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                        .onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                    refresh++
                }) { Text("Open voice input settings") }
            }

            // 3) Try it
            TestSection(enabled = hasMic)
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

@Composable
private fun TestSection(enabled: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Try it", fontWeight = FontWeight.Bold)
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
            ) { Text("Test microphone") }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            if (result.isNotBlank()) Text("Heard: $result")
        }
    }
}
