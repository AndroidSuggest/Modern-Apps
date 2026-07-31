package org.schabi.newpipe.extractor.services.youtube.sabr

import java.util.ArrayList
import java.util.Collections
import java.util.Objects

/**
 * Policy contract between protocol code and the small set of capabilities owned by the Host.
 * Implementations receive bounded, data-only events and return request bytes or Host actions.
 */
interface SabrSessionPolicy : AutoCloseable {

    companion object {
        const val MAX_DEMAND_RETURNED_SEGMENTS: Int = 64
        const val MAX_DEMAND_RETRY_DELAY_MS: Int = 5_000
    }

    enum class ActionType {
        SEND_INITIAL_REQUEST,
        SEND_FOLLOW_UP_REQUEST,
        APPLY_BUILTIN_RESPONSE_STATE,
        APPLY_REDIRECT,
        FAIL_SABR_ERROR,
        TRY_RELOAD,
        REFRESH_PO_TOKEN,
        REQUIRE_PO_TOKEN,
        RESET_RECOVERY_BUDGETS,
        SLEEP_BACKOFF,
        DEFER_BACKOFF,
        CLEAR_DEMAND_BACKOFF,
        RETRY,
        CONTINUE,
        APPLY_RESPONSE_STATE
    }

    enum class ControlMode {
        PUMP,
        FETCH_SEGMENT
    }

    enum class DemandRoute {
        STREAM,
        REWIND,
        FORWARD,
        RECOVER_REWIND,
        RECOVER_FORWARD,
        RECOVER_MISSING
    }

    enum class DemandOutcome {
        CONTINUE,
        FAIL_REPEATED_TARGET_OMISSION,
        FAIL_NO_TARGET_MEDIA
    }

    class State(
        private val requestNumber: Int,
        private val redirectCount: Int,
        private val poTokenRefreshes: Int,
        private val reloads: Int
    ) {
        fun getRequestNumber(): Int = requestNumber
        fun getRedirectCount(): Int = redirectCount
        fun getPoTokenRefreshes(): Int = poTokenRefreshes
        fun getReloads(): Int = reloads

        fun resetRecoveryBudgets(): State {
            return State(requestNumber, 0, 0, reloads)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is State) return false
            return requestNumber == other.requestNumber &&
                redirectCount == other.redirectCount &&
                poTokenRefreshes == other.poTokenRefreshes &&
                reloads == other.reloads
        }

