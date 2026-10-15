package com.vayunmathur.games.pipes

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.games.pipes.ui.PipesTheme
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    override fun onCreateBeforeContent() {
        LevelPack.init(this)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        PipesTheme(content = content)
    }

    @Composable
    override fun Content() {
        val viewModel: PipesViewModel = viewModel()
        Navigation(viewModel)
    }
}
