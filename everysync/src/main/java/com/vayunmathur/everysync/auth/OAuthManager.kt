package com.vayunmathur.everysync.auth

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.util.DataStoreUtils
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OAuth2 Authorization Code + PKCE (RFC 7636). The authorization request opens in
 * a Custom Tab; the redirect (`com.vayunmathur.everysync:/oauth`) is caught by
 * [OAuthCallbackActivity], which calls [complete] to exchange the code for tokens.
 */
object OAuthManager {
    private const val TAG = "OAuthManager"
    private const val PENDING_KEY = "everysync_pending_oauth"
    private const val REFRESH_SKEW_MILLIS = 60_000L
    private const val TOKEN_EXPIRY_UNKNOWN = 0L
    private const val MILLIS_PER_SECOND = 1000L
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Pending(val providerId: String, val verifier: String, val state: String)

    suspend fun start(context: Context, providerId: String) {
        val provider = ProviderRegistry.get(providerId) ?: return
        val config = provider.oauthConfig() ?: return
        if (!config.hasClientId) return // No client ID configured for this build.
        val verifier = randomUrlSafe(64)
        val challenge = codeChallenge(verifier)
        val state = randomUrlSafe(24)
        DataStoreUtils.getInstance(context)
            .setString(PENDING_KEY, json.encodeToString(Pending(providerId, verifier, state)))

        val url = config.authEndpoint.toUri().buildUpon()
            .appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("redirect_uri", config.redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", config.scopes.joinToString(" "))
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .apply { config.extraAuthParams.forEach { (k, v) -> appendQueryParameter(k, v) } }
            .build()

        // Launched from an application context (the ViewModel), so the Custom Tab
        // intent needs NEW_TASK or startActivity throws. Guarded so a device with
        // no browser/Custom Tabs handler doesn't crash the app.
        val customTabs = CustomTabsIntent.Builder().build()
        customTabs.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            customTabs.launchUrl(context, url)
        } catch (expected: Exception) {
            Log.error(TAG, "Failed to launch OAuth Custom Tab", expected)
        }
    }

    /** Handle the OAuth redirect. Returns the created account name on success. */
    suspend fun complete(context: Context, redirect: Uri): String? {
        val pending = readPending(context)
        val code = redirect.getQueryParameter("code")
        val provider = pending?.let { ProviderRegistry.get(it.providerId) }
        val config = provider?.oauthConfig()
        if (!isResolvable(pending, code, provider, config)) return null
        if (redirect.getQueryParameter("state") != pending?.state) {
            Log.error(TAG, "OAuth state mismatch")
            return null
        }

        val tokens = exchangeCode(config!!, code!!, pending!!.verifier) ?: return null
        val accountName = resolveName(context, provider!!, tokens)
        persistAccount(context, provider, accountName, tokens)
        DataStoreUtils.getInstance(context).setString(PENDING_KEY, "")
        return accountName
    }

    private fun isResolvable(
        pending: Pending?,
        code: String?,
        provider: com.vayunmathur.everysync.provider.SyncProvider?,
        config: OAuthConfig?,
    ): Boolean = pending != null && code != null && provider != null && config != null

    private suspend fun readPending(context: Context): Pending? {
        val raw = DataStoreUtils.getInstance(context).getString(PENDING_KEY) ?: return null
        return runCatching { json.decodeFromString<Pending>(raw) }.getOrNull()
    }

    private suspend fun resolveName(
        context: Context,
        provider: com.vayunmathur.everysync.provider.SyncProvider,
        tokens: OAuthTokens,
    ): String = try {
        provider.resolveAccountName(context, tokens)
    } catch (expected: Exception) {
        Log.error(TAG, "resolveAccountName failed", expected)
        "${provider.displayName} account"
    }

    private suspend fun persistAccount(
        context: Context,
        provider: com.vayunmathur.everysync.provider.SyncProvider,
        accountName: String,
        tokens: OAuthTokens,
    ) {
        TokenStore.getInstance(context).putTokens(accountName, tokens)
        AccountStore.getInstance(context).upsert(
            AccountConfig(
                accountName = accountName,
                providerId = provider.id,
                enabledTypes = provider.capabilities,
            ),
        )
    }

    /** Return a valid access token, refreshing it first if it has expired. */
    suspend fun validAccessToken(
        context: Context,
        accountName: String,
        providerId: String,
    ): String? {
        val store = TokenStore.getInstance(context)
        val tokens = store.getTokens(accountName) ?: return null
        val fresh = if (isExpired(tokens)) {
            refresh(context, accountName, providerId, tokens) ?: tokens
        } else {
            tokens
        }
        return fresh.accessToken
    }

    private fun isExpired(tokens: OAuthTokens): Boolean =
        tokens.expiresAtMs != TOKEN_EXPIRY_UNKNOWN &&
            tokens.expiresAtMs < System.currentTimeMillis() + REFRESH_SKEW_MILLIS

    private suspend fun refresh(
        context: Context,
        accountName: String,
        providerId: String,
        tokens: OAuthTokens,
    ): OAuthTokens? {
        val refreshToken = tokens.refreshToken ?: return null
        val config = ProviderRegistry.get(providerId)?.oauthConfig() ?: return null
        val form = buildMap {
            put("client_id", config.clientId)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val parsed = postForm(config, form) ?: return null
        val updated = tokens.copy(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken ?: tokens.refreshToken,
            expiresAtMs = parsed.expiresAtMs,
        )
        TokenStore.getInstance(context).putTokens(accountName, updated)
        return updated
    }

    private suspend fun exchangeCode(
        config: OAuthConfig,
        code: String,
        verifier: String,
    ): OAuthTokens? {
        val form = buildMap {
            put("client_id", config.clientId)
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", config.redirectUri)
            put("code_verifier", verifier)
        }
        return postForm(config, form)
    }

    private suspend fun postForm(config: OAuthConfig, form: Map<String, String>): OAuthTokens? {
        return try {
            val body = form.entries.joinToString("&") {
                "${Uri.encode(it.key)}=${Uri.encode(it.value)}"
            }
            val resp = NetworkClient.performRequest(
                config.tokenEndpoint,
                "POST",
                mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body,
            )
            val root = json.parseToJsonElement(resp.body) as? JsonObject ?: return null
            val access = root["access_token"]?.jsonPrimitive?.contentOrNullSafe() ?: return null
            val refresh = root["refresh_token"]?.jsonPrimitive?.contentOrNullSafe()
            val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull ?: TOKEN_EXPIRY_UNKNOWN
            OAuthTokens(
                accessToken = access,
                refreshToken = refresh,
                expiresAtMs = if (expiresIn > 0) {
                    System.currentTimeMillis() + expiresIn * MILLIS_PER_SECOND
                } else {
                    TOKEN_EXPIRY_UNKNOWN
                },
            )
        } catch (expected: Exception) {
            Log.error(TAG, "token endpoint call failed", expected)
            null
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { this.content }.getOrNull()?.ifBlank { null }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.UrlSafe.encode(buf).replace("=", "")
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.UrlSafe.encode(digest).replace("=", "")
    }
}
