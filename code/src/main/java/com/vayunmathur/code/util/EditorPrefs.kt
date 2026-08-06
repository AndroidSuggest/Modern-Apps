package com.vayunmathur.code.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.editorDataStore: DataStore<Preferences> by preferencesDataStore(name = "code_editor")

/**
 * Persists the small amount of state that should survive relaunches: the last opened folder
 * tree (so the file browser reopens where it was) and the editor preferences (soft-wrap, font
 * size, tab width, theme mode and the smart-input toggles).
 */
class EditorPrefs(context: Context) {

    private val appContext = context.applicationContext

    val folderUri: Flow<String?> = appContext.editorDataStore.data.map { it[FOLDER_URI_KEY] }
    val softWrap: Flow<Boolean> = appContext.editorDataStore.data.map { it[SOFT_WRAP_KEY] ?: false }
    val fontSize: Flow<Int> = appContext.editorDataStore.data.map { it[FONT_SIZE_KEY] ?: DEFAULT_FONT_SIZE }
    val tabWidth: Flow<Int> = appContext.editorDataStore.data.map { it[TAB_WIDTH_KEY] ?: DEFAULT_TAB_WIDTH }
    val themeMode: Flow<String> = appContext.editorDataStore.data.map { it[THEME_MODE_KEY] ?: THEME_SYSTEM }
    val autoIndent: Flow<Boolean> = appContext.editorDataStore.data.map { it[AUTO_INDENT_KEY] ?: true }
    val autoCloseBrackets: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[AUTO_CLOSE_KEY] ?: true }

    suspend fun setFolderUri(uri: String) {
        appContext.editorDataStore.edit { it[FOLDER_URI_KEY] = uri }
    }

    suspend fun clearFolderUri() {
        appContext.editorDataStore.edit { it.remove(FOLDER_URI_KEY) }
    }

    suspend fun setSoftWrap(enabled: Boolean) {
        appContext.editorDataStore.edit { it[SOFT_WRAP_KEY] = enabled }
    }

    suspend fun setFontSize(size: Int) {
        appContext.editorDataStore.edit { it[FONT_SIZE_KEY] = size }
    }

    suspend fun setTabWidth(width: Int) {
        appContext.editorDataStore.edit { it[TAB_WIDTH_KEY] = width }
    }

    suspend fun setThemeMode(mode: String) {
        appContext.editorDataStore.edit { it[THEME_MODE_KEY] = mode }
    }

    suspend fun setAutoIndent(enabled: Boolean) {
        appContext.editorDataStore.edit { it[AUTO_INDENT_KEY] = enabled }
    }

    suspend fun setAutoCloseBrackets(enabled: Boolean) {
        appContext.editorDataStore.edit { it[AUTO_CLOSE_KEY] = enabled }
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val DEFAULT_TAB_WIDTH = 4
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private val FOLDER_URI_KEY = stringPreferencesKey("folder_uri")
        private val SOFT_WRAP_KEY = booleanPreferencesKey("soft_wrap")
        private val FONT_SIZE_KEY = intPreferencesKey("font_size")
        private val TAB_WIDTH_KEY = intPreferencesKey("tab_width")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val AUTO_INDENT_KEY = booleanPreferencesKey("auto_indent")
        private val AUTO_CLOSE_KEY = booleanPreferencesKey("auto_close_brackets")
    }
}
