package com.vayunmathur.appstore.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlinx.coroutines.launch

// ---- Detail ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

fun AppStoreViewModel.selectApp(app: UnifiedApp) {
    detailJob?.cancel()
    _selectedApp.value = app
    detailJob = viewModelScope.launch {
        // Play lists the Sandboxed Google Play components too, so a search hit for one can
        // arrive carrying AppSource.PLAYSTORE. Describe it from GrapheneOS regardless: that
        // is the row an install would actually use, and the Play build is the wrong
        // artifact for the device even though Play would happily deliver it.
        sandboxedGooglePlayRow(app.packageName)?.let { sandboxed ->
            _selectedApp.value = sandboxed
            return@launch
        }
        // The catalogue row wins whenever there is one, even if the user tapped a Play
        // tile for the same package. It is the row an install would actually use — it
        // carries the signer and hash an authenticated index published — so showing
        // the Play listing here would describe a download this store is not going to
        // make. Only F-Droid and Modern Apps rows are ever cached, so this never
        // replaces one Play listing with another.
        val cached = catalog.byPackage(app.packageName)
        if (cached != null) {
            _selectedApp.value = cached
            return@launch
        }
        // Accrescent listings from the home carousel are shells (no version code, no signer
        // yet); fetch the full listing + package info + trust anchor before the page settles.
        if (app.source == AppSource.ACCRESCENT) {
            _isLoadingDetails.value = true
            val details = accrescent.details(app.packageName)
            if (details != null) _selectedApp.value = details
            _isLoadingDetails.value = false
            return@launch
        }
        // Play listings from a cluster are shells: no description, no
        // screenshots, no version code. Fill them in before the page settles.
        if (app.source == AppSource.PLAYSTORE && app.screenshots.isEmpty() &&
            AppSource.PLAYSTORE in enabledSources.value
        ) {
            _isLoadingDetails.value = true
            val details = play.details(app.packageName)
            if (details != null) _selectedApp.value = details
            _isLoadingDetails.value = false
        }
    }
}

/** Open a package the store only knows by name, e.g. from a `market://` link. */
fun AppStoreViewModel.selectPackage(packageName: String) {
    viewModelScope.launch {
        sandboxedGooglePlayRow(packageName)?.let {
            selectApp(it)
            return@launch
        }
        val known = catalog.byPackage(packageName)
        if (known != null) {
            selectApp(known)
            return@launch
        }
        _isLoadingDetails.value = true
        _selectedApp.value = UnifiedApp(
            packageName = packageName,
            source = AppSource.PLAYSTORE,
            name = packageName.substringAfterLast('.'),
        )
        if (AppSource.PLAYSTORE in enabledSources.value) {
            val details = play.details(packageName)
            if (details != null) _selectedApp.value = details
        }
        _isLoadingDetails.value = false
    }
}

fun AppStoreViewModel.clearSelection() {
    detailJob?.cancel()
    _selectedApp.value = null
}

/**
 * The GrapheneOS row for a Sandboxed Google Play component, or null for any other package.
 *
 * Carries whatever [loadHome] enriched the stand-in with, so this is the best row the store
 * holds for GSF, GMS or Vending.
 */
internal fun AppStoreViewModel.sandboxedGooglePlayRow(packageName: String): UnifiedApp? =
    _sandboxedGooglePlay.value.firstOrNull { it.packageName == packageName }
