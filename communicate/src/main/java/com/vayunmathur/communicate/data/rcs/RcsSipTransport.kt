package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.ims.DelegateRegistrationState
import android.telephony.ims.DelegateRequest
import android.telephony.ims.FeatureTagState
import android.telephony.ims.ImsException
import android.telephony.ims.SipDelegateConfiguration
import android.telephony.ims.SipDelegateConnection
import android.telephony.ims.SipDelegateManager
import android.telephony.ims.SipMessage
import android.telephony.ims.stub.DelegateConnectionMessageCallback
import android.telephony.ims.stub.DelegateConnectionStateCallback
import android.util.Log
import com.vayunmathur.communicate.data.SimManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Per-subscription `SipDelegateManager` wrapper (single-registration transport).
 *
 * One delegate per active [SubscriptionManager] subId, tied to
 * [SimManager]'s active subscription. CPM feature tags only. Every platform
 * call catches `SecurityException` (no carrier privilege on a normal
 * dev-build install) and maps to `Unavailable(NoCarrierPrivilege)` — never
 * crashes. Until the transport is `Available`, sends fall back to SMS.
 */
object RcsSipTransport {
    private const val TAG = "RcsSipTransport"

    /**
     * CPM feature tags for RCS chat + file transfer (RCC.07 §2.6.1.3). Must be
     * allowed by the carrier (`KEY_RCS_FEATURE_TAG_ALLOWED_STRING_ARRAY`) or
     * the request is denied. Tag set mirrors TestRcsApp's DelegateActivity:
     * session + pager-mode variants + FT.
     */
    val CPM_FEATURE_TAGS: Set<String> = setOf(
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"",
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg\"",
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"",
        "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.deferred\"",
        "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp\"",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor: Executor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow<RcsRegistrationState>(RcsRegistrationState.Unknown)
    val state: StateFlow<RcsRegistrationState> = _state.asStateFlow()

    @Volatile private var connection: SipDelegateConnection? = null
    @Volatile private var configVersion: Long = -1L
    @Volatile private var activeSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    /** Last IMS configuration (identity, server, route headers for sends). */
    @Volatile private var lastConfig: SipDelegateConfiguration? = null

    /**
     * Snapshot of the fields SIP construction needs, decoupled from the hidden
     * config class so callers never touch framework types.
     */
    data class ConfigSnapshot(
        val version: Long,
        val publicUserId: String?,
        val homeDomain: String?,
        val msrpLocalIp: String?,
        val serviceRoute: String?,
        val pani: String?,
        val userAgent: String?,
        val imei: String?,
    )

    fun lastConfigSnapshot(): ConfigSnapshot? {
        val cfg = lastConfig ?: return null
        return runCatching {
            ConfigSnapshot(
                version = cfg.getVersion(),
                publicUserId = cfg.getPublicUserIdentifier(),
                homeDomain = cfg.getHomeDomain(),
                // IMS-PDN local address when known — routable by carrier peers,
                // unlike the delegate config (which carries no local IP field).
                msrpLocalIp = RcsImsNetwork.lastLocalIp,
                serviceRoute = cfg.getSipServiceRouteHeader(),
                pani = cfg.getSipPaniHeader(),
                userAgent = cfg.getSipUserAgentHeader(),
                imei = cfg.getImei(),
            )
        }.getOrNull()
    }

    /** Inbound SIP MESSAGE listener (sync service writes to Room + notifies). */
    @Volatile
    var onInboundMessage: ((SipMessage) -> Unit)? = null

    /** Pending outbound sends keyed by Via branch transaction id. */
    private data class PendingSend(
        val callback: (Boolean) -> Unit,
        val startLine: String,
        val headers: String,
        val body: ByteArray,
        val authAttempts: Int = 0,
    )

    private val pendingSends = ConcurrentHashMap<String, PendingSend>()

    private val stateCallback = object : DelegateConnectionStateCallback {
        override fun onCreated(c: SipDelegateConnection) {
            connection = c
            _state.value = RcsRegistrationState.Available
            Log.i(TAG, "SipDelegate created")
        }

        override fun onFeatureTagStatusChanged(
            registrationState: DelegateRegistrationState,
            deniedFeatureTags: Set<FeatureTagState>,
        ) {
            if (deniedFeatureTags.isNotEmpty()) {
                Log.w(TAG, "Feature tags denied: ${deniedFeatureTags.map { it.getFeatureTag() }}")
                _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.TransportDenied)
            }
        }

        override fun onConfigurationChanged(registeredSipConfig: SipDelegateConfiguration) {
            configVersion = registeredSipConfig.getVersion()
            lastConfig = registeredSipConfig
        }

        override fun onDestroyed(reason: Int) {
            Log.w(TAG, "SipDelegate destroyed reason=$reason")
            connection = null
            configVersion = -1L
            lastConfig = null
            if (_state.value is RcsRegistrationState.Available) {
                _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
            }
        }
    }

