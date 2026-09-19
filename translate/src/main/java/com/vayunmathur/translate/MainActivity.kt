package com.vayunmathur.translate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.translate.platform.MadladModel
import com.vayunmathur.translate.platform.NllbModel
import com.vayunmathur.translate.platform.TranslateViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: TranslateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        enableEdgeToEdge()
        val initialText = processTextFromIntent(intent)
        val ds = DataStoreUtils.getInstance(this)
        setContent {
            DynamicTheme {
                // NLLB only: MADLAD's files are skipped so the app does not download ~1 GB
                // it cannot yet use (Q2_K decode is too slow on-device — see the bring-up
                // notes). Re-add `+ MadladModel.FILES` when the engine selector lands.
                InitialModelDownloadChecker(ds, NllbModel.FILES) {
                    Navigation(viewModel, initialText)
                }
            }
        }
    }

    private fun processTextFromIntent(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) return ""
        return intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    }
}
