package com.vayunmathur.messages.meta

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import com.vayunmathur.library.network.NetworkClient

/**
 * Web auth/config bootstrap (#5). Ports the behaviour of messagix's
 * {client,configs,js_module_parser}.go: it loads the messenger.com /
 * instagram.com web page with the user's cookies and parses the embedded JS
 * bundles for the data the realtime layer needs:
 *
 *  - schema versionId (LSVersion module)
 *  - MQTT broker endpoint + appId (MqttWebConfig / CurrentUserInitialData)
 *  - ParentThreadKeys (LSThreadsRangesQuery in the preloaded LS payload)
 *  - LSD / fb_dtsg / jazoest tokens (needed for authenticated HTTP calls such
 *    as the Mercury media upload)
 *  - sync params (LSPlatformMessengerSyncParams) for the DB SyncManager
 *
 * The Go implementation walks the parsed DOM and replays the BBox module
 * graph. On Android we don't have a full HTML/JS module runtime, so this is a
 * best-effort regex/substring extraction over the same page payload. The wire
 * format (where these values live in the page) is stable enough for the values
 * we need, but the network round-trip and exact extraction are
 * runtime-dependent.
 *
 * // UNVERIFIED: extraction relies on live page structure and cannot be
 * // exercised without real messenger.com/instagram.com cookies + network.
 */
data class MetaConfig(
    val versionId: Long = 0L,
    val broker: String? = null,
    val appId: Long = 0L,
    val parentThreadKeys: List<Long> = listOf(-1L),
    val lsdToken: String = "",
    val fbDtsg: String = "",
    val jazoest: String = "",
    val clientId: String = "",
    val syncParamsMailbox: String = "",
    val syncParamsContact: String = "",
    val syncParamsE2ee: String = "",
    // Page-embedded initial inbox snapshot (RelayPrefetchedStreamCache →
    // lightspeed_web_request_for_igd). This is where the real thread rows (deleteThenInsertThread)
    // live; the socket only carries ranges. [initialSnapshotPayload] is the inner LightSpeedData
    // JSON string; [initialSnapshotSp] is the dependency name list (used as the /ls_resp "sp").
    val initialSnapshotPayload: String? = null,
    val initialSnapshotSp: List<String> = emptyList(),
    // Mailbox (sync-group 1 / database 1) cursor from the page's
    // LSExecuteFirstBlockForSyncTransaction. Required for FetchMessages history:
    // an empty cursor makes the server return zero older messages.
    val mailboxCursor: String = "",
    val loaded: Boolean = false,
) {
    fun defaultAppId(platform: MetaAuthData.Platform): Long = when {
        appId != 0L -> appId
        platform == MetaAuthData.Platform.INSTAGRAM -> 936619743392459L
        else -> 219994525426954L
    }
}

object MetaBootstrap {
    private const val TAG = "MetaBootstrap"
    private const val MAX_JS_CRAWL = 6

