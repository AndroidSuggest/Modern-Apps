package com.vayunmathur.code.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract

/**
 * A single entry (file or directory) discovered through the Storage Access Framework.
 *
 * We keep the [documentId] around because child enumeration and document-URI
 * construction are both keyed off document ids rather than the URIs themselves.
 */
data class DocEntry(
    val documentId: String,
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
)

/**
 * Thin wrappers over [DocumentsContract] + [android.content.ContentResolver].
 *
 * All calls here touch the content resolver and may block, so callers run them off
 * the main thread. Nothing is cached: the file tree is rebuilt lazily as folders
 * expand, which keeps large trees cheap and always in sync with the on-disk state.
 */
object SafFiles {

    /** Resolves the root document of a persisted tree URI into a browsable [DocEntry]. */
    fun rootEntry(context: Context, treeUri: Uri): DocEntry {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = queryDisplayName(context, docUri) ?: treeUri.lastPathSegment.orEmpty()
        return DocEntry(rootId, name, docUri, isDirectory = true)
    }

    /**
     * Lists the direct children of [parentDocumentId] inside [treeUri], sorted with
     * directories first and then case-insensitively by name (VS Code's ordering).
     */
    fun listChildren(context: Context, treeUri: Uri, parentDocumentId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val result = ArrayList<DocEntry>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor: Cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                result.add(DocEntry(docId, name, docUri, isDir))
            }
        }
        return result.sortedWith(
            compareByDescending<DocEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    /** Reads a document's display name; used for single-file and external-intent opens. */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Reads the full UTF-8 text of a document. */
    fun readText(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open $uri for reading" }
            return input.bufferedReader().use { it.readText() }
        }
    }

    /**
     * Overwrites a document with [text]. The `"wt"` mode truncates first so shrinking
     * a file doesn't leave stale trailing bytes behind.
     */
    fun writeText(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output) { "Unable to open $uri for writing" }
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }
}
