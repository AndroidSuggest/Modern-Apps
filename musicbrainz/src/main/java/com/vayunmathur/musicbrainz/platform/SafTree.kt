package com.vayunmathur.musicbrainz.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** One entry in the user's music folder. */
data class DocEntry(
    val documentId: String,
    val name: String,
    val uri: Uri,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * Storage Access Framework access to the folder the user nominated as their music library.
 *
 * Raw [DocumentsContract] queries rather than `DocumentFile`: a library scan reads every
 * file in the tree, and `DocumentFile` costs a separate query per attribute per file.
 * Everything here blocks on the content resolver, so callers stay off the main thread.
 */
object SafTree {

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun listChildren(context: Context, treeUri: Uri, parentDocumentId: String): List<DocEntry> {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val result = ArrayList<DocEntry>()
        context.contentResolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val columns = ChildColumns(cursor)
            while (cursor.moveToNext()) {
                readChild(cursor, columns, treeUri)?.let { result.add(it) }
            }
        }
        return result
    }

    private class ChildColumns(cursor: android.database.Cursor) {
        val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val size = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        val modified =
            cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }

    private fun readChild(
        cursor: android.database.Cursor,
        columns: ChildColumns,
        treeUri: Uri,
    ): DocEntry? {
        val docId = cursor.getString(columns.id) ?: return null
        val name = cursor.getString(columns.name) ?: return null
        val mime = cursor.getString(columns.mime)
        return DocEntry(
            documentId = docId,
            name = name,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            size = if (cursor.isNull(columns.size)) 0L else cursor.getLong(columns.size),
            lastModified = if (cursor.isNull(columns.modified)) {
                0L
            } else {
                cursor.getLong(columns.modified)
            },
        )
    }

    fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Every file in the tree, depth first.
     *
     * [onFile] is called as the walk proceeds rather than the whole list being returned,
     * so a large library does not have to be held in memory at once. Directories are
     * followed only as deep as [maxDepth]; a music folder nested deeper than that is
     * almost certainly a symlink loop or a misplaced pick like the storage root.
     */
    fun walkFiles(
        context: Context,
        treeUri: Uri,
        maxDepth: Int = 8,
        onFile: (DocEntry) -> Unit,
    ) {
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootDocumentId(treeUri) to 0)
        while (queue.isNotEmpty()) {
            val (docId, depth) = queue.removeFirst()
            collectFiles(context, treeUri, docId, depth, maxDepth, queue, onFile)
        }
    }

    private fun collectFiles(
        context: Context,
        treeUri: Uri,
        docId: String,
        depth: Int,
        maxDepth: Int,
        queue: ArrayDeque<Pair<String, Int>>,
        onFile: (DocEntry) -> Unit,
    ) {
        for (entry in listChildren(context, treeUri, docId)) {
            if (!entry.isDirectory) {
                onFile(entry)
            } else if (depth < maxDepth) {
                queue.add(entry.documentId to depth + 1)
            }
        }
    }

    /** Finds a direct child by name, or creates it as a directory. */
    fun findOrCreateDirectory(context: Context, treeUri: Uri, parentDocumentId: String, name: String): String? {
        val existing = listChildren(context, treeUri, parentDocumentId)
            .firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.documentId
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    /** Resolves (creating as needed) a chain of nested directories under the tree root. */
    fun ensurePath(context: Context, treeUri: Uri, segments: List<String>): String? {
        var current = rootDocumentId(treeUri)
        for (segment in segments) {
            current = findOrCreateDirectory(context, treeUri, current, segment) ?: return null
        }
        return current
    }

    /**
     * Creates a file, replacing any existing one with the same name.
     *
     * The delete-first matters: providers append " (1)" to a clashing name rather than
     * overwriting, so re-downloading a track would otherwise pile up duplicates that the
     * library scan then reports as separate copies.
     */
    fun createFile(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
        name: String,
        mimeType: String,
    ): Uri? {
        listChildren(context, treeUri, parentDocumentId)
            .firstOrNull { !it.isDirectory && it.name == name }
            ?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        return DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, name)
    }

    /** Strips the characters document providers reject, so a title can become a filename. */
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
            .take(MAX_FILENAME_LENGTH)
            .ifEmpty { "Unknown" }

    private const val MAX_FILENAME_LENGTH = 120
}
