package com.vayunmathur.appstore.data.play

import android.content.Context
import android.util.Log
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.helpers.AuthHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * Anonymous auth via Aurora dispenser.
 * Ported from AuroraStore's AuthProvider.
 */
class AnonymousAuthRepository(
    private val okHttpClient: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "AnonAuthRepo"
        const val DEFAULT_DISPENSER_URL = "https://auroraoss.com/api/auth"
        val FALLBACK_DISPENSERS = listOf(
            "https://auroraoss.com/api/auth"
        )

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    data class AuthResponse(
        val email: String,
        @SerialName("authToken") val auth: String
    )

    sealed class AuthError : Exception() {
        object RateLimited : AuthError()
        object BadRequest : AuthError()
        object VpnRequired : AuthError()
        object NotFound : AuthError()
        object Maintenance : AuthError()
        data class Unknown(val code: Int, val body: String?) : AuthError()
        data class Network(override val message: String?) : AuthError()
    }

    /**
     * Fetch raw credentials from dispenser.
     */
    suspend fun fetchAnonCredentials(
        context: Context,
        deviceProps: Properties,
        dispenserUrl: String = DEFAULT_DISPENSER_URL
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val propsJson = propertiesToJson(deviceProps)
            val appId = context.packageName
            val versionName = try {
                context.packageManager.getPackageInfo(appId, 0).versionName ?: "1.0"
            } catch (_: Exception) { "1.0" }
            val versionCode = try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(appId, 0).versionCode.toString()
            } catch (_: Exception) { "1" }

            val userAgent = "$appId-$versionName-$versionCode"
            val body = propsJson.toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(dispenserUrl)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(mapError(response.code, responseBody))
            }

            try {
                val auth = json.decodeFromString(AuthResponse.serializer(), responseBody)
                Result.success(auth)
            } catch (e: Exception) {
                Log.w(TAG, "Parse auth failed: ${e.message}")
                Result.failure(AuthError.Unknown(response.code, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(AuthError.Network(e.message))
        }
    }

    /**
     * Build AuthData from dispenser response using gplayapi AuthHelper.
     * AuthHelper.build(email, token, Token.AUTH, isAnonymous=true, properties, locale)
     */
    suspend fun buildAuthData(
        context: Context,
        email: String,
        token: String,
        deviceProps: Properties
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            val locale = java.util.Locale.getDefault()
            val authData = AuthHelper
                .build(
                    email = email,
                    token = token,
                    tokenType = AuthHelper.Token.AUTH,
                    isAnonymous = true,
                    properties = deviceProps,
                    locale = locale
                )
            Result.success(authData)
        } catch (e: Exception) {
            Log.w(TAG, "buildAuthData failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Try dispensers in order until success.
     */
    suspend fun ensureAuthData(
        context: Context,
        deviceProps: Properties,
        dispenserUrls: List<String> = FALLBACK_DISPENSERS
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (url in dispenserUrls) {
            val credResult = fetchAnonCredentials(context, deviceProps, url)
            if (credResult.isFailure) {
                lastError = credResult.exceptionOrNull()
                Log.w(TAG, "Dispenser $url failed: $lastError")
                continue
            }
            val cred = credResult.getOrNull() ?: continue
            val authResult = buildAuthData(context, cred.email, cred.auth, deviceProps)
            if (authResult.isSuccess) return@withContext authResult
            lastError = authResult.exceptionOrNull()
        }
        Result.failure(lastError ?: Exception("All dispensers failed"))
    }

    private fun propertiesToJson(props: Properties): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in props.entries) {
            if (!first) sb.append(",")
            sb.append("\"").append(escapeJson(k.toString())).append("\":\"").append(escapeJson(v.toString())).append("\"")
            first = false
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun mapError(code: Int, body: String?): Throwable {
        return when (code) {
            400 -> AuthError.BadRequest
            403 -> AuthError.VpnRequired
            404 -> AuthError.NotFound
            429 -> AuthError.RateLimited
            503 -> AuthError.Maintenance
            else -> AuthError.Unknown(code, body)
        }
    }

    fun errorMessage(error: Throwable): String {
        return when (error) {
            is AuthError.RateLimited -> "Login rate limited, try later"
            is AuthError.VpnRequired -> "VPN/proxy detected – dispenser blocked"
            is AuthError.BadRequest -> "Bad request to dispenser"
            is AuthError.NotFound -> "Dispenser unreachable"
            is AuthError.Maintenance -> "Dispenser maintenance, try later"
            is AuthError.Unknown -> "Dispenser error ${error.code}"
            is AuthError.Network -> "Network error: ${error.message}"
            else -> error.message ?: "Auth failed"
        }
    }
}
