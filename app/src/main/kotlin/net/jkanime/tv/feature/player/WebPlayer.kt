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

    /** Salta a una posición absoluta (usado para reanudar donde quedó). */
    fun seekTo(positionMs: Long) = evaluate(
        "(()=>{const v=document.querySelector('video');" +
            "if(v){v.currentTime=$positionMs/1000;}})()",
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
    // Dominios concretos observados sirviendo el banner con imagen+cierre
    // dentro de un iframe sandboxed; estos dominios "doorway" rotan, de
    // ahí el patrón de ruta/query adicional en AD_URL_PATTERNS y el barrido
    // por hit-testing en HOOK_SCRIPT como defensa principal.
    "janitorprecisiontrio.com",
    "subduepaler.cyou",
)

// Firma de URL (ruta + query) de la llamada de red que la red de anuncios
// usa para inyectar el banner dentro del iframe vecino, independiente del
// dominio "doorway" que esté rotando esa semana (ej. janitorprecisiontrio.com
// hoy, otro dominio random.com mañana). Bloquear esta petición evita que el
// anuncio llegue a inyectarse, en vez de tener que barrerlo del DOM después
// (que falla cuando el iframe es same-origin opaco y contentDocument no es
// accesible).
private val AD_URL_PATTERNS = listOf(
    "/sn/pr/",
    "zoneid=",
)

