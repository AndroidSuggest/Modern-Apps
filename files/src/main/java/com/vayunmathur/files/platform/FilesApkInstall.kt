package com.vayunmathur.files.platform

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.files.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * The APK "install unknown apps" permission flow behind [FilesViewModel], as `internal`
 * extensions so FilesViewModel.kt stays under the FileLength limit. State
 * ([FilesViewModel.pendingApkInstall], [FilesViewModel.installPermissionRequests]) stays on
 * the ViewModel; only the method bodies live here.
 */

internal fun FilesViewModel.installApkFile(item: FileBrowserItem) {
    val ctx = getApplication<Application>()
    if (isZipMode()) {
        emit(ctx.getString(R.string.zip_browse_only))
        return
    }
    val file = item.realFile ?: return
    if (ctx.packageManager.canRequestPackageInstalls()) {
        launchApkInstallFile(file)
    } else {
        pendingApkInstall = file
        emit(ctx.getString(R.string.install_permission_needed))
        _installPermissionRequests.tryEmit(Unit)
    }
}

/** Called by the UI after returning from the "install unknown apps" settings screen. */
internal fun FilesViewModel.onApkInstallPermissionResult() {
    val ctx = getApplication<Application>()
    val file = pendingApkInstall ?: return
    pendingApkInstall = null
    if (ctx.packageManager.canRequestPackageInstalls()) {
        launchApkInstallFile(file)
    } else {
        emit(ctx.getString(R.string.install_permission_denied))
    }
}

private fun FilesViewModel.launchApkInstallFile(file: File) {
    val ctx = getApplication<Application>()
    val uri = try {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    } catch (e: Exception) {
        emitMoveFailed(e)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    viewModelScope.launch { _intents.emit(intent) }
}
