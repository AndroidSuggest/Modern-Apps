package com.vayunmathur.code.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.editorDataStore: DataStore<Preferences> by preferencesDataStore(name = "code_editor")

/**
 * Persists the small amount of state that should survive relaunches: the last opened
 * folder tree (so the file browser reopens where it was) and the soft-wrap toggle.
 */
class EditorPrefs(context: Context) {

    private val appContext = context.applicationContext

    val folderUri: Flow<String?> = appContext.editorDataStore.data.map { it[FOLDER_URI_KEY] }
    val softWrap: Flow<Boolean> = appContext.editorDataStore.data.map { it[SOFT_WRAP_KEY] ?: false }

    suspend fun setFolderUri(uri: String) {
        appContext.editorDataStore.edit { it[FOLDER_URI_KEY] = uri }
    }

    suspend fun clearFolderUri() {
        appContext.editorDataStore.edit { it.remove(FOLDER_URI_KEY) }
    }

    suspend fun setSoftWrap(enabled: Boolean) {
        appContext.editorDataStore.edit { it[SOFT_WRAP_KEY] = enabled }
    }

    private companion object {
        val FOLDER_URI_KEY = stringPreferencesKey("folder_uri")
        val SOFT_WRAP_KEY = booleanPreferencesKey("soft_wrap")
    }
}
