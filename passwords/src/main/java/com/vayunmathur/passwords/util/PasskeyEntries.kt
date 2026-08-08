package com.vayunmathur.passwords.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.provider.BeginGetCredentialOption
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.ui.PasskeyAuthActivity
import org.json.JSONObject

/**
 * Builds the credential entries (passkeys + saved passwords) shown to the
 * Credential Manager for a get-credential request. Shared by
 * [PasskeyCredentialService] and [PasskeyAuthActivity] so both produce
 * identical entries / [PendingIntent]s.
 */
suspend fun buildGetCredentialResponse(
    context: Context,
    options: List<BeginGetCredentialOption>,
    passkeyDao: PasskeyDao,
    passwordDao: PasswordDao,
): BeginGetCredentialResponse {
    val responseBuilder = BeginGetCredentialResponse.Builder()
    for (option in options) {
        when (option) {
            is BeginGetPublicKeyCredentialOption -> {
                val rpId = JSONObject(option.requestJson).optString("rpId", "")
                if (rpId.isBlank()) continue
                for (passkey in passkeyDao.getByRpId(rpId)) {
                    val intent = Intent(context, PasskeyAuthActivity::class.java).apply {
                        putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_GET)
                        putExtra(PasskeyAuthActivity.EXTRA_CREDENTIAL_ID, passkey.credentialId)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        passkey.id.toInt(),
                        intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    responseBuilder.addCredentialEntry(
                        PublicKeyCredentialEntry.Builder(
                            context,
                            passkey.userName,
                            pendingIntent,
                            option,
                        ).build()
                    )
                }
            }
            is BeginGetPasswordOption -> {
                for (pass in passwordDao.getAll()) {
                    val intent = Intent(context, PasskeyAuthActivity::class.java).apply {
                        putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_PASSWORD)
                        putExtra(PasskeyCredentialService.EXTRA_PASSWORD_ID, pass.id)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        (pass.id + 100000).toInt(),
                        intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    responseBuilder.addCredentialEntry(
                        PasswordCredentialEntry.Builder(
                            context,
                            pass.username,
                            pendingIntent,
                            option,
                        ).setDisplayName(pass.name.ifBlank { null })
                            .build()
                    )
                }
            }
        }
    }
    return responseBuilder.build()
}
