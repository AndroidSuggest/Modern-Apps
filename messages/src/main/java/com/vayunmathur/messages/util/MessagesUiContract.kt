package com.vayunmathur.messages.util

import com.vayunmathur.messages.data.Conversation
import com.vayunmathur.messages.data.Message
import com.vayunmathur.messages.data.MessageSource

/**
 * The UI contract between [MessagesViewModel] / the database and the two screens the store
 * listing is rendered from: the inbox and a chat thread.
 *
 * Those screens take a state value plus an actions interface instead of a ViewModel and a
 * [com.vayunmathur.messages.data.MessagesDatabase], so a `@Preview` can render them from
 * literal sample conversations — nothing here touches the encrypted database or any of the
 * bridge protocols. It lives in `util` so `ui` depends on `util` and never the reverse.
 *
 * Every timestamp is pre-formatted into a string by the binder rather than passed as epoch
 * millis. Both screens would otherwise format against the current clock ("Today", "14:32"
 * vs. a date), which would give a different image on every run.
 */

// ===================== Inbox =====================

/** One inbox row: the conversation plus its pre-formatted last-activity label. */
data class InboxRow(
    val conversation: Conversation,
    /** "14:32" today, "Tue" this week, a short date beyond that. Empty when unknown. */
    val timeLabel: String = "",
)

/** Everything the inbox draws. */
data class InboxUiState(
    val rows: List<InboxRow> = emptyList(),
    /** Sources a new conversation can be started on, i.e. the ones actually signed in. */
    val connectedSources: List<MessageSource> = emptyList(),
)

/**
 * Inbox callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * The ViewModel does not implement this itself: each of these pairs a ViewModel or database
 * call with a navigation side effect, which only the binder can do.
 */
interface InboxActions {
    fun openConversation(id: String) {}
    fun openSettings() {}
    fun composeOn(source: MessageSource) {}
    fun deleteConversation(id: String) {}

    companion object {
        val Noop: InboxActions = object : InboxActions {}
    }
}

// ===================== Chat thread =====================

/**
 * One rendered unit of a thread. The binder walks the message list once and emits these, so
 * the screen never has to group or re-scan on recomposition.
 */
sealed interface ChatItem {
    data class DayDivider(
        val label: String,
        /** Unique per calendar day so the LazyColumn key won't collide on a thread that
         *  spans multiple Fridays. */
        val dayKey: Long,
    ) : ChatItem

    data class Msg(
        val message: Message,
        /** Pre-formatted send time, shown under the last bubble of a run. */
        val timeLabel: String,
        /** Show the sender name above this bubble (groups only). */
        val showSender: Boolean,
        /** First message in a contiguous run from the same sender — used to give the
         *  bubble a square corner at the join point. */
        val isFirstInRun: Boolean,
        val isLastInRun: Boolean,
    ) : ChatItem
}

/** Everything a chat thread draws. */
data class ConversationUiState(
    val conversation: Conversation? = null,
    /** Newest first: the list is fed to a `reverseLayout` LazyColumn. */
    val items: List<ChatItem> = emptyList(),
    val draft: String = "",
    val sending: Boolean = false,
    /** Whether the source supports reactions at all; gates the long-press. */
    val canReact: Boolean = false,
)

/** Chat-thread callbacks. Same no-op-default arrangement as [InboxActions]. */
interface ConversationActions {
    fun navigateBack() {}
    fun setDraft(text: String) {}
    fun send() {}
    fun attach() {}

    /** Open the system contact editor for the peer. */
    fun editContact() {}

    /** Long-press on a bubble: open the reaction picker for [messageId]. */
    fun react(messageId: String) {}

    fun votePoll(messageId: String, options: List<String>) {}
    fun acceptMessageRequest() {}
    fun blockConversation() {}
    fun deleteConversation() {}

    companion object {
        val Noop: ConversationActions = object : ConversationActions {}
    }
}
