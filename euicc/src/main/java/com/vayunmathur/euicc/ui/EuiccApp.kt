package com.vayunmathur.euicc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun EuiccApp(viewModel: EuiccViewModel) {
    AppScaffold(
        title = "EUICC",
        actions = {
            IconButton(onClick = viewModel::refresh) { IconRefresh() }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val state = viewModel.state) {
                is EuiccUiState.Loading -> CircularProgressIndicator()
                is EuiccUiState.Error -> Text(state.message)
                is EuiccUiState.Ready -> ReadyContent(state)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: EuiccUiState.Ready) {
    LabeledCard(label = "EID") {
        Text(state.eid ?: "unavailable")
    }
    val info = state.info
    if (info != null) {
        LabeledCard(label = "eUICC info") {
            Text("SGP.22 version: ${info.svn.ifEmpty { "unknown" }}")
            KeyIdList("CI keys (verification)", info.ciPkIdListForVerification)
            KeyIdList("CI keys (signing)", info.ciPkIdListForSigning)
        }
    }
}

@Composable
private fun KeyIdList(label: String, ids: List<String>) {
    if (ids.isEmpty()) return
    Text(label, style = MaterialTheme.typography.labelMedium)
    for (id in ids) {
        Text(id, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LabeledCard(label: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
