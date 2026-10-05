package com.vayunmathur.email.platform

import com.vayunmathur.email.data.DraftEntry
import com.vayunmathur.email.data.EmailOutboxDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Draft CRUD extracted from [EmailViewModel] to keep it under the function cap. */
class EmailDraftActions(
    private val scope: CoroutineScope,
    private val outboxDao: EmailOutboxDao,
) {
    /** Load a draft for resuming in the composer. */
    suspend fun load(id: Long): DraftEntry? = outboxDao.getDraft(id)

    /** Insert or update a draft; returns its id (new id when [id] is null). */
    fun save(
        id: Long?,
        accountEmail: String,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        onSaved: (Long) -> Unit = {},
    ) {
        scope.launch {
            val rowId = outboxDao.insertDraft(
                DraftEntry(
                    id = id ?: 0,
                    accountEmail = accountEmail,
                    to = to, cc = cc, bcc = bcc, subject = subject, body = body,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            onSaved(if (id != null && id != 0L) id else rowId)
        }
    }

    fun delete(id: Long) {
        scope.launch { outboxDao.deleteDraftById(id) }
    }
}
