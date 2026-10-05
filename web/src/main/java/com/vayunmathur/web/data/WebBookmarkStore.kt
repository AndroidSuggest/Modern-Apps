package com.vayunmathur.web.data

import kotlinx.coroutines.flow.Flow

/** Bookmarks and bookmark folders behind [BookmarkDao]. */
class WebBookmarkStore internal constructor(private val dao: BookmarkDao) {
    fun allFlow(): Flow<List<Bookmark>> = dao.allFlow()
    fun byFolderFlow(folderId: Long?): Flow<List<Bookmark>> = dao.byFolderFlow(folderId)
    suspend fun byUrl(url: String): Bookmark? = dao.byUrl(url)
    fun byUrlFlow(url: String): Flow<Bookmark?> = dao.byUrlFlow(url)
    suspend fun upsert(entry: Bookmark): Long = dao.upsert(entry)
    suspend fun delete(entry: Bookmark) = dao.delete(entry)
    fun foldersFlow(): Flow<List<BookmarkFolder>> = dao.foldersFlow()
    suspend fun upsertFolder(folder: BookmarkFolder): Long = dao.upsertFolder(folder)
    suspend fun deleteFolder(folder: BookmarkFolder) = dao.deleteFolder(folder)
    suspend fun deleteByFolder(folderId: Long) = dao.deleteByFolder(folderId)
}
