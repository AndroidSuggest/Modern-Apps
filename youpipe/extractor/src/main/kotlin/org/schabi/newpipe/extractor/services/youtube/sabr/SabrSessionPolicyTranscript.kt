package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.ArrayDeque
import java.util.Collections
import java.util.Deque

/** Bounded, payload-free audit trail of policy decisions and applied Host actions. */
class SabrSessionPolicyTranscript(private val capacity: Int) {

    private val entries: Deque<Entry> = ArrayDeque()

    init {
        if (capacity <= 0) throw IllegalArgumentException("Invalid transcript capacity")
    }

    @Synchronized
    internal fun record(
        state: SabrSessionPolicy.State,
        event: SabrSessionPolicy.Event,
        result: SabrSessionPolicy.Result
    ) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(Entry(summary(state, event, result)))
    }

    @Synchronized
    internal fun recordDemandRoute(
        event: SabrSessionPolicy.DemandRouteEvent,
        route: SabrSessionPolicy.DemandRoute
    ) {
        append(
            "v1 event=demand-route target=" + event.targetItag + ':' +
                event.targetSequenceNumber + " elapsedMs=" +
                event.getState().getElapsedMs() + " omissions=" +
                event.getState().responsesWithoutDemandedSegment + " recoveries=" +
                event.getState().getRecoveryCount() + " route=$route"
        )
    }

    @Synchronized
    internal fun recordDemandResponse(
        event: SabrSessionPolicy.DemandResponseEvent,
        decision: SabrSessionPolicy.DemandResponseDecision
    ) {
        append(
            "v1 event=demand-response target=" + event.targetItag + ':' +
                event.targetSequenceNumber + " segments=" + event.segmentCount +
                " targetTrack=" + event.targetTrackSegmentCount + " returned=" +
                event.getReturnedSegments().size + " truncated=" +
                event.areReturnedSegmentsTruncated() + " omissions=" +
                event.getState().responsesWithoutDemandedSegment + " outcome=" +
                decision.outcome + " retryDelayMs=" + decision.getRetryDelayMs()
        )
    }

    private fun append(summary: String) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(Entry(summary))
    }

    @Synchronized
    internal fun commitLast(
        result: SabrSessionPolicy.Result,
        appliedState: SabrSessionPolicy.State,
        actions: List<SabrSessionPolicy.Action>,
        completed: Boolean
    ) {
        val types = ArrayList<SabrSessionPolicy.ActionType>()
        for (action in actions) types.add(action.getType())
        commitLastTypes(result, appliedState, types, completed)
    }

    @Synchronized
    internal fun commitLastTypes(
        result: SabrSessionPolicy.Result,
        appliedState: SabrSessionPolicy.State,
        actions: List<SabrSessionPolicy.ActionType>,
        completed: Boolean
    ) {
        val entry = entries.peekLast()
        if (entry != null) {
            entry.commit = " applied=" + state(appliedState) +
                " executed=$actions completed=$completed"
        }
    }

    @Synchronized
    fun snapshot(): List<String> {
        val list = ArrayList<String>()
        for (entry in entries) list.add(entry.decision + entry.commit)
        return Collections.unmodifiableList(list)
    }

    private fun summary(
        state: SabrSessionPolicy.State,
        event: SabrSessionPolicy.Event,
        result: SabrSessionPolicy.Result
    ): String {
        val kind = if (event is SabrSessionPolicy.RequestEvent) "request" else "control"
        val bytes = result.getRequestBody()?.size ?: 0
        return "v1 state=" + state(state) + " event=$kind actions=" +
            result.getActions() + " requestBytes=$bytes"
    }

    private fun state(state: SabrSessionPolicy.State): String {
        return state.requestNumber.toString() + "," + state.redirectCount + "," +
            state.poTokenRefreshes + "," + state.getReloads()
    }

    private class Entry(val decision: String) {
        var commit: String = ""
    }
}
