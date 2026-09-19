package com.vayunmathur.communicate.service.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.communicate.data.loadSmsThreadsMerged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Car App Library [Session] for messaging + calls.
 *
 * Loads the merged thread list off the main thread (provider + network +
 * Room reads) and hands screens a snapshot; replies and calls route through
 * the shared repository choke-points. GoogleVoice reply stays unavailable
 * here: `sendMessage` mints its bot-defense token from an Activity, which a
 * headless car session has none of — SIM/WhatsApp/Signal reply ships first
 * (see the token-path follow-up).
 */
class CommunicateCarSession : Session() {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val repository = CommunicateRepository

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen =
        CommunicateCarThreadListScreen(carContext, this)

    /** Thread → reply choice, mirroring the phone conversation screen. */
    fun lineChoiceFor(thread: SmsThread): LineChoice = when (thread.line) {
        CommunicateLine.GoogleVoice -> LineChoice.GoogleVoice
        CommunicateLine.WhatsApp -> LineChoice.WhatsApp
        CommunicateLine.Signal -> LineChoice.Signal
        CommunicateLine.Sim -> LineChoice.Sim(
            thread.subscriptionId ?: -1,
            thread.displayName ?: thread.address,
        )
    }

    /** GoogleVoice threads are read-only in the car until the token follow-up. */
    fun canReply(thread: SmsThread): Boolean =
        thread.line != CommunicateLine.GoogleVoice

    fun refreshThreads(onDone: (List<SmsThread>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val threads = runCatching {
                repository.loadSmsThreadsMerged(carContext)
            }.getOrDefault(emptyList())
            scope.launch(Dispatchers.Main) { onDone(threads) }
        }
    }
}
