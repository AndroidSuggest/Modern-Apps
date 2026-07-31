@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.messages.meta

import kotlin.uuid.Uuid
import kotlin.concurrent.atomics.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

object MetaProtocol {
    private const val TAG = "MetaProtocol"

    // MQTT Topics (from messagix/topics.go)
    const val TOPIC_LS_APP_SETTINGS = "/ls_app_settings"
    const val TOPIC_LS_FOREGROUND_STATE = "/ls_foreground_state"
    const val TOPIC_LS_REQ = "/ls_req"
    const val TOPIC_LS_RESP = "/ls_resp"
    const val TOPIC_T_MS = "/t_ms"
    const val TOPIC_THREAD_TYPING = "/thread_typing"
    const val TOPIC_ORCA_TYPING_NOTIFICATIONS = "/orca_typing_notifications"
    const val TOPIC_ORCA_PRESENCE = "/orca_presence"
    const val TOPIC_LEGACY_WEB = "/legacy_web"
    const val TOPIC_WEBRTC = "/webrtc"
    const val TOPIC_BR_SR = "/br_sr"
    const val TOPIC_SR_RES = "/sr_res"
    const val TOPIC_GRAPHQL = "/graphql"

    // Messenger endpoints
    const val MESSENGER_BASE_URL = "https://www.messenger.com"
    const val MESSENGER_MQTT_URL = "wss://edge-chat.messenger.com/chat?"

    // Instagram endpoints
    const val INSTAGRAM_BASE_URL = "https://www.instagram.com"
    const val INSTAGRAM_MQTT_URL = "wss://edge-chat.instagram.com/chat?"

    // Connection codes (from messagix/codes.go)
    const val CONNECTION_ACCEPTED = 0
    const val CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION = 1
    const val CONNECTION_REFUSED_IDENTIFIER_REJECTED = 2
    const val CONNECTION_REFUSED_SERVER_UNAVAILABLE = 3
    const val CONNECTION_REFUSED_BAD_USERNAME_OR_PASSWORD = 4
    const val CONNECTION_REFUSED_UNAUTHORIZED = 5
    const val CONNECTION_REFUSED_UNKNOWN_24 = 24

    // Thread types (from messagix/table/thread.go)
    const val THREAD_TYPE_ONE_TO_ONE = 1
    const val THREAD_TYPE_GROUP = 2
    const val THREAD_TYPE_FOLDER = 3
    const val THREAD_TYPE_MARKETPLACE = 4
    const val THREAD_TYPE_ENCRYPTED_OVER_WA_ONE_TO_ONE = 5
    const val THREAD_TYPE_ENCRYPTED_OVER_WA_GROUP = 6
    const val THREAD_TYPE_COMMUNITY_GROUP = 7
    const val THREAD_TYPE_UNKNOWN = 0

    // Message-ID epoch (messagix/methods/methods.go MetaEpochMS)
    const val META_EPOCH_MS = 1072915200000L

    // Folder names (from connector/events.go)
    const val FOLDER_INBOX = "inbox"
    const val FOLDER_OTHER = "other"
    const val FOLDER_SPAM = "spam"
    const val FOLDER_PENDING = "pending"
    const val FOLDER_MONTAGE = "montage"
    const val FOLDER_HIDDEN = "hidden"
    const val FOLDER_LEGACY = "legacy"
    const val FOLDER_DISABLED = "disabled"
    const val FOLDER_PAGE_BACKGROUND = "page_background"
    const val FOLDER_PAGE_DONE = "page_done"
    const val FOLDER_BLOCKED = "blocked"
    const val FOLDER_COMMUNITY = "community"
    const val FOLDER_RESTRICTED = "restricted"
    const val FOLDER_BC_PARTNERSHIP = "bc_partnership"
    const val FOLDER_E2EE_CUTOVER = "e2ee_cutover"
    const val FOLDER_E2EE_CUTOVER_ARCHIVED = "e2ee_cutover_archived"
    const val FOLDER_E2EE_CUTOVER_PENDING = "e2ee_cutover_pending"
    const val FOLDER_E2EE_CUTOVER_OTHER = "e2ee_cutover_other"
    const val FOLDER_INTEROP = "interop"
    const val FOLDER_ARCHIVED = "archived"
    const val FOLDER_AI_ACTIVE = "ai_active"
    const val FOLDER_SALSA_RESTRICTED = "salsa_restricted"
    const val FOLDER_MESSENGER_MARKETING_MESSAGE = "messenger_marketing_message"

    // Capabilities (from connector/capabilities.go)
    const val MAX_TEXT_LENGTH = 20000
    const val MAX_FILE_SIZE = 25 * 1000 * 1000
    const val MAX_FILE_SIZE_WITH_E2E = 100 * 1000 * 1000
    const val MAX_IMAGE_SIZE = 8 * 1000 * 1000
    const val EDIT_MAX_COUNT = 5
    const val EDIT_MAX_AGE_MINUTES = 15

    // Well-known Meta AI IDs (from connector/userinfo.go)
    const val META_AI_INSTAGRAM_ID = 656175869434325L
    const val META_AI_MESSENGER_ID = 156025504001094L

    // LS Request types (from messagix/socket.go)
    const val LS_REQUEST_TYPE_DB_QUERY = 1
    const val LS_REQUEST_TYPE_DB_QUERY_CURSOR = 2
    const val LS_REQUEST_TYPE_TASK = 3
    const val LS_REQUEST_TYPE_STATELESS = 4

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Task-body serializer that mirrors Go's `omitempty`: encodeDefaults keeps the always-present
    // zero fields (skip_url_preview_gen, text_has_links, multitab_env) while explicitNulls=false
    // drops nullable fields, so the SendMessageTask wire body matches messagix exactly.
    private val bodyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val nextTaskId = AtomicLong(-1)

    fun getTaskId(): Long = nextTaskId.incrementAndFetch()

    // --- Connect JSON (from messagix/json.go) ---

    @Serializable
    data class ConnectJson(
        @SerialName("u") val accountId: String,
        @SerialName("s") val sessionId: Long,
        @SerialName("cp") val clientCapabilities: Int = 3,
        @SerialName("ecp") val capabilities: Int = 10,
        @SerialName("chat_on") val chatOn: Boolean = true,
        @SerialName("fg") val fg: Boolean = false,
        @SerialName("d") val cid: String,
        @SerialName("ct") val connectionType: String,
        @SerialName("mqtt_sid") val mqttSid: String = "",
        @SerialName("aid") val appId: Long,
        @SerialName("st") val subscribedTopics: List<String> = emptyList(),
        @SerialName("pm") val postMessage: List<ConnectPostMessage> = emptyList(),
        @SerialName("dc") val dc: String = "",
        @SerialName("no_auto_fg") val noAutoFg: Boolean = true,
        @SerialName("gas") val gas: JsonElement = JsonNull,
        @SerialName("pack") val pack: List<JsonElement> = emptyList(),
        @SerialName("php_override") val hostNameOverride: String = "",
        @SerialName("p") val p: JsonElement = JsonNull,
        @SerialName("a") val userAgent: String,
        @SerialName("aids") val aids: JsonElement = JsonNull,
    )

    @Serializable
    data class ConnectPostMessage(
        val isBase64Publish: Boolean = false,
        val messageId: Long = 65536,
        val payload: String,
        val qos: Int = 1,
        val topic: String,
    )

    @Serializable
    data class AppSettingsPublish(
        @SerialName("ls_fdid") val lsFdid: String = "",
        @SerialName("ls_sv") val schemaVersion: String,
    )

