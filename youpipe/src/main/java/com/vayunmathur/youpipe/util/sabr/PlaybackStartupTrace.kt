package com.vayunmathur.youpipe.util.sabr

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Measures the production detail-click to rendered-first-frame path. */
object PlaybackStartupTrace {
    const val EXTRA_TRACE_ID = "com.vayunmathur.youpipe.util.sabr.extra.STARTUP_TRACE_ID"
    private const val TAG = "PlaybackStartup"
    private const val RECORD = "PIPEPIPE_PLAYBACK_STARTUP"
    private const val MAX_TRACES = 32
    private val NEXT_ID = AtomicLong()
    private val TRACES: MutableMap<Long, Trace> = ConcurrentHashMap()
    private val ACTIVE_URLS: MutableMap<String, Long> = ConcurrentHashMap()
    private val ACTIVE_VIDEO_IDS: MutableMap<String, Long> = ConcurrentHashMap()

    @JvmStatic
    fun begin(videoId: String, url: String): Long {
        val id = NEXT_ID.incrementAndGet()
        val trace = Trace(id, videoId, url, SystemClock.elapsedRealtimeNanos())
        TRACES[id] = trace
        ACTIVE_URLS[url] = id
        ACTIVE_VIDEO_IDS[videoId] = id
        trim()
        mark(id, "detail_click")
        return id
    }

    @JvmStatic
    fun attach(intent: Intent, id: Long) {
        if (id > 0) {
            intent.putExtra(EXTRA_TRACE_ID, id)
            mark(id, "intent_created")
        }
    }

    @JvmStatic
    fun fromIntent(intent: Intent): Long = intent.getLongExtra(EXTRA_TRACE_ID, 0)

    @JvmStatic
    fun markForUrl(url: String, stage: String) {
        ACTIVE_URLS[url]?.let { mark(it, stage) }
    }

    @JvmStatic
    fun markForVideoId(videoId: String, stage: String) {
        ACTIVE_VIDEO_IDS[videoId]?.let { mark(it, stage) }
    }

    @JvmStatic
    fun mark(id: Long, stage: String) {
        val trace = TRACES[id] ?: return
        val recorded = trace.mark(stage, SystemClock.elapsedRealtimeNanos())
        if (recorded != null) {
            Log.i(TAG, RECORD + " " + trace.stageJson(stage, recorded))
        }
    }

    @JvmStatic
    fun finish(id: Long) {
        mark(id, "first_frame")
        val trace = TRACES[id]
        if (trace != null && trace.finish()) {
            ACTIVE_URLS.remove(trace.url, id)
            ACTIVE_VIDEO_IDS.remove(trace.videoId, id)
            Log.i(TAG, RECORD + " " + trace.summaryJson())
        }
    }

    @JvmStatic
    fun snapshot(id: Long): Snapshot? = TRACES[id]?.snapshot()

    private fun trim() {
        if (TRACES.size <= MAX_TRACES) {
            return
        }
        val oldestId = TRACES.keys.minOrNull() ?: return
        val removed = TRACES.remove(oldestId)
        if (removed != null) {
            ACTIVE_URLS.remove(removed.url, oldestId)
            ACTIVE_VIDEO_IDS.remove(removed.videoId, oldestId)
        }
    }

    class Snapshot internal constructor(
        @JvmField val id: Long,
        @JvmField val videoId: String,
        @JvmField val url: String,
        @JvmField val elapsedMs: Map<String, Long>,
        @JvmField val finished: Boolean
    ) {
        @Throws(JSONException::class)
        fun toJson(): JSONObject {
            val stages = JSONObject()
            for ((key, value) in elapsedMs) {
                stages.put(key, value)
            }
            return JSONObject().put("record", "click_to_first_frame")
                .put("traceId", id).put("videoId", videoId).put("url", url)
                .put("finished", finished).put("stagesMs", stages)
        }
    }

    private class Trace(
        private val id: Long,
        val videoId: String,
        val url: String,
        private val startedNs: Long
    ) {
        private val stages = LinkedHashMap<String, Stage>()
        private var finished = false

        @Synchronized
        fun mark(stage: String, nowNs: Long): Stage? {
            if (stages.containsKey(stage)) {
                return null
            }
            val previousNs = if (stages.isEmpty()) startedNs else stages.values.last().atNs
            val value = Stage(toMs(nowNs - startedNs), toMs(nowNs - previousNs), nowNs)
            stages[stage] = value
            return value
        }

        @Synchronized
        fun stageJson(stage: String, value: Stage): String =
            "{\"record\":\"stage\",\"traceId\":" + id +
                ",\"videoId\":\"" + json(videoId) + "\",\"stage\":\"" +
                json(stage) + "\",\"elapsedMs\":" + value.elapsedMs +
                ",\"deltaMs\":" + value.deltaMs + "}"

        @Synchronized
        fun summaryJson(): String = try {
            snapshot().toJson().toString()
        } catch (impossible: JSONException) {
            "{\"record\":\"click_to_first_frame\",\"traceId\":$id}"
        }

        @Synchronized
        fun snapshot(): Snapshot {
            val values = LinkedHashMap<String, Long>()
            for ((key, value) in stages) {
                values[key] = value.elapsedMs
            }
            return Snapshot(id, videoId, url, values, finished)
        }

        @Synchronized
        fun finish(): Boolean {
            if (finished) {
                return false
            }
            finished = true
            return true
        }
    }

    private class Stage(
        val elapsedMs: Long,
        val deltaMs: Long,
        val atNs: Long
    )

    private fun toMs(nanos: Long): Long = nanos / 1_000_000L

    private fun json(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
