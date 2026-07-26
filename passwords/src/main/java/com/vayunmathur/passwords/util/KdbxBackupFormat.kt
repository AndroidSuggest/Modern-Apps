package com.vayunmathur.passwords.util

import android.content.Context
import android.util.Base64
import com.vayunmathur.library.util.BackupFormat
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

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

        for (pw in passwordDao.getAll()) {
            val entry = JSONObject()
            entry.put("Title", pw.name)
            entry.put("UserName", pw.userId)
            entry.put("Password", pw.password)
            if (pw.websites.isNotEmpty()) {
                entry.put("URL", pw.websites.first())
                if (pw.websites.size > 1) {
                    entry.put("Websites", pw.websites.joinToString("\n"))
                }
            }
            pw.totpSecret?.let { secret ->
                entry.put("otp", "otpauth://totp/?secret=$secret")
            }
            entry.put("_Type", "password")
            entries.put(entry)
        }

        for (pk in passkeyDao.getAll()) {
            val entry = JSONObject()
            entry.put("Title", pk.rpName)
            entry.put("UserName", pk.userName)
            entry.put("URL", pk.rpId)
            entry.put("_Type", "passkey")
            entry.put("KPEX_PASSKEY_USERNAME", pk.userName)
            entry.put("KPEX_PASSKEY_PRIVATE_KEY_PEM", Base64.encodeToString(pk.privateKeyBytes, Base64.NO_WRAP))
            entry.put("KPEX_PASSKEY_CREDENTIAL_ID", pk.credentialId)
            entry.put("KPEX_PASSKEY_USER_HANDLE", pk.userId)
            entry.put("KPEX_PASSKEY_RELYING_PARTY", pk.rpId)
            entries.put(entry)
        }

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
            val isPasskey = entry.keys.any { it.startsWith("KPEX_PASSKEY_") }
            if (isPasskey) {
                importPasskey(entry)
            } else {
                importPassword(entry)
            }
        }
    }

    private suspend fun importPassword(entry: Map<String, String>) {
        val websites = mutableListOf<String>()
        val url = entry["URL"].orEmpty()
        if (url.isNotEmpty()) websites.add(url)
        val extraWebsites = entry["Websites"]
        if (!extraWebsites.isNullOrEmpty()) {
            extraWebsites.split("\n").filter { it.isNotBlank() }.forEach { w ->
                if (w !in websites) websites.add(w)
            }
        }
        var totpSecret: String? = null
        val otp = entry["otp"]
        if (!otp.isNullOrEmpty()) {
            val match = Regex("[?&]secret=([^&]+)").find(otp)
            totpSecret = match?.groupValues?.get(1) ?: otp
        }
        if (totpSecret == null) {
            totpSecret = entry["TOTP Seed"]
        }

        val pw = Password(
            name = entry["Title"].orEmpty(),
            userId = entry["UserName"].orEmpty(),
            password = entry["Password"].orEmpty(),
            websites = websites,
            totpSecret = totpSecret,
        )
        passwordDao.upsert(pw)
    }

    private suspend fun importPasskey(entry: Map<String, String>) {
        val privateKeyB64 = entry["KPEX_PASSKEY_PRIVATE_KEY_PEM"].orEmpty()
        val privateKeyBytes = if (privateKeyB64.isNotEmpty()) Base64.decode(privateKeyB64, Base64.NO_WRAP) else ByteArray(0)

        val pk = Passkey(
            rpId = entry["KPEX_PASSKEY_RELYING_PARTY"].orEmpty().ifEmpty { entry["URL"].orEmpty() },
            rpName = entry["Title"].orEmpty(),
            credentialId = entry["KPEX_PASSKEY_CREDENTIAL_ID"].orEmpty(),
            userId = entry["KPEX_PASSKEY_USER_HANDLE"].orEmpty(),
            userName = entry["KPEX_PASSKEY_USERNAME"].orEmpty().ifEmpty { entry["UserName"].orEmpty() },
            userDisplayName = entry["UserName"].orEmpty(),
            privateKeyBytes = privateKeyBytes,
        )
        passkeyDao.upsert(pk)
    }

    private fun JSONObject.toStringMap(): Map<String, String> = buildMap {
        for (key in keys()) {
            put(key, getString(key))
        }
    }
}
