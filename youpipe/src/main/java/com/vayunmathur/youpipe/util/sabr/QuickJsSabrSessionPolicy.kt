package com.vayunmathur.youpipe.util.sabr

import android.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getBoolean
import org.schabi.newpipe.extractor.utils.getInt
import org.schabi.newpipe.extractor.utils.getLong
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaHeader
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaProtocol
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrContextSendingPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrContextUpdate
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrFormatInitializationMetadata
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrLiveMetadata
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrNextRequestPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrResponseStatePatch
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrScriptPolicy
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSessionPolicy

class QuickJsSabrSessionPolicy @Throws(SabrProtocolException::class) constructor(
    script: SabrScriptPolicy,
) : SabrSessionPolicy {
    private var sessionId = -1
    private val mediaProtocol: SabrMediaProtocol
    private val scriptedDemand: Boolean
    private var closed = false

    init {
        try {
            sessionId = QuickJsSabrRuntime.createSession(script)
            val description = invokeObject("describe", jsonObject())
            scriptedDemand = description.getBoolean("demand", false)
            val media = description.getObject("media")
                ?: throw SabrProtocolException("QuickJS policy has no media protocol")
            mediaProtocol = ScriptMediaProtocol(
                media.getInt("headerType", 0),
                media.getInt("mediaType", 0),
                media.getInt("endType", 0),
                media.getString("headerDecoder") == "builtin",
            )
        } catch (error: SabrProtocolException) {
            closeCreatedSession()
            throw error
        } catch (error: Exception) {
            closeCreatedSession()
            throw SabrProtocolException("Could not initialize SABR QuickJS policy", error)
        }
    }

    override fun getMediaProtocol(): SabrMediaProtocol = mediaProtocol

    @Synchronized
    override fun evaluateDemandRoute(
        event: SabrSessionPolicy.DemandRouteEvent,
    ): SabrSessionPolicy.DemandRoute {
        if (!scriptedDemand) return super<SabrSessionPolicy>.evaluateDemandRoute(event)
        val output = invokeObject("demandRoute", JsonObject(demandInput(event)))
        return try {
            SabrSessionPolicy.DemandRoute.valueOf(
                output.getString("route")
                    ?: throw SabrProtocolException("QuickJS demand policy returned no route"),
            )
        } catch (error: IllegalArgumentException) {
            throw SabrProtocolException("QuickJS demand policy returned unknown route", error)
        }
    }

    @Synchronized
    override fun evaluateDemandResponse(
        event: SabrSessionPolicy.DemandResponseEvent,
    ): SabrSessionPolicy.DemandResponseDecision {
        if (!scriptedDemand) return super<SabrSessionPolicy>.evaluateDemandResponse(event)
        val input = demandInput(event)
        input["segmentCount"] = JsonPrimitive(event.segmentCount)
        input["targetTrackSegmentCount"] = JsonPrimitive(event.targetTrackSegmentCount)
        input["returnedSegmentsTruncated"] = JsonPrimitive(event.areReturnedSegmentsTruncated())
        input["returnedSegments"] = JsonArray(
            event.getReturnedSegments().map { segment ->
                jsonObject(
                    "itag" to JsonPrimitive(segment.itag),
                    "sequenceNumber" to JsonPrimitive(segment.sequenceNumber),
                    "startMs" to JsonPrimitive(segment.startMs),
                    "durationMs" to JsonPrimitive(segment.getDurationMs()),
                )
            }
        )
        val output = invokeObject("demandResponse", JsonObject(input))
        val outcome = try {
            SabrSessionPolicy.DemandOutcome.valueOf(
                output.getString("outcome")
                    ?: throw SabrProtocolException("QuickJS demand policy returned no outcome"),
            )
        } catch (error: IllegalArgumentException) {
            throw SabrProtocolException("QuickJS demand policy returned unknown outcome", error)
        }
        val retryDelayMs = output.getInt("retryDelayMs", 0)
        if (retryDelayMs !in 0..SabrSessionPolicy.MAX_DEMAND_RETRY_DELAY_MS ||
            outcome != SabrSessionPolicy.DemandOutcome.CONTINUE && retryDelayMs != 0
        ) {
            throw SabrProtocolException("QuickJS demand policy returned invalid retry delay")
        }
        return SabrSessionPolicy.DemandResponseDecision(
            outcome,
            retryDelayMs,
        )
    }

    @Synchronized
    override fun evaluate(
        state: SabrSessionPolicy.State,
        event: SabrSessionPolicy.Event,
    ): SabrSessionPolicy.Result {
        ensureOpen()
        if (event is SabrSessionPolicy.RequestEvent) {
            val input = stateJson(state)
            input["playerTimeMs"] = JsonPrimitive(event.playerTimeMs)
            input["bufferedEdgeMs"] = JsonPrimitive(event.bufferedEdgeMs)
            input["poTokenBytes"] = JsonPrimitive(event.poTokenBytes)
            input["bufferedRangeCount"] = JsonPrimitive(event.bufferedRangeCount)
            input["fallbackBody"] =
                JsonPrimitive(Base64.encodeToString(event.getProposedBody(), Base64.NO_WRAP))
            val output = invokeObject(
                if (state.requestNumber == 0) "initialRequest" else "followUpRequest",
                JsonObject(input),
            )
            val body = output.getString("body")
                ?: throw SabrProtocolException("QuickJS policy returned no request body")
            val bytes = try {
                Base64.decode(body, Base64.DEFAULT)
            } catch (error: IllegalArgumentException) {
                throw SabrProtocolException("Invalid QuickJS request body", error)
            }
            return SabrSessionPolicy.Result.request(
                state,
                if (state.requestNumber == 0) {
                    SabrSessionPolicy.ActionType.SEND_INITIAL_REQUEST
                } else {
                    SabrSessionPolicy.ActionType.SEND_FOLLOW_UP_REQUEST
                },
                bytes,
            )
        }
        return control(state, event as SabrSessionPolicy.ControlResponseEvent)
    }

    private fun control(
        state: SabrSessionPolicy.State,
        event: SabrSessionPolicy.ControlResponseEvent,
    ): SabrSessionPolicy.Result {
        val input = stateJson(state)
        input["segmentCount"] = JsonPrimitive(event.segmentCount)
        input["honorBackoff"] = JsonPrimitive(event.shouldHonorBackoff())
        input["mode"] = JsonPrimitive(event.mode.name)
        input["parts"] = JsonArray(
            event.getResponse().getParts()
                .filter { it.type != mediaProtocol.getMediaPartType() }
                .map { part ->
                    jsonObject(
                        "type" to JsonPrimitive(part.type),
                        "data" to JsonPrimitive(
                            Base64.encodeToString(part.getData(), Base64.NO_WRAP)
                        ),
                    )
                }
        )
        input["builtin"] = jsonObject(
            "error" to JsonPrimitive(event.getResponse().getSabrErrorDetails() != null),
            "reload" to JsonPrimitive(event.getResponse().isReloadRequested()),
            "protection" to
                JsonPrimitive(event.getResponse().isProtectionBoundaryNoMediaResponse()),
            "redirectUrl" to JsonPrimitive(event.getResponse().getRedirectUrl()),
            "backoffMs" to JsonPrimitive(maxOf(0, event.getResponse().getBackoffTimeMs())),
        )

        val output = invokeObject("response", JsonObject(input))
        val outputActions = output.getArray("actions")
        if (outputActions == null || outputActions.isEmpty()) {
            throw SabrProtocolException("QuickJS policy returned no actions")
        }
        val actions = ArrayList<SabrSessionPolicy.Action>(outputActions.size)
        for (index in outputActions.indices) {
            try {
                actions.add(
                    SabrSessionPolicy.Action(
                        SabrSessionPolicy.ActionType.valueOf(
                            outputActions.getString(index).orEmpty()
                        ),
                    ),
                )
            } catch (error: RuntimeException) {
                throw SabrProtocolException("QuickJS policy returned unknown action", error)
            }
        }
        val next = output.getObject("state")
        val nextState = if (next == null) state else SabrSessionPolicy.State(
            state.requestNumber,
            next.getInt("redirectCount", state.redirectCount),
            next.getInt("poTokenRefreshes", state.poTokenRefreshes),
            state.getReloads(),
        )
        return SabrSessionPolicy.Result.control(
            nextState,
            actions,
            SabrSessionPolicy.ControlDecision(
                output.getInt("backoffMs", 0),
                output.getString("redirectUrl"),
                output.getString("errorDetails"),
            ),
            if (output.containsKey("statePatch")) {
                parseStatePatch(output.getObject("statePatch"), event)
            } else {
                null
            },
        )
    }

    private fun parseStatePatch(
        value: JsonObject?,
        event: SabrSessionPolicy.ControlResponseEvent,
    ): SabrResponseStatePatch? {
        if (value == null) return null
        val builder = SabrResponseStatePatch.builder()
        val next = value.getObject("nextRequest")
        if (next != null) {
            builder.setNextRequestPolicy(
                SabrNextRequestPolicy.normalized(
                    next.getInt("targetAudioReadaheadMs", -1),
                    next.getInt("targetVideoReadaheadMs", -1),
                    next.getInt("maxTimeSinceLastRequestMs", -1),
                    next.getInt("backoffTimeMs", -1),
                    next.getInt("minAudioReadaheadMs", -1),
                    next.getInt("minVideoReadaheadMs", -1),
                    decodeOptional(next.getString("playbackCookie")),
                    next.getString("videoId"),
                ),
            )
        }
        val live = value.getArray("live")
        if (live != null) {
            for (index in live.indices) {
                val item = live.getObject(index) ?: continue
                builder.addLiveMetadata(
                    SabrLiveMetadata.normalized(
                        item.getString("broadcastId"),
                        item.getLong("headSequenceNumber", -1),
                        item.getLong("headTimeMs", -1),
                        item.getLong("wallTimeMs", -1),
                        item.getString("videoId"),
                        item.getBoolean("postLiveDvr", false),
                        item.getLong("headm", -1),
                        item.getLong("minSeekableTimeTicks", -1),
                        item.getInt("minSeekableTimescale", -1),
                        item.getLong("maxSeekableTimeTicks", -1),
                        item.getInt("maxSeekableTimescale", -1),
                    ),
                )
            }
        }
        val formats = value.getArray("formats")
        if (formats != null) {
            for (index in formats.indices) {
                val item = formats.getObject(index) ?: continue
                builder.addFormatMetadata(
                    SabrFormatInitializationMetadata.normalized(
                        item.getString("videoId"),
                        item.getInt("itag", -1),
                        item.getLong("lastModified", -1),
                        item.getString("xtags"),
                        item.getLong("endTimeMs", -1),
                        item.getLong("endSegmentNumber", -1),
                        item.getString("mimeType"),
                        item.getLong("initRangeStart", -1),
                        item.getLong("initRangeEnd", -1),
                        item.getLong("indexRangeStart", -1),
                        item.getLong("indexRangeEnd", -1),
                        item.getLong("field8", -1),
                        item.getLong("durationUnits", -1),
                        item.getLong("durationTimescale", -1),
                    ),
                )
            }
        }
        val contexts = value.getArray("contexts")
        if (contexts != null) {
            for (index in contexts.indices) {
                val item = contexts.getObject(index) ?: continue
                builder.addContextUpdate(
                    SabrContextUpdate.normalized(
                        item.getInt("type", -1),
                        item.getInt("scope", -1),
                        decodeRequired(item.getString("value"), "context value"),
                        item.getBoolean("sendByDefault", false),
                        item.getInt("writePolicy", -1),
                    ),
                )
            }
        }
        val contextPolicy = value.getObject("contextPolicy")
        if (contextPolicy != null) {
            builder.setContextSendingPolicy(
                SabrContextSendingPolicy.normalized(
                    intList(contextPolicy.getArray("start")),
                    intList(contextPolicy.getArray("stop")),
                    intList(contextPolicy.getArray("discard")),
                ),
            )
        }
        for (header in event.getResponse().getMediaHeaders()) builder.addMediaHeader(header)
        return builder.build()
    }

    private fun intList(values: JsonArray?): List<Int> {
        if (values == null) return emptyList()
        return values.indices.map { values.getInt(it, 0) }
    }

    private fun decodeOptional(value: String?): ByteArray? =
        value?.let { decodeRequired(it, "optional bytes") }

    private fun decodeRequired(value: String?, name: String): ByteArray {
        if (value == null) throw SabrProtocolException("QuickJS policy returned no $name")
        return try {
            Base64.decode(value, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            throw SabrProtocolException("QuickJS policy returned invalid $name", error)
        }
    }

    @Synchronized
    private fun invokeObject(method: String, input: JsonObject): JsonObject {
        ensureOpen()
        return QuickJsSabrRuntime.invoke(sessionId, method, input)
    }

    private fun stateJson(state: SabrSessionPolicy.State) = linkedMapOf<String, JsonElement>(
        "requestNumber" to JsonPrimitive(state.requestNumber),
        "redirectCount" to JsonPrimitive(state.redirectCount),
        "poTokenRefreshes" to JsonPrimitive(state.poTokenRefreshes),
        "reloads" to JsonPrimitive(state.getReloads()),
    )

    private fun demandInput(event: SabrSessionPolicy.DemandEvent) =
        linkedMapOf<String, JsonElement>(
            "targetItag" to JsonPrimitive(event.targetItag),
            "targetSequenceNumber" to JsonPrimitive(event.targetSequenceNumber),
            "targetStartMs" to JsonPrimitive(event.targetStartMs),
            "bufferedEdgeMs" to JsonPrimitive(event.bufferedEdgeMs),
            "createdAtMs" to JsonPrimitive(event.getState().createdAtMs),
            "nowMs" to JsonPrimitive(event.getState().nowMs),
            "elapsedMs" to JsonPrimitive(event.getState().getElapsedMs()),
            "responsesWithoutDemandedSegment" to
                JsonPrimitive(event.getState().responsesWithoutDemandedSegment),
            "recoveryCount" to JsonPrimitive(event.getState().getRecoveryCount()),
        )

    private fun jsonObject(vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(linkedMapOf(*entries))

    private fun ensureOpen() {
        if (closed) throw SabrProtocolException("SABR QuickJS policy is closed")
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            closeCreatedSession()
        }
    }

    private fun closeCreatedSession() {
        if (sessionId >= 0) {
            QuickJsSabrRuntime.closeSession(sessionId)
            sessionId = -1
        }
    }

    private inner class ScriptMediaProtocol(
        private val headerType: Int,
        private val mediaType: Int,
        private val endType: Int,
        private val builtinHeaderDecoder: Boolean,
    ) : SabrMediaProtocol {
        init {
            if (headerType < 0 || mediaType < 0 || endType < 0 ||
                headerType == mediaType || headerType == endType || mediaType == endType
            ) {
                throw IllegalArgumentException("Invalid QuickJS media protocol types")
            }
        }

        override fun getHeaderPartType() = headerType
        override fun getMediaPartType() = mediaType
        override fun getEndPartType() = endType

        override fun decodeHeader(payload: ByteArray): SabrMediaHeader {
            if (builtinHeaderDecoder) return SabrMediaProtocol.builtin().decodeHeader(payload)
            val value = invokeObject(
                "mediaHeader",
                jsonObject(
                    "data" to JsonPrimitive(Base64.encodeToString(payload, Base64.NO_WRAP))
                ),
            )
            val headerId = value.getInt("headerId", -1)
            val itag = value.getInt("itag", -1)
            if (headerId !in 0..255 || itag <= 0) {
                throw SabrProtocolException("QuickJS policy returned invalid media header identity")
            }
            return SabrMediaHeader.normalized(
                headerId,
                value.getString("videoId"),
                itag,
                value.getLong("lastModified", -1),
                value.getString("xtags"),
                value.getLong("startRange", -1),
                value.getInt("compressionAlgorithm", -1),
                value.getBoolean("initSegment", false),
                value.getInt("sequenceNumber", -1),
                value.getLong("bitrateBps", -1),
                value.getLong("startMs", -1),
                value.getLong("durationMs", -1),
                value.getLong("contentLength", -1),
                value.getLong("timeRangeStartTicks", -1),
                value.getLong("timeRangeDurationTicks", -1),
                value.getInt("timeRangeTimescale", -1),
                value.getLong("sequenceLastModified", -1),
            )
        }
    }

}
