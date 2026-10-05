package com.vayunmathur.web.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository

/**
 * Single owner of [WebDatabase]. Per-domain stores hang off here so no single
 * class exceeds the function-count budget; call sites use `repository.<domain>`.
 */
class WebRepository private constructor(context: Context) :
    RoomRepository<WebDatabase>(context, WebDatabase::class, DB_NAME) {

    val history = WebHistoryStore(db.historyDao())
    val bookmarks = WebBookmarkStore(db.bookmarkDao())
    val permissions = WebSitePermissionStore(db.sitePermissionDao())
    val storage = WebStorageStore(db.storageInfoDao())
    val downloads = WebDownloadStore(db.downloadDao())
    val installed = WebInstalledSiteStore(db.installedSiteDao())
    val shields = WebShieldStore(db.shieldSettingDao())

    companion object {
        @Volatile private var instance: WebRepository? = null
        fun get(context: Context): WebRepository =
            instance ?: synchronized(this) {
                instance ?: WebRepository(context).also { instance = it }
            }
    }
}
