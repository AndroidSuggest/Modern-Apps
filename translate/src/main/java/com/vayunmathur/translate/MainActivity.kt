package com.vayunmathur.translate

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.translate.platform.MadladModel
import com.vayunmathur.translate.platform.NllbModel
import com.vayunmathur.translate.platform.TranslateViewModel

class MainActivity : MaActivity() {
    private val viewModel: TranslateViewModel by viewModels()
    private var initialText: String = ""

    override fun onCreateBeforeContent() {
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        initialText = processTextFromIntent(intent)
    }

    @Composable
    override fun Content() {
        val ds = DataStoreUtils.getInstance(this)
        // NLLB only: MADLAD's files are skipped so the app does not download ~1 GB
        // it cannot yet use (Q2_K decode is too slow on-device — see the bring-up
        // notes). Re-add `+ MadladModel.FILES` when the engine selector lands.
        InitialModelDownloadChecker(ds, NllbModel.FILES) {
            Navigation(viewModel, initialText)
        }
    }

    private fun processTextFromIntent(intent: Intent?): String {
        if (intent?.action != Intent.ACTION_PROCESS_TEXT) return ""
        return intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
    }
}
