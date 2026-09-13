package com.vayunmathur.pdf.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Launch functions owned by the screen, passed down to chrome and page list. */
internal class SafePdfLauncherSet(
    val saveCopy: (String) -> Unit,
    val saveEncrypted: () -> Unit,
    val pickImage: () -> Unit,
)

/**
 * Screen-level launchers: save-copy, encrypted-save and image-stamp pickers.
 * Logic is verbatim from [SafePdfViewerScreen]'s body; only the surrounding
 * structure changed so the screen stays under the FileLength limit.
 */
@Composable
internal fun rememberSafePdfLaunchers(
    onSaveCopyPicked: (android.net.Uri) -> Unit,
    onSaveEncryptedPicked: (android.net.Uri) -> Unit,
    onImagePicked: (android.net.Uri?) -> Unit,
): SafePdfLauncherSet {
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let(onSaveCopyPicked) }
    val encryptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> uri?.let(onSaveEncryptedPicked) }
    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
        onImagePicked,
    )
    return remember(saveLauncher, encryptLauncher, imageLauncher) {
        SafePdfLauncherSet(
            saveCopy = { name -> saveLauncher.launch(name) },
            saveEncrypted = { encryptLauncher.launch("encrypted.pdf") },
            pickImage = { imageLauncher.launch("image/*") },
        )
    }
}
