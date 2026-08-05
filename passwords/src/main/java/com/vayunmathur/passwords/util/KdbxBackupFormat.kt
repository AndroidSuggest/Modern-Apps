package com.vayunmathur.passwords.util

import android.content.Context
import com.vayunmathur.library.util.BackupFormat
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.data.newSyncId
import com.vayunmathur.passwords.sync.EntryMapper
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * One-shot kdbx backup/restore. Shares [EntryMapper] with the bidirectional sync, but
 * deliberately keeps the standalone semantics: exports carry no sync identity, and an
 * import always inserts fresh rows.
 */
class KdbxBackupFormat(
    private val passwordDao: PasswordDao,
    private val passkeyDao: PasskeyDao,
) : BackupFormat {
    override val mimeType = "application/octet-stream"
    override val defaultFileName = "passwords.kdbx"
    override val needsPassword = true

    override suspend fun export(context: Context, password: String?, outputStream: OutputStream) {
        requireNotNull(password) { "Password required for KDBX export" }

        val entries = JSONArray()
        for (pw in passwordDao.getAll()) entries.put(EntryMapper.toFields(pw).withoutSyncFields())
        for (pk in passkeyDao.getAll()) entries.put(EntryMapper.toFields(pk).withoutSyncFields())

        val bytes = KdbxNative.nativeExport(password, entries.toString())
            ?: error("Failed to write KDBX file")
        outputStream.write(bytes)
    }

    override suspend fun import(context: Context, password: String?, inputStream: InputStream) {
        requireNotNull(password) { "Password required for KDBX import" }

        val json = KdbxNative.nativeImport(password, inputStream.readBytes())
            ?: error("Failed to open KDBX file (wrong password or corrupt file)")

        val entries = JSONArray(json)
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i).toStringMap()
            if (EntryMapper.isPasskeyEntry(entry)) {
                passkeyDao.upsert(EntryMapper.toPasskey(entry).copy(syncId = newSyncId()))
            } else {
                passwordDao.upsert(EntryMapper.toPassword(entry).copy(syncId = newSyncId()))
            }
        }
    }

    private fun Map<String, String>.withoutSyncFields(): JSONObject {
        val json = JSONObject()
        for ((key, value) in this) {
            if (key == EntryMapper.FIELD_SYNC_ID || key == EntryMapper.FIELD_MODIFIED) continue
            json.put(key, value)
        }
        return json
    }

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        for (key in keys()) {
            put(key, getString(key))
        }
    }
}
