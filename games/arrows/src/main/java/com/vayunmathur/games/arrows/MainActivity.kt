package com.vayunmathur.games.arrows

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.arrows.platform.ArrowsViewModel
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        val viewModel: ArrowsViewModel = viewModel()
        Navigation(viewModel)
    }
}
