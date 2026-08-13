package com.vayunmathur.communicate.data.whatsapp.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData

/**
 * Imports a decrypted `msgstore.db` (see [Crypt15Decryptor]) into the WhatsApp Room cache. Opens the
 * plain SQLite file read-only and maps the modern msgstore schema (`message` ⋈ `chat` ⋈ `jid`) into
 * [WhatsAppCachedMessage]/[WhatsAppConversation]. Media is metadata-only (WhatsApp media is
 * cloud-referenced). Dedupe is inherent: cached rows are keyed by the message `key_id`.
 *
 * ⚠️ msgstore schema drifts across WhatsApp versions; column reads are defensive (`getColumnIndex`
 * null-checks) and failures are collected into [ImportResult.errors] rather than aborting.
 */
object BackupImporter {

    private const val TAG = "WABackupImporter"
    private const val BATCH = 500

    data class ImportResult(
        val conversationCount: Int,
        val messageCount: Int,
        val errors: List<String>,
    )

    suspend fun import(context: Context, crypt15: ByteArray, backupKey: ByteArray): ImportResult {
        val errors = mutableListOf<String>()
        val dbFile = try {
            Crypt15Decryptor.decryptToFile(crypt15, backupKey, context.cacheDir)
        } catch (t: Throwable) {
            return ImportResult(0, 0, listOf("Decrypt failed: ${t.message}"))
        }

        var messageCount = 0
        val conversationJids = HashSet<String>()
        val room = WhatsAppDatabase.getDatabase(context)
        val msgDao = room.cachedMessageDao()
        val convDao = room.conversationDao()

        try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { src ->
                val sql = """
                    SELECT m.key_id AS key_id, m.from_me AS from_me, m.timestamp AS ts,
                           m.text_data AS text_data, j.raw_string AS chat_jid
                    FROM message m
                    JOIN chat c ON m.chat_row_id = c._id
                    JOIN jid j ON c.jid_row_id = j._id
                    ORDER BY m.timestamp ASC
                """.trimIndent()
                src.rawQuery(sql, null).use { cur ->
                    val iKey = cur.getColumnIndex("key_id")
                    val iFrom = cur.getColumnIndex("from_me")
                    val iTs = cur.getColumnIndex("ts")
                    val iText = cur.getColumnIndex("text_data")
                    val iJid = cur.getColumnIndex("chat_jid")
                    if (iKey < 0 || iJid < 0) {
                        errors.add("Unexpected msgstore schema (missing key_id/jid)")
                    } else {
                        val batch = ArrayList<WhatsAppCachedMessage>(BATCH)
                        while (cur.moveToNext()) {
                            val jid = cur.getString(iJid) ?: continue
                            val body = if (iText >= 0) cur.getString(iText) ?: "" else ""
                            val ts = if (iTs >= 0) cur.getLong(iTs) else 0L
                            val outgoing = iFrom >= 0 && cur.getInt(iFrom) == 1
                            val keyId = cur.getString(iKey) ?: continue
                            conversationJids.add(jid)
                            val sd = WhatsAppServiceData(isGroup = jid.endsWith("@g.us"))
                            batch.add(
                                WhatsAppCachedMessage(
                                    messageId = keyId,
                                    conversationJid = jid,
                                    body = body,
                                    timestamp = ts,
                                    outgoing = outgoing,
                                    serviceData = sd.serialize(),
                                ),
                            )
                            if (batch.size >= BATCH) {
                                msgDao.upsertAll(batch)
                                messageCount += batch.size
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) {
                            msgDao.upsertAll(batch)
                            messageCount += batch.size
                        }
                    }
                }
            }

            for (jid in conversationJids) {
                convDao.upsert(WhatsAppConversation(chatJid = jid))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "import failed", t)
            errors.add("Import failed: ${t.message}")
        } finally {
            runCatching { dbFile.delete() }
        }

        return ImportResult(conversationJids.size, messageCount, errors)
    }
}