    private val messageCallback = object : DelegateConnectionMessageCallback {
        override fun onMessageReceived(message: SipMessage) {
            runCatching { connection?.notifyMessageReceived(message.getViaBranchParameter()) }
            onInboundMessage?.invoke(message)
        }

        override fun onMessageSent(viaTransactionId: String) {
            pendingSends.remove(viaTransactionId)?.callback?.invoke(true)
        }

        override fun onMessageSendFailure(viaTransactionId: String, reason: Int) {
            Log.w(TAG, "Message send failed tx=$viaTransactionId reason=$reason")
            pendingSends.remove(viaTransactionId)?.callback?.invoke(false)
        }
    }

    /**
     * Bring up the transport for the active subscription. Runs provisioning
     * first; only creates the delegate when provisioned. Never throws.
     */
    fun ensureRegistered(context: Context) {
        if (!RcsFeature.enabled) {
            _state.value = RcsRegistrationState.Disabled
            return
        }
        scope.launch {
            val app = context.applicationContext
            val subId = activeSubId(app)
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription)
                return@launch
            }
            if (subId == activeSubId && connection != null) return@launch
            if (subId != activeSubId) tearDown(app)
            activeSubId = subId
            // Start the live provisioning watch before creating the delegate:
            // config changes (FT server URI, re-provisioning) arrive via
            // callback instead of waiting for the next probe.
            RcsProvisioningWatcher.watch(app, subId)
            when (val probe = RcsProvisioning.probe(app, subId)) {
                is RcsRegistrationState.Available -> createDelegate(app, subId)
                is RcsRegistrationState.Unavailable -> {
                    _state.value = probe
                }
                else -> Unit
            }
        }
    }

    fun tearDown(context: Context? = null) {
        val c = connection
        if (c != null && context != null && RcsFeature.enabled) {
            runCatching {
                // Requires the same privileged permission; failure just orphans the
                // delegate, which the framework reaps.
                val manager = RcsHiddenApi.sipDelegateManager(context, activeSubId)
                manager?.destroySipDelegate(c, SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP)
            }
        }
        RcsProvisioningWatcher.unwatch()
        RcsMsrpListen.stop()
        RcsImsNetwork.reset()
        connection = null
        configVersion = -1L
        activeSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /** True when a SIP MESSAGE can be sent right now. */
    fun canSend(): Boolean =
        RcsFeature.enabled && _state.value is RcsRegistrationState.Available && connection != null

    /**
     * Tell the framework to release delegate state for [callId]
     * (`SipDelegateConnection#cleanupSession`). Best-effort: no connection
     * or any failure is a silent no-op.
     */
    fun cleanupSession(callId: String) {
        val c = connection ?: return
        if (!RcsFeature.enabled || callId.isBlank()) return
        runCatching { c.cleanupSession(callId) }
    }

    /**
     * Send a SIP MESSAGE. Returns true on delegate ack, false otherwise
     * (caller falls back to SMS). Never throws.
     *
     * The full request is stashed per branch so 401/407 challenges can be
     * retried with digest auth ([onSipResponseMessage]).
     */
    suspend fun sendSipMessage(startLine: String, headerSection: String, body: ByteArray): Boolean {
        val c = connection
        if (!canSend() || c == null) return false
        val version = configVersion
        if (version < 0) return false
        return runCatching {
            val message = SipMessage(startLine, headerSection, body)
            suspendCancellableCoroutine { cont ->
                val branch = message.getViaBranchParameter()
                pendingSends[branch] = PendingSend(
                    callback = { ok -> if (cont.isActive) cont.resume(ok) },
                    startLine = startLine,
                    headers = headerSection,
                    body = body,
                )
                runCatching { c.sendMessage(message, version) }.onFailure {
                    pendingSends.remove(branch)
                    if (cont.isActive) cont.resume(false)
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Route an inbound SIP *response* (`SIP/2.0 xxx` start line). Returns
     * true when the response was consumed (matched a pending transaction or
     * a session dialog); false when the caller should treat it as an
     * inbound MESSAGE.
     *
     * - 1xx → absorbed (retransmissions/100 Trying need no action).
     * - 2xx to INVITE → session establishment + ACK (§1.1).
     * - 401/407 → one digest-auth retry, then fail (§1.3).
     * - 481/408/480/486/603 → dialog-mismatch recovery (§1.6).
     * - 2xx to anything else → pending send succeeds.
     * - anything else → pending send fails.
     */
    suspend fun onSipResponseMessage(message: SipMessage): Boolean {
        if (!RcsFeature.enabled) return false
        val parsed = RcsSipResponse.parse(message) ?: return false
        // 2xx to our INVITE: complete the dialog + ACK, even if the original
        // branch aged out of pendingSends (responses can arrive late).
        if (parsed.statusCode in 200..299 && parsed.cseqMethod.equals("INVITE", ignoreCase = true)) {
            pendingSends.remove(parsed.branch)?.callback?.invoke(true)
            RcsSessionManager.onSipResponse(
                statusCode = parsed.statusCode,
                callId = parsed.callId,
                remoteTag = parsed.toTag,
                sdpAnswer = parsed.body,
                responseHeaders = parsed.headers,
            )
            return true
        }
        val pending = pendingSends[parsed.branch]
        if (pending == null) {
            // Stray response (late duplicate, CANCEL race): still route
            // dialog finals to the session manager by Call-ID.
            if (parsed.statusCode >= 300) {
                RcsSessionManager.onSipResponse(
                    statusCode = parsed.statusCode,
                    callId = parsed.callId,
                    remoteTag = parsed.toTag,
                    sdpAnswer = parsed.body,
                    responseHeaders = parsed.headers,
                )
                return true
            }
            return false
        }
        when {
            parsed.statusCode in 100..199 -> Unit // absorbed; ack comes later
            parsed.statusCode in 200..299 -> {
                pendingSends.remove(parsed.branch)?.callback?.invoke(true)
            }
            parsed.statusCode == 401 || parsed.statusCode == 407 -> {
                if (!retryWithDigestAuth(parsed, pending)) {
                    pendingSends.remove(parsed.branch)?.callback?.invoke(false)
                }
                // On retry the callback stays parked under the NEW branch.
            }
            parsed.statusCode == 481 || parsed.statusCode == 408 ||
                parsed.statusCode == 480 || parsed.statusCode == 486 ||
                parsed.statusCode == 603 -> {
                pendingSends.remove(parsed.branch)?.callback?.invoke(false)
                RcsSessionManager.onDialogError(parsed.callId, parsed.statusCode)
            }
            else -> {
                pendingSends.remove(parsed.branch)?.callback?.invoke(false)
                if (parsed.statusCode >= 300) {
                    RcsSessionManager.onSipResponse(
                        statusCode = parsed.statusCode,
                        callId = parsed.callId,
                        remoteTag = parsed.toTag,
                        sdpAnswer = parsed.body,
                        responseHeaders = parsed.headers,
                    )
                }
            }
        }
        return true
    }

    /**
     * Rebuild a challenged request with digest `Authorization` /
     * `Proxy-Authorization` and resend under a fresh branch (RFC 3261
     * §22.2: same Call-ID/CSeq/From-tag, new Via branch). One retry per
     * transaction max. Returns true when a retry was issued.
     */
    private suspend fun retryWithDigestAuth(
        parsed: RcsSipResponse.Parsed,
        pending: PendingSend,
    ): Boolean {
        if (pending.authAttempts >= 1) return false
        val proxy = parsed.statusCode == 407
        val challengeHeader = if (proxy) "Proxy-Authenticate:" else "WWW-Authenticate:"
        val challenge = RcsSipDialog.headerValue(parsed.headers, challengeHeader) ?: return false
        val gba = RcsGbaAuth.injected
        val username = gba?.btId
            ?: lastConfigSnapshot()?.publicUserId?.substringAfter("sip:")?.substringBefore("@")
                ?.takeIf { it.isNotBlank() } ?: return false
        val password = gba?.let {
            android.util.Base64.encodeToString(it.key, android.util.Base64.NO_WRAP)
        }.orEmpty()
        val method = pending.startLine.substringBefore(" ").trim().ifBlank { return false }
        val uri = pending.startLine.substringAfter(" ").substringBefore(" SIP/").trim()
            .ifBlank { return false }
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val authValue = RcsGbaAuth.digestAuthorizationHeader(
            challenge = challenge,
            method = method,
            uri = uri,
            username = username,
            password = password,
            cnonce = cnonce,
        ).ifBlank { return false }
        // Same request, fresh branch, auth header appended (replaces any
        // stale Authorization/Proxy-Authorization from a previous attempt).
        val newBranch = RcsSipDialog.newBranch()
        val baseHeaders = pending.headers.lineSequence()
            .filter { line ->
                val t = line.trim()
                !(t.startsWith("Via:", ignoreCase = true) ||
                    t.startsWith("Authorization:", ignoreCase = true) ||
                    t.startsWith("Proxy-Authorization:", ignoreCase = true))
            }.joinToString("\r\n")
        val viaHost = pending.headers.lineSequence()
            .firstOrNull { it.trim().startsWith("Via:", ignoreCase = true) }
            ?.substringAfter("SIP/2.0/TCP")?.substringBefore(";")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "local"
        val rebuilt = buildString {
            append(baseHeaders)
            if (baseHeaders.isNotEmpty()) append("\r\n")
            append("Via: SIP/2.0/TCP $viaHost;branch=$newBranch\r\n")
            append(if (proxy) "Proxy-Authorization: $authValue\r\n" else "Authorization: $authValue\r\n")
        }
        // Re-park the callback BEFORE sending (the ack may arrive inline).
        val oldBranch = parsed.branch
        pendingSends.remove(oldBranch)
        val c = connection ?: run {
            pending.callback(false)
            return false
        }
        val version = configVersion
        if (version < 0) {
            pending.callback(false)
            return false
        }
        var issued = false
        runCatching {
            val message = SipMessage(pending.startLine, rebuilt, pending.body)
            // SipMessage computes its own branch from the header section;
            // our rebuilt headers carry newBranch, so key on it.
            pendingSends[newBranch] = pending.copy(
                headers = rebuilt,
                authAttempts = pending.authAttempts + 1,
            )
            runCatching { c.sendMessage(message, version) }.onFailure {
                pendingSends.remove(newBranch)
                pending.callback(false)
            }
            issued = true
        }.onFailure {
            pendingSends.remove(newBranch)
            pending.callback(false)
        }
        Log.i(TAG, "Digest auth retry issued for ${parsed.statusCode} (proxy=$proxy)")
        return issued
    }

    /**
     * Build a pager-mode SIP MESSAGE start line + headers for a 1:1 CPM chat
     * message. The branch parameter doubles as the send ack key.
     *
     * The body is a `message/cpim` envelope (TestRcsApp CpimUtils) carrying
     * IMDN metadata; the From identity comes from the delegate configuration
     * (public user identifier) when known, else the [fromUri] fallback.
     */
    fun buildChatMessage(
        fromUri: String,
        toUri: String,
        callId: String = "${UUID.randomUUID()}@rcs",
        body: String,
    ): Triple<String, String, ByteArray> {
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val from = lastConfig?.getPublicUserIdentifier()?.takeIf { it.isNotBlank() } ?: fromUri
        val cpim = buildCpimBody(body)
        val bytes = cpim.toByteArray(Charsets.UTF_8)
        val startLine = "MESSAGE $toUri SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP $from;branch=$branch\r\n")
            append("From: <$from>;tag=${UUID.randomUUID().toString().take(8)}\r\n")
            append("To: <$toUri>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 MESSAGE\r\n")
            append("Content-Type: message/cpim\r\n")
            append("Content-Length: ${bytes.size}\r\n")
        }
        return Triple(startLine, headers, bytes)
    }

    /**
     * Minimal `message/cpim` envelope for a text message (TestRcsApp
     * CpimUtils.createForText): IMDN namespace + delivery/display disposition,
     * anonymous From/To (routing uses the SIP headers), ISO-instant DateTime.
     */
    fun buildCpimBody(text: String): String = buildString {
        append("NS: imdn <urn:ietf:params:imdn>\r\n")
        append("imdn.Message-ID: rcs-${UUID.randomUUID()}\r\n")
        append("imdn.Disposition-Notification: positive-delivery, display\r\n")
        append("To: <sip:anonymous@anonymous.invalid>\r\n")
        append("From: <sip:anonymous@anonymous.invalid>\r\n")
        append("DateTime: ${java.time.Instant.now()}\r\n")
        append("Content-Type: text/plain;charset=UTF-8\r\n")
        append("Content-Length: ${text.toByteArray(Charsets.UTF_8).size}\r\n")
        append("\r\n")
        append(text)
    }

    private fun createDelegate(context: Context, subId: Int) {
        try {
            val manager = RcsHiddenApi.sipDelegateManager(context, subId)
                ?: run {
                    _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
                    return
                }
            val supported: Boolean = runCatching { manager.isSupported() }.getOrElse {
                _state.value = mapSetupFailure(it)
                return
            }
            if (!supported) {
                _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NotSupported)
                return
            }
            manager.createSipDelegate(DelegateRequest(CPM_FEATURE_TAGS), executor, stateCallback, messageCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "No carrier privilege for single registration", e)
            _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NoCarrierPrivilege)
        } catch (e: ImsException) {
            Log.w(TAG, "IMS service unavailable", e)
            _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid subscription", e)
            _state.value = RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription)
        }
    }

    private fun mapSetupFailure(t: Throwable): RcsRegistrationState.Unavailable =
        when (t) {
            is SecurityException -> RcsRegistrationState.Unavailable(RcsUnavailableReason.NoCarrierPrivilege)
            is ImsException -> RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable)
            else -> RcsRegistrationState.Unavailable(RcsUnavailableReason.Unknown)
        }

    private fun activeSubId(context: Context): Int {
        if (!RcsProvisioning.hasPhoneStatePermission(context)) {
            // TestRcsApp keys everything off the default SMS subscription.
            return SubscriptionManager.getDefaultSmsSubscriptionId()
        }
        val sims = SimManager.activeSims(context)
        if (sims.isEmpty()) return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val current = activeSubId
        return sims.firstOrNull { it.subscriptionId == current }?.subscriptionId
            ?: sims.firstOrNull()?.subscriptionId
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }
}
