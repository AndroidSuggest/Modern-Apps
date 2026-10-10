package com.vayunmathur.everysync.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-process set of account names currently syncing. Fed by [SyncEngine] (which
 * the WorkManager worker routes through) and observed by the UI to show a live
 * progress indicator.
 */
object SyncStatus {
    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    fun begin(accountName: String) = _syncing.update { it + accountName }
    fun finish(accountName: String) = _syncing.update { it - accountName }
}
