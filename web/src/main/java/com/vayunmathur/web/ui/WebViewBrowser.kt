package com.vayunmathur.web.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.web.util.WebViewModel

/**
 * The core WebView composable. It owns one android.webkit.WebView per tab via [webViewPool],
 * reporting page lifecycle back to [viewModel].
 *
 * We keep WebViews alive out of Compose by storing them in a map kept at composition scope via
 * remember, so switching activities or tabs does not destroy navigation state.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBrowser(
    tabId: String,
    initialUrl: String,
    viewModel: WebViewModel,
    modifier: Modifier = Modifier,
    onRequestNewTab: (String) -> Unit = {},
    webViewPool: MutableMap<String, WebView>,
) {
    // Ensure we have a WebView for this tab
    // The actual creation happens inside AndroidView factory; but we also need to preserve.
    val holder = remember(tabId) { WebViewHolder() }

    LaunchedEffect(tabId, initialUrl) {
        // When tab id switches to one whose WebView url differs from tab state's url,
        // we will load the new url (via AndroidView factory/update path).
        // No-op here; the AndroidView update handles it.
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            // Reuse if pool already has it (tab switch back)
            webViewPool[tabId]?.let { existing ->
                // Detach from previous parent if any
                (existing.parent as? ViewGroup)?.removeView(existing)
                return@AndroidView existing
            }

            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Let WebView handle http/https; open custom schemes externally.
                        val url = request.url.toString()
                        val scheme = request.url.scheme ?: return false
                        // For target=_blank within same tab, shouldOverrideUrlLoading is called;
                        // opening new tab keeps UX closer to desktop.
                        if (request.isForMainFrame) {
                            if (scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data") {
                                // Let system handle tel:, mailto:, intent:, etc.
                                return false
                            }
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        url?.let { viewModel.onTabUrlChange(tabId, it) }
                        viewModel.onTabCanGoBack(tabId, view.canGoBack())
                        viewModel.onTabCanGoForward(tabId, view.canGoForward())
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        url?.let { viewModel.onTabUrlChange(tabId, it) }
                        viewModel.onTabCanGoBack(tabId, view.canGoBack())
                        viewModel.onTabCanGoForward(tabId, view.canGoForward())
                        CookieManager.getInstance().flush()
                        val title = view.title?.takeIf { it.isNotBlank() } ?: url ?: ""
                        if (title.isNotBlank()) {
                            viewModel.onTabTitleChange(tabId, title)
                            viewModel.recordHistoryVisit(url ?: "", title)
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                        url?.let { viewModel.onTabUrlChange(tabId, it) }
                        viewModel.onTabCanGoBack(tabId, view.canGoBack())
                        viewModel.onTabCanGoForward(tabId, view.canGoForward())
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        viewModel.onTabProgress(tabId, newProgress / 100f)
                    }

                    override fun onReceivedTitle(view: WebView, title: String?) {
                        if (!title.isNullOrBlank()) {
                            viewModel.onTabTitleChange(tabId, title)
                        }
                    }

                    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                        // Could store favicon; for now title path is enough.
                    }

                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                        // Handle window.open() by opening a new tab.
                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val newWebView = WebView(view.context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                        }
                        // Intercept its load to extract url
                        newWebView.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                onRequestNewTab(request.url.toString())
                                return true
                            }

                            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                                url?.let { onRequestNewTab(it) }
                                v.stopLoading()
                            }
                        }
                        transport.webView = newWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                val toLoad = if (initialUrl.isBlank()) "about:blank" else initialUrl
                loadUrl(toLoad)
            }.also { webViewPool[tabId] = it }
        },
        update = { webView ->
            // If the model url diverges from WebView's current url (e.g. omnibox navigation,
            // external intent), load it. Avoid reload loops when it's the same or during typing.
            val current = webView.url ?: ""
            val desired = viewModel.getCurrentUrl(tabId)
            if (desired.isNotBlank() && desired != current && !viewModel.omniboxFocused) {
                // Only load if the desired url is not just pretty-printed version,
                // and if it's different from current.
                // Basic guard: don't reload if current is prefix of desired handling in-progress loads is tricky,
                // so we only load when explicitly different and tab is active.
                if (viewModel.activeTabId == tabId) {
                    // Avoid reloading if webView is still loading desired (progress < 1)
                    val prog = viewModel.getProgress(tabId)
                    if (current.isBlank() || (current != desired && prog >= 1f)) {
                        webView.loadUrl(desired)
                    }
                }
            }
            holder.webView = webView
        }
    )

    DisposableEffect(tabId) {
        onDispose {
            // Do NOT destroy WebView on dispose; we keep it in pool for tab reuse.
            // Just detach it from composition — AndroidView will handle removal.
            // holder.webView is still in pool.
        }
    }
}

private class WebViewHolder {
    var webView: WebView? = null
}