// Los "captcha" tipo "Confirm you're not a robot" y los banners con imagen
// que se ven en estos embeds NO son del host del video ni una navegación
// nueva: son DOM inyectado por un script de publicidad por encima del
// <video> (confirmado con dumpsys: sigue siendo la misma ventana de WebView,
// no un popup real). Bloquear sub-recursos (shouldInterceptRequest) o
// window.open no alcanza porque no hay navegación ni recurso nuevo.
// El nodo visible del anuncio a veces es un <iframe> con position:static
// (su contenido llega vía about:srcdoc), así que el filtro no mira solo el
// estilo del propio elemento sino el de toda su cadena de ancestros, y
// elimina además los iframes-tracker de 0-2px típicos de redes de
// publicidad. Se barre el árbol periódicamente y con MutationObserver.
private const val HOOK_SCRIPT = """
(function(){
  if (window.__jkHooked) return;
  window.__jkHooked = true;
  window.open = function(){ return null; };

  function isAncestorOfVideo(el, video) {
    var n = video;
    while (n) { if (n === el) return true; n = n.parentElement; }
    return false;
  }

  function hasFloatingAncestor(el) {
    var n = el;
    while (n && n.nodeType === 1) {
      var s;
      try { s = window.getComputedStyle(n); } catch (e) { break; }
      if (s && (s.position === 'fixed' || s.position === 'absolute')) return true;
      if (n === document.body) break;
      // Al llegar a la raíz de un shadow root, seguir subiendo por el
      // elemento "host" que lo contiene (si no, parentElement es null y
      // se cortaría la búsqueda justo dentro del shadow DOM del anuncio).
      if (!n.parentElement && n.getRootNode && n.getRootNode() !== document) {
        var root = n.getRootNode();
        n = root.host || null;
        continue;
      }
      n = n.parentElement;
    }
    return false;
  }

  function sweepOverlaysInDoc(doc, isTopDoc) {
    var video = isTopDoc ? doc.querySelector('video') : null;
    var vw = window.innerWidth, vh = window.innerHeight;
    // doc puede ser un Document (tiene .body) o un ShadowRoot (no tiene
    // .body, pero sí querySelectorAll directamente sobre sí mismo).
    var root = doc.body || doc;
    var nodes = root.querySelectorAll ? root.querySelectorAll('*') : [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.tagName === 'VIDEO' || el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue;
      if (video && isAncestorOfVideo(el, video)) continue;
      var id = (el.id || '') + ' ' + (el.className || '');
      // Nunca se elimina el contenedor directo del propio player (ids/clases
      // típicas de jwplayer/plyr/vjs); solo overlays ajenos a él.
      if (/jw-|plyr|video-js|vjs-|player/i.test(id) && !/captcha|robot|ads|popup|overlay/i.test(id)) continue;

      var rect = el.getBoundingClientRect();

      // Algunos anuncios se renderizan dentro de un shadow root abierto
      // (el.shadowRoot) adjunto a un <div> normal; querySelectorAll('*') NO
      // atraviesa esa frontera, así que hay que bajar explícitamente.
      if (el.shadowRoot) {
        try { sweepOverlaysInDoc(el.shadowRoot, false); } catch (e) {}
      }

      if (el.tagName === 'IFRAME') {
        // Trackers/pixeles invisibles de publicidad (iframes de 0-2px que
        // las redes de anuncios usan para cargar scripts sin UI visible).
        if (rect.width <= 2 || rect.height <= 2) {
          try { el.remove(); JkBridge.onAdRemoved('tracker-iframe'); } catch (e) {}
          continue;
        }
        // Muchos anuncios (incluido el falso captcha) se renderizan dentro
        // de un <iframe> same-origin con sandbox="allow-same-origin ...";
        // sin bajar a su contentDocument nunca veríamos el <img>/<div> real
        // que hay que eliminar, porque vive en otro Document.
        try {
          var innerDoc = el.contentDocument;
          if (innerDoc) sweepOverlaysInDoc(innerDoc, false);
        } catch (e) { /* cross-origin: no accesible, se ignora */ }
      }

      // El overlay publicitario (incluido el "captcha" falso y los anuncios
      // con imagen+cierre) casi siempre cuelga de un ancestro con
      // position:fixed/absolute aunque el propio nodo (p.ej. un <iframe>
      // con contenido vía srcdoc) tenga position:static. Por eso se mira
      // toda la cadena de ancestros, no solo el estilo del propio elemento.
      if (!hasFloatingAncestor(el)) continue;

      var style;
      try { style = window.getComputedStyle(el); } catch (e) { continue; }
      if (!style) continue;
      var zIndex = parseInt(style.zIndex, 10) || 0;
      // Antes exigíamos ancho Y alto >=30% (rectángulo "grande" en ambos
      // ejes), pero el anuncio de imagen+cierre es alto y angosto (~24%
      // ancho x 74% alto): una sola dimensión grande ya es intrusiva en
      // pantalla, así que basta con OR, reforzado por un área mínima para
      // no confundir con barras finas legítimas (ej. gradientes de UI).
      var area = rect.width * rect.height;
      var coversScreen = (rect.width >= vw * 0.25 || rect.height >= vh * 0.25) &&
        area >= (vw * vh * 0.08);
      var looksLikeOverlay = zIndex >= 999 || coversScreen;
      if (!looksLikeOverlay) continue;

      try {
        el.remove();
        JkBridge.onAdRemoved(id || el.tagName);
      } catch (e) {}
    }
  }

  function sweepOverlays() {
    sweepOverlaysInDoc(document, true);
  }

  var observer = new MutationObserver(function(){ sweepOverlays(); });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  setInterval(sweepOverlays, 1000);

  // Defensa principal: algunos anuncios se pintan dentro de un iframe/
  // shadow root totalmente inaccesible por recorrido de árbol (ni
  // querySelectorAll ni contentDocument ni shadowRoot los exponen: se
  // comprobó con diagnóstico exhaustivo que nunca aparecen ahí pese a
  // ocupar píxeles reales en pantalla). La única forma fiable de
  // encontrarlos es el hit-testing: preguntar a Chromium qué elemento
  // pinta en cada punto de una grilla sobre el viewport, igual que un
  // toque/click real (document.elementFromPoint). "pierceElementAt" repite
  // esa consulta dentro de shadow roots abiertos y de iframes same-origin
  // para llegar al nodo real; si ese nodo real no coincide con el que se
  // ve desde el documento superior (pierced == true), el contenedor de
  // nivel superior (topEl) es el iframe/overlay inyectado por la red de
  // anuncios y se elimina por completo.
  function pierceElementAt(x, y) {
    var el = document.elementFromPoint(x, y);
    var guard = 0;
    while (el && guard++ < 8) {
      if (el.shadowRoot && el.shadowRoot.elementFromPoint) {
        var inner;
        try { inner = el.shadowRoot.elementFromPoint(x, y); } catch (e) { inner = null; }
        if (inner && inner !== el) { el = inner; continue; }
      }
      if (el.tagName === 'IFRAME') {
        var r = el.getBoundingClientRect();
        try {
          var innerDoc = el.contentDocument;
          if (innerDoc && innerDoc.elementFromPoint) {
            var child = innerDoc.elementFromPoint(x - r.left, y - r.top);
            if (child && child !== el) { el = child; continue; }
          }
        } catch (e) { /* cross-origin */ }
      }
      break;
    }
    return el;
  }

  function hitTestSweep() {
    var vw = window.innerWidth, vh = window.innerHeight;
    var video = document.querySelector('video');
    var cols = 14, rows = 10;
    var seen = {};
    var removedTopEls = [];
    for (var r = 0; r < rows; r++) {
      for (var c = 0; c < cols; c++) {
        var x = Math.round((c + 0.5) * vw / cols);
        var y = Math.round((r + 0.5) * vh / rows);
        var topEl;
        try { topEl = document.elementFromPoint(x, y); } catch (e) { continue; }
        if (!topEl || topEl === document.documentElement || topEl === document.body) continue;
        if (topEl.tagName === 'VIDEO' || topEl.tagName === 'HTML' || topEl.tagName === 'BODY') continue;
        if (video && isAncestorOfVideo(topEl, video)) continue;
        var el;
        try { el = pierceElementAt(x, y); } catch (e) { el = topEl; }
        var id = (el.id || '') + ' ' + (el.className || '');
        if (/jw-|plyr|video-js|vjs-/i.test(id) && !/captcha|robot|ads|popup|overlay|close/i.test(id)) continue;
        var key = el.tagName + '#' + id;
        if (seen[key]) continue;
        seen[key] = true;
        var pierced = el !== topEl;
        if (pierced && removedTopEls.indexOf(topEl) === -1) {
          try {
            topEl.remove();
            removedTopEls.push(topEl);
            JkBridge.onAdRemoved('hit-pierced:' + key);
          } catch (e) {}
        }
      }
    }
  }
  setInterval(hitTestSweep, 1200);

  setInterval(function(){
    var v = document.querySelector('video');
    if (!v) return;
   if (!v.__jkBound) {
      v.__jkBound = true;
      // Algunos WebView de Android TV reproducen el audio pero dejan la
      // superficie de vídeo invisible si el elemento conserva estilos del
      // embed. Forzamos una superficie visible y acelerada.
      v.style.visibility = 'visible';
      v.style.display = 'block';
      v.style.opacity = '1';
      v.style.transform = 'translateZ(0)';
      v.style.width = '100%';
      v.style.height = '100%';
      v.style.objectFit = 'contain';
      v.setAttribute('playsinline', 'true');
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
            NonFocusableWebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // Dejamos que la ventana acelerada gestione la capa de vídeo.
                // Forzar LAYER_TYPE_HARDWARE en WebView puede crear una capa
                // separada que reproduce audio/tiempo pero no compone los
                // frames de vídeo en algunos Android TV.
                setLayerType(android.view.View.LAYER_TYPE_NONE, null)

                // El WebView pide el foco al cargar y se queda con TODO el
                // D-pad: Chromium navega su propio reproductor y el overlay de
                // Compose ("Usar otro servidor", "Salir") queda inalcanzable.
                // Como al WebView solo le hablamos por JS (play, seek, tiempo),
                // no necesita foco. `isFocusable = false` no basta: Chromium
                // llama requestFocus() por dentro, así que la subclase lo
                // rechaza explícitamente.
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
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
                        android.util.Log.i("JkPlayer", "page start: $url")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        android.util.Log.i("JkPlayer", "page finish: $url")
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): android.webkit.WebResourceResponse? {
                        val url = request?.url
                        val host = url?.host?.lowercase().orEmpty()
                        val fullUrl = url?.toString()?.lowercase().orEmpty()
                        val blocked = AD_HOSTS.any { host.contains(it) } ||
                            AD_URL_PATTERNS.any { fullUrl.contains(it) }
                        return if (blocked) {
                            android.util.Log.d("JkAdBlock", "recurso bloqueado: $host -> $fullUrl")
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

/**
 * WebView que nunca acepta el foco.
 *
 * Chromium pide el foco al terminar de cargar la página y, con él, se traga
 * todas las teclas del D-pad (navegación de sus propios controles). En TV el
 * control lo lleva el overlay de Compose, así que el WebView solo tiene que
 * reproducir: rechazar `requestFocus()` es la única forma fiable de evitarlo.
 */
private class NonFocusableWebView(context: android.content.Context) : WebView(context) {
    // View.requestFocus() delega en esta sobrecarga, así que bloquearla cubre
    // tanto la llamada sin argumentos como las de navegación por D-pad.
    override fun requestFocus(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean = false
}

/**
 * Puente JS -> Kotlin.
 *
 * OJO con los nombres: los callbacks se llaman `...Event` a propósito. Si la
 * propiedad se llamara igual que el método `@JavascriptInterface` (por ejemplo
 * `onReady` y `fun onReady()`), dentro del método la llamada `onReady()`
 * resolvería al propio método y no a la lambda: recursión infinita en el hilo
 * principal. Pasó, y el síntoma era que `playerReady` nunca se ponía a true y
 * el reproductor avisaba "no cargó" aunque el vídeo estuviera reproduciendo.
 */
private class Bridge(
    private val onProgressEvent: (Long, Long) -> Unit,
    private val onEndedEvent: () -> Unit,
    private val onReadyEvent: () -> Unit,
    private val handler: Handler,
) {
    @JavascriptInterface
    fun onProgress(positionMs: Double, durationMs: Double) {
        handler.post { onProgressEvent(positionMs.toLong(), durationMs.toLong()) }
    }

    @JavascriptInterface
    fun onEnded() {
        handler.post { onEndedEvent() }
    }

    @JavascriptInterface
    fun onReady() {
        handler.post { onReadyEvent() }
    }

    @JavascriptInterface
    fun onAdRemoved(descriptor: String?) {
        // Solo logging: confirma en logcat qué overlays está barriendo el
        // ad-killer de HOOK_SCRIPT, sin tocar el hilo de UI de Compose.
        android.util.Log.d("JkAdBlock", "overlay eliminado: $descriptor")
    }
}
