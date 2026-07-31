@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.messages.gvoice

import kotlin.concurrent.atomics.*
import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import waa.Waa
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView-based WAA request signer.
 *
 * Mirrors the Go bridge's Electron subprocess
 * (`pkg/connector/electron.go` + `electron.mjs`): the Go bridge spawns a
 * headless Electron browser, loads Google's WAA interpreter JS from the
 * URL returned by `CreateWaa`, and invokes `window[globalName].a(program,
 * …)` to produce the per-message `TrackingData` token sent with
 * `sendsms`. We do the same thing with an Android [WebView] (Chromium)
 * which gives us a real browser JS engine + voice.google.com origin.
 *
 * Flow (matches electron.mjs):
 *  1. [ensureReady] calls `CreateWaa` (via the injected callback) to get
 *     `program`, `globalName`, and the interpreter script URL.
 *  2. A WebView is loaded with a `https://voice.google.com/about` base
 *     origin and the interpreter `<script>` is injected.
 *  3. [sign] hashes the request triple (thread id, recipients, txn id)
 *     exactly like `electron.go:requestSignature`, then runs the
 *     interpreter to obtain the signature string.
 *
 * The WAA payload is re-created after [WAA_EXPIRY_MS] (1h), matching
 * Go's `WaaExpiry`.
 *
 * NOTE: the JS-execution boundary (steps 2–3 actually producing a valid
 * Google-accepted token) cannot be exercised in this environment, so the
 * interpreter-invocation path is marked // UNVERIFIED. Callers fall back
 * to the legacy "!" tracking data if signing returns null.
 */
