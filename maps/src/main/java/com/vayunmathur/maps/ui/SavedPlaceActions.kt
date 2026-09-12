package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconWork
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.util.PlacePanelActions
import com.vayunmathur.maps.util.PlacePanelState
import com.vayunmathur.maps.util.SavedPlacesViewModel

/**
 * Two chips under a place's details letting the user pin it to Home or Work.
 * If the place is already saved in a slot, the chip is selected and tapping it
 * again removes it, so the same control handles setting, replacing and clearing.
 */
@Composable
fun SavedPlaceActions(
    feature: SpecificFeature.RoutableFeature,
    savedPlacesViewModel: SavedPlacesViewModel,
) {
    val home by savedPlacesViewModel.home.collectAsState()
    val work by savedPlacesViewModel.work.collectAsState()
    val panelActions = remember(feature) {
        object : PlacePanelActions {
            override fun setHome() {
                savedPlacesViewModel.setHome(feature)
            }
            override fun clearHome() {
                savedPlacesViewModel.clearHome()
            }
            override fun setWork() {
                savedPlacesViewModel.setWork(feature)
            }
            override fun clearWork() {
                savedPlacesViewModel.clearWork()
            }
        }
    }
    SavedPlaceActions(
        feature = feature,
        panelState = PlacePanelState(home = home, work = work),
        panelActions = panelActions,
    )
}

/**
 * Stateless Home/Work chips: the same control as [SavedPlaceActions], driven
 * by [PlacePanelState] instead of the ViewModel, so previews can render it
 * with literal state.
 */
@Composable
fun SavedPlaceActions(
    feature: SpecificFeature.RoutableFeature,
    panelState: PlacePanelState,
    panelActions: PlacePanelActions,
) {
    val isHome = panelState.isHome(feature)
    val isWork = panelState.isWork(feature)

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = isHome,
            onClick = { if (isHome) panelActions.clearHome() else panelActions.setHome() },
            label = {
                Text(stringResource(if (isHome) R.string.remove_from_home else R.string.save_as_home))
            },
            leadingIcon = { IconHome(Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = isWork,
            onClick = { if (isWork) panelActions.clearWork() else panelActions.setWork() },
            label = {
                Text(stringResource(if (isWork) R.string.remove_from_work else R.string.save_as_work))
            },
            leadingIcon = { IconWork(Modifier.size(18.dp)) },
        )
    }
}
