package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeSessionPoToken
import org.schabi.newpipe.extractor.services.youtube.YoutubeSessionPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.sabrng.exception.SabrProtocolException
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

/**
 * Global PO-token minter for the session-based SABR stack.
 *
 * Port of PipePipe's `LocalDomPoTokenProvider` (post-#84 rewrite): a single mint session is
 * bootstrapped from the YouTube home page ([parseYoutubePageAttestationBootstrap]) and every
 * token — per-video content tokens for SABR playback/downloads and the session token for the
 * WEB player fetch — is minted from that one BotGuard session. The previous design minted each
 * video's token from its own `youtubei/v1/att/get` UNBOUND attestation, whose tokens the server
 * leaves at attestation-pending forever (#565: playback stalls mid-video after 6 no-media
 * responses).
 *
 * When the server rejects the attestation identity, [invalidate] tears the whole session down
 * and re-bootstraps from a fresh home page (upstream `invalidate()`), so the next mint comes
 * from a new identity instead of re-minting from the burned one.
 */
class LocalDomPoTokenProvider(context: Context) : YoutubeSessionPoTokenProvider {
    private data class MintState(
        val bootstrap: YoutubePageAttestationBootstrap,
        val generator: LocalDomPoTokenGenerator,
        val sessionPoToken: ByteArray?,
    )

    private val appContext = context.applicationContext
    private val initializationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "YoutubePoTokenWarmup").apply { isDaemon = true }
    }
    private val initializationLock = Any()

    @Volatile
    private var initializationTask: FutureTask<MintState>? = null

    fun warmUp() {
        ensureInitializationTask()
    }

    /**
     * Raw PO token bytes for the session-based SABR stack ([SabrNgSession]), minted from the
     * global home-page session for [videoId].
     */
    @JvmOverloads
    fun getPoTokenBytes(
        videoId: String,
        forceRefresh: Boolean = false,
    ): ByteArray? {
        if (forceRefresh) {
            invalidate()
        }
        val state = getState()
        return when (state.bootstrap.binding) {
            YoutubePoTokenBinding.CONTENT -> state.generator.mint(videoId)
            YoutubePoTokenBinding.SESSION ->
                requireNotNull(state.sessionPoToken).clone()
            YoutubePoTokenBinding.NONE -> throw SabrProtocolException(
                "YouTube home does not enable a supported PO token binding",
            )
        }
    }

    /**
     * Closes the rejected mint session and re-bootstraps from a fresh home page. Called when
     * the server rejects the attestation identity so the next mint does not come from the
     * burned session. Port of upstream `LocalDomPoTokenProvider.invalidate()`.
     */
    fun invalidate() {
        val sessionToClose: LocalDomPoTokenGenerator
        synchronized(initializationLock) {
            val task = initializationTask ?: return
            if (!task.isDone || task.isCancelled) return
            val state = try {
                task.get()
            } catch (_: Exception) {
                return
            }
            initializationTask = null
            sessionToClose = state.generator
        }
        sessionToClose.close()
        Log.i(TAG, "Invalidated rejected PO token minter")
        warmUp()
    }

    override fun getSessionPoToken(
        clientName: String,
        localization: Localization,
        contentCountry: ContentCountry,
        loggedIn: Boolean,
    ): YoutubeSessionPoToken? {
        val state = getState()
        if (state.bootstrap.binding == YoutubePoTokenBinding.NONE) {
            throw SabrProtocolException(
                "YouTube home does not enable a supported PO token binding",
            )
        }
        // Token for the WEB player request's serviceIntegrityDimensions, bound to the same
        // BotGuard session as the per-video SABR tokens.
        val rawToken = state.sessionPoToken
            ?: state.generator.mint(state.bootstrap.visitorData)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken)
        Log.i(
            TAG,
            "session token ready client=$clientName loggedIn=$loggedIn bytes=${rawToken.size}",
        )
        return YoutubeSessionPoToken(state.bootstrap.visitorData, encoded)
    }

    fun prewarmSessionPoToken(
        localization: Localization,
        contentCountry: ContentCountry,
        loggedIn: Boolean,
    ) {
        warmUp()
    }

    private fun getState(): MintState {
        while (true) {
            val task = ensureInitializationTask()
            val state = try {
                task.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SabrProtocolException("Global PO token initialization interrupted", error)
            } catch (error: ExecutionException) {
                synchronized(initializationLock) {
                    if (initializationTask === task) {
                        initializationTask = null
                    }
                }
                val cause = error.cause ?: error
                throw SabrProtocolException(
                    "Global PO token initialization failed: ${cause.message}",
                    cause,
                )
            }
            if (!state.generator.isExpired()) {
                return state
            }
            synchronized(initializationLock) {
                if (initializationTask === task) {
                    initializationTask = null
                    state.generator.close()
                }
            }
        }
    }

    private fun ensureInitializationTask(): FutureTask<MintState> {
        initializationTask?.let { return it }
        synchronized(initializationLock) {
            initializationTask?.let { return it }
            val task = FutureTask {
                val bootstrap = fetchHomeBootstrap()
                if (bootstrap.binding == YoutubePoTokenBinding.NONE) {
                    throw SabrProtocolException(
                        "YouTube home does not enable a supported PO token binding",
                    )
                }
                val generator = LocalDomPoTokenGenerator.create(appContext, bootstrap)
                try {
                    val sessionPoToken =
                        if (bootstrap.binding == YoutubePoTokenBinding.SESSION) {
                            generator.mint(bootstrap.visitorData)
                        } else {
                            null
                        }
                    Log.i(
                        TAG,
                        "Global PO minter ready client=${bootstrap.clientName} " +
                            "version=${bootstrap.clientVersion} " +
                            "binding=${bootstrap.binding}",
                    )
                    MintState(bootstrap, generator, sessionPoToken)
                } catch (error: Throwable) {
                    generator.close()
                    throw error
                }
            }
            initializationTask = task
            initializationExecutor.execute(task)
            return task
        }
    }

    private fun fetchHomeBootstrap(): YoutubePageAttestationBootstrap {
        val downloader = NewPipe.getDownloader()
        val response = downloader.get(
            YOUTUBE_HOME,
            mapOf(
                "Accept-Language" to listOf("en-US"),
                "Cookie" to listOf(ANONYMOUS_COOKIE),
                "User-Agent" to listOf(SharedWebViewRuntime.USER_AGENT),
            ),
        )
        if (response.responseCode() != 200) {
            throw SabrProtocolException(
                "YouTube home initialization failed: ${response.responseCode()}",
            )
        }
        return parseYoutubePageAttestationBootstrap(response.responseBody())
    }

    companion object {
        private const val TAG = "SabrLocalDomPoToken"
        private const val YOUTUBE_HOME = "https://www.youtube.com"
        private const val ANONYMOUS_COOKIE = "PREF=hl=en&gl=US"

        @Volatile
        private var sharedInstance: LocalDomPoTokenProvider? = null

        @JvmStatic
        fun shared(context: Context): LocalDomPoTokenProvider {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: LocalDomPoTokenProvider(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }
    }
}
