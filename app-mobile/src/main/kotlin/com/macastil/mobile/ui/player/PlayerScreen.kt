package com.macastil.mobile.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.mobile.JkGraph
import com.macastil.mobile.R
import com.macastil.tv.model.VideoServer
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reproductor móvil: WebView sobre el embed del servidor + barra de transporte
 * propia (play/pausa, ±10 s, posición arrastrable y detener).
 *
 * Hasta 1.5.0 solo había una barra de progreso de **lectura**: no existía
 * ningún control táctil, así que "fallaban la parada y el adelantamiento".
 * Los controles hablan con [PlayerController], que decide entre el
 * reproductor nativo y el `<video>` del WebView.
 *
 * La pantalla se pone en apaisado mientras dura el player (16:9 útil sin
 * rotar el teléfono) y vuelve a retrato al salir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    slug: String,
    episodeNumber: Int,
    episodeUrl: String,
    episodeTitle: String,
    onExit: () -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(
        key = "player-$episodeUrl",
        factory = PlayerViewModel.factory(JkGraph.repository, episodeUrl),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = remember { PlayerController() }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var ended by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var videoReady by remember { mutableStateOf(false) }

    // Posición mientras se arrastra el slider: no se pisa con `timeupdate`
    // hasta soltar, o la barra "pelea" con el reproductor.
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    // ------------------------------------------------ orientación apaisada
    val activity = LocalContext.current.findActivity()
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // OJO con la orientación: NO se puede leer de `LocalConfiguration` ni de
    // `LocalWindowInfo`. El manifest declara
    // `configChanges="orientation|screenSize|..."`, así que la activity no se
    // recrea al girar, y el Context que devuelve `AppLocale.wrap`
    // (`createConfigurationContext`) lleva una Configuration copiada al
    // arranque: en horizontal seguía diciendo "portrait" (orientation=1) y se
    // aplicaba la rama de 16:9 — 1207 px de alto en una pantalla de 1080 — que
    // desbordaba la vista y empujaba la barra de transporte fuera de la
    // pantalla. El tamaño medido del layout sí es fiable.
    var landscape by remember { mutableStateOf(false) }

    // Guarda progreso cada 5 s de avance real y al terminar.
    LaunchedEffect(episodeUrl, positionMs, durationMs, ended) {
        if (durationMs > 0L) {
            JkGraph.library.saveProgress(
                slug = slug,
                episode = episodeNumber,
                positionMs = if (ended) durationMs else positionMs,
                durationMs = durationMs,
                completed = ended || positionMs >= durationMs - 5000L,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (durationMs > 0L) {
                JkGraph.appScope.launch {
                    JkGraph.library.saveProgress(
                        slug = slug,
                        episode = episodeNumber,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        completed = ended || positionMs >= durationMs - 5000L,
                    )
                }
            }
        }
    }

    val ready = state as? PlayerUiState.Ready
    val title = stringResource(
        R.string.player_episode_title,
        episodeNumber,
        episodeTitle.ifBlank { slug },
    )

    // Vigilante: si en 20 s no hay `<video>`, el servidor no cargó (Desu
    // devuelve 500 en algunos episodios). En la TV se abre el overlay de
    // servidores; en móvil se avisa y se ofrece cambiar directamente.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(ready?.selected?.url) {
        stalled = false
        videoReady = false
        ready?.selected?.url ?: return@LaunchedEffect
        delay(20_000)
        if (!videoReady) stalled = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { landscape = it.width > it.height },
    ) {
        Scaffold(
            topBar = {
                // En apaisado la barra del sistema se superpone al vídeo: el
                // alto es oro y el título no aporta (ya se ve en el chip del
                // episodio). Portrait mantiene la barra normal.
                if (!landscape) {
                    TopAppBar(
                        title = { Text(text = title, maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onExit) {
                                Icon(
                                    Icons.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_volver),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.load(force = true) }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.player_reload_cd),
                                )
                            }
                        },
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val videoModifier = if (landscape) {
                    // El embed (DPlayer) ajusta el vídeo a la ALTURA del
                    // WebView, así que si la caja ocupa todo el ancho (3.5:1 en
                    // un teléfono apaisado) el vídeo queda con dos franjas
                    // negras enormes a los lados. Se limita la caja a 16:9 y se
                    // centra: el vídeo llena el área y las franjas desaparecen.
                    Modifier
                        .weight(1f)
                        .aspectRatio(16f / 9f)
                        .align(Alignment.CenterHorizontally)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                }

                Box(modifier = videoModifier.background(Color.Black)) {
                    when (val current = state) {
                        is PlayerUiState.Loading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color.White)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.player_loading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                    )
                                }
                            }
                        }

                        is PlayerUiState.Error -> {
                            Box(
                                Modifier.fillMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = stringResource(current.messageRes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(onClick = { viewModel.load(force = true) }) {
                                        Text(stringResource(R.string.common_retry))
                                    }
                                }
                            }
                        }

                        is PlayerUiState.Ready -> {
                            WebPlayer(
                                url = current.selected.url,
                                controller = controller,
                                onProgress = { position, duration ->
                                    positionMs = position
                                    durationMs = duration
                                },
                                onEnded = { ended = true },
                                onReady = { videoReady = true },
                                onPlayingChanged = { playing ->
                                    isPlaying = playing
                                    if (playing) {
                                        videoReady = true
                                        stalled = false
                                    }
                                },
                            )
                        }
                    }

                    if (landscape) {
                        // Barra mínima sobre el vídeo (volver / título / recargar).
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .fillMaxWidth()
                                .background(Color(0x99000000))
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onExit) {
                                Icon(
                                    Icons.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_volver),
                                    tint = Color.White,
                                )
                            }
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.load(force = true) }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.player_reload_cd),
                                    tint = Color.White,
                                )
                            }
                        }
                    }

                    if (stalled && ready != null) {
                        StalledNotice(
                            serverName = ready.selected.name,
                            onTryNext = {
                                viewModel.selectNext()
                                stalled = false
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                TransportBar(
                    positionMs = scrubbing?.toLong() ?: positionMs,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    scrubbing = scrubbing,
                    onScrub = { scrubbing = it },
                    onScrubFinished = {
                        scrubbing?.let { controller.seekTo(it.toLong()) }
                        scrubbing = null
                    },
                    onPlayPause = { controller.togglePlayPause() },
                    onRewind = { controller.seekBy(-10) },
                    onForward = { controller.seekBy(10) },
                    onStop = {
                        controller.pause()
                        JkGraph.appScope.launch {
                            if (durationMs > 0L) {
                                JkGraph.library.saveProgress(
                                    slug = slug,
                                    episode = episodeNumber,
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                    completed = ended || positionMs >= durationMs - 5000L,
                                )
                            }
                        }
                        onExit()
                    },
                )

                // La etiqueta "Servidor" sobra en apaisado: el alto es escaso y
                // los chips se identifican solos.
                if (!landscape) {
                    Text(
                        text = stringResource(R.string.player_servers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (ready != null) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = if (landscape) {
                            Modifier.padding(bottom = 8.dp)
                        } else {
                            Modifier
                        },
                    ) {
                        items(ready.servers, key = { it.name + it.url }) { server ->
                            ServerChip(
                                server = server,
                                selected = server.url == ready.selected.url,
                                onClick = {
                                    viewModel.select(server)
                                    stalled = false
                                },
                            )
                        }
                    }
                }

                if (!landscape) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.player_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = JkTextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/** Barra de transporte: posición arrastrable + play/pausa, ±10 s y detener. */
@Composable
private fun TransportBar(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    scrubbing: Float?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onStop: () -> Unit,
) {
    val max = durationMs.coerceAtLeast(1L).toFloat()

    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Slider(
            value = (scrubbing ?: positionMs.toFloat()).coerceIn(0f, max),
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            valueRange = 0f..max,
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRewind) { Text("−10 s") }
            FilledIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
            ) {
                if (isPlaying) {
                    PauseGlyph(tint = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.player_play_cd),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            TextButton(onClick = onForward) { Text("+10 s") }
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.player_stop_cd),
                    tint = JkTextSecondary,
                )
            }
        }
    }
}

/** Dos barras: `Icons.Filled.Pause` no existe en material-icons-core. */
@Composable
private fun PauseGlyph(tint: Color) {
    Row(
        modifier = Modifier.size(20.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(2) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

@Composable
private fun StalledNotice(
    serverName: String,
    onTryNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC000000))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_stalled, serverName),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onTryNext) {
            Text(stringResource(R.string.player_try_next))
        }
    }
}

@Composable
private fun ServerChip(
    server: VideoServer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) JkOrange else Color(0xFF2A2A2A)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = server.name,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = server.langLabel,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Activity del Context (para pedir la orientación apaisada en el player). */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
