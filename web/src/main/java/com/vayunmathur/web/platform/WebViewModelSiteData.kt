package com.vayunmathur.web.platform

import android.net.Uri
import com.vayunmathur.web.data.DownloadEntry
import com.vayunmathur.web.data.StorageInfo
import kotlinx.coroutines.launch

// ---- File chooser ----

fun WebViewModel.requestFileChooser(
    callback: android.webkit.ValueCallback<Array<Uri>>,
    params: android.webkit.WebChromeClient.FileChooserParams
) {
    pendingFileChooser = callback to params
}

fun WebViewModel.clearFileChooser() {
    pendingFileChooser?.first?.onReceiveValue(null)
    pendingFileChooser = null
}

fun WebViewModel.deliverFileChooserResult(uris: Array<Uri>?) {
    pendingFileChooser?.first?.onReceiveValue(uris)
    pendingFileChooser = null
}

// ---- Site storage footprint ----

fun WebViewModel.updateStorageFootprint(
    origin: String,
    cookieCount: Int,
    hasLocalStorage: Boolean,
    hasIndexedDb: Boolean,
    hasServiceWorker: Boolean,
    estBytes: Long
) {
    scope.launch {
        val existing = repository.storage.byOrigin(origin)
        val info = if (existing != null) {
            existing.copy(
                cookieCount = cookieCount,
                hasLocalStorage = hasLocalStorage || existing.hasLocalStorage,
                hasIndexedDb = hasIndexedDb || existing.hasIndexedDb,
                hasServiceWorker = hasServiceWorker || existing.hasServiceWorker,
                estimatedBytes = if (estBytes > 0) estBytes else existing.estimatedBytes,
                lastSeen = System.currentTimeMillis()
            )
        } else {
            StorageInfo(
                origin = origin,
                host = BrowserUtils.hostFromUrl(origin),
                cookieCount = cookieCount,
                hasLocalStorage = hasLocalStorage,
                hasIndexedDb = hasIndexedDb,
                hasServiceWorker = hasServiceWorker,
                estimatedBytes = estBytes,
                lastSeen = System.currentTimeMillis()
            )
        }
        repository.storage.upsert(info)
    }
}

fun WebViewModel.clearSiteData(origin: String) {
    scope.launch {
        repository.storage.deleteOrigin(origin)
        repository.permissions.deleteOrigin(origin)
    }
}

fun WebViewModel.clearAllSiteData() {
    scope.launch {
        repository.storage.clearAll()
        repository.permissions.clearAll()
    }
}

// ---- Downloads ----

fun WebViewModel.addDownload(url: String, fileName: String, mime: String?, length: Long) {
    scope.launch {
        val entry = DownloadEntry(url = url, fileName = fileName, mimeType = mime, contentLength = length)
        repository.downloads.upsert(entry)
    }
}

fun WebViewModel.clearAllDownloads() {
    scope.launch { repository.downloads.clearAll() }
}
