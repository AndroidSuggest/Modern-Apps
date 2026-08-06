package com.vayunmathur.appstore.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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

    init {
        scope.launch {
            context.settingsDataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .map { it[KEY_BACKGROUND_UPDATE_INSTALL] ?: DEFAULT_BACKGROUND_UPDATE_INSTALL }
                .collect { _backgroundUpdateInstall.value = it }
        }
    }

    suspend fun setBackgroundUpdateInstall(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_BACKGROUND_UPDATE_INSTALL] = enabled }
    }

    companion object {
        const val DEFAULT_BACKGROUND_UPDATE_INSTALL = true
        private val KEY_BACKGROUND_UPDATE_INSTALL =
            booleanPreferencesKey("background_update_installation")
    }
}
