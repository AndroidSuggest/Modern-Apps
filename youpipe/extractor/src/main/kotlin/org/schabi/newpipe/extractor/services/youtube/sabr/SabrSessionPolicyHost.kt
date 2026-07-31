package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.EnumSet

/** Validates policy output before executing any network, token, timing, or media capability. */
class SabrSessionPolicyHost(
    private val policy: SabrSessionPolicy,
    private val transcript: SabrSessionPolicyTranscript?
) : AutoCloseable {

    init {
        requireNotNull(policy)
    }

    @Throws(SabrProtocolException::class)
    fun evaluate(state: SabrSessionPolicy.State, event: SabrSessionPolicy.Event): SabrSessionPolicy.Result {
        validateState(state)
        val result = policy.evaluate(state, event)
        validateResult(state, event, result)
        transcript?.record(state, event, result)
        return result
    }

    fun getMediaProtocol(): SabrMediaProtocol = policy.getMediaProtocol()

    @Throws(SabrProtocolException::class)
    fun evaluateDemandRoute(event: SabrSessionPolicy.DemandRouteEvent): SabrSessionPolicy.DemandRoute {
        validateDemandEvent(event)
        val route = policy.evaluateDemandRoute(event)
            ?: throw IllegalStateException("SABR demand policy returned no route")
        transcript?.recordDemandRoute(event, route)
        return route
    }

    @Throws(SabrProtocolException::class)
    fun evaluateDemandResponse(event: SabrSessionPolicy.DemandResponseEvent): SabrSessionPolicy.DemandResponseDecision {
        validateDemandEvent(event)
        if (event.segmentCount <= 0 ||
            event.targetTrackSegmentCount < 0 ||
            event.targetTrackSegmentCount > event.segmentCount ||
            event.getReturnedSegments().size > SabrSessionPolicy.MAX_DEMAND_RETURNED_SEGMENTS ||
            (!event.areReturnedSegmentsTruncated() && event.getReturnedSegments().size > event.segmentCount)
        ) {
            throw IllegalArgumentException("Invalid SABR demand response event")
        }
        for (segment in event.getReturnedSegments()) {
            if (segment == null ||
                segment.itag <= 0 ||
                segment.sequenceNumber < 0 ||
                segment.startMs < 0 ||
                segment.getDurationMs() < 0
            ) {
                throw IllegalArgumentException("Invalid SABR returned segment identity")
            }
        }
        val decision = policy.evaluateDemandResponse(event)
        if (decision == null ||
            decision.outcome == null ||
            decision.getRetryDelayMs() < 0 ||
            decision.getRetryDelayMs() > SabrSessionPolicy.MAX_DEMAND_RETRY_DELAY_MS
        ) {
            throw IllegalStateException("Invalid SABR demand response decision")
        }
        if (decision.outcome != SabrSessionPolicy.DemandOutcome.CONTINUE &&
            decision.getRetryDelayMs() != 0
        ) {
            throw IllegalStateException("Terminal SABR demand decision requested retry delay")
        }
        transcript?.recordDemandResponse(event, decision)
        return decision
    }

    fun snapshotTranscript(): List<String> {
        return transcript?.snapshot() ?: emptyList()
    }

    fun commitAppliedState(result: SabrSessionPolicy.Result, state: SabrSessionPolicy.State) {
        transcript?.commitLast(result, state, result.getActions(), true)
    }

    fun commitAppliedState(
        result: SabrSessionPolicy.Result,
        state: SabrSessionPolicy.State,
        actions: List<SabrSessionPolicy.ActionType>,
        completed: Boolean
    ) {
        transcript?.commitLastTypes(result, state, actions, completed)
    }

    override fun close() {
        policy.close()
    }

    companion object {
        private const val MAX_REQUEST_BYTES: Int = 256 * 1024
        private val TERMINAL: Set<SabrSessionPolicy.ActionType> = EnumSet.of(
            SabrSessionPolicy.ActionType.CONTINUE,
            SabrSessionPolicy.ActionType.RETRY,
            SabrSessionPolicy.ActionType.FAIL_SABR_ERROR,
            SabrSessionPolicy.ActionType.TRY_RELOAD
        )

        private fun validateState(state: SabrSessionPolicy.State) {
            if (state.requestNumber < 0 ||
                state.redirectCount < 0 ||
                state.poTokenRefreshes < 0 ||
                state.getReloads() < 0
            ) {
                throw IllegalStateException("Invalid SABR policy state")
            }
        }

        private fun validateDemandEvent(event: SabrSessionPolicy.DemandEvent) {
            val st = event.getState()
            if (event.targetItag <= 0 ||
                event.targetSequenceNumber < 0 ||
                event.targetStartMs < 0 ||
                event.bufferedEdgeMs < 0 ||
                st.createdAtMs < 0 ||
                st.nowMs < st.createdAtMs ||
                st.responsesWithoutDemandedSegment < 0 ||
                st.getRecoveryCount() < 0 ||
                st.getRecoveryCount() > st.responsesWithoutDemandedSegment
            ) {
                throw IllegalArgumentException("Invalid SABR demand policy event")
            }
        }

        private fun validateResult(
            state: SabrSessionPolicy.State,
            event: SabrSessionPolicy.Event,
            result: SabrSessionPolicy.Result?
        ) {
            if (result == null || result.getActions().isEmpty()) {
                throw IllegalStateException("SABR policy returned no result")
            }
            validateState(result.nextState)
            if (event is SabrSessionPolicy.RequestEvent) {
                val expected = if (state.requestNumber == 0)
                    SabrSessionPolicy.ActionType.SEND_INITIAL_REQUEST
                else
                    SabrSessionPolicy.ActionType.SEND_FOLLOW_UP_REQUEST
                if (result.getActions().size != 1 ||
                    result.getActions()[0].getType() != expected ||
                    result.getRequestBody() == null ||
                    result.getRequestBody()!!.isEmpty() ||
                    result.getRequestBody()!!.size > MAX_REQUEST_BYTES ||
                    result.controlDecision != null ||
                    state != result.nextState
                ) {
                    throw IllegalStateException("Invalid SABR request policy result")
                }
                return
            }
            if (result.getRequestBody() != null || result.controlDecision == null) {
                throw IllegalStateException("Invalid SABR control policy result")
            }
            val actions = result.getActions()
            val seen = EnumSet.noneOf(SabrSessionPolicy.ActionType::class.java)
            for (action in actions) {
                if (action == null || !seen.add(action.getType())) {
                    throw IllegalStateException("Duplicate SABR control action")
                }
            }
            var terminalCount = 0
            for (action in actions) {
                if (TERMINAL.contains(action.getType())) terminalCount++
            }
            if (terminalCount != 1 || !TERMINAL.contains(actions[actions.size - 1].getType())) {
                throw IllegalStateException("SABR control policy has no terminal action")
            }
            val decision = result.controlDecision!!
            if (seen.contains(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE) != (result.getStatePatch() != null)) {
                throw IllegalStateException("SABR response state action/patch mismatch")
            }
            if (seen.contains(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE) &&
                seen.contains(SabrSessionPolicy.ActionType.APPLY_BUILTIN_RESPONSE_STATE)
            ) {
                throw IllegalStateException("SABR response state actions are mutually exclusive")
            }
            if (seen.contains(SabrSessionPolicy.ActionType.APPLY_REDIRECT) !=
                (decision.redirectUrl != null && decision.redirectUrl!!.isNotEmpty())
            ) {
                throw IllegalStateException("SABR redirect action/value mismatch")
            }
            val control = event as SabrSessionPolicy.ControlResponseEvent
            val reset = seen.contains(SabrSessionPolicy.ActionType.RESET_RECOVERY_BUDGETS)
            val redirect = seen.contains(SabrSessionPolicy.ActionType.APPLY_REDIRECT)
            val expectedRedirects = if (reset) 0 else state.redirectCount + if (redirect) 1 else 0
            val expectedRefreshes = if (reset) 0 else state.poTokenRefreshes
            val next = result.nextState
            if (next.requestNumber != state.requestNumber ||
                next.getReloads() != state.getReloads() ||
                next.redirectCount != expectedRedirects ||
                next.poTokenRefreshes != expectedRefreshes ||
                reset && (control.mode != SabrSessionPolicy.ControlMode.PUMP || control.segmentCount <= 0)
            ) {
                throw IllegalStateException("Invalid SABR recovery state transition")
            }
            if (seen.contains(SabrSessionPolicy.ActionType.SLEEP_BACKOFF) !=
                (decision.backoffTimeMs > 0 && control.shouldHonorBackoff()) ||
                seen.contains(SabrSessionPolicy.ActionType.DEFER_BACKOFF) !=
                (decision.backoffTimeMs > 0 && !control.shouldHonorBackoff()) ||
                seen.contains(SabrSessionPolicy.ActionType.CLEAR_DEMAND_BACKOFF) &&
                (decision.backoffTimeMs > 0 || control.shouldHonorBackoff()) ||
                seen.contains(SabrSessionPolicy.ActionType.REQUIRE_PO_TOKEN) &&
                control.mode != SabrSessionPolicy.ControlMode.FETCH_SEGMENT ||
                seen.contains(SabrSessionPolicy.ActionType.REFRESH_PO_TOKEN) &&
                control.mode != SabrSessionPolicy.ControlMode.PUMP
            ) {
                throw IllegalStateException("SABR Host action/event mismatch")
            }
        }
    }
}
