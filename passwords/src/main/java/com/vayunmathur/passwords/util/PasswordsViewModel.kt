package com.vayunmathur.passwords.util

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Passwords app.
 *
 * Owns:
 *  - Central TOTP ticker as a single shared [StateFlow] (replaces per-row
 *    [androidx.compose.runtime.LaunchedEffect] that ticked once per row).
 *  - Bitwarden-style CSV import (content-resolver read + parse + per-row upsert).
 *  - Edit-form draft state for [Password] (decoupled from composable lifetime).
 *  - Copy-to-clipboard actions, with a [SharedFlow] for one-shot "copied" events.
 *
 * Uses [PasswordDao] directly for all persistence. Exposes the password list
 * as a [StateFlow] and provides Composable helpers for per-row reads and
 * editable bindings.
 */
class PasswordsViewModel(
    application: Application,
    val passwordDao: PasswordDao,
    val passkeyDao: PasskeyDao,
) : AndroidViewModel(application), PasswordsActions {

    // -- Data -------------------------------------------------------------

    val passwords: StateFlow<List<Password>> = passwordDao.getAllFlow().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val passkeys: StateFlow<List<Passkey>> = passkeyDao.getAllFlow().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun deletePasskey(passkey: Passkey) {
        viewModelScope.launch(Dispatchers.IO) {
            passkeyDao.delete(passkey)
        }
    }

    fun upsert(password: Password, onSaved: ((Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val newId = passwordDao.upsert(password)
            onSaved?.invoke(newId)
        }
    }

    override fun delete(password: Password) {
        viewModelScope.launch(Dispatchers.IO) {
            passwordDao.delete(password)
        }
    }

    /**
     * Returns a [State] tracking the password with [initialId]. If not yet
     * loaded (or absent), returns [default]. Recomposes when the underlying
     * list changes.
     */
    @Composable
    fun passwordState(initialId: Long, default: () -> Password = { Password() }): State<Password> {
        val list by passwords.collectAsState()
        return remember(initialId) {
            derivedStateOf { list.firstOrNull { it.id == initialId } ?: default() }
        }
    }

    // -- TOTP ticker ------------------------------------------------------

    /**
     * Wall-clock millis, ticked once per second. The flow is shared across
     * every TOTP row so we don't allocate a coroutine per row. It is
     * stopped via [SharingStarted.WhileSubscribed] when no composable is
     * observing, matching the previous per-row `LaunchedEffect` behavior
     * which cancelled on leaving composition.
     */
    val tickerFlow: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(1000),
        System.currentTimeMillis(),
    )

    // -- Clipboard --------------------------------------------------------

    private val _copyEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits a short label (e.g. "Password copied") for snackbar feedback. */
    override val copyEvents: SharedFlow<String> = _copyEvents.asSharedFlow()

    // Default arguments live on the PasswordsActions declaration; an override may not
    // repeat them.
    override fun copyToClipboard(label: String, text: String, feedback: String?) {
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        if (feedback != null) {
            viewModelScope.launch { _copyEvents.emit(feedback) }
        }
    }

    // -- Edit-form draft --------------------------------------------------

    private val _draft = MutableStateFlow<Password?>(null)
    /** Currently-edited password draft, or null if no edit in progress. */
    val draft: StateFlow<Password?> = _draft.asStateFlow()

    /**
     * Initialize the draft from the persisted [seed] the first time the edit
     * page is shown for a given id. Subsequent calls with the same id are
     * ignored so user edits are not clobbered.
     */
    fun initDraft(seed: Password) {
        val current = _draft.value
        if (current == null || current.id != seed.id) {
            _draft.value = seed
        }
    }

    override fun updateDraft(transform: (Password) -> Password) {
        _draft.value = _draft.value?.let(transform)
    }

    fun clearDraft() {
        _draft.value = null
    }

    /**
     * Persists the current draft. For new rows, the assigned id is reported
     * via [onSaved]. Clears the draft once enqueued.
     */
    override fun saveDraft(onSaved: ((Long) -> Unit)?) {
        val current = _draft.value ?: return
        upsert(current, onSaved)
        _draft.value = null
    }

    // -- CSV import -------------------------------------------------------

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    fun importCsv(uri: Uri, source: ImportSource = ImportSource.BITWARDEN) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _importing.value = true
            _importMessage.value = null
            // Best-effort: persist read access for potential re-reads. Not
            // required for this one-shot import, so failure must not abort it.
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            try {
                val result = withContext(Dispatchers.IO) {
                    importCsvFromUri(ctx.contentResolver, uri, source)
                }
                _importMessage.value =
                    "Imported ${result.inserted} rows, skipped ${result.skipped} rows"
            } catch (e: Exception) {
                _importMessage.value = "Import failed: ${e.message}"
            } finally {
                _importing.value = false
            }
        }
    }

    private data class ImportResult(val inserted: Int, val skipped: Int)

    private suspend fun importCsvFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        source: ImportSource,
    ): ImportResult {
        val rows = contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = inputStream.bufferedReader()
            val list = mutableListOf<List<String>>()
            var line = reader.readLine()
            while (line != null) {
                val row = mutableListOf<String>()
                var current = StringBuilder()
                var inQuotes = false
                var i = 0
                while (i < line.length) {
                    val c = line[i]
                    if (c == '"') {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    } else if (c == ',' && !inQuotes) {
                        row.add(current.toString())
                        current = StringBuilder()
                    } else {
                        current.append(c)
                    }
                    i++
                }
                row.add(current.toString())
                list.add(row)
                line = reader.readLine()
            }
            list
        } ?: throw Exception("Unable to open selected file")
        if (rows.isEmpty()) return ImportResult(0, 0)

        val header = rows.first().map { it.trim().lowercase() }
        fun findCol(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1

        val nameIdx = findCol(*source.nameHeaders)
        val usernameIdx = findCol(*source.usernameHeaders)
        val passwordIdx = findCol(*source.passwordHeaders)
        val urlIdx = findCol(*source.urlHeaders)
        val totpIdx = findCol(*source.totpHeaders)

        var inserted = 0
        var skipped = 0

        for (row in rows.drop(1)) {
            try {
                fun col(idx: Int) = if (idx in row.indices) row[idx] else ""
                val rawName = col(nameIdx)
                val rawUrl = col(urlIdx)
                val name = rawName.ifEmpty { rawUrl }
                val username = col(usernameIdx)
                val password = col(passwordIdx)
                var totp = col(totpIdx).takeIf { it.isNotEmpty() }

                if (totp != null && totp.startsWith("otpauth://")) {
                    val match = Regex("[?&]secret=([^&]+)").find(totp)
                    totp = match?.groupValues?.get(1) ?: totp
                }

                val websites = rawUrl.split(';', '\n', '\r')
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

                passwordDao.upsert(
                    Password(
                        name = name,
                        userId = username,
                        password = password,
                        totpSecret = totp,
                        websites = websites,
                    ),
                )
                inserted++
            } catch (_: Exception) {
                skipped++
            }
        }

        return ImportResult(inserted, skipped)
    }
}

/** Factory for constructing [PasswordsViewModel] with a [PasswordDao]. */
class PasswordsViewModelFactory(
    private val application: Application,
    private val passwordDao: PasswordDao,
    private val passkeyDao: PasskeyDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PasswordsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return PasswordsViewModel(application, passwordDao, passkeyDao) as T
    }
}
