package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.Collections
import java.util.EnumSet

/** Validates policy output before executing any network, token, timing, or media capability. */
class SabrSessionPolicyHost(
    private val policy: SabrSessionPolicy,
    private val transcript: SabrSessionPolicyTranscript?
) : AutoCloseable {

    companion object {
        private const val MAX_REQUEST_BYTES: Int = 256 * 1024
        private val TERMINAL: Set<SabrSessionPolicy.ActionType> = EnumSet.of(
            SabrSessionPolicy.ActionType.CONTINUE,
            SabrSessionPolicy.ActionType.RETRY,
            SabrSessionPolicy.ActionType.FAIL_SABR_ERROR,
            SabrSessionPolicy.ActionType.TRY_RELOAD
        )
    }

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
        if (event.getSegmentCount() <= 0 ||
            event.getTargetTrackSegmentCount() < 0 ||
            event.getTargetTrackSegmentCount() > event.getSegmentCount() ||
            event.getReturnedSegments().size > SabrSessionPolicy.MAX_DEMAND_RETURNED_SEGMENTS ||
            (!event.areReturnedSegmentsTruncated() && event.getReturnedSegments().size > event.getSegmentCount())
        ) {
            throw IllegalArgumentException("Invalid SABR demand response event")
        }
        for (segment in event.getReturnedSegments()) {
            if (segment == null ||
                segment.getItag() <= 0 ||
                segment.getSequenceNumber() < 0 ||
                segment.getStartMs() < 0 ||
                segment.getDurationMs() < 0
            ) {
                throw IllegalArgumentException("Invalid SABR returned segment identity")
            }
        }
        val decision = policy.evaluateDemandResponse(event)
        if (decision == null ||
            decision.getOutcome() == null ||
            decision.getRetryDelayMs() < 0 ||
            decision.getRetryDelayMs() > SabrSessionPolicy.MAX_DEMAND_RETRY_DELAY_MS
        ) {
            throw IllegalStateException("Invalid SABR demand response decision")
        }
        if (decision.getOutcome() != SabrSessionPolicy.DemandOutcome.CONTINUE &&
            decision.getRetryDelayMs() != 0
        ) {
            throw IllegalStateException("Terminal SABR demand decision requested retry delay")
        }
        transcript?.recordDemandResponse(event, decision)
        return decision
    }

    fun snapshotTranscript(): List<String> {
        return transcript?.snapshot() ?: Collections.emptyList()
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

    companion object Validator {
        private fun validateState(state: SabrSessionPolicy.State) {
            if (state.getRequestNumber() < 0 ||
                state.getRedirectCount() < 0 ||
                state.getPoTokenRefreshes() < 0 ||
                state.getReloads() < 0
            ) {
                throw IllegalStateException("Invalid SABR policy state")
            }
        }

        private fun validateDemandEvent(event: SabrSessionPolicy.DemandEvent) {
            val st = event.getState()
            if (event.getTargetItag() <= 0 ||
                event.getTargetSequenceNumber() < 0 ||
                event.getTargetStartMs() < 0 ||
                event.getBufferedEdgeMs() < 0 ||
                st.getCreatedAtMs() < 0 ||
                st.getNowMs() < st.getCreatedAtMs() ||
                st.getResponsesWithoutDemandedSegment() < 0 ||
                st.getRecoveryCount() < 0 ||
                st.getRecoveryCount() > st.getResponsesWithoutDemandedSegment()
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
            validateState(result.getNextState())
            if (event is SabrSessionPolicy.RequestEvent) {
                val expected = if (state.getRequestNumber() == 0)
                    SabrSessionPolicy.ActionType.SEND_INITIAL_REQUEST
                else
                    SabrSessionPolicy.ActionType.SEND_FOLLOW_UP_REQUEST
                if (result.getActions().size != 1 ||
                    result.getActions()[0].getType() != expected ||
                    result.getRequestBody() == null ||
                    result.getRequestBody()!!.isEmpty() ||
                    result.getRequestBody()!!.size > MAX_REQUEST_BYTES ||
                    result.getControlDecision() != null ||
                    state != result.getNextState()
                ) {
                    throw IllegalStateException("Invalid SABR request policy result")
                }
                return
            }
            if (result.getRequestBody() != null || result.getControlDecision() == null) {
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
            val decision = result.getControlDecision()!!
            if (seen.contains(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE) != (result.getStatePatch() != null)) {
                throw IllegalStateException("SABR response state action/patch mismatch")
            }
            if (seen.contains(SabrSessionPolicy.ActionType.APPLY_RESPONSE_STATE) &&
                seen.contains(SabrSessionPolicy.ActionType.APPLY_BUILTIN_RESPONSE_STATE)
            ) {
                throw IllegalStateException("SABR response state actions are mutually exclusive")
            }
            if (seen.contains(SabrSessionPolicy.ActionType.APPLY_REDIRECT) !=
                (decision.getRedirectUrl() != null && decision.getRedirectUrl()!!.isNotEmpty())
            ) {
                throw IllegalStateException("SABR redirect action/value mismatch")
            }
            val control = event as SabrSessionPolicy.ControlResponseEvent
            val reset = seen.contains(SabrSessionPolicy.ActionType.RESET_RECOVERY_BUDGETS)
            val redirect = seen.contains(SabrSessionPolicy.ActionType.APPLY_REDIRECT)
            val expectedRedirects = if (reset) 0 else state.getRedirectCount() + if (redirect) 1 else 0
            val expectedRefreshes = if (reset) 0 else state.getPoTokenRefreshes()
            val next = result.getNextState()
            if (next.getRequestNumber() != state.getRequestNumber() ||
                next.getReloads() != state.getReloads() ||
                next.getRedirectCount() != expectedRedirects ||
                next.getPoTokenRefreshes() != expectedRefreshes ||
                reset && (control.getMode() != SabrSessionPolicy.ControlMode.PUMP || control.getSegmentCount() <= 0)
            ) {
                throw IllegalStateException("Invalid SABR recovery state transition")
            }
            if (seen.contains(SabrSessionPolicy.ActionType.SLEEP_BACKOFF) !=
                (decision.getBackoffTimeMs() > 0 && control.shouldHonorBackoff()) ||
                seen.contains(SabrSessionPolicy.ActionType.DEFER_BACKOFF) !=
                (decision.getBackoffTimeMs() > 0 && !control.shouldHonorBackoff()) ||
                seen.contains(SabrSessionPolicy.ActionType.CLEAR_DEMAND_BACKOFF) &&
                (decision.getBackoffTimeMs() > 0 || control.shouldHonorBackoff()) ||
                seen.contains(SabrSessionPolicy.ActionType.REQUIRE_PO_TOKEN) &&
                control.getMode() != SabrSessionPolicy.ControlMode.FETCH_SEGMENT ||
                seen.contains(SabrSessionPolicy.ActionType.REFRESH_PO_TOKEN) &&
                control.getMode() != SabrSessionPolicy.ControlMode.PUMP
            ) {
                throw IllegalStateException("SABR Host action/event mismatch")
            }
        }
    }
}
