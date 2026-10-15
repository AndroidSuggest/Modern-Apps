package com.vayunmathur.games.logicgate

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.games.logicgate.ui.LogicGateTheme
import com.vayunmathur.library.ui.MaActivity

class MainActivity : MaActivity() {
    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        LogicGateTheme(content = content)
    }

    @Composable
    override fun Content() {
        val vm: LogicViewModel = viewModel()
        Navigation(vm)
    }
}
