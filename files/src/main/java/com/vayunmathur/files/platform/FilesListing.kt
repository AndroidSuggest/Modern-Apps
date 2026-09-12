package com.vayunmathur.files.platform

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.zip.ZipFile

/**
 * Stateless helpers behind [FilesViewModel]'s home screen and zip browsing, split out
 * so the ViewModel file stays under the FileLength limit. Pure functions where
 * possible; the MediaStore queries take a [Context] instead of reaching for one.
 */

internal fun File.toBrowserItem() = FileBrowserItem(
    name = name,
    isDirectory = isDirectory,
    size = if (isFile) length() else null,
    realFile = this,
    zipInnerPath = null,
    key = absolutePath,
    lastModified = lastModified(),
)

internal fun queryCategoryItems(context: Context, category: FileCategory): List<FileBrowserItem> =
    when (category) {
        FileCategory.IMAGES -> queryMediaItems(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, 2000,
        )
        FileCategory.VIDEOS -> queryMediaItems(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null, null, 2000,
        )
        FileCategory.AUDIO -> queryMediaItems(
            context,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null, 2000,
        )
        FileCategory.DOCUMENTS -> {
            val mimes = arrayOf(
                "application/pdf", "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain", "text/markdown", "text/csv", "application/rtf",
            )
            val selection = mimes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE}=?" }
            queryMediaItems(context, MediaStore.Files.getContentUri("external"), selection, mimes, 2000)
        }
        FileCategory.DOWNLOADS -> emptyList()
    }

internal fun queryRecentItems(context: Context): List<FileBrowserItem> =
    queryMediaItems(context, MediaStore.Files.getContentUri("external"), null, null, 40)

internal fun queryMediaItems(
    context: Context,
    uri: Uri,
    selection: String?,
    args: Array<String>?,
    limit: Int,
): List<FileBrowserItem> {
    val projection = arrayOf(MediaStore.MediaColumns.DATA)
    val sort = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
    val out = mutableListOf<FileBrowserItem>()
    try {
        context.contentResolver.query(uri, projection, selection, args, sort)?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(idx) ?: continue
                val f = File(path)
                if (f.isFile) out.add(f.toBrowserItem())
            }
        }
    } catch (_: Exception) {
    }
    return out
}

internal fun loadBookmarkItems(prefs: android.content.SharedPreferences): List<FileBrowserItem> {
    val saved = prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()
    return saved.map { File(it) }
        .filter { it.exists() }
        .sortedBy { it.name.lowercase() }
        .map { it.toBrowserItem() }
}

/** A destination in [dir] named [name], suffixed with " (n)" if that already exists. */
internal fun uniqueDestination(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (candidate.exists()) {
        candidate = File(dir, "$base ($n)$ext")
        n++
    }
    return candidate
}

internal fun listRealDirItems(
    dir: File,
    showHidden: Boolean,
): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
    val all = dir.listFiles()?.toList() ?: emptyList()
    val visible = if (showHidden) all else all.filterNot { it.name.startsWith(".") }
    val items = visible.map { it.toBrowserItem() }
    return items.partition { it.isDirectory }
}

internal fun listZipDirItems(
    zipFile: File,
    internalDir: String,
): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
    return try {
        ZipFile(zipFile).use { zf ->
            val prefix = if (internalDir.isEmpty()) "" else "$internalDir/"
            val dirMap = mutableMapOf<String, FileBrowserItem>()
            // Keyed, not a list: ZIP allows two entries with the same name, and the key ends up as
            // a Compose item key, where a duplicate crashes the browser.
            val fileMap = mutableMapOf<String, FileBrowserItem>()
            for (entry in zf.entries()) {
                val rawName = entry.name
                val normalized = rawName.trimEnd('/')
                if (normalized.isEmpty()) continue
                if (normalized == internalDir) continue
                if (internalDir.isNotEmpty() && !rawName.startsWith(prefix) && !normalized.startsWith(prefix)) continue
                val remainder = if (prefix.isEmpty()) normalized else {
                    if (normalized.length <= prefix.length) continue
                    normalized.substring(prefix.length)
                }
                if (remainder.isEmpty()) continue
                val slashIdx = remainder.indexOf('/')
                if (slashIdx != -1) {
                    val first = remainder.substring(0, slashIdx)
                    if (first.isEmpty()) continue
                    if (!dirMap.containsKey(first)) {
                        val fullInner = if (internalDir.isEmpty()) first else "$internalDir/$first"
                        dirMap[first] = FileBrowserItem(
                            name = first,
                            isDirectory = true,
                            size = null,
                            realFile = null,
                            zipInnerPath = fullInner,
                            key = "zip:$fullInner",
                        )
                    }
                } else {
                    if (entry.isDirectory) {
                        if (!dirMap.containsKey(remainder)) {
                            val fullInner = if (internalDir.isEmpty()) remainder else "$internalDir/$remainder"
                            dirMap[remainder] = FileBrowserItem(
                                name = remainder,
                                isDirectory = true,
                                size = null,
                                realFile = null,
                                zipInnerPath = fullInner,
                                key = "zip:$fullInner",
                            )
                        }
                    } else {
                        val fullInner = if (internalDir.isEmpty()) remainder else "$internalDir/$remainder"
                        fileMap[remainder] = FileBrowserItem(
                            name = remainder,
                            isDirectory = false,
                            size = entry.size.takeIf { it >= 0 },
                            realFile = null,
                            zipInnerPath = fullInner,
                            key = "zip:$fullInner",
                        )
                    }
                }
            }
            dirMap.values.toList() to fileMap.values.toList()
        }
    } catch (_: Exception) {
        emptyList<FileBrowserItem>() to emptyList()
    }
}
