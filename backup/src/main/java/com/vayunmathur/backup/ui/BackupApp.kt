package com.vayunmathur.backup.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Root of the Backup UI: routes to onboarding until a recovery code and a destination
 * are set, then to the dashboard. Owns the SAF folder picker so both flows can select
 * a backup destination.
 */
@Composable
fun BackupApp(viewModel: BackupViewModel) {
    val context = LocalContext.current
    val state = viewModel.state

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.setSafBackend(uri.toString())
        }
    }

    if (state.onboarded) {
        DashboardScreen(
            state = state,
            onPickFolder = { folderPicker.launch(null) },
            onSetWebDav = viewModel::setWebDavBackend,
            onAppBackupToggle = viewModel::setAppBackupEnabled,
            onFileBackupToggle = viewModel::setFileBackupEnabled,
            onBackupNow = viewModel::backupFilesNow,
            onRestoreNow = viewModel::restoreFilesNow,
            onDismissMessages = viewModel::dismissMessages,
        )
    } else {
        OnboardingScreen(
            state = state,
            onPickFolder = { folderPicker.launch(null) },
            onSetWebDav = viewModel::setWebDavBackend,
            onGenerate = viewModel::generateRecoveryCode,
            onConfirmNew = viewModel::confirmNewCode,
            onRestoreWithCode = viewModel::restoreWithCode,
            onDismissMessages = viewModel::dismissMessages,
        )
    }
}
