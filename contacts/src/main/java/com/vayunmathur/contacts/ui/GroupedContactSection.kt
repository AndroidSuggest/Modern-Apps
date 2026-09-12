package com.vayunmathur.contacts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface

@Composable
fun GroupedContactSection(
    count: Int,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    topAttached: Boolean = false,
    row: @Composable (index: Int) -> Unit,
) {
    if (count == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until count) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = groupShape(i, count, flatTop = topAttached && i == 0),
                color = containerColor,
            ) { row(i) }
        }
    }
}
