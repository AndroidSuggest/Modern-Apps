package com.vayunmathur.code.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vayunmathur.code.syntax.EditorThemes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.editorDataStore: DataStore<Preferences> by preferencesDataStore(name = "code_editor")

/**
 * Persists the small amount of state that should survive relaunches: the last opened folder
 * path (so the file browser reopens where it was) and the editor preferences (soft-wrap, font
 * size, tab width, theme mode and the smart-input toggles).
 */
class EditorPrefs(context: Context) {

    private val appContext = context.applicationContext

    val folderPath: Flow<String?> = appContext.editorDataStore.data.map { it[FOLDER_PATH_KEY] }
    val softWrap: Flow<Boolean> = appContext.editorDataStore.data.map { it[SOFT_WRAP_KEY] ?: false }
    val fontSize: Flow<Int> = appContext.editorDataStore.data.map { it[FONT_SIZE_KEY] ?: DEFAULT_FONT_SIZE }
    val tabWidth: Flow<Int> = appContext.editorDataStore.data.map { it[TAB_WIDTH_KEY] ?: DEFAULT_TAB_WIDTH }
    val themeMode: Flow<String> = appContext.editorDataStore.data.map { it[THEME_MODE_KEY] ?: THEME_SYSTEM }
    val autoIndent: Flow<Boolean> = appContext.editorDataStore.data.map { it[AUTO_INDENT_KEY] ?: true }
    val autoCloseBrackets: Flow<Boolean> =
        appContext.editorDataStore.data.map { it[AUTO_CLOSE_KEY] ?: true }
    val autoSave: Flow<Boolean> = appContext.editorDataStore.data.map { it[AUTO_SAVE_KEY] ?: false }
    val editorTheme: Flow<String> =
        appContext.editorDataStore.data.map { it[EDITOR_THEME_KEY] ?: EditorThemes.DEFAULT }

    /** Open tab file paths, in order, for session restore. */
    val sessionPaths: Flow<List<String>> = appContext.editorDataStore.data.map { prefs ->
        prefs[SESSION_PATHS_KEY]?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** File path of the tab that was in the foreground, for session restore. */
    val sessionCurrent: Flow<String?> = appContext.editorDataStore.data.map { it[SESSION_CURRENT_KEY] }

    // Git remote credentials + author. DataStore is not encrypted; acceptable for F-Droid/local.
    val gitUsername: Flow<String> = appContext.editorDataStore.data.map { it[GIT_USERNAME_KEY] ?: "" }
    val gitToken: Flow<String> = appContext.editorDataStore.data.map { it[GIT_TOKEN_KEY] ?: "" }
    val gitAuthorName: Flow<String> = appContext.editorDataStore.data.map { it[GIT_AUTHOR_NAME_KEY] ?: "" }
    val gitAuthorEmail: Flow<String> = appContext.editorDataStore.data.map { it[GIT_AUTHOR_EMAIL_KEY] ?: "" }

    suspend fun setFolderPath(path: String) {
        appContext.editorDataStore.edit { it[FOLDER_PATH_KEY] = path }
    }

    suspend fun clearFolderPath() {
        appContext.editorDataStore.edit { it.remove(FOLDER_PATH_KEY) }
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

    suspend fun setAutoSave(enabled: Boolean) {
        appContext.editorDataStore.edit { it[AUTO_SAVE_KEY] = enabled }
    }

    suspend fun setEditorTheme(theme: String) {
        appContext.editorDataStore.edit { it[EDITOR_THEME_KEY] = theme }
    }

    /** Persists the open-tabs session; clears the keys when there is nothing open. */
    suspend fun setSession(paths: List<String>, current: String?) {
        appContext.editorDataStore.edit { prefs ->
            if (paths.isEmpty()) prefs.remove(SESSION_PATHS_KEY)
            else prefs[SESSION_PATHS_KEY] = paths.joinToString("\n")
            if (current == null) prefs.remove(SESSION_CURRENT_KEY) else prefs[SESSION_CURRENT_KEY] = current
        }
    }

    suspend fun setGitUsername(value: String) {
        appContext.editorDataStore.edit { it[GIT_USERNAME_KEY] = value }
    }

    suspend fun setGitToken(value: String) {
        appContext.editorDataStore.edit { it[GIT_TOKEN_KEY] = value }
    }

    suspend fun setGitAuthorName(value: String) {
        appContext.editorDataStore.edit { it[GIT_AUTHOR_NAME_KEY] = value }
    }

    suspend fun setGitAuthorEmail(value: String) {
        appContext.editorDataStore.edit { it[GIT_AUTHOR_EMAIL_KEY] = value }
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val DEFAULT_TAB_WIDTH = 4
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        private val FOLDER_PATH_KEY = stringPreferencesKey("folder_path")
        private val SOFT_WRAP_KEY = booleanPreferencesKey("soft_wrap")
        private val FONT_SIZE_KEY = intPreferencesKey("font_size")
        private val TAB_WIDTH_KEY = intPreferencesKey("tab_width")
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        private val AUTO_INDENT_KEY = booleanPreferencesKey("auto_indent")
        private val AUTO_CLOSE_KEY = booleanPreferencesKey("auto_close_brackets")
        private val AUTO_SAVE_KEY = booleanPreferencesKey("auto_save")
        private val EDITOR_THEME_KEY = stringPreferencesKey("editor_theme")
        private val SESSION_PATHS_KEY = stringPreferencesKey("session_paths")
        private val SESSION_CURRENT_KEY = stringPreferencesKey("session_current")
        private val GIT_USERNAME_KEY = stringPreferencesKey("git_username")
        private val GIT_TOKEN_KEY = stringPreferencesKey("git_token")
        private val GIT_AUTHOR_NAME_KEY = stringPreferencesKey("git_author_name")
        private val GIT_AUTHOR_EMAIL_KEY = stringPreferencesKey("git_author_email")
    }
}
