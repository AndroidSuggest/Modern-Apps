@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.messages.telegram.mtproto.rpc

import kotlin.concurrent.atomics.*
import android.util.Log
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class RpcException(val errorCode: Int, override val message: String) : Exception("RPC error $errorCode: $message")

class RetryLimitReachedException(val retries: Int) : Exception("Retry limit reached ($retries)")

class RpcEngine(
    private val retryInterval: Long = 5_000L,
    private val maxRetries: Int = 5,
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<TlBuffer>>()
    private val acks = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val payloads = ConcurrentHashMap<Long, ByteArray>()
    private val closed = AtomicBoolean(false)
    private val activeCount = AtomicInt(0)
    private val idleLock = Object()
    private val TAG = "RpcEngine"

    suspend fun <R : TlObject> execute(
        method: TlMethod<R>,
        sendFn: suspend (ByteArray, Long) -> Long,
        decoder: (TlBuffer) -> R,
    ): R = coroutineScope {
        check(!closed.load()) { "Engine is closed" }
        activeCount.incrementAndFetch()

        val buf = TlBuffer()
        method.encode(buf)
        val encoded = buf.raw
        val deferred = CompletableDeferred<TlBuffer>()
        val ackDeferred = CompletableDeferred<Unit>()
        val firstMsgId = sendFn(encoded, 0)
        // A resend (retry / bad_msg) uses a FRESH msg_id, so track the current id and
        // migrate the pending/ack/payload entries to it — otherwise the server's reply
        // to the retried msg_id finds "No pending request" and the call never completes.
        val currentMsgId = AtomicLong(firstMsgId)
        pending[firstMsgId] = deferred
        payloads[firstMsgId] = encoded
        acks[firstMsgId] = ackDeferred

        val retryJob = launch {
            var retries = 0
            while (isActive && retries < maxRetries) {
                try {
                    withTimeout(retryInterval) { ackDeferred.await() }
                    return@launch
                } catch (_: TimeoutCancellationException) {
                    Log.w(TAG, "Ack timeout for msgId=${currentMsgId.load()}, retry ${retries + 1}/$maxRetries")
                    try {
                        val old = currentMsgId.load()
                        val newMsgId = sendFn(encoded, old)
                        if (newMsgId != old) {
                            migratePending(old, newMsgId)
                            currentMsgId.store(newMsgId)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry send failed for msgId=${currentMsgId.load()}", e)
                        deferred.completeExceptionally(e)
                        return@launch
                    }
                    retries++
                    if (retries >= maxRetries) {
                        Log.e(TAG, "Retry limit reached for msgId=${currentMsgId.load()}")
                        deferred.completeExceptionally(RetryLimitReachedException(retries))
                        return@launch
                    }
                }
            }
        }

        try {
            val result = withTimeout(30_000) { deferred.await() }
            decoder(result)
        } catch (e: Exception) {
            pending.remove(currentMsgId.load())
            payloads.remove(currentMsgId.load())
            throw e
        } finally {
            retryJob.cancel()
            acks.remove(currentMsgId.load())
            if (activeCount.decrementAndFetch() == 0) {
                synchronized(idleLock) { idleLock.notifyAll() }
            }
        }
    }

    fun notifyResult(msgId: Long, buffer: TlBuffer) {
        acks[msgId]?.complete(Unit)
        acks.remove(msgId)
        payloads.remove(msgId)
        val deferred = pending.remove(msgId)
        if (deferred != null) {
            deferred.complete(buffer)
        } else {
            Log.w(TAG, "No pending request for msgId=$msgId")
        }
    }

    fun notifyError(msgId: Long, code: Int, message: String) {
        acks[msgId]?.complete(Unit)
        acks.remove(msgId)
        payloads.remove(msgId)
        val deferred = pending.remove(msgId)
        if (deferred != null) {
            deferred.completeExceptionally(RpcException(code, message))
        }
    }

    fun notifyAck(msgIds: List<Long>) {
        for (id in msgIds) {
            acks[id]?.complete(Unit)
            acks.remove(id)
        }
    }

    fun close() {
        closed.store(true)
        synchronized(idleLock) {
            while (activeCount.load() > 0) {
                idleLock.wait()
            }
        }
    }

    fun forceClose() {
        closed.store(true)
        dropAll(Exception("Engine forcibly closed"))
    }

    fun dropAll(error: Exception) {
        val entries = pending.entries.toList()
        pending.clear()
        payloads.clear()
        for ((id, deferred) in entries) {
            acks[id]?.complete(Unit)
            deferred.completeExceptionally(error)
        }
        acks.clear()
    }

    fun hasPending(msgId: Long): Boolean = pending.containsKey(msgId)

    fun storePayload(msgId: Long, data: ByteArray) {
        payloads[msgId] = data
    }

    fun getPendingPayload(msgId: Long): ByteArray? = payloads.remove(msgId)

    fun migratePending(oldMsgId: Long, newMsgId: Long) {
        pending.remove(oldMsgId)?.let { pending[newMsgId] = it }
        acks.remove(oldMsgId)?.let { acks[newMsgId] = it }
        payloads.remove(oldMsgId)?.let { payloads[newMsgId] = it }
    }
}
