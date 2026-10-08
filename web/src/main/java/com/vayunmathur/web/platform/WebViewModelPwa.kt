package com.vayunmathur.web.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.web.data.InstalledSite
import kotlinx.coroutines.launch

private const val TAG = "WebViewModel"

// ---- PWA / Installed sites ----

fun WebViewModel.onPwaInfoDetected(tabId: String, info: PwaInfo) {
    pwaInfos[tabId] = info
}

fun WebViewModel.getPwaInfo(tabId: String) = pwaInfos[tabId]

fun WebViewModel.installAsPwa(
    tabId: String,
    url: String,
    pwaInfo: PwaInfo?,
    onResult: (Boolean) -> Unit = {},
) {
    scope.launch {
        runCatching {
            val origin = BrowserUtils.originFromUrl(url)
            val id = PwaHelper.shortcutId(url)
            val title = PwaHelper.displayTitle(pwaInfo, tabs.find { it.id == tabId }?.title ?: "", url)
            val entry = InstalledSite(
                id = id,
                url = url,
                title = title,
                shortName = pwaInfo?.shortName ?: "",
                iconUrl = pwaInfo?.iconUrl,
                faviconUrl = pwaInfo?.faviconUrl,
                themeColor = pwaInfo?.themeColor,
                backgroundColor = pwaInfo?.backgroundColor,
                displayMode = pwaInfo?.displayMode ?: "standalone",
                startUrl = pwaInfo?.startUrl ?: url,
                origin = origin,
            )
            repository.installed.upsert(entry)
            PwaHelper.requestPinShortcut(
                context = context,
                url = url,
                title = title,
                iconUrl = entry.iconUrl,
                faviconUrl = entry.faviconUrl,
            )
        }.onSuccess { accepted ->
            onResult(accepted)
        }.onFailure { e ->
            Log.error(TAG, "installAsPwa failed", e)
            onResult(false)
        }
    }
}

fun WebViewModel.removeInstalledSite(id: String) {
    scope.launch { repository.installed.deleteById(id) }
}
