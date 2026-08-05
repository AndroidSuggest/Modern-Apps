package com.vayunmathur.passwords.sync

import android.content.Context
import com.vayunmathur.library.util.DatabaseHelper

/**
 * Keystore-backed storage for the kdbx vault password, under its own alias so it is
 * independent of the Room passphrase.
 *
 * The key is deliberately not user-authentication bound: the periodic worker has no way
 * to show a biometric prompt.
 */
class KdbxPasswordHelper(context: Context) : DatabaseHelper(context) {
    override val keyStoreAlias = "kdbx_vault_key"
    override val sharedPrefsName = "kdbx_sync_prefs"
    override val passphraseKey = "encrypted_kdbx_password"
    override val ivKey = "kdbx_password_iv"
}
