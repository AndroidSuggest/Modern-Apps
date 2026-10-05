package com.vayunmathur.web.platform

import com.vayunmathur.web.data.ShieldSetting
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldsSettings
import com.vayunmathur.web.platform.shields.FarblingConfig
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

// ---- Brave Shields ----

/**
 * Resolved shields for [host]. Safe to call from the WebView render thread: it only
 * touches the concurrent override map, never the Compose-backed tab list.
 *
 * @param isPrivate the owning tab is private, which forces the full aggressive preset
 */
fun WebViewModel.shieldsFor(host: String, isPrivate: Boolean = false): EffectiveShields {
    if (isPrivate || incognito) {
        return EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS)
    }
    return EffectiveShields.resolve(shields, shieldOverrides[host])
}

/**
 * Snapshot of the farbling decision for every site, for the document-start script.
 * Changes to this are what force a script re-registration.
 */
fun WebViewModel.farblingConfig(): FarblingConfig =
    FarblingConfig.of(shields, shieldOverrides.toMap())

fun WebViewModel.updateShields(settings: ShieldsSettings) {
    shields = settings
    persistPrefs()
}

fun WebViewModel.updateSiteShields(host: String, settings: ShieldsSettings) {
    if (host.isBlank()) return
    // Mirror immediately so the next request sees it without waiting for Room.
    if (settings.isEmpty) shieldOverrides.remove(host) else shieldOverrides[host] = settings
    scope.launch {
        if (settings.isEmpty) {
            repository.shields.deleteHost(host)
        } else {
            repository.shields.upsert(ShieldSetting.from(host, settings))
        }
    }
}

fun WebViewModel.clearSiteShields() {
    shieldOverrides.clear()
    scope.launch { repository.shields.clearAll() }
}

/** Called from the render thread on every blocked request; coalesces UI updates. */
fun WebViewModel.onRequestBlocked(tabId: String) {
    blockedTotals.computeIfAbsent(tabId) { AtomicInteger() }
        .incrementAndGet()
    if (blockedPublishPending.putIfAbsent(tabId, true) == null) {
        mainHandler.post {
            blockedPublishPending.remove(tabId)
            blockedCounts[tabId] = blockedTotals[tabId]?.get() ?: 0
        }
    }
}

fun WebViewModel.resetBlockedCount(tabId: String) {
    blockedTotals.remove(tabId)
    blockedCounts[tabId] = 0
}

fun WebViewModel.blockedCount(tabId: String): Int = blockedCounts[tabId] ?: 0
