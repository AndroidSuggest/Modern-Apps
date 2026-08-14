package com.vayunmathur.communicate.data.whatsapp.mex

import android.content.Context
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a MEX/GraphQL **persisted-query `doc_id`** from a GraphQL **operation name**.
 *
 * MEX never sends raw GraphQL text — the wire `query_id`/`doc_id` is a server-assigned 17-digit id
 * keyed by GraphQL operation name (see w2.md §5.5). The official client resolves
 * `operationName → doc_id` from a bundled assets JSON of the shape
 * `{"version":1,"data":{OperationName: docId, …}}`. This class replicates that, backed by
 * `assets/whatsapp/mex_persist_ids.json`, seeded with the 7 real committed ids from §5.5.
 *
 * Operations whose id is not present return `null` (callers surface a typed
 * `no_persisted_id:<op>` failure). This deliberately does NOT crash like the official debug
 * client's `MexPersistedIdNotFoundFailure` — a dev can drop a captured `operationName:doc_id` pair
 * into the JSON to enable an operation without touching code.
 */
object MexPersistedQueryProvider {

    private const val TAG = "WAMex"
    private const val ASSET_PATH = "whatsapp/mex_persist_ids.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Cached `operationName → doc_id` map; loaded lazily from the asset on first use. */
    @Volatile
    private var cache: Map<String, String>? = null

    /**
     * The doc_id for [operationName], or null when it isn't in the bundled JSON (uncaptured op).
     */
    fun docIdFor(context: Context, operationName: String): String? {
        val map = cache ?: loadFromAsset(context).also { cache = it }
        return map[operationName]
    }

    /** Test/refresh hook: drop the cached map so the next [docIdFor] re-reads the asset. */
    fun invalidate() {
        cache = null
    }

    private fun loadFromAsset(context: Context): Map<String, String> {
        return try {
            val text = context.assets.open(ASSET_PATH).use { it.readBytes().toString(Charsets.UTF_8) }
            parsePersistIds(text)
        } catch (t: Throwable) {
            WhatsAppDiag.log(TAG, "persist-ids: failed to load $ASSET_PATH: ${t.message}")
            emptyMap()
        }
    }

    /**
     * Pure parser for the `{"version":1,"data":{name:docId}}` shape. Kept JVM-pure (kotlinx only)
     * so it is unit-testable without Android. Returns an empty map on malformed input.
     */
    fun parsePersistIds(jsonText: String): Map<String, String> {
        return try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            val data = root["data"]?.jsonObject ?: return emptyMap()
            data.mapValues { (_, v) -> v.jsonPrimitive.content }
        } catch (t: Throwable) {
            emptyMap()
        }
    }
}
