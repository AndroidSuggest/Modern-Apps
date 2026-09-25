package com.vayunmathur.communicate.data.rcs.e2e

import com.vayunmathur.communicate.data.rcs.RcsFeature

/**
 * Pending encrypted-group setups awaiting member key packages.
 *
 * When the user creates an encrypted group before every member's key package
 * is cached, the conversation id + member set is parked here. Each inbound
 * key package re-checks pending sets; once all members are cached, the group
 * is created and the entry removed. In-memory like [RcsPeerKeys] (v1 scope).
 */
object RcsPendingGroups {
    private data class Pending(val conversationId: String, val members: Set<String>)

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

    /** Park [conversationId] with [members] until all key packages arrive. */
    fun add(conversationId: String, members: Collection<String>) {
        if (!RcsFeature.enabled || conversationId.isBlank() || members.isEmpty()) return
        pending[conversationId] = Pending(conversationId, members.toSet())
    }

    /** Drop the pending entry (group created or abandoned). */
    fun remove(conversationId: String) {
        pending.remove(conversationId)
    }

    /**
     * Pending entries whose member set includes [peerE164] and whose every
     * member now has a cached key package. Callers create the group for each
     * returned conversation id, then [remove] it.
     */
    fun readyFor(peerE164: String): List<Pair<String, Map<String, ByteArray>>> {
        if (!RcsFeature.enabled) return emptyList()
        return pending.values.mapNotNull { p ->
            if (!p.members.contains(peerE164)) return@mapNotNull null
            val packages = p.members.associateWith { RcsPeerKeys.get(it) }
            if (packages.values.any { it == null }) return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            p.conversationId to (packages as Map<String, ByteArray>)
        }
    }

    /** True when [conversationId] is parked awaiting keys ("waiting" banner). */
    fun isPending(conversationId: String): Boolean =
        RcsFeature.enabled && pending.containsKey(conversationId)
}