        override fun hashCode(): Int {
            return Objects.hash(requestNumber, redirectCount, poTokenRefreshes, reloads)
        }
    }

    abstract class Event

    /** Immutable Host-owned counters exposed to demand policy decisions. */
    class DemandState(
        private val createdAtMs: Long,
        private val nowMs: Long,
        private val responsesWithoutDemandedSegment: Int,
        private val recoveryCount: Int
    ) {
        fun getCreatedAtMs(): Long = createdAtMs
        fun getNowMs(): Long = nowMs
        fun getElapsedMs(): Long = (nowMs - createdAtMs).coerceAtLeast(0)
        fun getResponsesWithoutDemandedSegment(): Int = responsesWithoutDemandedSegment
        fun getRecoveryCount(): Int = recoveryCount
    }

    abstract class DemandEvent(
        private val targetItag: Int,
        private val targetSequenceNumber: Int,
        private val targetStartMs: Long,
        private val bufferedEdgeMs: Long,
        private val state: DemandState
    ) {
        init {
            Objects.requireNonNull(state)
        }

        fun getTargetItag(): Int = targetItag
        fun getTargetSequenceNumber(): Int = targetSequenceNumber
        fun getTargetStartMs(): Long = targetStartMs
        fun getBufferedEdgeMs(): Long = bufferedEdgeMs
        fun getState(): DemandState = state
    }

    class DemandRouteEvent(
        targetItag: Int,
        targetSequenceNumber: Int,
        targetStartMs: Long,
        bufferedEdgeMs: Long,
        state: DemandState
    ) : DemandEvent(targetItag, targetSequenceNumber, targetStartMs, bufferedEdgeMs, state)

    /** Payload-free identity of one media segment returned while a reader demand was pending. */
    class DemandReturnedSegment(
        private val itag: Int,
        private val sequenceNumber: Int,
        private val startMs: Long,
        private val durationMs: Long
    ) {
        fun getItag(): Int = itag
        fun getSequenceNumber(): Int = sequenceNumber
        fun getStartMs(): Long = startMs
        fun getDurationMs(): Long = durationMs
    }

    class DemandResponseEvent(
        targetItag: Int,
        targetSequenceNumber: Int,
        targetStartMs: Long,
        bufferedEdgeMs: Long,
        state: DemandState,
        private val segmentCount: Int,
        private val targetTrackSegmentCount: Int,
        returnedSegments: List<DemandReturnedSegment>,
        private val returnedSegmentsTruncated: Boolean
    ) : DemandEvent(targetItag, targetSequenceNumber, targetStartMs, bufferedEdgeMs, state) {

        private val returnedSegments: List<DemandReturnedSegment> =
            Collections.unmodifiableList(ArrayList(Objects.requireNonNull(returnedSegments)))

        fun getSegmentCount(): Int = segmentCount
        fun getTargetTrackSegmentCount(): Int = targetTrackSegmentCount
        fun getReturnedSegments(): List<DemandReturnedSegment> = returnedSegments
        fun areReturnedSegmentsTruncated(): Boolean = returnedSegmentsTruncated
    }

    class DemandResponseDecision(
        private val outcome: DemandOutcome,
        private val retryDelayMs: Int
    ) {
        init {
            Objects.requireNonNull(outcome)
        }

        fun getOutcome(): DemandOutcome = outcome
        fun getRetryDelayMs(): Int = retryDelayMs
    }

    class RequestEvent(
        private val playerTimeMs: Long,
        private val bufferedEdgeMs: Long,
        private val poTokenBytes: Int,
        private val bufferedRangeCount: Int,
        proposedBody: ByteArray
    ) : Event() {
        private val proposedBody: ByteArray = proposedBody.clone()

        fun getPlayerTimeMs(): Long = playerTimeMs
        fun getBufferedEdgeMs(): Long = bufferedEdgeMs
        fun getPoTokenBytes(): Int = poTokenBytes
        fun getBufferedRangeCount(): Int = bufferedRangeCount
        fun getProposedBody(): ByteArray = proposedBody.clone()
    }

    class ControlResponseEvent(
        private val segmentCount: Int,
        private val honorBackoff: Boolean,
        private val mode: ControlMode,
        private val response: SabrDecodedResponse
    ) : Event() {
        init {
            Objects.requireNonNull(mode)
            Objects.requireNonNull(response)
        }

        fun getSegmentCount(): Int = segmentCount
        fun shouldHonorBackoff(): Boolean = honorBackoff
        fun getMode(): ControlMode = mode
        fun getResponse(): SabrDecodedResponse = response
    }

    class Action(private val type: ActionType) {
        init {
            Objects.requireNonNull(type)
        }

        fun getType(): ActionType = type

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Action) return false
            return type == other.type
        }

        override fun hashCode(): Int = type.hashCode()
        override fun toString(): String = "Action($type)"
    }

    /** Values interpreted by Host capabilities; protocol parsing remains in the policy. */
    class ControlDecision(
        private val backoffTimeMs: Int,
        private val redirectUrl: String?,
        private val errorDetails: String?
    ) {
        init {
            if (backoffTimeMs < 0) throw IllegalArgumentException("Negative SABR backoff")
        }

        fun getBackoffTimeMs(): Int = backoffTimeMs
        fun getRedirectUrl(): String? = redirectUrl
        fun getErrorDetails(): String? = errorDetails
    }

    class Result private constructor(
        private val nextState: State,
        actions: List<Action>,
        requestBody: ByteArray?,
        private val controlDecision: ControlDecision?,
        private val statePatch: SabrResponseStatePatch?
    ) {
        private val actions: List<Action> = Collections.unmodifiableList(ArrayList(actions))
        private val requestBody: ByteArray? = requestBody?.clone()

        companion object {
            @JvmStatic
            fun request(state: State, action: ActionType, body: ByteArray): Result {
                return Result(state, Collections.singletonList(Action(action)), body, null, null)
            }

            @JvmStatic
            fun control(state: State, actions: List<Action>, decision: ControlDecision): Result {
                return control(state, actions, decision, null)
            }

            @JvmStatic
            fun control(
                state: State,
                actions: List<Action>,
                decision: ControlDecision,
                statePatch: SabrResponseStatePatch?
            ): Result {
                return Result(state, actions, null, decision, statePatch)
            }
        }

        fun getNextState(): State = nextState
        fun getActions(): List<Action> = actions
        fun getRequestBody(): ByteArray? = requestBody?.clone()
        fun getControlDecision(): ControlDecision? = controlDecision
        fun getStatePatch(): SabrResponseStatePatch? = statePatch
    }

    /** Media framing is queried by the streaming Host without exposing media bytes to control JS. */
    fun getMediaProtocol(): SabrMediaProtocol = SabrMediaProtocol.builtin()

    @Throws(SabrProtocolException::class)
    fun evaluate(state: State, event: Event): Result

    /** Bundled demand routing; cloud policies may override this without owning the pump. */
    @Throws(SabrProtocolException::class)
    fun evaluateDemandRoute(event: DemandRouteEvent): DemandRoute {
        val demand = event.getState()
        if (demand.getResponsesWithoutDemandedSegment() > demand.getRecoveryCount()) {
            if (event.getTargetStartMs() < event.getBufferedEdgeMs()) {
                return DemandRoute.RECOVER_REWIND
            }
            if (event.getTargetStartMs() > event.getBufferedEdgeMs() + 30_000) {
                return DemandRoute.RECOVER_FORWARD
            }
            return DemandRoute.RECOVER_MISSING
        }
        if (event.getTargetStartMs() < event.getBufferedEdgeMs()) {
            return DemandRoute.REWIND
        }
        if (event.getTargetStartMs() > event.getBufferedEdgeMs() + 30_000) {
            return DemandRoute.FORWARD
        }
        return DemandRoute.STREAM
    }

    /** Every event here is a media-bearing response that omitted the demanded itag/sequence. */
    @Throws(SabrProtocolException::class)
    fun evaluateDemandResponse(event: DemandResponseEvent): DemandResponseDecision {
        val demand = event.getState()
        if (demand.getResponsesWithoutDemandedSegment() >= 3) {
            return DemandResponseDecision(DemandOutcome.FAIL_REPEATED_TARGET_OMISSION, 0)
        }
        if (demand.getElapsedMs() >= 15_000) {
            return DemandResponseDecision(
                if (event.getTargetTrackSegmentCount() > 0)
                    DemandOutcome.FAIL_REPEATED_TARGET_OMISSION
                else
                    DemandOutcome.FAIL_NO_TARGET_MEDIA,
                0
            )
        }
        return DemandResponseDecision(DemandOutcome.CONTINUE, 0)
    }

    override fun close() {}
}
