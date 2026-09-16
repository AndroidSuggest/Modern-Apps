package com.vayunmathur.emergency.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * Answers Settings' search-indexables query so the Safety & emergency rows are searchable.
 *
 * GrapheneOS's `EmergencySearchIndexablesProvider` serves static index data from resources;
 * ours serves an empty result: the rows themselves are declared in the manifest (IA_SETTINGS
 * injection + suggestion alias), which is what makes them resolve and rank. Returning an
 * empty cursor rather than throwing keeps Settings' indexer from logging failures against us.
 */
class EmergencySearchIndexablesProvider : ContentProvider() {

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? =
        Bundle()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(
        arrayOf("rank", "xmlResId", "className", "iconResId", "intentAction", "intentTargetPackage",
            "intentTargetClass", "key", "userId"),
    )

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0
}
