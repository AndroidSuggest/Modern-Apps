package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.solitaire.R

@Composable
fun GameActionBar(
    onUndo: () -> Unit,
    onGiveUp: () -> Unit,
    undoEnabled: Boolean,
    modifier: Modifier = Modifier,
    extraContent: @Composable () -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onUndo, enabled = undoEnabled) {
            Text(stringResource(UiR.string.undo))
        }
        extraContent()
        Button(onClick = onGiveUp) {
            Text(stringResource(R.string.give_up))
        }
    }
}
