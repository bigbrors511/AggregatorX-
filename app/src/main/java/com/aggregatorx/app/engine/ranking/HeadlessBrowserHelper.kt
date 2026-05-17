package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HeadlessBrowserHelper — Android WebView-based headless browser.
 * Provides a Playwright-like API for JS execution, shadow DOM traversal,
 * and automated ad-bypass in a background WebView.
 */
@Singleton
class HeadlessBrowserHelper @Inject constructor(
    private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // Static accessors for compatibility with existing engine calls
        private var instance: HeadlessBrowserHelper? = null

        fun init(helper: HeadlessBrowserHelper) {
            instance = helper
        }

        suspend fun fetchPageContentWithShadowAndAdSkip(url: String, waitSelector: String? = null, timeout: Int = 20000): String? =
            instance?.fetchPageContentWithShadowAndAdSkipInternal(url, waitSelector, timeout)

        suspend fun fetchPageContent(url: String, timeout: Int = 15000): String? =
            instance?.fetchPageContentWithShadowAndAdSkipInternal(url, null, timeout)

        fun createAntiDetectionPage(): Page =
            Page(instance!!.context)

        suspend fun extractVideoUrls(url: String): List<String> =
            instance?.extractVideoUrlsInternal(url) ?: emptyList()

        suspend fun discoverSearchAPIEndpoints(baseUrl: String, query: String): List<String> = emptyList()

        suspend fun searchViaHeadlessForm(baseUrl: String, query: String): String? = null
    }

    private suspend fun fetchPageContentWithShadowAndAdSkipInternal(
        url: String,
        waitSelector: String?,
        timeout: Int
    ): String? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String?>()
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = EngineUtils.DEFAULT_USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    val script = """
                        (function() {
                            const adSelectors = ['.ad-close', '.close-button', '.skip-ad', '.fc-close'];
                            adSelectors.forEach(s => document.querySelectorAll(s).forEach(el => { try { el.click(); } catch(e) {} }));
                            
                            function getDeepHtml(root = document) {
                                let html = root.innerHTML || "";
                                root.querySelectorAll('*').forEach(el => {
                                    if (el.shadowRoot) html += "\n<!-- Shadow -->\n" + el.shadowRoot.innerHTML + getDeepHtml(el.shadowRoot);
                                });
                                return html;
                            }
                            return getDeepHtml();
                        })();
                    """.trimIndent()
                    
                    mainHandler.postDelayed({
                        view?.evaluateJavascript(script) { result ->
                            // Unescape JSON string returned by evaluateJavascript
                            deferred.complete(result?.trim('"')?.replace("\\u003C", "<")?.replace("\\\"", "\""))
                            view.destroy()
                        }
                    }, 2000) // Allow 2s for JS rendering
                }
            }
        }
        webView.loadUrl(url)
        mainHandler.postDelayed({ if (deferred.isActive) deferred.complete(null) }, timeout.toLong())
        deferred.await()
    }

    private suspend fun extractVideoUrlsInternal(url: String): List<String> {
        val content = fetchPageContentWithShadowAndAdSkipInternal(url, null, 20000) ?: ""
        val videoRegex = Regex("""https?://[^"'\s]+\.(?:mp4|m3u8|webm|mpd)[^"'\s]*""", RegexOption.IGNORE_CASE)
        return videoRegex.findAll(content).map { it.value }.toList().distinct()
    }

    class Page(private val context: Context) {
        private var webView: WebView? = null
        private val loadDeferred = CompletableDeferred<Unit>()

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun navigate(url: String) = withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loadDeferred.complete(Unit)
                    }
                }
                loadUrl(url)
            }
        }

        suspend fun waitForLoadState() = loadDeferred.await()

        suspend fun evaluate(script: String): Any? = withContext(Dispatchers.Main) {
            val evalDeferred = CompletableDeferred<Any?>()
            webView?.evaluateJavascript(script) { evalDeferred.complete(it) }
            evalDeferred.await()
        }

        suspend fun close() = withContext(Dispatchers.Main) {
            webView?.destroy()
            webView = null
        }
    }
}