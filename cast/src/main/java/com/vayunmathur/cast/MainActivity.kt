package com.vayunmathur.cast

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.cast.platform.CastViewModel
import com.vayunmathur.cast.platform.CastViewModelFactory
import com.vayunmathur.library.ui.MaActivity

/**
 * The app's only Activity.
 *
 * Has to survive the move to a Quick Settings tile whatever else changes: it is the host the
 * screen-capture consent dialog needs, and the tile's long-press target.
 */
class MainActivity : MaActivity() {

    private val viewModel: CastViewModel by viewModels { CastViewModelFactory(application) }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
