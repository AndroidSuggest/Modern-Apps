package com.vayunmathur.appstore.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlinx.coroutines.launch

// ---- Detail ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

fun AppStoreViewModel.selectApp(app: UnifiedApp) {
    detailJob?.cancel()
    selectedAppFlow.value = app
    detailJob = viewModelScope.launch {
        // The catalogue row wins whenever there is one, even if the user tapped a Play
        // tile for the same package. It is the row an install would actually use — it
        // carries the signer and hash an authenticated index published — so showing
        // the Play listing here would describe a download this store is not going to
        // make. Only F-Droid and Modern Apps rows are ever cached, so this never
        // replaces one Play listing with another.
        val cached = catalog.byPackage(app.packageName)
        if (cached != null) {
            selectedAppFlow.value = cached
            return@launch
        }
        // Accrescent listings from the home carousel are shells (no version code, no signer
        // yet); fetch the full listing + package info + trust anchor before the page settles.
        if (app.source == AppSource.ACCRESCENT) {
            isLoadingDetailsFlow.value = true
            val details = accrescent.details(app.packageName)
            if (details != null) selectedAppFlow.value = details
            isLoadingDetailsFlow.value = false
            return@launch
        }
        // Play listings from a cluster are shells: no description, no
        // screenshots, no version code. Fill them in before the page settles.
        if (app.source == AppSource.PLAYSTORE && app.screenshots.isEmpty() &&
            AppSource.PLAYSTORE in enabledSources.value
        ) {
            isLoadingDetailsFlow.value = true
            val details = play.details(app.packageName)
            if (details != null) selectedAppFlow.value = details
            isLoadingDetailsFlow.value = false
        }
    }
}

/** Open a package the store only knows by name, e.g. from a `market://` link. */
fun AppStoreViewModel.selectPackage(packageName: String) {
    viewModelScope.launch {
        val known = catalog.byPackage(packageName)
        if (known != null) {
            selectApp(known)
            return@launch
        }
        isLoadingDetailsFlow.value = true
        selectedAppFlow.value = UnifiedApp(
            packageName = packageName,
            source = AppSource.PLAYSTORE,
            name = packageName.substringAfterLast('.'),
        )
        if (AppSource.PLAYSTORE in enabledSources.value) {
            val details = play.details(packageName)
            if (details != null) selectedAppFlow.value = details
        }
        isLoadingDetailsFlow.value = false
    }
}

fun AppStoreViewModel.clearSelection() {
    detailJob?.cancel()
    selectedAppFlow.value = null
}
