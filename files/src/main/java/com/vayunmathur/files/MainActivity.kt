package com.vayunmathur.files

import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    private val viewModel: FilesViewModel by viewModels()

    override fun onCreateBeforeContent() {
        handleIntent(intent)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.takeIf { it.type != null } ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            )?.let { viewModel.setIncomingUris(it) }
        }
    }
}
