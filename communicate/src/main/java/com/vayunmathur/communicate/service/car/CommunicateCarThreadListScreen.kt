package com.vayunmathur.communicate.service.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.vayunmathur.communicate.data.SmsThread

/**
 * Merged conversation list: SIM + GoogleVoice + WhatsApp + Signal threads,
 * newest first.
 *
 * Rows show the display name, snippet, and unread state; tapping opens
 * [CommunicateCarConversationScreen]. GoogleVoice rows carry no reply
 * affordance (read-only until the token follow-up) but stay readable.
 */
class CommunicateCarThreadListScreen(
    carContext: CarContext,
    private val session: CommunicateCarSession,
) : Screen(carContext) {

    private var threads: List<SmsThread> = emptyList()
    private var loading = true

    init {
        session.refreshThreads {
            threads = it
            loading = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        for (thread in threads) {
            val title = thread.displayName?.takeIf { it.isNotBlank() } ?: thread.address
            val row = Row.Builder()
                .setTitle(title)
            thread.snippet.takeIf { it.isNotBlank() }?.let { row.addText(it) }
            row.setBrowsable(true)
            row.setOnClickListener {
                screenManager.push(CommunicateCarConversationScreen(carContext, session, thread))
            }
            list.addItem(row.build())
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(if (loading) "Loading…" else "Messages")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
