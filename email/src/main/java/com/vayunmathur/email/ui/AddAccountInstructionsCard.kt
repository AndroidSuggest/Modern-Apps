package com.vayunmathur.email.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.data.ProviderPreset
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

@Composable
internal fun InstructionsCard(preset: ProviderPreset) {
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.how_to_get_your_app_password), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            preset.instructions.forEachIndexed { i, line -> Text("${i + 1}. $line", style = MaterialTheme.typography.bodySmall) }
            preset.appPasswordHelpUrl?.let { url ->
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { openUrl(context, url) }) { Text(stringResource(R.string.open_app_password_help, preset.displayName)) }
            }
        }
    }
}

internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }
}
