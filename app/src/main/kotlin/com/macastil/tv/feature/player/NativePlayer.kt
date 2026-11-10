package com.macastil.tv.feature.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import com.macastil.tv.model.Jk

/**
 * Reproductor nativo (ExoPlayer) sobre un `m3u8` directo.
 *
 * Existe porque el WebView de la TV **sí decodifica** el HLS del player
 * propio (1920x1080, readyState=4, audio y currentTime avanzando) pero su
 * superficie no se compone: la pantalla queda negra. Medido en el dispositivo
 * real comparando `screencap` (el overlay de Compose se captura, el área del
 * vídeo sale negra). La superficie nativa de `PlayerView` (SurfaceView) sí se
 * compone en cualquier dispositivo.
 *
 * Mismas callbacks que `WebPlayer`, para que `PlayerScreen` no distinga.
 */
@Composable
fun NativePlayer(
    url: String,
    referer: String?,
    controller: PlayerController,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onEnded: () -> Unit,
    onReady: () -> Unit = {},
    onError: () -> Unit = {},
    onPlayingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val player = remember(url) { buildPlayer(context, url, referer) }

    // El controller delega en ExoPlayer mientras este composable esté vivo.
    DisposableEffect(player) {
        controller.player = player
        onDispose {
            controller.player = null
            player.release()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> onReady()
                    Player.STATE_ENDED -> onEnded()
                }
            }

            // El estado real de reproducción manda sobre el optimista del
            // overlay: si el usuario pausa con la tecla del mando (o el
            // sistema lo hace), el botón debe reflejarlo.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlayingChanged(isPlaying)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("JkPlayer", "ExoPlayer error: ${error.errorCodeName}", error)
                onError()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Progreso para la barra del overlay y para guardar "continuar viendo".
    LaunchedEffect(player) {
        while (true) {
            delay(1_000)
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            onProgress(player.currentPosition, duration)
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                // Los controles son los de Compose (overlay del PlayerScreen):
                // este PlayerView solo aporta la superficie de vídeo.
                useController = false
                isFocusable = false
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view -> view.player = player },
        onRelease = { view -> view.player = null },
    )
}

private fun buildPlayer(context: Context, url: String, referer: String?): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(Jk.USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)

    val dataSourceFactory = DataSource.Factory {
        httpFactory.createDataSource().apply {
            // El CDN del stream valida el origen: sin Referer de jkanime
            // responde 403 y ExoPlayer no arranca.
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
        }
    }

    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .build()
        .apply {
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
}
