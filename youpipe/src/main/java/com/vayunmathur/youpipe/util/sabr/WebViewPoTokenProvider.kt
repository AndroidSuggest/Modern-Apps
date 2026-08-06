package com.vayunmathur.youpipe.util.sabr

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.edit
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Legacy YouTube-page WebPoClient SABR PO-token provider.
 *
 * The app no longer calls this provider. Normal playback and download use
 * [LocalDomPoTokenProvider], which avoids loading the YouTube page and reuses the shared local
 * JavaScript runtime. This class is kept only as a legacy fallback/debug reference for the old
 * page-loaded WebPoClient pipeline.
 */
@Deprecated("Use LocalDomPoTokenProvider; kept as a legacy fallback/debug reference.")
class WebViewPoTokenProvider(context: Context) : SabrPoTokenProvider {

    private class CachedToken(val token: ByteArray, val mintedAtMs: Long)

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cache: MutableMap<String, CachedToken> = ConcurrentHashMap()

    // one lock per videoId so two callers (pre-warm + pump) don't both fire the ~45s WebView mint
    // for the same video. second one just waits and takes the cached token.
    private val mintLocks: MutableMap<String, Any> = ConcurrentHashMap()

    @Throws(SabrProtocolException::class)
    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState
    ): ByteArray? = getPoToken(info, streamState, false)

    @Throws(SabrProtocolException::class)
    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
        forceRefresh: Boolean
    ): ByteArray? {
        val videoId = info.videoId
        Log.i(
            TAG,
            "get video=$videoId force=$forceRefresh thread=${Thread.currentThread().name}"
        )
        if (forceRefresh) {
            // Server rejected the cached token: drop it (memory + disk) and mint fresh.
            cache.remove(videoId)
            prefs.edit { remove(videoId) }
        }
        synchronized(mintLocks.computeIfAbsent(videoId) { Any() }) {
            val now = System.currentTimeMillis()
            var cached = cache[videoId]
            if (cached == null) {
                cached = diskLoad(videoId) // survive process restart, skip the ~45s mint
                if (cached != null) {
                    cache[videoId] = cached
                }
            }
            if (cached != null && now - cached.mintedAtMs < TOKEN_TTL_MS) {
                Log.i(
                    TAG,
                    "cache hit video=$videoId bytes=${cached.token.size}" +
                        " ageMs=${now - cached.mintedAtMs}"
                )
                return cached.token
            }
            if (Thread.currentThread().isInterrupted) {
                Log.w(TAG, "mint skipped: interrupted video=$videoId")
                throw SabrProtocolException("PO token mint interrupted before start")
            }
            // One retry avoids failing playback on a transient WebPoClient error.
            val contentBinding = info.videoId
            Log.i(TAG, "mint start video=$videoId binding=video_id")
            var tokenB64 = mintBlocking(contentBinding)
            if (Thread.currentThread().isInterrupted) {
                throw SabrProtocolException("PO token mint interrupted after pipeline")
            }
            if (tokenB64.isNullOrEmpty()) {
                Log.w(TAG, "PO token mint returned null, retrying once for $videoId")
                tokenB64 = mintBlocking(contentBinding)
                if (Thread.currentThread().isInterrupted) {
                    throw SabrProtocolException("PO token mint interrupted after retry")
                }
            }
            if (tokenB64.isNullOrEmpty()) {
                Log.e(TAG, "PO token mint failed after retry video=$videoId")
                return null
            }
            val token = try {
                Base64.getUrlDecoder().decode(tokenB64)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "could not decode PO token", e)
                return null
            }
            cache[videoId] = CachedToken(token, now)
            diskSave(videoId, tokenB64, now)
            Log.i(TAG, "mint complete video=$videoId bytes=${token.size}")
            return token
        }
    }

    /**
     * True if a non-expired PO token for this video is already in memory or on disk, WITHOUT
     * minting. Lets a caller pre-load metadata cheaply when we've recently played this video
     * (cold-restore / re-resolve) while NOT blocking the first-ever play on the ~45s mint.
     */
    fun hasCachedToken(videoId: String): Boolean {
        val mem = cache[videoId]
        if (mem != null && System.currentTimeMillis() - mem.mintedAtMs < TOKEN_TTL_MS) {
            return true
        }
        return diskLoad(videoId) != null
    }

    private fun diskLoad(videoId: String): CachedToken? {
        val v = prefs.getString(videoId, null) ?: return null
        val sep = v.indexOf('|')
        if (sep <= 0) {
            return null
        }
        return try {
            val mintedAt = v.substring(0, sep).toLong()
            if (System.currentTimeMillis() - mintedAt >= TOKEN_TTL_MS) {
                prefs.edit { remove(videoId) }
                null
            } else {
                CachedToken(Base64.getUrlDecoder().decode(v.substring(sep + 1)), mintedAt)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun diskSave(videoId: String, tokenB64: String, mintedAt: Long) {
        // commit() (sync) not apply(): the token must hit disk before a fast force-stop/process
        // kill, else an app cold-start re-mints (~45s) even though a valid token was just minted.
        prefs.edit(commit = true) { putString(videoId, "$mintedAt|$tokenB64") }
    }

    @Throws(SabrProtocolException::class)
    private fun mintBlocking(contentBinding: String): String? {
        val latch = CountDownLatch(1)
        val canceled = AtomicBoolean(false)
        val tokenRef = AtomicReference<String?>()
        val webViewRef = AtomicReference<WebView?>()
        val stage = AtomicReference<String?>("posting_create")
        val detail = AtomicReference<String?>("none")
        val failureRef = AtomicReference<Throwable?>()
        val startedAt = System.currentTimeMillis()

        mainHandler.post {
            if (canceled.get()) {
                Log.w(TAG, "create canceled before main-thread start")
                latch.countDown()
                return@post
            }
            try {
                stage.set("creating_webview")
                Log.i(
                    TAG,
                    "creating WebView mainThread=${Looper.myLooper() == Looper.getMainLooper()}"
                )
                val webView = createWebView(
                    contentBinding, tokenRef, latch, canceled, stage, detail, failureRef
                )
                if (canceled.get()) {
                    Log.w(TAG, "create completed after cancellation")
                    destroyWebView(webView)
                    latch.countDown()
                } else {
                    webViewRef.set(webView)
                    Log.i(TAG, "WebView created and load requested")
                }
            } catch (e: Exception) {
                stage.set("create_failed")
                failureRef.set(e)
                Log.e(TAG, "failed to start WebView pipeline", e)
                latch.countDown()
            }
        }

        try {
            if (!latch.await(PIPELINE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(
                    TAG,
                    "pipeline timeout stage=${stage.get()}" +
                        " elapsedMs=${System.currentTimeMillis() - startedAt}" +
                        " webView=${webViewRef.get() != null} detail=${detail.get()}"
                )
                throw SabrProtocolException(
                    "PO token pipeline timed out at ${stage.get()}, detail=${detail.get()}"
                )
            }
            Log.i(
                TAG,
                "pipeline released stage=${stage.get()}" +
                    " elapsedMs=${System.currentTimeMillis() - startedAt}" +
                    " token=${if (tokenRef.get() == null) "null" else "present"}"
            )
            val failure = failureRef.get()
            if (failure != null) {
                throw SabrProtocolException(
                    "PO token pipeline failed at ${stage.get()}, detail=${detail.get()}" +
                        ": ${failure.message}",
                    failure
                )
            }
            if (tokenRef.get().isNullOrEmpty()) {
                throw SabrProtocolException(
                    "PO token pipeline returned no token at ${stage.get()}," +
                        " detail=${detail.get()}"
                )
            }
        } catch (e: InterruptedException) {
            val interruptedStage = stage.get()
            Log.w(
                TAG,
                "pipeline interrupted stage=$interruptedStage" +
                    " elapsedMs=${System.currentTimeMillis() - startedAt} detail=${detail.get()}",
                e
            )
            Thread.currentThread().interrupt()
            throw SabrProtocolException(
                "PO token pipeline interrupted at $interruptedStage, detail=${detail.get()}", e
            )
        } finally {
            canceled.set(true)
            mainHandler.post { destroyWebView(webViewRef.getAndSet(null)) }
        }
        return tokenRef.get()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        contentBinding: String,
        tokenRef: AtomicReference<String?>,
        latch: CountDownLatch,
        canceled: AtomicBoolean,
        stage: AtomicReference<String?>,
        detail: AtomicReference<String?>,
        failureRef: AtomicReference<Throwable?>
    ): WebView {
        val webView = WebView(appContext)
        val injected = AtomicBoolean(false)
        WebViewCompat.getCurrentWebViewPackage(appContext)?.let { pkg ->
            Log.i(TAG, "WebView package=${pkg.packageName} version=${pkg.versionName}")
            detail.set("webView=${pkg.packageName}/${pkg.versionName}")
        }
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = DESKTOP_UA
        webView.addJavascriptInterface(
            Bridge(tokenRef, latch, canceled, stage, detail, failureRef),
            "SabrPocBridge"
        )
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(
                    TAG,
                    "console ${message.messageLevel()} ${message.message()}" +
                        " @${message.sourceId()}:${message.lineNumber()}"
                )
                detail.set(
                    "console=${limit(message.message(), 300)}" +
                        " level=${message.messageLevel()} line=${message.lineNumber()}"
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (url.contains("/js/th/")) {
                    Log.i(TAG, "intercept interpreter url=$url")
                    return fetchWithCors(url)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "page finished url=$url canceled=${canceled.get()}")
                if (canceled.get() || url == null || !url.contains("youtube.com") ||
                    !injected.compareAndSet(false, true)
                ) {
                    return
                }
                stage.set("page_finished")
                waitForReadyThenInject(
                    view, contentBinding, 0, canceled, stage, detail, failureRef, latch
                )
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                Log.i(TAG, "page commit url=$url canceled=${canceled.get()}")
                if (canceled.get() || url == null || !url.contains("youtube.com") ||
                    !injected.compareAndSet(false, true)
                ) {
                    return
                }
                stage.set("page_committed")
                waitForReadyThenInject(
                    view, contentBinding, 0, canceled, stage, detail, failureRef, latch
                )
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                Log.w(
                    TAG,
                    "resource error main=${request.isForMainFrame} code=${error.errorCode}" +
                        " description=${error.description} url=${request.url}"
                )
                if (request.isForMainFrame && failureRef.compareAndSet(
                        null,
                        IllegalStateException(
                            "WebView main page error ${error.errorCode}: ${error.description}"
                        )
                    )
                ) {
                    stage.set("main_page_failed")
                    latch.countDown()
                }
            }

            // Returning false hands the dead renderer back to the framework, which kills the
            // whole app process. Fail this PO token attempt instead.
            override fun onRenderProcessGone(
                view: WebView,
                gone: RenderProcessGoneDetail,
            ): Boolean {
                Log.w(TAG, "renderer gone crashed=${gone.didCrash()}")
                if (failureRef.compareAndSet(
                        null,
                        IllegalStateException(
                            "WebView renderer process gone (didCrash=${gone.didCrash()})"
                        )
                    )
                ) {
                    stage.set("renderer_gone")
                    latch.countDown()
                }
                return true
            }
        }
        stage.set("loading_page")
        Log.i(TAG, "load page")
        webView.loadUrl("https://www.youtube.com?themeRefresh=1")
        return webView
    }

    private fun waitForReadyThenInject(
        view: WebView,
        contentBinding: String,
        attempt: Int,
        canceled: AtomicBoolean,
        stage: AtomicReference<String?>,
        detail: AtomicReference<String?>,
        failureRef: AtomicReference<Throwable?>,
        latch: CountDownLatch
    ) {
        if (canceled.get()) {
            Log.w(TAG, "ready poll canceled attempt=$attempt")
            return
        }
        stage.set("ready_poll_$attempt")
        view.evaluateJavascript("document.readyState") { value ->
            if (canceled.get()) {
                Log.w(TAG, "ready result canceled attempt=$attempt")
                return@evaluateJavascript
            }
            val complete = value != null && value.contains("complete")
            Log.i(TAG, "ready attempt=$attempt value=$value complete=$complete")
            detail.set("readyState=$value attempt=$attempt")
            if (complete || attempt >= READY_RETRIES) {
                stage.set("injecting_binding")
                view.evaluateJavascript(
                    "window.__SABR_WEBPO_CONTENT_BINDING=${jsString(contentBinding)};"
                ) { result -> Log.i(TAG, "binding injected result=$result") }
                stage.set("injecting_script")
                val script = loadPipelineScript()
                if (script.isEmpty()) {
                    failureRef.compareAndSet(
                        null, IllegalStateException("WebPo pipeline asset is empty")
                    )
                    stage.set("script_load_failed")
                    latch.countDown()
                    return@evaluateJavascript
                }
                view.evaluateJavascript(script) { result ->
                    stage.set("waiting_bridge")
                    Log.i(TAG, "script injected result=$result")
                }
            } else {
                mainHandler.postDelayed({
                    waitForReadyThenInject(
                        view, contentBinding, attempt + 1, canceled, stage, detail, failureRef,
                        latch
                    )
                }, READY_POLL_MS)
            }
        }
    }

    private fun loadPipelineScript(): String {
        return try {
            appContext.assets.open(ASSET).use { input ->
                ByteArrayOutputStream().use { out ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val read = input.read(chunk)
                        if (read == -1) break
                        out.write(chunk, 0, read)
                    }
                    out.toString("UTF-8")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not read pipeline asset", e)
            ""
        }
    }

    private class Bridge(
        private val tokenRef: AtomicReference<String?>,
        private val latch: CountDownLatch,
        private val canceled: AtomicBoolean,
        private val stage: AtomicReference<String?>,
        private val detail: AtomicReference<String?>,
        private val failureRef: AtomicReference<Throwable?>
    ) {
        @JavascriptInterface
        fun onStage(nextStage: String?, nextDetail: String?) {
            stage.set("js_${limit(nextStage, 80)}")
            detail.set(limit(nextDetail, 500))
            Log.i(TAG, "JS stage=${stage.get()} detail=${detail.get()}")
        }

        @JavascriptInterface
        fun onResult(json: String?) {
            stage.set("bridge_called")
            Log.i(
                TAG,
                "bridge called canceled=${canceled.get()} jsonLength=${json?.length ?: -1}"
            )
            try {
                if (canceled.get()) {
                    Log.w(TAG, "bridge result ignored after cancellation")
                    return
                }
                val obj = JSONObject(json!!)
                if (obj.optBoolean("ok", false)) {
                    val token: String? = obj.opt("poToken")?.toString()
                    tokenRef.set(token)
                    stage.set("bridge_success")
                    Log.i(TAG, "bridge success tokenB64Length=${token?.length ?: -1}")
                } else {
                    stage.set("bridge_failed")
                    val error = obj.optString("error", "unknown")
                    failureRef.compareAndSet(null, IllegalStateException(error))
                    Log.w(TAG, "PO token pipeline failed: $error")
                }
            } catch (e: Exception) {
                stage.set("bridge_parse_failed")
                failureRef.compareAndSet(null, e)
                Log.e(TAG, "could not parse pipeline result", e)
            } finally {
                latch.countDown()
            }
        }
    }

    private companion object {
        private const val TAG = "WebViewPoToken"
        private const val ASSET = "sabr_webpo_client.js"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private const val TOKEN_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours

        // The WebView mint can occasionally run long. 60s + one retry avoids a token-less cold
        // start.
        private const val PIPELINE_TIMEOUT_MS = 60_000L

        // Persist minted tokens across process restarts so an app cold-start can reuse a valid
        // token.
        private const val PREFS = "sabr_webpo_video_token_cache"
        private const val READY_RETRIES = 20
        private const val READY_POLL_MS = 250L

        private fun destroyWebView(webView: WebView?) {
            if (webView == null) {
                return
            }
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            } catch (ignored: Exception) {
                // best effort
            }
        }

        private fun jsString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        private fun limit(value: String?, maxLength: Int): String? {
            if (value == null || value.length <= maxLength) {
                return value
            }
            return value.substring(0, maxLength)
        }

        private fun fetchWithCors(url: String): WebResourceResponse? {
            return try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", DESKTOP_UA)
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val code = connection.responseCode
                val body: InputStream? = if (code >= 400) {
                    connection.errorStream
                } else {
                    connection.inputStream
                }
                val contentType = connection.contentType
                var mime = "application/javascript"
                if (contentType != null) {
                    val sep = contentType.indexOf(';')
                    mime = if (sep > 0) {
                        contentType.substring(0, sep).trim()
                    } else {
                        contentType.trim()
                    }
                }
                val headers = HashMap<String, String>()
                headers["Access-Control-Allow-Origin"] = "*"
                val response = WebResourceResponse(mime, "UTF-8", body)
                response.setStatusCodeAndReasonPhrase(code, if (code >= 400) "ERROR" else "OK")
                response.responseHeaders = headers
                response
            } catch (e: Exception) {
                Log.e(TAG, "interpreter native fetch failed", e)
                null
            }
        }
    }
}
