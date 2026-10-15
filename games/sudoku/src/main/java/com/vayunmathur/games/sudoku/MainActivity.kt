package com.vayunmathur.games.sudoku

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.sudoku.platform.SudokuViewModel
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    @Composable
    override fun Content() {
        val viewModel: SudokuViewModel = viewModel()
        Navigation(viewModel)
    }
}