    // __d("LSVersion", ... ){ e.exports="12345" }  (ref js_module_parser.go versionPattern)
    private val versionPattern =
        Regex("""__d\("LSVersion"[^)]+\)\{\w+\.exports="(\d+)"\}""")
    // MqttWebConfig endpoint, e.g. "endpoint":"wss:\/\/edge-chat.messenger.com\/chat?..."
    private val brokerPattern =
        Regex("""["']endpoint["']\s*:\s*["'](wss:\\?/\\?/[^"']+edge-chat[^"']+)["']""")
    private val appIdPattern =
        Regex("""["'](?:appID|app_id|appId)["']\s*:\s*["']?(\d{6,})["']?""")
    private val lsdPattern =
        Regex("""\["LSD",\[\],\{"token":"([^"]+)"\}""")
    private val dtsgPattern =
        Regex("""\["DTSGInitData",\[\],\{"token":"([^"]+)""")
    private val jazoestPattern = Regex("""jazoest=(\d+)""")
    // MqttWebDeviceID clientID — the web device id the inbox page registers server-side. It MUST be
    // reused as the MQTT cid (broker url) and CONNECT "d" field, otherwise the server accepts the
    // socket + pings but silently drops Lightspeed (LS) requests → FetchThreads never answered.
    // Matches ["MqttWebDeviceID",[],{"clientID":"..."} ...] in the SSJS config bundle.
    private val clientIdPattern =
        Regex("""\["MqttWebDeviceID",\[\],\{"clientID":"([^"]+)"""")
    // Page-embedded Lightspeed snapshot: "payload" is a stringified LightSpeedData JSON. We select
    // the payload that actually carries thread rows by content (see parseInitialSnapshot).
    private val snapshotPayloadPattern = Regex(""""payload"\s*:\s*"((?:\\.|[^"\\])*)"""")
    private val parentThreadKeyPattern = Regex("""["']parent_thread_key["']\s*:\s*(-?\d+)""")
    // "version":123... inside the LSPlatformGraphQLLightspeedRequest preloader payload (the primary
    // schema-version source on the inbox page). Values may be JSON-in-JSON escaped ("version":...
    // or \"version\":...), so allow an optional backslash before the quotes.
    private val lightspeedVersionPattern =
        Regex("""\\?"version\\?"\s*:\s*(\d{6,})""")
    private val syncParamsPattern =
        Regex("""["']?(mailbox|contact|e2ee)["']?\s*:\s*"((?:\\.|[^"\\])*)""")

    /**
     * Fetch + parse the bootstrap config. Always returns a [MetaConfig]; on any
     * failure it returns a config with [MetaConfig.loaded] = false and the
     * callers fall back to their previous hardcoded defaults.
     */
    suspend fun load(
        authData: MetaAuthData,
    ): MetaConfig = withContext(Dispatchers.IO) {
        try {
            // Bootstrap must load the MESSAGES/INBOX page (the one that embeds the Lightspeed
            // bundle + LSVersion), not the site root. Instagram's root (instagram.com/) is the
            // feed and does NOT carry the messaging LS bundle → versionId parses as 0 → every
            // FetchThreads LS request is invalid and times out (zero conversations). Mirror the Go
            // bridge, which loads the platform "messages" endpoint (IG = /direct/inbox/).
            val baseUrl = when (authData.platform) {
                MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_BASE_URL + "/"
                MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_BASE_URL + "/direct/inbox/"
            }
            val html = fetch(baseUrl, authData) ?: return@withContext MetaConfig()

            // Primary source: the SSJS-preloaded LSPlatformGraphQLLightspeedRequest carries the
            // schema "version" inline on the inbox page (ref modules.go handleRequire). Fall back to
            // the __d("LSVersion") module (inline, then crawled JS bundles).
            var versionId = parseLightspeedVersion(html)
                ?: versionPattern.find(html)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L

            // The page sometimes preloads the JS instead of inlining LSVersion.
            // Crawl a bounded number of linked script files looking for it.
            if (versionId == 0L) {
                versionId = crawlForVersion(html, authData)
            }

            val broker = brokerPattern.find(html)?.groupValues?.get(1)?.let { unescapeSlashes(it) }
                ?: defaultBroker(authData.platform)

            val appId = appIdPattern.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            val parentThreadKeys = parentThreadKeyPattern.findAll(html)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .toMutableSet()
                .also { if (-1L !in it) it.add(-1L) }
                .toList()
                .ifEmpty { listOf(-1L) }

            val lsd = lsdPattern.find(html)?.groupValues?.get(1) ?: ""
            val dtsg = dtsgPattern.find(html)?.groupValues?.get(1) ?: ""
            val jazoest = jazoestPattern.find(html)?.groupValues?.get(1) ?: ""
            val clientId = clientIdPattern.find(html)?.groupValues?.get(1) ?: ""

            val sync = parseSyncParams(html)
            val snapshotPayload = parseInitialSnapshot(html)
            val mailboxCursor = parseInitialCursor(html)

            val config = MetaConfig(
                versionId = versionId,
                broker = broker,
                appId = appId,
                parentThreadKeys = parentThreadKeys,
                lsdToken = lsd,
                fbDtsg = dtsg,
                jazoest = jazoest,
                clientId = clientId,
                syncParamsMailbox = sync.first,
                syncParamsContact = sync.second,
                syncParamsE2ee = sync.third,
                initialSnapshotPayload = snapshotPayload,
                // Resolve the snapshot's procedures via the full SP_TABLE (the page block's own
                // dependency list is unreliable / may belong to the wrong block).
                initialSnapshotSp = if (snapshotPayload != null) LightspeedDecoder.allDependencyNames() else emptyList(),
                mailboxCursor = mailboxCursor,
                loaded = versionId != 0L,
            )
            Log.i(
                TAG,
                "Bootstrap for ${authData.platform}: versionId=$versionId appId=$appId " +
                    "broker=$broker ptks=$parentThreadKeys lsd=${lsd.isNotEmpty()} " +
                    "clientId=${clientId.isNotEmpty()} snapshot=${snapshotPayload != null}(len=${snapshotPayload?.length ?: 0}) " +
                    "cursor=${mailboxCursor.isNotEmpty()} loaded=${config.loaded}",
            )
            config
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed for ${authData.platform}", e)
            MetaConfig()
        }
    }

    private suspend fun fetch(url: String, authData: MetaAuthData): String? {
        val resp = NetworkClient.execute(
            url,
            "GET",
            mapOf(
                "Cookie" to authData.toCookieHeader(),
                "User-Agent" to MetaProtocol.USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "sec-fetch-dest" to "document",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "none",
            ),
        )
        if (!resp.isSuccess) {
            Log.w(TAG, "Page fetch $url returned ${resp.status}")
            return null
        }
        return resp.text
    }

    private suspend fun crawlForVersion(
        html: String,
        authData: MetaAuthData,
    ): Long {
        val hrefPattern = Regex("<(?:link|script)[^>]+(?:href|src)=\"(https://[^\"]+\\.js[^\"]*)\"")
        val urls = hrefPattern.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(MAX_JS_CRAWL)
        for (jsUrl in urls) {
            try {
                val js = fetch(jsUrl, authData) ?: continue
                val v = versionPattern.find(js)?.groupValues?.get(1)?.toLongOrNull()
                if (v != null) {
                    Log.i(TAG, "Found LSVersion=$v in $jsUrl")
                    return v
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to crawl $jsUrl: ${e.message}")
            }
        }
        return 0L
    }

    /**
     * Extract the Lightspeed schema version from the inbox page's SSJS-preloaded
     * LSPlatformGraphQLLightspeedRequest payload (the primary source; ref Go modules.go
     * handleRequire). Anchors on the preloader id so we don't pick up an unrelated "version".
     */
    private fun parseLightspeedVersion(html: String): Long? {
        val idx = html.indexOf("LSPlatformGraphQLLightspeedRequest")
        if (idx < 0) return null
        val region = html.substring(idx, minOf(idx + 8000, html.length))
        return lightspeedVersionPattern.find(region)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Extract the page-embedded Lightspeed inbox snapshot payload — the one that actually carries
     * thread rows. The page contains SEVERAL lightspeed payloads (sync-transaction wrappers, ranges,
     * etc.); we select by CONTENT: the payload whose steps reference deleteThenInsertThread /
     * updateOrInsertThread. Returns the inner LightSpeedData JSON string, or null if none carries
     * threads. Resolution uses the full SP_TABLE (see [load]), so we don't need the block's own
     * dependency list. Mirrors Go modules.go LSPlatformGraphQLLightspeedRequestForIGDQuery.
     */
    private fun parseInitialSnapshot(html: String): String? {
        // The page contains several lightspeed payloads (sync-transaction wrappers, ranges, etc.);
        // pick by CONTENT — the payload whose steps reference deleteThenInsertThread/updateOrInsertThread.
        for (m in snapshotPayloadPattern.findAll(html)) {
            val escaped = m.groupValues[1]
            if (!escaped.contains("deleteThenInsertThread") && !escaped.contains("updateOrInsertThread")) {
                continue
            }
            val inner = try {
                Json.parseToJsonElement("\"" + escaped + "\"").jsonPrimitive.content
            } catch (e: Exception) {
                continue
            }
            if (inner.contains("deleteThenInsertThread") || inner.contains("updateOrInsertThread")) {
                return inner
            }
        }
        return null
    }

    /**
     * Extract the mailbox (database 1) cursor from the page's LSExecuteFirstBlockForSyncTransaction
     * payload. FetchMessages history requires this cursor (sync_group=1) — the Go bridge passes
     * GetCursor(1); an empty cursor makes the server return zero older messages. We pick the
     * sync-transaction block for database 1 and use its nextCursor (falling back to currentCursor).
     */
    private fun parseInitialCursor(html: String): String {
        for (m in snapshotPayloadPattern.findAll(html)) {
            val escaped = m.groupValues[1]
            if (!escaped.contains("executeFirstBlockForSyncTransaction")) continue
            val inner = try {
                Json.parseToJsonElement("\"" + escaped + "\"").jsonPrimitive.content
            } catch (e: Exception) {
                continue
            }
            if (!inner.contains("executeFirstBlockForSyncTransaction")) continue
            val cursor = try {
                val events = LightspeedDecoder.decodePublishResponse(inner, LightspeedDecoder.allDependencyNames())
                val blocks = MetaProtocol.parseSyncTransactions(events)
                val block = blocks.firstOrNull { it.databaseId == 1L } ?: blocks.firstOrNull()
                (block?.nextCursor?.takeIf { it.isNotEmpty() && it != "dummy_cursor" }
                    ?: block?.currentCursor.orEmpty())
            } catch (e: Exception) {
                ""
            }
            if (cursor.isNotEmpty()) return cursor
        }
        return ""
    }

    private fun parseSyncParams(html: String): Triple<String, String, String> {
        var mailbox = ""
        var contact = ""
        var e2ee = ""
        // Find the LSPlatformMessengerSyncParams blob if present, then read keys.
        val idx = html.indexOf("LSPlatformMessengerSyncParams")
        val region = if (idx >= 0) html.substring(idx, minOf(idx + 4000, html.length)) else ""
        if (region.isNotEmpty()) {
            for (m in syncParamsPattern.findAll(region)) {
                val key = m.groupValues[1]
                val value = unescapeJson(m.groupValues[2])
                when (key) {
                    "mailbox" -> if (mailbox.isEmpty()) mailbox = value
                    "contact" -> if (contact.isEmpty()) contact = value
                    "e2ee" -> if (e2ee.isEmpty()) e2ee = value
                }
            }
        }
        return Triple(mailbox, contact, e2ee)
    }

    private fun defaultBroker(platform: MetaAuthData.Platform): String = when (platform) {
        MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_MQTT_URL
        MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_MQTT_URL
    }

    private fun unescapeSlashes(s: String): String = s.replace("\\/", "/")

    private fun unescapeJson(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
}
