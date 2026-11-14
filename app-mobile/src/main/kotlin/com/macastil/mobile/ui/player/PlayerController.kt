package com.macastil.mobile.ui.player

import android.webkit.WebView
import androidx.media3.exoplayer.ExoPlayer

/**
 * Controlador único de reproducción, con dos backends:
 *
 *  - **Nativo** (`ExoPlayer` sobre un `m3u8`): es el que se usa con los
 *    players propios del sitio (Desu/Magi) cuando el embed expone el stream.
 *    En la TV el WebView decodifica el HLS pero no compone la superficie
 *    (audio y tiempo avanzan, vídeo negro), así que la superficie nativa es
 *    la única que pinta de forma fiable.
 *  - **WebView**: fallback para los embeds de terceros (Streamwish, VOE…),
 *    que no exponen MP4 ni m3u8 y solo llevan un reproductor JS ofuscado.
 *
 * `PlayerScreen` no distingue: llama siempre a esta misma clase.
 */
class PlayerController {

    internal var webView: WebView? = null

    /** Asignado por `NativePlayer` al crear el reproductor; anulado al soltarlo. */
    internal var player: ExoPlayer? = null

    fun seekBy(seconds: Int) {
        val native = player
        if (native != null) {
            native.seekTo((native.currentPosition + seconds * 1000L).coerceAtLeast(0L))
            return
        }
        evaluate(
            "(()=>{const v=document.querySelector('video');" +
                "if(v){v.currentTime=Math.max(0,(v.currentTime||0)+($seconds));}})()",
        )
    }

    /** Salta a una posición absoluta (usado para reanudar donde quedó). */
    fun seekTo(positionMs: Long) {
        val native = player
        if (native != null) {
            native.seekTo(positionMs)
            return
        }
        evaluate(
            "(()=>{const v=document.querySelector('video');" +
                "if(v){v.currentTime=$positionMs/1000;}})()",
        )
    }

    fun togglePlayPause() {
        val native = player
        if (native != null) {
            if (native.isPlaying) native.pause() else native.play()
            return
        }
        evaluate(
            "(()=>{const v=document.querySelector('video');" +
                "if(v){v.paused?v.play():v.pause();}})()",
        )
    }

    fun reload() {
        webView?.reload()
    }

    private fun evaluate(script: String) {
        runCatching { webView?.evaluateJavascript(script, null) }
    }
}
