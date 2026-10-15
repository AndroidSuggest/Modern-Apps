package com.vayunmathur.games.minesweeper

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.minesweeper.platform.MinesweeperViewModel
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        val viewModel: MinesweeperViewModel = viewModel()
        Navigation(viewModel)
    }
}
