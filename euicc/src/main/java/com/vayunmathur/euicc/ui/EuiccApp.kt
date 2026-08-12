package com.vayunmathur.euicc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.euicc.EuiccNative
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text

@Composable
fun EuiccApp() {
    AppScaffold(title = "EUICC") { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("eSIM Local Profile Assistant")
            Text("Native core: ${runCatching { EuiccNative.nativeVersion() }.getOrElse { "unavailable" }}")
        }
    }
}
