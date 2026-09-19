package com.vayunmathur.communicate.service.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.model.Action
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.app.Person
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.loadSmsMessagesMerged
import com.vayunmathur.communicate.data.sendMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * One conversation: recent messages newest-last, read-only rows.
 *
 * Reply (SIM/WhatsApp/Signal only) goes through a voice-keyboard search
 * screen whose submit calls the shared `sendMessage` choke-point — the
 * same path the phone composer and the GAL ch14 mirror use. GoogleVoice
 * threads show no reply action until the headless-token follow-up.
 */
class CommunicateCarConversationScreen(
    carContext: CarContext,
    private val session: CommunicateCarSession,
    private val thread: SmsThread,
) : Screen(carContext) {

    private var messages: List<SmsMessage> = emptyList()
    private var loading = true

    init {
        refresh()
    }

    private fun refresh() {
        session.scope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                session.repository.loadSmsMessagesMerged(session.carContext, thread)
            }.getOrDefault(emptyList())
            session.scope.launch(Dispatchers.Main) {
                messages = loaded.takeLast(MAX_SHOWN)
                loading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        // API 7+: ConversationItem is the car-native conversation row with
        // host-driven reply/read callbacks. Older hosts keep the ListTemplate
        // + voice-keyboard reply flow below.
        if (carContext.getCarAppApiLevel() >= 7) {
            runCatching { return conversationTemplate() }
        }
        return legacyListTemplate()
    }

    /** Car-native conversation view (API 7+). Falls back on any failure. */
    @OptIn(ExperimentalCarApi::class)
    private fun conversationTemplate(): Template {
        val title = thread.displayName?.takeIf { it.isNotBlank() } ?: thread.address
        // ConversationItem requires a non-empty message list — before the
        // messages load, show the legacy list (which handles loading itself).
        if (messages.isEmpty()) return legacyListTemplate()
        val self = Person.Builder().setName("You").setKey("self").build()
        val carMessages = messages.map { message ->
            val sender = if (message.outgoing) {
                null
            } else {
                Person.Builder()
                    .setName(message.senderAddress ?: thread.address)
                    .setKey(message.senderAddress ?: thread.address)
                    .build()
            }
            androidx.car.app.messaging.model.CarMessage.Builder()
                .setSender(sender)
                .setBody(CarText.create(message.body))
                .setReceivedTimeEpochMillis(message.timestampMillis)
                .setRead(message.read)
                .build()
        }
        val item = androidx.car.app.messaging.model.ConversationItem.Builder(
            thread.remoteId ?: thread.threadId.toString(),
            CarText.create(title),
            self,
            carMessages,
            object : androidx.car.app.messaging.model.ConversationCallback {
                override fun onMarkAsRead() = Unit

                override fun onTextReply(replyText: String) {
                    if (!session.canReply(thread) || replyText.isBlank()) return
                    session.scope.launch(Dispatchers.IO) {
                        runCatching {
                            session.repository.sendMessage(
                                session.carContext,
                                session.lineChoiceFor(thread),
                                thread.address,
                                replyText,
                                threadRemoteId = thread.remoteId,
                                participants = thread.participants,
                            )
                        }
                    }
                }
            },
        )
            .setGroupConversation(thread.isGroup)
            .build()
        val list = ItemList.Builder().addItem(item).build()
        // ListTemplate.build() throws when loading==hasList: only set the
        // list once messages have loaded.
        val listBuilder = ListTemplate.Builder()
            .setHeader(
                androidx.car.app.model.Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(title)
                    .build(),
            )
            .setLoading(loading)
        if (!loading) {
            listBuilder.setSingleList(list)
        }
        return listBuilder.build()
    }

    private fun legacyListTemplate(): Template {
        val list = ItemList.Builder()
        for (message in messages) {
            val row = Row.Builder()
                .setTitle(if (message.outgoing) "You" else message.senderAddress ?: thread.address)
            message.body.takeIf { it.isNotBlank() }?.let { row.addText(it) }
            list.addItem(row.build())
        }
        val title = thread.displayName?.takeIf { it.isNotBlank() } ?: thread.address
        val builder = ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(if (loading) "$title…" else title)
            .setHeaderAction(Action.BACK)
        if (session.canReply(thread)) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Reply")
                    .setOnClickListener {
                        screenManager.push(CommunicateCarReplyScreen(carContext, session, thread))
                    }
                    .build(),
            )
        }
        return builder.build()
    }

    /** Voice-keyboard reply: submit sends, then pops back to the thread. */
    private class CommunicateCarReplyScreen(
        carContext: CarContext,
        private val session: CommunicateCarSession,
        private val thread: SmsThread,
    ) : Screen(carContext) {
        override fun onGetTemplate(): Template =
            SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) = Unit

                override fun onSearchSubmitted(searchText: String) {
                    if (searchText.isBlank()) return
                    session.scope.launch(Dispatchers.IO) {
                        runCatching {
                            session.repository.sendMessage(
                                session.carContext,
                                session.lineChoiceFor(thread),
                                thread.address,
                                searchText,
                                threadRemoteId = thread.remoteId,
                                participants = thread.participants,
                            )
                        }
                        session.scope.launch(Dispatchers.Main) {
                            screenManager.pop()
                        }
                    }
                }
            })
                .setSearchHint("Reply")
                .setShowKeyboardByDefault(true)
                .build()
    }

    private companion object {
        const val MAX_SHOWN = 25
    }
}
