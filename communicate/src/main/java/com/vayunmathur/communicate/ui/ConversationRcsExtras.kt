package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.vayunmathur.communicate.data.CommunicateLine
import kotlinx.coroutines.launch

/**
 * RCS-only conversation extras (§4.1), split from `ConversationScreen` for
 * file length.
 */

/** Collects the RCS peer-typing flow for 1:1 threads (null key = inactive). */
@Composable
internal fun rememberRcsPeerTyping(
    line: CommunicateLine,
    isGroup: Boolean,
    remoteId: String?,
    address: String,
): Boolean {
    val key = if (line == CommunicateLine.Rcs && !isGroup) {
        remoteId?.takeIf { it.isNotBlank() } ?: address
    } else {
        null
    }
    val typing by produceState(initialValue = false, key) {
        if (key == null) {
            value = false
        } else {
            com.vayunmathur.communicate.data.rcs.RcsSessionManager.typing.collect { map ->
                value = map[key] == true
            }
        }
    }
    return typing
}

/** Fire an outbound typing report for a draft change (RCS 1:1 only). */
internal fun kotlinx.coroutines.CoroutineScope.sendRcsTyping(
    line: CommunicateLine,
    isGroup: Boolean,
    remoteId: String?,
    address: String,
    draft: String,
) {
    if (line != CommunicateLine.Rcs || isGroup) return
    val peer = remoteId?.takeIf { it.isNotBlank() } ?: address
    launch {
        com.vayunmathur.communicate.data.rcs.RcsTypingThrottle.onDraftChanged(
            peer, draft,
        )
    }
}
