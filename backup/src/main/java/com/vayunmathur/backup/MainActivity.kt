package com.vayunmathur.backup

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.backup.platform.BackupViewModel
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    private val viewModel: BackupViewModel by viewModels()

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
