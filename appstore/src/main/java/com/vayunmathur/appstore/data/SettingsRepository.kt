package com.vayunmathur.appstore.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore(name = "appstore-settings")

/**
 * User-adjustable preferences for the store.
 *
 * The persisted value is mirrored into a [StateFlow] so the installer, which commits from a
 * plain function, can read the current choice synchronously without blocking on disk. The
 * default is applied on read ([DEFAULT_BACKGROUND_UPDATE_INSTALL]) rather than written at
 * first launch, so an existing user with nothing stored still gets the on-by-default value.
 */
class SettingsRepository(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val _backgroundUpdateInstall = MutableStateFlow(DEFAULT_BACKGROUND_UPDATE_INSTALL)

    /** Whether updates to store-owned apps install without a per-app confirmation prompt. */
    val backgroundUpdateInstall: StateFlow<Boolean> = _backgroundUpdateInstall.asStateFlow()

    private val _autoInstallUpdates = MutableStateFlow(DEFAULT_AUTO_INSTALL_UPDATES)

    /**
     * Whether the periodic update check may also download and install updates on its own,
     * with no tap. Off by default: this installs apps without the user present, so it is
     * strictly opt-in. Only ever acts on updates that install silently (see the worker).
     */
    val autoInstallUpdates: StateFlow<Boolean> = _autoInstallUpdates.asStateFlow()

    init {
        scope.launch {
            context.settingsDataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .collect { prefs ->
                    _backgroundUpdateInstall.value = prefs.backgroundUpdateInstall()
                    _autoInstallUpdates.value = prefs.autoInstallUpdates()
                }
        }
    }

    suspend fun setBackgroundUpdateInstall(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_BACKGROUND_UPDATE_INSTALL] = enabled }
    }

    suspend fun setAutoInstallUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_INSTALL_UPDATES] = enabled }
    }

    /**
     * One-shot read of the persisted values, for callers with no long-lived scope to
     * collect a flow — the [UpdateCheckWorker] is cold-started by WorkManager and needs the
     * committed choice, not the not-yet-populated flow default.
     */
    suspend fun readAutoInstallUpdates(): Boolean = read().autoInstallUpdates()

    suspend fun readBackgroundUpdateInstall(): Boolean = read().backgroundUpdateInstall()

    private suspend fun read(): Preferences =
        context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()

    private fun Preferences.backgroundUpdateInstall(): Boolean =
        this[KEY_BACKGROUND_UPDATE_INSTALL] ?: DEFAULT_BACKGROUND_UPDATE_INSTALL

    private fun Preferences.autoInstallUpdates(): Boolean =
        this[KEY_AUTO_INSTALL_UPDATES] ?: DEFAULT_AUTO_INSTALL_UPDATES

    companion object {
        const val DEFAULT_BACKGROUND_UPDATE_INSTALL = true
        const val DEFAULT_AUTO_INSTALL_UPDATES = false
        private val KEY_BACKGROUND_UPDATE_INSTALL =
            booleanPreferencesKey("background_update_installation")
        private val KEY_AUTO_INSTALL_UPDATES =
            booleanPreferencesKey("auto_install_updates")
    }
}