class WaaSigner(
    private val context: Context,
    private val createWaa: suspend () -> Waa.CreatedWaa?,
) {
    private companion object {
        const val TAG = "GVoice/Waa"
        const val WAA_EXPIRY_MS = 60L * 60L * 1000L // electron.go WaaExpiry = 1h
        const val SIGN_TIMEOUT_MS = 10_000L // electron.go requestSignatureDirect timeout
        const val LOAD_TIMEOUT_MS = 30_000L
        const val BASE_URL = "https://voice.google.com/about"
        val STATIC_HTML =
            "<!DOCTYPE html><html><head></head><body></body></html>"
    }

    @Volatile private var webView: WebView? = null
    @Volatile private var program: String = ""
    @Volatile private var globalName: String = ""
    @Volatile private var initedAt: Long = 0L
    @Volatile private var pageReady = false

    private val reqCounter = AtomicLong(0)
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Result<String>>>()
    @Volatile private var pageReadyWaiter: CompletableDeferred<Boolean>? = null
    @Volatile private var scriptLoadWaiter: CompletableDeferred<Result<Unit>>? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onPageReady() {
            pageReadyWaiter?.complete(true)
        }

        @JavascriptInterface
        fun onScriptLoaded() {
            scriptLoadWaiter?.complete(Result.success(Unit))
        }

        @JavascriptInterface
        fun onScriptError(error: String) {
            scriptLoadWaiter?.complete(Result.failure(RuntimeException(error)))
        }

        @JavascriptInterface
        fun onResult(reqId: String, result: String?) {
            waiters.remove(reqId)?.complete(Result.success(result ?: ""))
        }

        @JavascriptInterface
        fun onError(reqId: String, error: String) {
            waiters.remove(reqId)?.complete(Result.failure(RuntimeException(error)))
        }

        @JavascriptInterface
        fun onLog(message: String) {
            Log.d(TAG, "JS: $message")
        }
    }

    /**
     * Produce the `TrackingData` signature for a sendsms request. Mirrors
     * `electron.go:requestSignature`: each component is `base64(sha256(x))`.
     * Returns null on any failure (caller should fall back to "!").
     */
    suspend fun sign(threadId: String, recipients: List<String>, txnId: Long): String? {
        if (!ensureReady()) return null
        val payload = JSONObject().apply {
            put("thread_id", b64Sha256(threadId))
            put("destinations", b64Sha256(recipients.joinToString("|")))
            put("message_ids", b64Sha256(txnId.toString()))
        }
        return execute(payload)
    }

    /**
     * Produce a "blank" signature used for the WAA ping handshake
     * (electron.mjs `blank_payload: true`). Returns null on failure.
     */
    suspend fun signBlank(): String? {
        if (!ensureReady()) return null
        return execute(null)
    }

    /** Create-or-refresh the WAA interpreter if missing/expired. */
    private suspend fun ensureReady(): Boolean {
        val fresh = program.isNotEmpty() &&
            System.currentTimeMillis() - initedAt <= WAA_EXPIRY_MS
        if (fresh) return true
        val waa = try {
            createWaa()
        } catch (t: Throwable) {
            Log.w(TAG, "CreateWaa failed: ${t.message}")
            null
        } ?: return false
        return try {
            initFromWaa(waa)
        } catch (t: Throwable) {
            Log.w(TAG, "WAA init failed: ${t.message}")
            false
        }
    }

    private suspend fun initFromWaa(waa: Waa.CreatedWaa): Boolean {
        program = waa.program
        globalName = waa.globalName
        var src = waa.interpreterURL.url
        if (src.isEmpty() || globalName.isEmpty() || program.isEmpty()) {
            Log.w(TAG, "CreateWaa returned incomplete payload")
            return false
        }
        if (src.startsWith("//")) src = "https:$src"

        if (!pageReady) {
            val readyWaiter = CompletableDeferred<Boolean>()
            pageReadyWaiter = readyWaiter
            withContext(Dispatchers.Main) { ensureWebView() }
            val ok = withTimeoutOrNull(LOAD_TIMEOUT_MS) { readyWaiter.await() }
            if (ok != true) {
                Log.w(TAG, "WebView page load timed out")
                return false
            }
            pageReady = true
        }

        // UNVERIFIED: loading + executing Google's WAA interpreter JS cannot
        // be exercised here; the injection path mirrors electron.mjs loadScript.
        val loadWaiter = CompletableDeferred<Result<Unit>>()
        scriptLoadWaiter = loadWaiter
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript(buildLoadJs(src), null)
        }
        val loaded = withTimeoutOrNull(LOAD_TIMEOUT_MS) { loadWaiter.await() }
        if (loaded == null || loaded.isFailure) {
            Log.w(TAG, "WAA interpreter script load failed: ${loaded?.exceptionOrNull()?.message ?: "timeout"}")
            return false
        }
        initedAt = System.currentTimeMillis()
        Log.i(TAG, "WAA interpreter ready (global=$globalName)")
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) return
        val readyWaiter = pageReadyWaiter
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = VoiceEndpoints.UserAgent
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript("Android.onPageReady();", null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    Log.w(TAG, "WebView error $errorCode: $description")
                    readyWaiter?.complete(false)
                }
            }
            loadDataWithBaseURL(BASE_URL, STATIC_HTML, "text/html", "UTF-8", null)
        }
    }

    /**
     * Run the WAA interpreter. Mirrors electron.mjs `executeScript`:
     * `window[globalName].a(program, fns => …, true, undefined, () => {})`
     * then `fn1(result => …, [payload, undefined, undefined, undefined])`.
     * [payload] is null for the blank/ping signature.
     */
    private suspend fun execute(payload: JSONObject?): String? {
        val reqId = reqCounter.incrementAndFetch().toString()
        val waiter = CompletableDeferred<Result<String>>()
        waiters[reqId] = waiter
        val payloadJs = payload?.toString() ?: "undefined"
        try {
            withContext(Dispatchers.Main) {
                webView?.evaluateJavascript(buildExecuteJs(reqId, payloadJs), null)
            }
        } catch (t: Throwable) {
            waiters.remove(reqId)
            Log.w(TAG, "WAA execute dispatch failed: ${t.message}")
            return null
        }
        val result = withTimeoutOrNull(SIGN_TIMEOUT_MS) { waiter.await() }
        waiters.remove(reqId)
        if (result == null) {
            Log.w(TAG, "WAA signature timed out (req=$reqId)")
            return null
        }
        return result.fold(
            onSuccess = { it.takeIf { s -> s.isNotEmpty() } },
            onFailure = {
                Log.w(TAG, "WAA signature error (req=$reqId): ${it.message}")
                null
            },
        )
    }

    // UNVERIFIED: exact interpreter calling convention reproduced from
    // electron.mjs; not runtime-verified against live Google WAA JS.
    private fun buildExecuteJs(reqId: String, payloadJs: String): String {
        val gn = JSONObject.quote(globalName)
        val prog = JSONObject.quote(program)
        return """
            (function(){
              try {
                var gn = $gn;
                var prog = $prog;
                var payload = $payloadJs;
                new Promise(function(resolve, reject){
                  window[gn].a(prog, function(f1, f2, f3, f4){ resolve(f1); }, true, undefined, function(){});
                }).then(function(fn1){
                  fn1(function(result){ Android.onResult("$reqId", result); }, [payload, undefined, undefined, undefined]);
                }, function(err){ Android.onError("$reqId", String(err)); });
              } catch (e) {
                Android.onError("$reqId", String(e));
              }
            })();
        """.trimIndent()
    }

    private fun buildLoadJs(src: String): String {
        val q = JSONObject.quote(src)
        return """
            (function(){
              try {
                var s = document.createElement('script');
                s.setAttribute('src', $q);
                s.onload = function(){ Android.onScriptLoaded(); };
                s.onerror = function(e){ Android.onScriptError('script load error'); };
                document.head.appendChild(s);
              } catch (e) {
                Android.onScriptError(String(e));
              }
            })();
        """.trimIndent()
    }

    fun destroy() {
        val wv = webView
        webView = null
        pageReady = false
        program = ""
        globalName = ""
        initedAt = 0L
        waiters.values.forEach { it.complete(Result.failure(RuntimeException("signer destroyed"))) }
        waiters.clear()
        if (wv != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { wv.destroy() }
            }
        }
    }

    private fun b64Sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
