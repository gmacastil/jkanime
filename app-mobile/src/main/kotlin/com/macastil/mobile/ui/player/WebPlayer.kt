package com.macastil.mobile.ui.player

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

/**
 * Reproductor MVP: WebView sobre el embed del servidor.
 *
 * ¿Por qué sigue existiendo? Los embeds de terceros (Streamwish, VOE,
 * Filemoon, Vidhide…) no exponen MP4 ni m3u8: entregan un reproductor JS con
 * su propio ofuscado. Un extractor por host se rompe cada semana; el WebView
 * reproduce el 100% desde el día uno. El puente JS nos da progreso y "ended"
 * para la fase de favoritos / continuar viendo.
 *
 * Los players propios del sitio (Desu/Magi) sí exponen el `m3u8` y van por el
 * reproductor nativo (`NativePlayer` + `PlayerController`); el WebView queda
 * aquí solo como fallback de esos mismos servidores si la resolución del
 * stream falla.
 */

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

  // --- Viewport colapsado: la causa real de la "pantalla negra" ---
  // El embed (DPlayer) maqueta con `html, body { width:100%; height:100% }`.
  // En el WebView del móvil ese `height:100%` -y también `100vh`- se resuelven
  // a **0 px** aunque la vista sí tiene tamaño (medido por CDP: `innerHeight`
  // = 235 y `documentElement.clientHeight` = 235, pero un `div` con
  // `height:100vh` mide 0). Con el documento a 0 de alto y `overflow:hidden`
  // no se pinta NADA: pantalla negra, mientras el <video> decodifica, el
  // tiempo avanza y el audio suena (por eso "se escucha pero no se ve").
  // Si el documento resolvió a 0, se le da la altura real de la vista en px.
  // Solo se toca cuando está a 0: en un WebView sano (que ya resuelve el
  // `height:100%`) esto no hace nada.
  function fitDocumentHeight() {
    try {
      var de = document.documentElement, b = document.body;
      if (!de || !b) return;
      var vh = Math.max(window.innerHeight || 0, de.clientHeight || 0);
      if (vh <= 0) return;
      if (de.getBoundingClientRect().height < 1) {
        de.style.setProperty('height', vh + 'px', 'important');
      }
      if (b.getBoundingClientRect().height < 1) {
        b.style.setProperty('height', vh + 'px', 'important');
      }
    } catch (e) { /* nunca romper la reproducción por esto */ }
  }
  fitDocumentHeight();
  window.addEventListener('resize', fitDocumentHeight);
  window.addEventListener('orientationchange', fitDocumentHeight);

  // --- Controles duplicados del embed ---
  // El embed de jkplayer/Streamwish es DPlayer: pinta su propia barra
  // (`.dplayer-controller`, con play, tiempo, barra, volumen y fullscreen) y,
  // en móvil, un botón de play gigante sobre el vídeo. Como la app ya tiene
  // su barra de Compose debajo del vídeo, se veían DOS barras y el vídeo
  // parecía un mini-reproductor dentro de otro. Se oculta todo el chrome del
  // player y se fuerza el <video> a ocupar la caja completa (`object-fit:
  // contain` para respetar la relación de aspecto dentro de la caja).
  function injectPlayerStyle(doc) {
    try {
      if (!doc || !doc.createElement || doc.getElementById('jk-player-style')) return;
      var st = doc.createElement('style');
      st.id = 'jk-player-style';
      st.textContent = [
        '.dplayer-controller,.dplayer-controller-mask,.dplayer-mobile-play,',
        '.dplayer-bezel,.dplayer-notice,.dplayer-menu,.dplayer-menu-show,',
        '.dplayer-icons,.dplayer-bar-wrap,.dplayer-danmaku,.dplayer-subtitle,',
        '.dplayer-info-panel,.dplayer-setting,.dplayer-comment{display:none!important}',
        '.dplayer,.dplayer-video-wrap,.dplayer-video{background:#000!important}',
        '.dplayer,.dplayer-video-wrap{width:100%!important;height:100%!important}',
        '.dplayer-video,video{width:100%!important;height:100%!important;',
        'object-fit:contain!important;display:block!important}'
      ].join('');
      var head = doc.head || doc.documentElement;
      if (head) head.appendChild(st);
    } catch (e) { /* si falla, el player sigue usable con sus controles */ }
  }

  // El player puede estar en el documento superior o en un iframe same-origin.
  function stylePlayer() {
    injectPlayerStyle(document);
    var frames = [];
    try { frames = document.querySelectorAll('iframe'); } catch (e) { frames = []; }
    for (var i = 0; i < frames.length; i++) {
      try {
        var inner = frames[i].contentDocument;
        if (inner) injectPlayerStyle(inner);
      } catch (e) { /* cross-origin */ }
    }
  }
  stylePlayer();

  // El <video> puede vivir en el documento superior (Desu/jkplayer) o dentro
  // de un iframe same-origin (varios hosts de terceros). Antes solo se miraba
  // el documento superior, así que con el player en un iframe no llegaban
  // `onProgress` ni `onReady` y la app se quedaba "cargando" con el vídeo
  // reproduciéndose dentro. Se busca con profundidad acotada.
  function findVideo(doc, depth) {
    if (!doc) return null;
    var v = null;
    try { v = doc.querySelector ? doc.querySelector('video') : null; } catch (e) { v = null; }
    if (v) return v;
    if (depth <= 0) return null;
    var frames = [];
    try { frames = doc.querySelectorAll ? doc.querySelectorAll('iframe') : []; } catch (e) { frames = []; }
    for (var i = 0; i < frames.length; i++) {
      try {
        var inner = frames[i].contentDocument;
        var found = inner ? findVideo(inner, depth - 1) : null;
        if (found) return found;
      } catch (e) { /* cross-origin */ }
    }
    return null;
  }

  // ¿El elemento es, contiene o muestra un <video>? Se usa para NO borrar
  // nunca el reproductor: antes, con el vídeo dentro de un iframe, el barrido
  // no lo reconocía como "ancestro del vídeo" y eliminaba el iframe del
  // player entero (la reproducción "fallaba" al cabo de un segundo).
  function containsVideo(el) {
    if (!el) return false;
    if (el.tagName === 'VIDEO') return true;
    try { if (el.querySelector && el.querySelector('video')) return true; } catch (e) {}
    if (el.tagName === 'IFRAME') {
      try {
        var d = el.contentDocument;
        if (d && d.querySelector && d.querySelector('video')) return true;
      } catch (e) { /* cross-origin: no se puede saber */ }
    }
    return false;
  }

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
    var video = null;
    try { video = doc.querySelector ? doc.querySelector('video') : null; } catch (e) { video = null; }
    var vw = window.innerWidth, vh = window.innerHeight;
    // doc puede ser un Document (tiene .body) o un ShadowRoot (no tiene
    // .body, pero sí querySelectorAll directamente sobre sí mismo).
    var root = doc.body || doc;
    var nodes = root.querySelectorAll ? root.querySelectorAll('*') : [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (el.tagName === 'VIDEO' || el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue;
      if (video && isAncestorOfVideo(el, video)) continue;
      if (containsVideo(el)) continue;
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

  var observer = new MutationObserver(function(){ stylePlayer(); sweepOverlays(); });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  setInterval(function(){ stylePlayer(); sweepOverlays(); }, 1000);

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
    var video = findVideo(document, 3);
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
        // Nunca se toca nada que contenga el vídeo (ni el iframe del player).
        if (containsVideo(topEl)) continue;
        var el;
        try { el = pierceElementAt(x, y); } catch (e) { el = topEl; }
        if (containsVideo(el)) continue;
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
    fitDocumentHeight();
    stylePlayer();
    var v = findVideo(document, 3);
    if (!v) return;
   if (!v.__jkBound) {
      v.__jkBound = true;
      // Forzar `transform: translateZ(0)` (o dimensiones) crea una capa de
      // composición independiente que en muchas TV no se pinta: el audio y el
      // tiempo avanzan pero la pantalla queda negra. Solo garantizamos que el
      // elemento sea visible; el layout lo maneja DPlayer, igual que en el
      // navegador.
      v.style.visibility = 'visible';
      v.style.display = 'block';
      v.style.opacity = '1';
      v.setAttribute('playsinline', 'true');
      try { v.play(); } catch (e) {}
      v.addEventListener('timeupdate', function(){
        JkBridge.onProgress(v.currentTime * 1000, (v.duration || 0) * 1000);
      });
       v.addEventListener('ended', function(){ JkBridge.onEnded(); });
       // Estado real de reproducción para el botón play/pausa de Compose.
       // Sin esto el icono habría que invertirlo a mano y quedaría al revés.
       v.addEventListener('play', function(){ JkBridge.onPlaying(true); });
       v.addEventListener('playing', function(){ JkBridge.onPlaying(true); });
       v.addEventListener('pause', function(){ JkBridge.onPlaying(false); });
       // Con los controles del embed ocultos, un toque sobre el vídeo debe
       // seguir reanudando (si el navegador rechazó el autoplay o el usuario
       // pausó desde la barra de Compose).
       v.addEventListener('click', function(){
         if (v.paused) { try { v.play(); } catch (e) {} }
       });
       JkBridge.onPlaying(!v.paused);
       JkBridge.onReady();
    }
    // Diagnóstico periódico: si readyState/videoWidth nunca avanzan, el
    // stream no se está decodificando; si avanzan pero la pantalla queda
    // negra, es un problema de composición de la superficie.
    v.__jkDiag = (v.__jkDiag || 0) + 1;
    if (v.__jkDiag % 2 === 0) {
      var r = v.getBoundingClientRect();
      var cs = window.getComputedStyle(v);
      JkBridge.onVideoInfo(
        v.videoWidth, v.videoHeight, v.readyState,
        'ct=' + v.currentTime.toFixed(1) +
        ' dur=' + (v.duration || 0).toFixed(1) +
        ' paused=' + v.paused +
        ' rect=' + Math.round(r.width) + 'x' + Math.round(r.height) +
        // Altura del documento: si es 0 con rect>0, la página no se pinta
        // (el bug de viewport de arriba). Es el dato que faltaba para
        // distinguir "no decodifica" de "decodifica pero no se ve".
        ' doc=' + Math.round(document.documentElement.getBoundingClientRect().height) +
        '/' + Math.round(document.body.getBoundingClientRect().height) +
        ' vis=' + cs.visibility + '/' + cs.display + '/' + cs.opacity +
        ' src=' + (v.currentSrc || '').slice(0, 60)
      );
    }
  }, 1200);
})();
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayer(
    url: String,
    controller: PlayerController,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onEnded: () -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayingChanged: (Boolean) -> Unit = {},
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    // UA del propio dispositivo (quitando el marcador "; wv" que delata al
    // WebView). Antes se enviaba el UA de Android TV: varios hosts
    // (Streamwish/VOE/Filemoon) detectan "TV" y devuelven un player sin
    // controles táctiles, o directamente nada, y ahí empezaba el "falla la
    // reproducción" reportado en el móvil.
    val userAgent = remember(appContext) { mobileUserAgent(appContext) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            // En debug se habilita la inspección del WebView: sin ella no hay
            // forma de saber si el fallo es de la página (frames negros) o de
            // la composición de la superficie de vídeo en Android, porque
            // `screencap` de un `<video>` sale negro siempre.
            if (com.macastil.mobile.BuildConfig.DEBUG) {
                runCatching { WebView.setWebContentsDebuggingEnabled(true) }
            }
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                // Dejamos que la ventana acelerada gestione la capa de vídeo.
                // Forzar LAYER_TYPE_HARDWARE en WebView puede crear una capa
                // separada que reproduce audio/tiempo pero no compone los
                // frames de vídeo en algunos Android TV.
                setLayerType(android.view.View.LAYER_TYPE_NONE, null)

                // En móvil el WebView NO esconde el overlay de Compose: la
                // barra de transporte vive fuera del vídeo. Se deja sin foco
                // para que la barra y el botón atrás sigan siendo dueños del
                // teclado/foco, igual que en la TV.
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
                    userAgentString = userAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    builtInZoomControls = false
                    displayZoomControls = false
                }

                addJavascriptInterface(
                    Bridge(onProgress, onEnded, onReady, onPlayingChanged, mainHandler),
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

                     override fun onReceivedError(
                         view: WebView?,
                         request: android.webkit.WebResourceRequest?,
                         error: android.webkit.WebResourceError?,
                     ) {
                         android.util.Log.e(
                             "JkPlayer",
                             "error WebView ${request?.url}: ${error?.errorCode} ${error?.description}",
                         )
                         super.onReceivedError(view, request, error)
                     }

                     override fun onReceivedHttpError(
                         view: WebView?,
                         request: WebResourceRequest?,
                         errorResponse: android.webkit.WebResourceResponse?,
                     ) {
                         android.util.Log.e(
                             "JkPlayer",
                             "HTTP ${errorResponse?.statusCode} ${request?.url}",
                         )
                         super.onReceivedHttpError(view, request, errorResponse)
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
 * UA móvil real del dispositivo, sin el marcador `"; wv"` de WebView.
 *
 * Los hosts de video deciden con el UA qué player sirven; con el UA de Android
 * TV devolvían un reproductor pensado para D-pad (sin zonas táctiles útiles) o
 * una página vacía. `getDefaultUserAgent` da el Chrome del sistema; quitar
 * `; wv` evita que se detecte el WebView.
 */
private fun mobileUserAgent(context: android.content.Context): String =
    runCatching {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")

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
    private val onPlayingEvent: (Boolean) -> Unit,
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
    fun onPlaying(playing: Boolean) {
        handler.post { onPlayingEvent(playing) }
    }

    @JavascriptInterface
    fun onVideoInfo(width: Int, height: Int, readyState: Int, source: String?) {
        android.util.Log.i(
            "JkPlayer",
            "video info: ${width}x${height}, readyState=$readyState, source=$source",
        )
    }

    @JavascriptInterface
    fun onAdRemoved(descriptor: String?) {
        // Solo logging: confirma en logcat qué overlays está barriendo el
        // ad-killer de HOOK_SCRIPT, sin tocar el hilo de UI de Compose.
        android.util.Log.d("JkAdBlock", "overlay eliminado: $descriptor")
    }
}
