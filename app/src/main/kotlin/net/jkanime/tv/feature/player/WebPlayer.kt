package net.jkanime.tv.feature.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import net.jkanime.tv.model.Jk

/**
 * Reproductor MVP: WebView sobre el embed del servidor.
 *
 * ¿Por qué WebView y no ExoPlayer? Los servidores de jkanime (Streamwish, VOE,
 * Filemoon, Vidhide...) no exponen MP4 ni m3u8: entregan un reproductor JS con
 * su propio ofuscado. Un extractor por host se rompe cada semana; el WebView
 * reproduce el 100% desde el día uno. El puente JS nos da progreso y "ended"
 * para la fase de favoritos / continuar viendo.
 */
class WebPlayerController {

    internal var webView: WebView? = null

    fun seekBy(seconds: Int) = evaluate(
        "(()=>{const v=document.querySelector('video');" +
            "if(v){v.currentTime=Math.max(0,(v.currentTime||0)+($seconds));}})()",
    )

    fun togglePlayPause() = evaluate(
        "(()=>{const v=document.querySelector('video');" +
            "if(v){v.paused?v.play():v.pause();}})()",
    )

    fun reload() {
        webView?.reload()
    }

    private fun evaluate(script: String) {
        runCatching { webView?.evaluateJavascript(script, null) }
    }
}

private val AD_HOSTS = listOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "popads.net",
    "adsterra",
    "propellerads",
    "onclickalgo",
    "popcash.net",
    "juicyads",
    "exoclick",
    "agl003.com",
    "histats.com",
    "adnium",
    "realsrv",
)

private const val HOOK_SCRIPT = """
(function(){
  if (window.__jkHooked) return;
  window.__jkHooked = true;
  window.open = function(){ return null; };
  setInterval(function(){
    var v = document.querySelector('video');
    if (!v) return;
    if (!v.__jkBound) {
      v.__jkBound = true;
      try { v.play(); } catch (e) {}
      v.addEventListener('timeupdate', function(){
        JkBridge.onProgress(v.currentTime * 1000, (v.duration || 0) * 1000);
      });
      v.addEventListener('ended', function(){ JkBridge.onEnded(); });
      JkBridge.onReady();
    }
  }, 1200);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayer(
    url: String,
    controller: WebPlayerController,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onEnded: () -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString = Jk.USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                addJavascriptInterface(
                    Bridge(onProgress, onEnded, onReady, mainHandler),
                    "JkBridge",
                )

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) = Unit
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val host = request?.url?.host?.lowercase().orEmpty()
                        return if (AD_HOSTS.any { host.contains(it) }) {
                            android.webkit.WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0)),
                            )
                        } else {
                            null
                        }
                    }
                }

                controller.webView = this
                loadUrl(url)
            }
        },
        update = { webView ->
            controller.webView = webView
            if (webView.url != url) {
                webView.loadUrl(url)
            }
        },
        onRelease = { webView ->
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
            controller.webView = null
        },
    )
}

private class Bridge(
    private val onProgress: (Long, Long) -> Unit,
    private val onEnded: () -> Unit,
    private val onReady: () -> Unit,
    private val handler: Handler,
) {
    @JavascriptInterface
    fun onProgress(positionMs: Double, durationMs: Double) {
        handler.post { onProgress(positionMs.toLong(), durationMs.toLong()) }
    }

    @JavascriptInterface
    fun onEnded() {
        handler.post { onEnded() }
    }

    @JavascriptInterface
    fun onReady() {
        handler.post { onReady() }
    }
}
