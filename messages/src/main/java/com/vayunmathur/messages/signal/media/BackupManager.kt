package com.vayunmathur.messages.signal.media

import android.util.Log
import com.google.protobuf.CodedInputStream
import com.vayunmathur.messages.signal.proto.backup.Backup.BackupInfo
import com.vayunmathur.messages.signal.proto.backup.Backup.ChatItem
import com.vayunmathur.messages.signal.proto.backup.Backup.Frame
import com.vayunmathur.messages.signal.store.SignalBackupChatEntity
import com.vayunmathur.messages.signal.store.SignalBackupChatItemEntity
import com.vayunmathur.messages.signal.store.SignalBackupRecipientEntity
import com.vayunmathur.messages.signal.store.SignalDatabase
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupManager(
    private val ws: SignalWebSocket,
    private val selfAci: String,
    private val db: SignalDatabase,
) {
    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_VERSION = 1
        private const val TRANSFER_ARCHIVE_FETCH_TIMEOUT_MS = 60L * 60 * 1000 // 1 hour
        private const val REQUEST_TIMEOUT_SECONDS = 300L // 5 minutes
        const val TRANSFER_ERROR_RELINK_REQUESTED = "RELINK_REQUESTED"
        const val TRANSFER_ERROR_CONTINUE_WITHOUT_UPLOAD = "CONTINUE_WITHOUT_UPLOAD"
    }

    data class TransferArchiveMetadata(
        val cdn: Int,
        val key: String,
        val error: String = "",
    )

    suspend fun waitForTransfer(
        ephemeralBackupKey: ByteArray?,
    ): TransferArchiveMetadata? {
        if (ephemeralBackupKey == null) {
            throw IllegalStateException("No ephemeral backup key")
        }
        val deadline = System.currentTimeMillis() + TRANSFER_ARCHIVE_FETCH_TIMEOUT_MS
        while (true) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0) {
                throw Exception("Timed out waiting for transfer archive")
            }
            val reqTimeoutSeconds = minOf(remainingMs / 1000, REQUEST_TIMEOUT_SECONDS)
            val reqStart = System.currentTimeMillis()
            val result = tryRequestTransferArchive(reqTimeoutSeconds)
            if (result != null) return result
            val reqDuration = System.currentTimeMillis() - reqStart
            if (reqDuration < (reqTimeoutSeconds * 1000) - 10_000) {
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    private suspend fun tryRequestTransferArchive(
        timeoutSeconds: Long,
    ): TransferArchiveMetadata? {
        val resp = ws.sendRequest(
            "GET",
            "/v1/devices/transfer_archive?timeout=$timeoutSeconds",
            null,
        )
        return when (resp.status) {
            204 -> null
            200 -> {
                val json = JSONObject(resp.body.toStringUtf8())
                TransferArchiveMetadata(
                    cdn = json.optInt("cdn", 0),
                    key = json.optString("key", ""),
                    error = json.optString("error", ""),
                )
            }
            else -> throw Exception("Unexpected status code ${resp.status}")
        }
    }

    suspend fun fetchAndProcessTransfer(
        meta: TransferArchiveMetadata,
        ephemeralBackupKey: ByteArray,
    ): Boolean {
        if (meta.error.isNotEmpty()) {
            Log.e(TAG, "Transfer archive error: ${meta.error}")
            return false
        }
        return try {
            val keys = deriveTransferKeys(ephemeralBackupKey)
            val encrypted = downloadTransferArchive(meta) ?: return false
            if (!verifyMAC(keys.second, encrypted)) {
                Log.e(TAG, "Transfer archive MAC verification failed")
                return false
            }
            val decrypted = decryptTransferArchive(keys.first, encrypted)
            val decompressed = decompressGzip(decrypted)
            processBackupFrames(decompressed)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process transfer archive", e)
            false
        }
    }

    private suspend fun downloadTransferArchive(
        meta: TransferArchiveMetadata,
    ): ByteArray? {
        val host = SignalHttpClient.cdnHost(meta.cdn)
        val path = "/attachments/${meta.key}"
        val resp = SignalHttpClient.request(
            host = host,
            method = "GET",
            path = path,
        )
        if (!resp.isSuccess) return null
        return resp.bytes
    }

    private fun deriveTransferKeys(
        ephemeralBackupKey: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val backupKey = org.signal.libsignal.messagebackup.BackupKey(ephemeralBackupKey)
        val backupId = backupKey.deriveBackupId(
            org.signal.libsignal.protocol.ServiceId.Aci(java.util.UUID.fromString(selfAci))
        )
        val messageBackupKey = org.signal.libsignal.messagebackup.MessageBackupKey(backupKey, backupId)
        return Pair(messageBackupKey.aesKey, messageBackupKey.hmacKey)
    }

    private fun verifyMAC(hmacKey: ByteArray, data: ByteArray): Boolean {
        if (data.size < 32) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        mac.update(data, 0, data.size - 32)
        val computed = mac.doFinal()
        val expected = data.copyOfRange(data.size - 32, data.size)
        return MessageDigest.isEqual(computed, expected)
    }

    private fun decryptTransferArchive(
        aesKey: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val iv = data.copyOfRange(0, 16)
        val ciphertext = data.copyOfRange(16, data.size - 32)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun decompressGzip(data: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(data)
        val gzip = GZIPInputStream(bais)
        val baos = ByteArrayOutputStream()
        gzip.copyTo(baos)
        gzip.close()
        return baos.toByteArray()
    }

    private suspend fun processBackupFrames(data: ByteArray) {
        Log.d(TAG, "Processing ${data.size} bytes of backup data")
        val input = CodedInputStream.newInstance(data)
        var seenInfo = false
        val recipientDao = db.backupRecipientDao()
        val chatDao = db.backupChatDao()
        val chatItemDao = db.backupChatItemDao()

        // Clear existing backup data before importing
        chatItemDao.deleteAll()
        chatDao.deleteAll()
        recipientDao.deleteAll()

        while (!input.isAtEnd) {
            val length = input.readRawVarint32()
            if (length == 0) continue
            val limit = input.pushLimit(length)
            val chunk = input.readRawBytes(length)
            input.popLimit(limit)

            if (!seenInfo) {
                val info = BackupInfo.parseFrom(chunk)
                if (info.version != BACKUP_VERSION.toLong()) {
                    throw Exception("Unsupported backup version: ${info.version}")
                }
                Log.i(TAG, "Backup info: version=${info.version}")
                seenInfo = true
                continue
            }

            val frame = Frame.parseFrom(chunk)
            processFrame(frame, recipientDao, chatDao, chatItemDao)
        }
        Log.d(TAG, "Finished processing backup frames")
    }

    private suspend fun processFrame(
        frame: Frame,
        recipientDao: com.vayunmathur.messages.signal.store.SignalBackupRecipientDao,
        chatDao: com.vayunmathur.messages.signal.store.SignalBackupChatDao,
        chatItemDao: com.vayunmathur.messages.signal.store.SignalBackupChatItemDao,
    ) {
        when (frame.itemCase) {
            Frame.ItemCase.RECIPIENT -> {
                val recipient = frame.recipient
                if (recipient.destinationCase == com.vayunmathur.messages.signal.proto.backup.Backup.Recipient.DestinationCase.DESTINATION_NOT_SET) {
                    Log.d(TAG, "Ignoring recipient frame with no destination")
                    return
                }
                recipientDao.insert(
                    SignalBackupRecipientEntity(
                        id = recipient.id,
                        data = recipient.toByteArray(),
                    )
                )
            }
            Frame.ItemCase.CHAT -> {
                val chat = frame.chat
                chatDao.insert(
                    SignalBackupChatEntity(
                        id = chat.id,
                        recipientId = chat.recipientId,
                        data = chat.toByteArray(),
                    )
                )
            }
            Frame.ItemCase.CHATITEM -> {
                val chatItem = frame.chatItem
                when (chatItem.itemCase) {
                    ChatItem.ItemCase.DIRECTSTORYREPLYMESSAGE,
                    ChatItem.ItemCase.UPDATEMESSAGE,
                    ChatItem.ItemCase.ITEM_NOT_SET -> {
                        Log.d(TAG, "Not saving unsupported chat item type: ${chatItem.itemCase}")
                        return
                    }
                    else -> {}
                }
                chatItemDao.insert(
                    SignalBackupChatItemEntity(
                        chatId = chatItem.chatId,
                        authorId = chatItem.authorId,
                        messageId = chatItem.dateSent,
                        data = chatItem.toByteArray(),
                    )
                )
            }
            else -> {
                Log.d(TAG, "Ignoring backup frame: ${frame.itemCase}")
            }
        }
    }
}