    fun buildConnectJson(
        accountId: String,
        sessionId: Long,
        appId: Long,
        cid: String,
        platform: MetaAuthData.Platform,
        subscribedTopics: List<String> = emptyList(),
        hostNameOverride: String = "",
        previouslyConnected: Boolean = false,
        versionId: Long = 0,
    ): String {
        val connectionType = when (platform) {
            MetaAuthData.Platform.INSTAGRAM -> "cookie_auth"
            MetaAuthData.Platform.MESSENGER -> "websocket"
        }

        var topics = subscribedTopics.toMutableList()
        var postMessages = emptyList<ConnectPostMessage>()

        if (previouslyConnected) {
            val appSettingsJson = json.encodeToString(
                AppSettingsPublish(schemaVersion = versionId.toString())
            )
            postMessages = listOf(
                ConnectPostMessage(
                    payload = appSettingsJson,
                    topic = TOPIC_LS_APP_SETTINGS,
                )
            )
            if (TOPIC_LS_FOREGROUND_STATE !in topics) topics.add(TOPIC_LS_FOREGROUND_STATE)
            if (TOPIC_LS_RESP !in topics) topics.add(TOPIC_LS_RESP)
            if ("/t_ms" !in topics) topics.add("/t_ms")
            if (TOPIC_THREAD_TYPING !in topics) topics.add(TOPIC_THREAD_TYPING)
            if (TOPIC_ORCA_TYPING_NOTIFICATIONS !in topics) topics.add(TOPIC_ORCA_TYPING_NOTIFICATIONS)
            if ("/orca_presence" !in topics) topics.add("/orca_presence")
        }

        val connectPayload = ConnectJson(
            accountId = accountId,
            sessionId = sessionId,
            cid = cid,
            connectionType = connectionType,
            appId = appId,
            subscribedTopics = topics,
            postMessage = postMessages,
            hostNameOverride = hostNameOverride,
            userAgent = USER_AGENT,
        )

        return json.encodeToString(connectPayload)
    }

    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    // --- Task payloads (from messagix/socket/threads.go, messages.go) ---

    @Serializable
    data class TaskPayload(
        @SerialName("epoch_id") val epochId: Long,
        @SerialName("data_trace_id") val dataTraceId: String = "",
        val tasks: List<TaskData> = emptyList(),
        @SerialName("version_id") val versionId: String,
    )

    @Serializable
    data class TaskData(
        @SerialName("failure_count") val failureCount: JsonElement = JsonNull,
        val label: String,
        val payload: String,
        @SerialName("queue_name") val queueName: JsonElement,
        @SerialName("task_id") val taskId: Long,
    )

    @Serializable
    data class DatabaseQuery(
        val database: Long,
        @SerialName("last_applied_cursor") val lastAppliedCursor: String? = null,
        @SerialName("sync_params") val syncParams: String? = null,
        @SerialName("epoch_id") val epochId: Long,
        @SerialName("data_trace_id") val dataTraceId: String = "",
        val version: String,
        @SerialName("failure_count") val failureCount: JsonElement = JsonNull,
    )

    @Serializable
    data class SendMessageTask(
        @SerialName("thread_id") val threadId: Long,
        @SerialName("otid") val otid: String,
        val source: Int = 65537,
        @SerialName("send_type") val sendType: Int = 1,
        // Nullable + serialized with explicitNulls=false so these mirror Go's `omitempty` and are
        // OMITTED (not sent as null/0) for a plain text send. The IG server treats a text send with
        // extraneous attachment_fbids/reply_metadata/mention_data as malformed and no-ops it (the
        // task envelope is still acked with taskExists/removeTask but no message is created) — this
        // was the "send pretends to work but doesn't deliver" root cause. Ref socket/threads.go.
        @SerialName("attachment_fbids") val attachmentFbIds: List<Long>? = null,
        @SerialName("sync_group") val syncGroup: Long = 1,
        @SerialName("reply_metadata") val replyMetadata: ReplyMetaData? = null,
        @SerialName("mention_data") val mentionData: MentionData? = null,
        val text: String = "",
        @SerialName("hot_emoji_size") val hotEmojiSize: Int? = null,
        @SerialName("sticker_id") val stickerId: Long? = null,
        @SerialName("initiating_source") val initiatingSource: Int = 1,
        @SerialName("skip_url_preview_gen") val skipUrlPreviewGen: Int = 0,
        @SerialName("text_has_links") val textHasLinks: Int = 0,
        @SerialName("strip_forwarded_msg_caption") val stripForwardedMsgCaption: Int? = null,
        @SerialName("forwarded_msg_id") val forwardedMsgId: String? = null,
        @SerialName("multitab_env") val multitabEnv: Int = 0,
        val url: String? = null,
        @SerialName("attribution_app_id") val attributionAppId: Long? = null,
    )

    @Serializable
    data class ReplyMetaData(
        @SerialName("reply_source_id") val replySourceId: String,
        @SerialName("reply_source_type") val replySourceType: Long = 1,
        @SerialName("reply_type") val replyType: Long = 0,
    )

    @Serializable
    data class MentionData(
        @SerialName("mention_ids") val mentionIds: String,
        @SerialName("mention_offsets") val mentionOffsets: String,
        @SerialName("mention_lengths") val mentionLengths: String,
        @SerialName("mention_types") val mentionTypes: String,
    )

    @Serializable
    data class SendReactionTask(
        @SerialName("thread_key") val threadKey: Long = 0,
        @SerialName("timestamp_ms") val timestampMs: Long = System.currentTimeMillis(),
        @SerialName("message_id") val messageId: String,
        @SerialName("actor_id") val actorId: Long,
        val reaction: String,
        @SerialName("reaction_style") val reactionStyle: JsonElement = JsonNull,
        @SerialName("sync_group") val syncGroup: Int = 1,
        @SerialName("send_attribution") val sendAttribution: Int = 65537,
    )

