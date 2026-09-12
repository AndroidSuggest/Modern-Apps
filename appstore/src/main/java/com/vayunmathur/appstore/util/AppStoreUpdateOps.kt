package com.vayunmathur.appstore.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.SandboxedGooglePlay
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.grapheneos.toUnifiedApp
import com.vayunmathur.appstore.data.installer.InstallFailureBatch
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Updates ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

internal fun AppStoreViewModel.checkForUpdatesImpl() {
    if (_isCheckingUpdates.value) return
    viewModelScope.launch {
        val enabled = enabledSources.value
        _isCheckingUpdates.value = true
        _statusMessage.value = context.getString(R.string.updates_checking)

        installedRepo.refresh()
        _catalogUpdates.value = catalog.updatesFor(installedRepo.updatable.value)

        // Only ask Play about packages neither offline source lists — for the rest the
        // catalogue already answered, and Play would just re-answer it over the network.
        // The Sandboxed Google Play components are held back too: Play hosts newer builds
        // of all three, but only GrapheneOS's are the ones this device can use, so their
        // updates come from its signed index below instead.
        val index = catalog.packageIndex.value
        val installed = installedRepo.updatable.value
        if (AppSource.PLAYSTORE in enabled) {
            val playCandidates = installed
                .filter {
                    it.packageName !in index &&
                        it.packageName !in SandboxedGooglePlay.PACKAGES
                }
                .map { it.packageName }

            val remote = play.details(playCandidates).associateBy { it.packageName }
            _playUpdates.value = installed.mapNotNull { inst ->
                remote[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
            }
            // The same response tells us which of these packages Play actually hosts, which
            // the library uses to tell a genuine Play app from a sideloaded one.
            if (remote.isNotEmpty()) _playInstalledPackages.value = remote.keys.toSet()
        }

        // Accrescent: refresh the signed allowlist, then ask its API for a newer build of
        // each installed package it vouches for.
        if (AppSource.ACCRESCENT in enabled) {
            accrescent.refreshRepoData()
            val accrescentIds = accrescent.appIds()
            if (accrescentIds.isNotEmpty()) _accrescentPackages.value = accrescentIds
            _accrescentUpdates.value = installed
                .filter { it.packageName in accrescentIds }
                .mapNotNull { inst -> accrescentUpdate(inst.packageName, inst.versionCode) }
        }

        // GrapheneOS: its signed index is the only place a Sandboxed Google Play update
        // can come from, which is why the three are held back from the Play list above.
        _grapheneOSUpdates.value = grapheneOS.packages.mapNotNull { entry ->
            val current = installed.firstOrNull { it.packageName == entry.packageName }
                ?: return@mapNotNull null
            entry.toUnifiedApp().takeIf { it.versionCode > current.versionCode }
        }

        _lastUpdateCheck.value = System.currentTimeMillis()
        _statusMessage.value = ""
        _isCheckingUpdates.value = false
    }
}

/**
 * Update everything, and report the run once when it is over.
 *
 * Sequential on purpose: updates this store isn't the update owner of still get a
 * system confirmation dialog, and firing them concurrently buries the user in prompts.
 *
 * The reporting is the other half of issue #630. A run used to say nothing itself and
 * let each app's failure raise its own snackbar, so a phone whose updates were all
 * failing the same way — Play refusing the lot for going too fast, typically — showed
 * the user the same error once per app. Now the failures are collected and summarised,
 * and a reason that keeps coming back ends the run rather than being demonstrated
 * another dozen times.
 */
internal fun AppStoreViewModel.updateAllImpl() {
    if (updateAllJob?.isActive == true) return
    updateAllJob = viewModelScope.launch {
        val batch = updates.value
        if (batch.isEmpty()) return@launch
        val failures = mutableListOf<String>()
        var started = 0
        var repeats = 0
        var previousReason: String? = null
        InstallFailureBatch.begin()
        try {
            for ((index, app) in batch.withIndex()) {
                _statusMessage.value = context.getString(
                    R.string.updates_progress, app.name, index + 1, batch.size
                )
                val outcome = installer.install(app)
                if (outcome.started) {
                    started++
                    previousReason = null
                    repeats = 0
                    continue
                }
                val reason = outcome.verification.reason()
                failures += reason
                repeats = if (reason == previousReason) repeats + 1 else 1
                previousReason = reason
                if (repeats >= REPEATED_FAILURE_LIMIT) break
            }
            // PackageInstaller commits asynchronously, so the last app's real verdict
            // is still on its way. Wait for it before summarising, or it lands on its
            // own afterwards — which is the repetition this is here to stop.
            delay(INSTALL_SETTLE_MS)
        } finally {
            _statusMessage.value = ""
            failures += InstallFailureBatch.end()
        }

        AppMessages.show(
            if (failures.isEmpty()) {
                context.resources.getQuantityString(
                    R.plurals.updates_batch_installed, started, started
                )
            } else {
                context.getString(
                    R.string.updates_batch_partial,
                    started,
                    batch.size,
                    failures.first(),
                )
            },
            duration = if (failures.isEmpty()) {
                AppMessages.Duration.Short
            } else {
                AppMessages.Duration.Long
            },
        )
        installedRepo.refresh()
    }
}

/** What to tell the user a failed install went wrong with. */
internal fun AppStoreViewModel.reason(verification: VerificationResult): String = when (verification) {
    is VerificationResult.Rejected -> verification.reason
    is VerificationResult.Unverified -> verification.reason
    is VerificationResult.Verified -> context.getString(R.string.install_failure_unknown)
}

/**
 * The available Accrescent update for [packageName] as an installable listing, or null when
 * there is none. The version code is the update's, so the [updates] filter keeps it only
 * while it is genuinely newer than what is installed.
 */
internal suspend fun AppStoreViewModel.accrescentUpdate(packageName: String, currentVersionCode: Long): UnifiedApp? {
    val update = runCatching {
        accrescent.updateInfo(packageName, currentVersionCode)
    }.getOrNull() ?: return null
    val details = accrescent.details(packageName) ?: UnifiedApp(
        packageName = packageName,
        source = AppSource.ACCRESCENT,
        name = packageName.substringAfterLast('.'),
    )
    return details.copy(versionCode = update.versionCode, versionName = update.versionName)
}

/**
 * Refresh Accrescent's signed allowlist and its home listings. Both fail soft: a network
 * blip leaves the previous rows and attribution set in place rather than emptying them.
 */
internal suspend fun AppStoreViewModel.loadAccrescent(enabled: Set<AppSource> = enabledSources.value) {
    if (AppSource.ACCRESCENT !in enabled) return
    accrescent.refreshRepoData()
    val ids = accrescent.appIds()
    if (ids.isNotEmpty()) _accrescentPackages.value = ids
    val page = accrescent.listApps()
    if (page.apps.isNotEmpty()) _accrescentApps.value = page.apps.take(CAROUSEL_LIMIT)
}