    @Serializable
    data class ThreadMarkReadTask(
        @SerialName("thread_id") val threadId: Long,
        @SerialName("last_read_watermark_ts") val lastReadWatermarkTs: Long,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    @Serializable
    data class FetchThreadsTask(
        @SerialName("is_after") val isAfter: Int = 0,
        @SerialName("parent_thread_key") val parentThreadKey: Long = -1,
        @SerialName("reference_thread_key") val referenceThreadKey: Long = 0,
        @SerialName("reference_activity_timestamp") val referenceActivityTimestamp: Long = 9999999999999,
        @SerialName("additional_pages_to_fetch") val additionalPagesToFetch: Int = 0,
        val cursor: JsonElement = JsonNull,
        @SerialName("messaging_tag") val messagingTag: JsonElement = JsonNull,
        @SerialName("sync_group") val syncGroup: Int = 1,
    )

    @Serializable
    data class FetchMessagesTask(
        @SerialName("thread_key") val threadKey: Long,
        val direction: Long = 0,
        @SerialName("reference_timestamp_ms") val referenceTimestampMs: Long,
        @SerialName("reference_message_id") val referenceMessageId: String,
        @SerialName("sync_group") val syncGroup: Long = 1,
        val cursor: String = "",
    )

    @Serializable
    data class ReportAppStateTask(
        @SerialName("app_state") val appState: Int = 1, // FOREGROUND
        @SerialName("request_id") val requestId: String,
    )

    // Task labels (from messagix/socket/task.go)
    val TASK_LABELS = mapOf(
        "UpdatePresence" to "3",
        "ThreadMarkRead" to "21",
        "AddParticipantsTask" to "23",
        "UpdateAdminTask" to "25",
        "SendReactionTask" to "29",
        "SearchUserTask" to "30",
        "SearchUserSecondaryTask" to "31",
        "RenameThreadTask" to "32",
        "DeleteMessageTask" to "33",
        "SetThreadImageTask" to "37",
        "SendMessageTask" to "46",
        "ReportAppStateTask" to "123",
        "CreateGroupTask" to "130",
        "RemoveParticipantTask" to "140",
        "MuteThreadTask" to "144",
        "FetchThreadsTask" to "145",
        "DeleteThreadTask" to "146",
        "DeleteMessageMeOnlyTask" to "155",
        "CreatePollTask" to "163",
        "UpdatePollTask" to "164",
        "GetContactsFullTask" to "207",
        "CreateThreadTask" to "209",
        "FetchMessagesTask" to "228",
        "FetchCommunityMemberList" to "355",
        "CreateWhatsAppThreadTask" to "388",
        "GetContactsTask" to "452",
        "CommunityThreadHoleDetection" to "501",
        "FetchReactionsV2UserList" to "577",
        "SendReactionV2" to "604",
        "DeleteCommunitySubThread" to "639",
        "CreateCommunitySubThread" to "665",
        "FetchAdditionalThreadData" to "733",
        "EditMessageTask" to "742",
        "AcceptMessageRequestTask" to "43",
    )

    // --- LS Request wrapper (from messagix/socket.go) ---

    @Serializable
    data class SocketLSRequestPayload(
        @SerialName("app_id") val appId: String,
        val payload: String,
        @SerialName("request_id") val requestId: Int,
        val type: Int,
    )

    // --- Parsed incoming message ---

    data class MetaMessage(
        val messageId: String,
        val threadId: String,
        val senderId: String,
        val senderName: String?,
        val text: String,
        val timestamp: Long,
        val isGroup: Boolean,
    )

    data class MqttMessage(
        val topic: String,
        val payload: ByteArray,
        val packetId: Int = 0,
        val qos: Int = 0,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MqttMessage) return false
            return topic == other.topic && payload.contentEquals(other.payload) &&
                packetId == other.packetId && qos == other.qos
        }
        override fun hashCode(): Int {
            var result = topic.hashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + packetId
            result = 31 * result + qos
            return result
        }
    }

    // Dedicated lenient parser for inbound /ls_resp envelopes: coerceInputValues so a null on any
    // non-nullable field (with a default) can't abort the whole parse the way "name":null did (#34).
    private val responseJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun parsePublishResponse(payload: ByteArray): LightspeedDecoder.PublishResponseData? {
        return try {
            val jsonStr = String(payload, Charsets.UTF_8)
            responseJson.decodeFromString<LightspeedDecoder.PublishResponseData>(jsonStr)
        } catch (e: Exception) {
            null
        }
    }

    fun parseMessage(events: List<LightspeedDecoder.DecodedEvent>): MetaMessage? {
        for (event in events) {
            when (event.procedureName) {
                "LSInsertNewMessageRange",
                "LSUpsertMessage",
                "LSInsertMessage",
                "LSAddNewMessage",
                -> {
                    val args = event.args
                    if (args.size < 5) continue
                    val text = args.getOrNull(0)?.toString() ?: ""
                    val threadId = args.getOrNull(1)?.toString() ?: continue
                    val messageId = args.getOrNull(2)?.toString() ?: continue
                    val timestamp = (args.getOrNull(3) as? Long) ?: System.currentTimeMillis()
                    val senderId = args.getOrNull(4)?.toString() ?: ""
                    val senderName = args.getOrNull(5)?.toString()?.takeIf { it.isNotBlank() }
                    val isGroup = (threadId.toLongOrNull() ?: 0) < 0

                    return MetaMessage(
                        messageId = messageId,
                        threadId = threadId,
                        senderId = senderId,
                        senderName = senderName,
                        text = text,
                        timestamp = timestamp,
                        isGroup = isGroup,
                    )
                }
            }
        }
        return null
    }

    fun buildTaskPayload(
        label: String,
        taskPayloadJson: String,
        queueName: Any,
        versionId: Long,
    ): String {
        val taskId = getTaskId()
        val epochId = generateEpochId()

        val queueNameElement: JsonElement = when (queueName) {
            is String -> JsonPrimitive(queueName)
            is List<*> -> {
                val arr = queueName.filterIsInstance<String>()
                val encoded = json.encodeToString(arr)
                JsonPrimitive(encoded)
            }
            else -> JsonPrimitive(queueName.toString())
        }

        val payload = TaskPayload(
            epochId = epochId,
            versionId = versionId.toString(),
            tasks = listOf(
                TaskData(
                    label = label,
                    payload = taskPayloadJson,
                    queueName = queueNameElement,
                    taskId = taskId,
                )
            ),
        )
        return json.encodeToString(payload)
    }

    fun buildSendMessagePayload(threadId: Long, text: String, versionId: Long): String {
        val otid = generateEpochId().toString()
        val task = SendMessageTask(
            threadId = threadId,
            otid = otid,
            text = text,
        )
        val taskJson = bodyJson.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SendMessageTask"] ?: "46",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    fun buildMarkReadPayload(threadId: Long, versionId: Long, watermarkTs: Long = System.currentTimeMillis()): String {
        val task = ThreadMarkReadTask(
            threadId = threadId,
            lastReadWatermarkTs = watermarkTs,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["ThreadMarkRead"] ?: "21",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    fun buildReactionPayload(
        threadKey: Long,
        messageId: String,
        reaction: String,
        actorId: Long,
        versionId: Long,
    ): String {
        val task = SendReactionTask(
            threadKey = threadKey,
            messageId = messageId,
            reaction = reaction,
            actorId = actorId,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SendReactionTask"] ?: "29",
            taskPayloadJson = taskJson,
            queueName = listOf("reaction", messageId),
            versionId = versionId,
        )
    }

    fun buildDatabaseQueryPayload(databaseId: Long, versionId: Long, cursor: String? = null): String {
        val query = DatabaseQuery(
            database = databaseId,
            epochId = generateEpochId(),
            version = versionId.toString(),
            lastAppliedCursor = cursor,
        )
        return json.encodeToString(query)
    }

    // --- DB SyncManager support (from messagix/syncManager.go) ---

    // SyncChannel values (socket/database.go)
    const val SYNC_CHANNEL_MAILBOX = 1L
    const val SYNC_CHANNEL_CONTACT = 2L
    const val SYNC_CHANNEL_E2EE = 3L

    /**
     * Builds the socket DatabaseQuery payload used by the SyncManager.
     * When [sendSyncParams] is true the payload carries sync_params (LS request
     * type 1); otherwise it carries last_applied_cursor (type 2). Ref
     * syncManager.go SyncSocketData.
     */
    fun buildDatabaseSyncPayload(
        databaseId: Long,
        versionId: Long,
        sendSyncParams: Boolean,
        syncParams: String?,
        cursor: String?,
    ): String {
        val query = if (sendSyncParams) {
            DatabaseQuery(
                database = databaseId,
                epochId = generateEpochId(),
                version = versionId.toString(),
                syncParams = syncParams ?: "",
                lastAppliedCursor = null,
            )
        } else {
            DatabaseQuery(
                database = databaseId,
                epochId = generateEpochId(),
                version = versionId.toString(),
                syncParams = null,
                lastAppliedCursor = cursor,
            )
        }
        return json.encodeToString(query)
    }

    data class SyncTransactionBlock(
        val databaseId: Long,
        val currentCursor: String,
        val nextCursor: String,
        val sendSyncParams: Boolean,
        val syncChannel: Long,
    )

    data class SyncGroupRange(
        val syncGroup: Long,
        val parentThreadKey: Long,
        val minLastActivityTimestampMs: Long,
        val hasMoreBefore: Boolean,
        val minThreadKey: Long,
    )

    // LSExecuteFirstBlockForSyncTransaction: databaseId@0, currentCursor@2,
    // nextCursor@3, sendSyncParams@5, syncChannel@8 (table/sync_groups.go).
    fun parseSyncTransactions(events: List<LightspeedDecoder.DecodedEvent>): List<SyncTransactionBlock> {
        val out = mutableListOf<SyncTransactionBlock>()
        for (event in events) {
            if (event.procedureName != "LSExecuteFirstBlockForSyncTransaction") continue
            val args = event.args
            val dbId = args.argLong(0) ?: continue
            out.add(
                SyncTransactionBlock(
                    databaseId = dbId,
                    currentCursor = args.argStr(2) ?: "",
                    nextCursor = args.argStr(3) ?: "",
                    sendSyncParams = args.argBool(5),
                    syncChannel = args.argLong(8) ?: 0L,
                )
            )
        }
        return out
    }

    // LSUpsertSyncGroupThreadsRange: syncGroup@0, parentThreadKey@1,
    // minLastActivityTimestampMS@2, hasMoreBefore@3, minThreadKey@5.
    fun parseSyncGroupRanges(events: List<LightspeedDecoder.DecodedEvent>): List<SyncGroupRange> {
        val out = mutableListOf<SyncGroupRange>()
        for (event in events) {
            if (event.procedureName != "LSUpsertSyncGroupThreadsRange") continue
            val args = event.args
            val syncGroup = args.argLong(0) ?: continue
            out.add(
                SyncGroupRange(
                    syncGroup = syncGroup,
                    parentThreadKey = args.argLong(1) ?: -1L,
                    minLastActivityTimestampMs = args.argLong(2) ?: 9999999999999L,
                    hasMoreBefore = args.argBool(3),
                    minThreadKey = args.argLong(5) ?: 0L,
                )
            )
        }
        return out
    }

    fun buildLSRequestJson(appId: String, payload: String, requestId: Int, type: Int): String {
        val lsReq = SocketLSRequestPayload(
            appId = appId,
            payload = payload,
            requestId = requestId,
            type = type,
        )
        return json.encodeToString(lsReq)
    }

    @Serializable
    data class DeleteMessageTask(
        @SerialName("message_id") val messageId: String,
    )

    @Serializable
    data class DeleteMessageMeOnlyTask(
        @SerialName("thread_key") val threadKey: Long = 0,
        @SerialName("message_id") val messageId: String,
    )

    @Serializable
    data class EditMessageTask(
        @SerialName("message_id") val messageId: String,
        val text: String,
    )

    fun buildFetchThreadsPayload(
        versionId: Long,
        syncGroup: Int = 1,
        parentThreadKey: Long = -1,
        referenceThreadKey: Long = 0,
        referenceActivityTimestamp: Long = 9999999999999,
    ): String {
        val task = FetchThreadsTask(
            syncGroup = syncGroup,
            parentThreadKey = parentThreadKey,
            referenceThreadKey = referenceThreadKey,
            referenceActivityTimestamp = referenceActivityTimestamp,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["FetchThreadsTask"] ?: "145",
            taskPayloadJson = taskJson,
            queueName = "trq",
            versionId = versionId,
        )
    }

    fun buildFetchMessagesPayload(
        threadKey: Long,
        referenceTimestampMs: Long,
        referenceMessageId: String,
        versionId: Long,
        cursor: String = "",
    ): String {
        val task = FetchMessagesTask(
            threadKey = threadKey,
            referenceTimestampMs = referenceTimestampMs,
            referenceMessageId = referenceMessageId,
            cursor = cursor,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["FetchMessagesTask"] ?: "228",
            taskPayloadJson = taskJson,
            queueName = "mrq.$threadKey",
            versionId = versionId,
        )
    }

    fun buildReportAppStatePayload(versionId: Long): String {
        val task = ReportAppStateTask(
            requestId = Uuid.random().toString(),
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["ReportAppStateTask"] ?: "123",
            taskPayloadJson = taskJson,
            queueName = "ls_presence_report_app_state",
            versionId = versionId,
        )
    }

    fun buildDeleteMessagePayload(
        messageId: String,
        versionId: Long,
    ): String {
        val task = DeleteMessageTask(
            messageId = messageId,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["DeleteMessageTask"] ?: "33",
            taskPayloadJson = taskJson,
            queueName = "unsend_message",
            versionId = versionId,
        )
    }

    fun buildDeleteMessageMeOnlyPayload(
        threadKey: Long,
        messageId: String,
        versionId: Long,
    ): String {
        val task = DeleteMessageMeOnlyTask(
            threadKey = threadKey,
            messageId = messageId,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["DeleteMessageMeOnlyTask"] ?: "155",
            taskPayloadJson = taskJson,
            queueName = "155",
            versionId = versionId,
        )
    }

    fun buildEditMessagePayload(
        messageId: String,
        text: String,
        versionId: Long,
    ): String {
        val task = EditMessageTask(
            messageId = messageId,
            text = text,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["EditMessageTask"] ?: "742",
            taskPayloadJson = taskJson,
            queueName = "edit_message",
            versionId = versionId,
        )
    }

    @Serializable
    data class DeleteThreadTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("remove_type") val removeType: Long = 0,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    fun buildDeleteThreadPayload(
        threadKey: Long,
        versionId: Long,
        syncGroup: Long = 1,
    ): String {
        val task = DeleteThreadTask(threadKey = threadKey, syncGroup = syncGroup)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["DeleteThreadTask"] ?: "146",
            taskPayloadJson = taskJson,
            queueName = threadKey.toString(),
            versionId = versionId,
        )
    }

    @Serializable
    data class MuteThreadTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("mailbox_type") val mailboxType: Long = 0,
        @SerialName("mute_expire_time_ms") val muteExpireTimeMs: Long,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    fun buildMuteThreadPayload(
        threadKey: Long,
        muteExpireTimeMs: Long,
        versionId: Long,
    ): String {
        val task = MuteThreadTask(threadKey = threadKey, muteExpireTimeMs = muteExpireTimeMs)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["MuteThreadTask"] ?: "144",
            taskPayloadJson = taskJson,
            queueName = threadKey.toString(),
            versionId = versionId,
        )
    }

    @Serializable
    data class RenameThreadTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("thread_name") val threadName: String,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    fun buildRenameThreadPayload(
        threadKey: Long,
        threadName: String,
        versionId: Long,
    ): String {
        val task = RenameThreadTask(threadKey = threadKey, threadName = threadName)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["RenameThreadTask"] ?: "32",
            taskPayloadJson = taskJson,
            queueName = threadKey.toString(),
            versionId = versionId,
        )
    }

    // --- Additional task data classes (from Go bridge socket/*.go) ---

    @Serializable
    data class AddParticipantsTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("contact_ids") val contactIds: List<Long>,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    @Serializable
    data class RemoveParticipantTask(
        @SerialName("thread_id") val threadId: Long,
        @SerialName("contact_id") val contactId: Long,
    )

    @Serializable
    data class SearchUserTask(
        val query: String,
        @SerialName("supported_types") val supportedTypes: List<Int> = listOf(1, 3, 4, 2, 6, 7, 8, 9),
        @SerialName("session_id") val sessionId: JsonElement = JsonNull,
        @SerialName("surface_type") val surfaceType: Int = 15,
        @SerialName("selected_participants") val selectedParticipants: JsonElement = JsonNull,
        @SerialName("group_id") val groupId: JsonElement = JsonNull,
        @SerialName("community_id") val communityId: JsonElement = JsonNull,
        @SerialName("query_id") val queryId: JsonElement = JsonNull,
        @kotlinx.serialization.Transient val secondary: Boolean = false,
    )

    @Serializable
    data class SetThreadImageTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("image_id") val imageId: Long,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    @Serializable
    data class CreateGroupTask(
        val participants: List<Long>,
        @SerialName("send_payload") val sendPayload: CreateGroupPayload,
    )

    @Serializable
    data class CreateGroupPayload(
        @SerialName("thread_id") val threadId: Long,
        @SerialName("otid") val otid: String,
        val source: Int = 0,
        @SerialName("send_type") val sendType: Int = 8,
    )

    @Serializable
    data class CreatePollTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("question_text") val questionText: String,
        val options: List<String>,
        @SerialName("sync_group") val syncGroup: Long = 1,
    )

    @Serializable
    data class UpdatePresenceTask(
        @SerialName("thread_key") val threadKey: Long,
        @SerialName("is_group_thread") val isGroupThread: Long,
        @SerialName("is_typing") val isTyping: Long,
        val attribution: Long = 0,
        @SerialName("sync_group") val syncGroup: Long = 1,
        @SerialName("thread_type") val threadType: Long = 0,
    )

    @Serializable
    data class SendMediaTask(
        @SerialName("thread_id") val threadId: Long,
        @SerialName("otid") val otid: String,
        val source: Int = 0,
        @SerialName("send_type") val sendType: Int = 3,
        @SerialName("attachment_fbids") val attachmentFbIds: List<Long>,
        @SerialName("sync_group") val syncGroup: Long = 1,
        val text: String = "",
        @SerialName("mime_type") val mimeType: String? = null,
        @SerialName("file_name") val fileName: String? = null,
        @SerialName("initiating_source") val initiatingSource: Int = 0,
        @SerialName("skip_url_preview_gen") val skipUrlPreviewGen: Int = 0,
        @SerialName("text_has_links") val textHasLinks: Int = 0,
        @SerialName("multitab_env") val multitabEnv: Int = 0,
    )

    fun buildAddParticipantsPayload(threadKey: Long, contactIds: List<Long>, versionId: Long): String {
        val task = AddParticipantsTask(threadKey = threadKey, contactIds = contactIds)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["AddParticipantsTask"] ?: "23",
            taskPayloadJson = taskJson,
            queueName = threadKey.toString(),
            versionId = versionId,
        )
    }

    fun buildRemoveParticipantPayload(threadId: Long, contactId: Long, versionId: Long): String {
        val task = RemoveParticipantTask(threadId = threadId, contactId = contactId)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["RemoveParticipantTask"] ?: "140",
            taskPayloadJson = taskJson,
            queueName = "remove_participant_v2",
            versionId = versionId,
        )
    }

    fun buildSearchUserPayload(query: String, versionId: Long, isMessenger: Boolean = false): String {
        val supportedTypes = if (isMessenger) {
            listOf(1, 3, 4, 2, 6, 7, 8, 9, 13)
        } else {
            listOf(1, 3, 4, 2, 6, 7, 8, 9)
        }
        val task = SearchUserTask(
            query = query,
            supportedTypes = supportedTypes,
            surfaceType = if (isMessenger) 5 else 15,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SearchUserTask"] ?: "30",
            taskPayloadJson = taskJson,
            queueName = listOf("search_primary", System.currentTimeMillis().toString()),
            versionId = versionId,
        )
    }

    fun buildSearchUserSecondaryPayload(query: String, versionId: Long, isMessenger: Boolean = false): String {
        val supportedTypes = if (isMessenger) {
            listOf(1, 3, 4, 2, 6, 7, 8, 9, 13)
        } else {
            listOf(1, 3, 4, 2, 6, 7, 8, 9)
        }
        val task = SearchUserTask(
            query = query,
            supportedTypes = supportedTypes,
            surfaceType = if (isMessenger) 5 else 15,
            secondary = true,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SearchUserSecondaryTask"] ?: "31",
            taskPayloadJson = taskJson,
            queueName = "search_secondary",
            versionId = versionId,
        )
    }

    fun buildSetThreadImagePayload(threadKey: Long, imageId: Long, versionId: Long): String {
        val task = SetThreadImageTask(threadKey = threadKey, imageId = imageId)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SetThreadImageTask"] ?: "37",
            taskPayloadJson = taskJson,
            queueName = "thread_image",
            versionId = versionId,
        )
    }

    fun buildCreateGroupPayload(participants: List<Long>, versionId: Long): String {
        val threadId = generateEpochId()
        val otid = generateEpochId().toString()
        val task = CreateGroupTask(
            participants = participants,
            sendPayload = CreateGroupPayload(threadId = threadId, otid = otid),
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["CreateGroupTask"] ?: "130",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    /**
     * Build a CreatePollTask payload. [allowMultiple] is accepted to match the shared poll
     * contract, but the Meta CreatePollTask schema (messagix socket/threads.go) has no
     * multiple-choice field, so it currently has no wire representation — Messenger/Instagram
     * polls accept multiple votes per the server default.
     */
    fun buildCreatePollPayload(
        threadKey: Long,
        question: String,
        options: List<String>,
        versionId: Long,
        allowMultiple: Boolean = false,
    ): String {
        val task = CreatePollTask(threadKey = threadKey, questionText = question, options = options)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["CreatePollTask"] ?: "163",
            taskPayloadJson = taskJson,
            queueName = "poll_creation",
            versionId = versionId,
        )
    }

    @Serializable
    data class StatelessTaskData(
        val label: String,
        val payload: String,
        val version: String,
    )

    fun buildTypingIndicatorPayload(threadKey: Long, isTyping: Boolean, isGroup: Boolean, versionId: Long, threadType: Long = 0): String {
        val task = UpdatePresenceTask(
            threadKey = threadKey,
            isGroupThread = if (isGroup) 1L else 0L,
            isTyping = if (isTyping) 1L else 0L,
            threadType = threadType,
        )
        val taskJson = json.encodeToString(task)
        val statelessPayload = StatelessTaskData(
            label = TASK_LABELS["UpdatePresence"] ?: "3",
            payload = taskJson,
            version = versionId.toString(),
        )
        return json.encodeToString(statelessPayload)
    }

    fun buildSendMediaPayload(threadId: Long, attachmentFbIds: List<Long>, text: String, versionId: Long): String {
        val otid = generateEpochId().toString()
        val task = SendMediaTask(
            threadId = threadId,
            otid = otid,
            attachmentFbIds = attachmentFbIds,
            text = text,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SendMessageTask"] ?: "46",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    fun buildSendMediaPayload(
        threadId: Long,
        attachmentFbIds: List<Long>,
        text: String,
        mimeType: String?,
        fileName: String?,
        versionId: Long,
    ): String {
        val otid = generateEpochId().toString()
        val task = SendMediaTask(
            threadId = threadId,
            otid = otid,
            attachmentFbIds = attachmentFbIds,
            text = text,
            mimeType = mimeType,
            fileName = fileName,
        )
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["SendMessageTask"] ?: "46",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    @Serializable
    data class AcceptMessageRequestTask(
        @SerialName("thread_key") val threadKey: Long,
    )

    fun buildAcceptMessageRequestPayload(threadId: Long, versionId: Long): String {
        val task = AcceptMessageRequestTask(threadKey = threadId)
        val taskJson = json.encodeToString(task)
        return buildTaskPayload(
            label = TASK_LABELS["AcceptMessageRequestTask"] ?: "43",
            taskPayloadJson = taskJson,
            queueName = threadId.toString(),
            versionId = versionId,
        )
    }

    // --- Enriched message model (from Go bridge table/wrapped_message.go) ---

    data class MessageAttachment(
        val id: String,
        val mimeType: String?,
        val fileName: String?,
        val url: String?,
        val size: Long?,
        val previewUrl: String? = null,
        // image / video / audio / sticker / file / share
        val attachmentType: String? = null,
        val title: String? = null,
        val actionUrl: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    )

    data class MetaMessageEnriched(
        val messageId: String,
        val threadId: String,
        val senderId: String,
        val senderName: String?,
        val text: String,
        val timestamp: Long,
        val isGroup: Boolean,
        val attachments: List<MessageAttachment> = emptyList(),
        val replyToMessageId: String? = null,
        val mentions: List<String> = emptyList(),
        val editCount: Long = 0,
        val isUnsent: Boolean = false,
    )

    // --- Incoming event types (from Go bridge handlemeta.go parseTable) ---

    sealed interface IncomingEvent {
        data class MessageReceived(val message: MetaMessageEnriched) : IncomingEvent
        data class MessageEdited(val messageId: String, val newText: String, val editCount: Long) : IncomingEvent
        data class MessageDeleted(val threadId: String, val messageId: String) : IncomingEvent
        data class ReactionReceived(
            val threadId: String,
            val messageId: String,
            val senderId: String,
            val reaction: String,
        ) : IncomingEvent
        data class ReactionRemoved(
            val threadId: String,
            val messageId: String,
            val senderId: String,
        ) : IncomingEvent
        data class ReadReceipt(val threadId: String, val senderId: String, val watermarkTimestampMs: Long) : IncomingEvent
        data class TypingIndicator(val threadId: String, val senderId: String, val isTyping: Boolean) : IncomingEvent
        data class ThreadNameChanged(val threadId: String, val newName: String) : IncomingEvent
        data class ThreadImageChanged(val threadId: String, val imageUrl: String?) : IncomingEvent
        data class ParticipantAdded(val threadId: String, val participantId: String) : IncomingEvent
        data class ParticipantRemoved(val threadId: String, val participantId: String) : IncomingEvent
        data class ThreadMuteChanged(val threadId: String, val muteExpireTimeMs: Long) : IncomingEvent
        data class ThreadDeleted(val threadId: String) : IncomingEvent
        data class MessageRequestReceived(val threadId: String) : IncomingEvent
        data class ThreadSynced(
            val threadId: String,
            val threadName: String?,
            val lastActivityTimestampMs: Long,
            val isGroup: Boolean = false,
            val participantNames: List<String> = emptyList(),
        ) : IncomingEvent
        data class ThreadVerified(
            val threadId: String,
            val threadType: Int,
            val folderName: String,
        ) : IncomingEvent
        data class FolderSynced(
            val threadId: String,
        ) : IncomingEvent
        data class ThreadMovedToE2EECutover(
            val threadId: String,
        ) : IncomingEvent
    }

    fun parseAllEvents(events: List<LightspeedDecoder.DecodedEvent>): List<IncomingEvent> {
        // Pass 1: build contactId → display name from the contact rows in this response so 1:1
        // thread names (where threadName is empty) can be resolved. IG has no phone numbers — the
        // name is the contact's fullName (→ username). Mirrors Go userinfo.go GetName/GetUsername.
        val contactNames = HashMap<String, String>()
        for (e in events) {
            when (e.procedureName) {
                // LSDeleteThenInsertContact: id@0, name(fullName)@9, username(SecondaryName)@41.
                "LSDeleteThenInsertContact" -> {
                    val id = e.args.argLong(0)?.toString() ?: continue
                    val name = e.args.argStr(9) ?: e.args.argStr(41) ?: continue
                    contactNames[id] = name
                }
                // LSVerifyContactRowExists: id@0, name(fullName)@3, username(SecondaryName)@20.
                "LSVerifyContactRowExists" -> {
                    val id = e.args.argLong(0)?.toString() ?: continue
                    val name = e.args.argStr(3) ?: e.args.argStr(20) ?: continue
                    contactNames[id] = name
                }
            }
        }
        // Group participant names per thread (LSAddParticipantIdToGroupThread: threadKey@0,
        // contactId@1) resolved via contactNames — for ConversationUpdate.serviceData.
        val participantsByThread = HashMap<String, MutableList<String>>()
        for (e in events) {
            if (e.procedureName != "LSAddParticipantIdToGroupThread") continue
            val tid = e.args.argLong(0)?.toString() ?: continue
            val cid = e.args.argLong(1)?.toString() ?: continue
            val name = contactNames[cid] ?: continue
            participantsByThread.getOrPut(tid) { mutableListOf() }.add(name)
        }
        val result = mutableListOf<IncomingEvent>()
        val attachmentsByMsg = buildAttachmentMap(events)
        for (event in events) {
            val parsed = parseSingleEvent(event, contactNames, attachmentsByMsg, participantsByThread)
            if (parsed != null) result.add(parsed)
        }
        return result
    }

    /** Maps IG AttachmentType enum ints to a coarse media kind. */
    private fun attachmentTypeName(t: Long?): String = when (t) {
        1L, 10L, 15L -> "sticker"
        2L, 3L, 8L -> "image"
        4L, 9L -> "video"
        5L, 12L -> "audio"
        7L -> "share"
        else -> "file"
    }

    /** Prefer a concrete media kind from the MIME. IG delivers many native photos/videos as XMA
     * rows with an image or video mime — without this they'd be mis-typed "share" and render as a
     * card instead of inline media. Returns null for non-media (link/post shares). */
    private fun mediaTypeFromMime(mime: String?): String? = when {
        mime == null -> null
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        else -> null
    }

    /** Short typed label used as the message body when there's no caption (media renders separately). */
    private fun attachmentLabel(a: MessageAttachment): String = when (a.attachmentType) {
        "image" -> "\uD83D\uDCF7 Photo"
        "video" -> "\uD83C\uDFA5 Video"
        "audio" -> "\uD83C\uDFA4 Voice message"
        "sticker" -> "\uD83D\uDDBC\uFE0F Sticker"
        "share" -> a.title?.let { "\uD83D\uDD17 $it" } ?: "\uD83D\uDD17 Shared post"
        else -> a.fileName?.let { "\uD83D\uDCCE $it" } ?: "\uD83D\uDCCE Attachment"
    }

    /**
     * Build a messageId → primary MessageAttachment map from the separate IG attachment rows in the
     * same response. Native media (blob/sticker) wins over shares (xma); CTA rows enrich the share
     * link. URL prefers the playable/full URL, falling back to the preview thumbnail. Arg indices
     * mirror the Go bridge table/attachments.go.
     */
    private fun buildAttachmentMap(events: List<LightspeedDecoder.DecodedEvent>): Map<String, MessageAttachment> {
        val byMsg = HashMap<String, MessageAttachment>()
        val ctaByMsg = HashMap<String, Pair<String?, String?>>() // messageId -> (actionUrl, title)
        for (e in events) {
            val a = e.args
            when (e.procedureName) {
                // LSInsertBlobAttachment: filename@0, playableUrl@3, previewUrl@8, w@14, h@15,
                // type@29, mime@30, messageId@32, fbid@34.
                "LSInsertBlobAttachment" -> {
                    val mid = a.argStr(32) ?: continue
                    byMsg[mid] = MessageAttachment(
                        id = a.argStr(34) ?: mid,
                        mimeType = a.argStr(30) ?: a.argStr(6),
                        fileName = a.argStr(0),
                        url = a.argStr(3) ?: a.argStr(8),
                        size = a.argLong(1),
                        previewUrl = a.argStr(8),
                        attachmentType = mediaTypeFromMime(a.argStr(30) ?: a.argStr(6))
                            ?: attachmentTypeName(a.argLong(29)),
                        width = a.argLong(14)?.toInt(),
                        height = a.argLong(15)?.toInt(),
                    )
                }
                // LSInsertStickerAttachment: playableUrl@0, previewUrl@4, w@9, h@10, messageId@18, fbid@19.
                "LSInsertStickerAttachment" -> {
                    val mid = a.argStr(18) ?: continue
                    byMsg[mid] = MessageAttachment(
                        id = a.argStr(19) ?: mid,
                        mimeType = a.argStr(3),
                        fileName = null,
                        url = a.argStr(0) ?: a.argStr(4),
                        size = null,
                        previewUrl = a.argStr(4),
                        attachmentType = "sticker",
                        width = a.argLong(9)?.toInt(),
                        height = a.argLong(10)?.toInt(),
                    )
                }
                // LSInsertXmaAttachment (shares): playableUrl@4, previewUrl@8, w@13, h@14, type@27,
                // messageId@30, fbid@32, actionUrl@57, title@58, headerTitle@102.
                "LSInsertXmaAttachment" -> {
                    val mid = a.argStr(30) ?: continue
                    if (byMsg[mid] == null) {
                        byMsg[mid] = MessageAttachment(
                            id = a.argStr(32) ?: mid,
                            mimeType = a.argStr(7),
                            fileName = null,
                            url = a.argStr(8) ?: a.argStr(4),
                            size = null,
                            previewUrl = a.argStr(8),
                            // Native photos/videos often arrive as XMA with a real media mime →
                            // type by mime; genuine link/post shares (no media mime) stay "share".
                            attachmentType = mediaTypeFromMime(a.argStr(7)) ?: "share",
                            title = a.argStr(58) ?: a.argStr(102),
                            actionUrl = a.argStr(57),
                            width = a.argLong(13)?.toInt(),
                            height = a.argLong(14)?.toInt(),
                        )
                    }
                }
                // LSInsertAttachment (legacy): filename@1, playableUrl@5, previewUrl@10, type@34,
                // mime@35, messageId@37, fbid@39, title@66.
                "LSInsertAttachment" -> {
                    val mid = a.argStr(37) ?: continue
                    if (byMsg[mid] == null) {
                        byMsg[mid] = MessageAttachment(
                            id = a.argStr(39) ?: mid,
                            mimeType = a.argStr(35),
                            fileName = a.argStr(1),
                            url = a.argStr(5) ?: a.argStr(10),
                            size = a.argLong(2),
                            previewUrl = a.argStr(10),
                            attachmentType = mediaTypeFromMime(a.argStr(35))
                                ?: attachmentTypeName(a.argLong(34)),
                            title = a.argStr(66),
                        )
                    }
                }
                // LSInsertAttachmentCta: messageId@5, title@6, actionUrl@9, nativeUrl@10.
                "LSInsertAttachmentCta" -> {
                    val mid = a.argStr(5) ?: continue
                    ctaByMsg[mid] = (a.argStr(9) ?: a.argStr(10)) to a.argStr(6)
                }
            }
        }
        // Enrich share attachments with their CTA link/title. A CTA row (LSInsertAttachmentCta,
        // e.g. igd_web_post_share) means this is a genuine reel/post/link SHARE — force type "share"
        // (card), even if its XMA carried a video/image mime. Native media has no CTA, so it keeps
        // its mime-based image/video/audio type and renders inline.
        for ((mid, cta) in ctaByMsg) {
            val existing = byMsg[mid] ?: continue
            byMsg[mid] = existing.copy(
                attachmentType = "share",
                actionUrl = existing.actionUrl ?: cta.first,
                title = existing.title ?: cta.second,
            )
        }
        return byMsg
    }

    /**
     * Resolve a thread's display name from LSDeleteThenInsertThread args (verified on-device):
     *  - Group (threadType@9 == 2): title is threadName@3 (e.g. "stemengers").
     *  - 1:1 (threadType@9 == 1): threadName@3 is null; the display name ("<handle> · Instagram")
     *    is at idx 36. (idx 36 on a group is a creation/context snippet, so it's gated to 1:1.)
     *  - Fallback: the contactId→name map (contactId == 1:1 threadKey).
     * IG has no phone numbers — never fall back to one. Mirrors Go chatinfo.go/userinfo.go.
     */
    private fun resolveThreadName(
        args: List<Any?>,
        threadId: String,
        contactNames: Map<String, String>,
    ): String? {
        val name3 = args.argStr(3)
        if (!name3.isNullOrEmpty()) return name3
        if (args.argLong(9) == 1L) {
            // 1:1: prefer the contact's real display name (e.g. "Madhulika Mathur")
            // over idx36, which is the "<handle> · Instagram" snippet.
            contactNames[threadId]?.let { return it }
            val name36 = args.argStr(36)
            if (!name36.isNullOrEmpty()) return name36
        }
        return contactNames[threadId]
    }

    // Typed positional accessors for Lightspeed args. Indices match the Go
    // `index:` struct tags in messagix/table/{messages,threads}.go — they are
    // non-sequential, so reading by Go index is mandatory for correctness.
    private fun List<Any?>.argStr(i: Int): String? =
        getOrNull(i)?.toString()?.takeIf { it.isNotEmpty() }

    private fun List<Any?>.argLong(i: Int): Long? = when (val v = getOrNull(i)) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    private fun List<Any?>.argBool(i: Int): Boolean = when (val v = getOrNull(i)) {
        is Boolean -> v
        is Long -> v != 0L
        is Int -> v != 0
        else -> false
    }

    private fun parseSingleEvent(
        event: LightspeedDecoder.DecodedEvent,
        contactNames: Map<String, String> = emptyMap(),
        attachmentsByMsg: Map<String, MessageAttachment> = emptyMap(),
        participantsByThread: Map<String, List<String>> = emptyMap(),
    ): IncomingEvent? {
        val args = event.args
        return when (event.procedureName) {
            // Shared layout (table/messages.go LSUpsertMessage/LSInsertMessage/
            // LSDeleteThenInsertMessage): text@0, threadKey@3, timestampMs@5,
            // messageId@8, senderId@10, isUnsent@17, replySourceId@23.
            "LSUpsertMessage",
            "LSInsertMessage",
            "LSDeleteThenInsertMessage" -> {
                val threadId = args.argLong(3)?.toString() ?: return null
                val messageId = args.argStr(8) ?: return null
                val isUnsent = args.argBool(17)
                if (isUnsent && event.procedureName == "LSDeleteThenInsertMessage") {
                    return IncomingEvent.MessageDeleted(threadId, messageId)
                }
                // text@0 is empty for attachment/sticker/share messages (the content is in separate
                // attachment rows, matched by messageId). Attach the media and give a typed body
                // label so it's never blank before/without inline rendering.
                val rawText = args.argStr(0)
                val attachment = attachmentsByMsg[messageId]
                val attachments = if (attachment != null) listOf(attachment) else emptyList()
                val body = when {
                    !rawText.isNullOrEmpty() -> rawText
                    attachment != null -> attachmentLabel(attachment)
                    (args.argLong(11) ?: 0L) != 0L -> "\uD83D\uDDBC\uFE0F Sticker"
                    else -> "\uD83D\uDCCE Attachment"
                }
                IncomingEvent.MessageReceived(
                    MetaMessageEnriched(
                        messageId = messageId,
                        threadId = threadId,
                        senderId = args.argLong(10)?.toString() ?: "",
                        senderName = contactNames[args.argLong(10)?.toString()],
                        text = body,
                        timestamp = args.argLong(5) ?: System.currentTimeMillis(),
                        isGroup = (threadId.toLongOrNull() ?: 0) < 0,
                        attachments = attachments,
                        replyToMessageId = args.argStr(23),
                        isUnsent = isUnsent,
                    )
                )
            }
            "LSEditMessage" -> {
                // messageId@0, text@2, editCount@3
                val messageId = args.argStr(0) ?: return null
                val newText = args.argStr(2) ?: return null
                val editCount = args.argLong(3) ?: 1L
                IncomingEvent.MessageEdited(messageId, newText, editCount)
            }
            "LSDeleteMessage" -> {
                // threadKey@0, messageId@1
                val threadId = args.argLong(0)?.toString() ?: return null
                val messageId = args.argStr(1) ?: return null
                IncomingEvent.MessageDeleted(threadId, messageId)
            }
            "LSUpsertReaction" -> {
                // threadKey@0, messageId@2, actorId@3, reaction@4
                val threadId = args.argLong(0)?.toString() ?: return null
                val messageId = args.argStr(2) ?: return null
                val senderId = args.argLong(3)?.toString() ?: return null
                val reaction = args.argStr(4)
                if (reaction.isNullOrBlank()) {
                    IncomingEvent.ReactionRemoved(threadId, messageId, senderId)
                } else {
                    IncomingEvent.ReactionReceived(threadId, messageId, senderId, reaction)
                }
            }
            "LSDeleteReaction" -> {
                // threadKey@0, messageId@1, actorId@2
                val threadId = args.argLong(0)?.toString() ?: return null
                val messageId = args.argStr(1) ?: return null
                val senderId = args.argLong(2)?.toString() ?: return null
                IncomingEvent.ReactionRemoved(threadId, messageId, senderId)
            }
            "LSUpdateReadReceipt" -> {
                // readWatermarkTimestampMs@0, threadKey@1, contactId@2
                val watermark = args.argLong(0) ?: return null
                val threadId = args.argLong(1)?.toString() ?: return null
                val senderId = args.argLong(2)?.toString() ?: "self"
                IncomingEvent.ReadReceipt(threadId, senderId, watermark)
            }
            "LSMarkThreadRead" -> {
                // lastReadWatermarkTimestampMs@0, threadKey@1
                val watermark = args.argLong(0) ?: return null
                val threadId = args.argLong(1)?.toString() ?: return null
                IncomingEvent.ReadReceipt(threadId, "self", watermark)
            }
            "LSMarkThreadReadV2" -> {
                // threadKey@0, lastReadWatermarkTimestampMs@1
                val threadId = args.argLong(0)?.toString() ?: return null
                val watermark = args.argLong(1) ?: return null
                IncomingEvent.ReadReceipt(threadId, "self", watermark)
            }
            "LSUpdateTypingIndicator" -> {
                // threadKey@0, senderId@1, isTyping@2
                val threadId = args.argLong(0)?.toString() ?: return null
                val senderId = args.argLong(1)?.toString() ?: return null
                IncomingEvent.TypingIndicator(threadId, senderId, args.argBool(2))
            }
            "LSSyncUpdateThreadName" -> {
                // threadName@0, threadKey@1
                val newName = args.argStr(0) ?: return null
                val threadId = args.argLong(1)?.toString() ?: return null
                IncomingEvent.ThreadNameChanged(threadId, newName)
            }
            "LSSetThreadImageURL" -> {
                // threadKey@0, imageURL@1
                val threadId = args.argLong(0)?.toString() ?: return null
                IncomingEvent.ThreadImageChanged(threadId, args.argStr(1))
            }
            "LSAddParticipantIdToGroupThread" -> {
                // threadKey@0, contactId@1
                val threadId = args.argLong(0)?.toString() ?: return null
                val participantId = args.argLong(1)?.toString() ?: return null
                IncomingEvent.ParticipantAdded(threadId, participantId)
            }
            "LSRemoveParticipantFromThread" -> {
                // threadKey@0, participantId@1
                val threadId = args.argLong(0)?.toString() ?: return null
                val participantId = args.argLong(1)?.toString() ?: return null
                IncomingEvent.ParticipantRemoved(threadId, participantId)
            }
            "LSVerifyThreadExists" -> {
                // threadKey@0, threadType@1, folderName@2
                val threadId = args.argLong(0)?.toString() ?: return null
                val threadType = args.argLong(1)?.toInt() ?: THREAD_TYPE_UNKNOWN
                val folderName = args.argStr(2) ?: FOLDER_INBOX
                IncomingEvent.ThreadVerified(threadId, threadType, folderName)
            }
            "LSUpsertFolder" -> {
                // threadKey@0
                val threadId = args.argLong(0)?.toString() ?: return null
                IncomingEvent.FolderSynced(threadId)
            }
            "LSMoveThreadToE2EECutoverFolder" -> {
                // threadKey@0
                val threadId = args.argLong(0)?.toString() ?: return null
                IncomingEvent.ThreadMovedToE2EECutover(threadId)
            }
            "LSUpdateThreadMuteSetting" -> {
                // threadKey@0, muteExpireTimeMS@1
                val threadId = args.argLong(0)?.toString() ?: return null
                val muteExpireTimeMs = args.argLong(1) ?: return null
                IncomingEvent.ThreadMuteChanged(threadId, muteExpireTimeMs)
            }
            "LSDeleteThread" -> {
                // threadKey@0
                val threadId = args.argLong(0)?.toString() ?: return null
                IncomingEvent.ThreadDeleted(threadId)
            }
            "LSDeleteThenInsertMessageRequest" -> {
                // threadKey@0
                val threadId = args.argLong(0)?.toString() ?: return null
                IncomingEvent.MessageRequestReceived(threadId)
            }
            "LSDeleteThenInsertThread" -> {
                // lastActivityTimestampMs@0, threadName@3, threadKey@7, threadType@9, folderName@10
                val threadId = args.argLong(7)?.toString() ?: return null
                val folderName = args.argStr(10)
                if (folderName == FOLDER_SPAM) return null
                IncomingEvent.ThreadSynced(
                    threadId = threadId,
                    threadName = resolveThreadName(args, threadId, contactNames),
                    lastActivityTimestampMs = args.argLong(0) ?: 0L,
                    isGroup = args.argLong(9) == 2L,
                    participantNames = participantsByThread[threadId] ?: emptyList(),
                )
            }
            "LSUpdateOrInsertThread" -> {
                // lastActivityTimestampMs@0, threadName@3, threadKey@7, threadType@9
                val threadId = args.argLong(7)?.toString() ?: return null
                IncomingEvent.ThreadSynced(
                    threadId = threadId,
                    threadName = resolveThreadName(args, threadId, contactNames),
                    lastActivityTimestampMs = args.argLong(0) ?: 0L,
                    isGroup = args.argLong(9) == 2L,
                    participantNames = participantsByThread[threadId] ?: emptyList(),
                )
            }
            else -> null
        }
    }

    fun parseMessageEnriched(events: List<LightspeedDecoder.DecodedEvent>): MetaMessageEnriched? {
        for (event in events) {
            val parsed = parseSingleEvent(event)
            if (parsed is IncomingEvent.MessageReceived) return parsed.message
        }
        return null
    }

    fun parseMessageId(messageId: String): Long? {
        // Format: "mid.$" + chatType char + base64url(21 bytes); timestamp is the
        // 5 big-endian bytes at [8..12] offset by the Meta epoch. Ref
        // messagix/methods/methods.go ParseMessageIDFull/ParseMessageID.
        if (!messageId.startsWith("mid.\$") || messageId.length < 6) return null
        return try {
            val payload = android.util.Base64.decode(
                messageId.substring(6),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
            if (payload.size != 21) return null
            var ts = 0L
            for (i in 8 until 13) ts = (ts shl 8) or (payload[i].toLong() and 0xFF)
            ts + META_EPOCH_MS
        } catch (e: Exception) {
            null
        }
    }

    fun buildAppSettingsJson(versionId: Long): String {
        return json.encodeToString(AppSettingsPublish(schemaVersion = versionId.toString()))
    }

    @Serializable
    private data class SnapshotLsResp(
        @SerialName("request_id") val requestId: Long = 0,
        val payload: String,
        val sp: List<String>,
    )

    /**
     * Wrap the page-embedded snapshot (inner LightSpeedData JSON [payload] + dependency names [sp])
     * into a synthetic /ls_resp envelope so it can be pushed through the exact same decode+emit path
     * as a real socket response (emitForProcessing → handleIncomingMessage → decodePublishResponse).
     * kotlinx re-escapes [payload] as a JSON string; the decoder unescapes it back.
     */
    fun buildInitialSnapshotLsResp(payload: String, sp: List<String>): ByteArray =
        json.encodeToString(SnapshotLsResp(payload = payload, sp = sp)).toByteArray(Charsets.UTF_8)

    /** Map parsed IG attachments to the shared UI attachment model (data.MessageAttachment). */
    fun toSharedAttachments(
        attachments: List<MessageAttachment>,
    ): List<com.vayunmathur.messages.data.MessageAttachment> =
        attachments.mapNotNull { a ->
            val url = a.url ?: a.previewUrl ?: return@mapNotNull null
            com.vayunmathur.messages.data.MessageAttachment(
                url = url,
                previewUrl = a.previewUrl,
                mimeType = a.mimeType,
                attachmentType = a.attachmentType ?: "file",
                fileName = a.fileName,
                title = a.title,
                actionUrl = a.actionUrl,
                width = a.width ?: 0,
                height = a.height ?: 0,
            )
        }

    /** {"participantNames":["a","b"]} for ConversationUpdate.serviceData, or null if empty. */
    fun buildParticipantNamesServiceData(names: List<String>): String? {
        if (names.isEmpty()) return null
        val arr = JsonArray(names.map { JsonPrimitive(it) })
        return JsonObject(mapOf("participantNames" to arr)).toString()
    }

    fun removeVariationSelectors(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (c !in '\uFE00'..'\uFE0F') sb.append(c)
        }
        return sb.toString()
    }

    fun generateEpochId(): Long = generateEpochId_internal()

    private var lastTimestamp = 0L
    private var epochCounter = 0L
    private val epochLock = Any()

    private fun generateEpochId_internal(): Long {
        synchronized(epochLock) {
            val timestamp = System.currentTimeMillis()
            if (timestamp == lastTimestamp) {
                epochCounter++
            } else {
                lastTimestamp = timestamp
                epochCounter = 0
            }
            return (timestamp shl 22) or (epochCounter shl 12) or 42L
        }
    }

    @Deprecated("Use generateEpochId()", ReplaceWith("generateEpochId()"))
    fun generateEpochID(): Long = generateEpochId()

    fun generateSessionId(): Long {
        val min = 2171078810009599L
        val max = 4613554604867583L
        val range = max - min + 1
        return min + (SecureRandom().nextLong().ushr(1) % range)
    }
}
