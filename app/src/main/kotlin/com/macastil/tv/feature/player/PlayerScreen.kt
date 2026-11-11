package com.macastil.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.tv.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.macastil.tv.Graph
import com.macastil.tv.R
import com.macastil.tv.model.ServerKind
import com.macastil.tv.ui.component.ErrorState
import com.macastil.tv.ui.component.LoadingState
import com.macastil.tv.ui.component.TvActionButton
import com.macastil.tv.ui.component.TvIconButton
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary

private val PauseIcon: ImageVector = ImageVector.Builder(
    name = "Pause",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(
    pathData = PathParser().parsePathString("M6,19h4V5H6v14zM14,5v14h4V5h-4z").toNodes(),
    fill = SolidColor(Color.White),
).build()

@Composable
fun PlayerScreen(
    slug: String,
    episodeNumber: Int,
    episodeUrl: String,
    title: String,
    onExit: () -> Unit,
) {
    // El contexto se pide antes del ViewModel: hace falta en el factory
    // para localizar los mensajes de error (fuera de la composición).
    val context = LocalContext.current
    val viewModel: PlayerViewModel = viewModel(
        key = episodeUrl,
        factory = PlayerViewModel.factory(Graph.repository, episodeUrl, context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Salir del reproductor es solo con BACK: ya no hay botón en el overlay.
    BackHandler(onBack = onExit)

    val controller = remember { PlayerController() }
    val rootFocus = remember { FocusRequester() }
    // El WebView se queda con el foco del sistema (Chromium lo pide al
    // cargar), así que el D-pad no llega solo a los botones del overlay: hay
    // que moverlo a mano al entrar/salir de los controles.
    val overlayFocus = remember { FocusRequester() }
    // Al abrir la lista de fuentes el foco va al primer servidor: la búsqueda
    // geométrica de Compose no sube desde el botón "Usar otro servidor" a la
    // fila de arriba (se queda atascada en el propio botón).
    val firstServerFocus = remember { FocusRequester() }
    var overlayVisible by remember { mutableStateOf(true) }
    // Cada interacción (buscar, pausar, elegir servidor) reinicia el
    // temporizador de auto-ocultar: antes el contador arrancaba al abrirse el
    // overlay y los controles desaparecían a los 6 s aunque el usuario
    // estuviera adelantando/retrocediendo.
    var overlayInteraction by remember { mutableIntStateOf(0) }
    // Los servidores están ocultos por defecto: se reproduce con Desu (el
    // player propio del sitio) y solo se ofrece la lista si falla.
    var showServers by remember { mutableStateOf(false) }
    var fallbackSuggested by remember { mutableStateOf(false) }
    // true cuando el foco está en un botón del overlay. Sin esta bandera el
    // Box raíz también vería el OK (fase KeyDown) y ocultaría los controles
    // justo antes de que el botón reciba su click (fase KeyUp).
    var overlayHasFocus by remember { mutableStateOf(false) }

    // F4: progreso persistente. Se guarda cada ~5 s de avance real (no en cada
    // `timeupdate`, que dispara ~4 veces por segundo y machacaría Room).
    val library = remember(context) { Graph.library(context) }
    val scope = rememberCoroutineScope()
    var lastSavedMs by remember { mutableStateOf(-1L) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var resumeFromMs by remember { mutableStateOf<Long?>(null) }
    var playerReady by remember { mutableStateOf(false) }

    // Pausa/play desde el overlay o con la tecla del mando.
    //
    // El estado NO se invierte a ciegas: `NativePlayer` ya emite el estado
    // real por `onPlayingChanged` (que se dispara de forma síncrona dentro de
    // `pause()`/`play()`). Invertir después pisaba ese valor y el icono
    // quedaba al revés (se pausaba el vídeo y el botón seguía en "Pausa").
    // El WebView, en cambio, no avisa, así que ahí sí se invierte a mano.
    val togglePlay: () -> Unit = {
        val wasPlaying = controller.player?.isPlaying
        controller.togglePlayPause()
        isPlaying = if (wasPlaying == null) !isPlaying else !wasPlaying
    }

    // Reanudar donde quedó el usuario la última vez.
    LaunchedEffect(episodeUrl) {
        if (episodeNumber <= 0) return@LaunchedEffect
        val saved = library.episodeProgress(slug, episodeNumber)
        if (saved != null && !saved.completed && saved.positionMs > 15_000) {
            resumeFromMs = saved.positionMs
        }
    }

    // El seek se aplica cuando ya se cumplen las dos condiciones (posición
    // leída de Room + <video> enganchado por el hook), sin importar el orden.
    LaunchedEffect(resumeFromMs, playerReady) {
        val target = resumeFromMs ?: return@LaunchedEffect
        if (!playerReady) return@LaunchedEffect
        controller.seekTo(target)
        lastSavedMs = target
        resumeFromMs = null
    }

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
    }

    // El overlay se oculta solo mientras se reproduce: en pausa los controles
    // se quedan (es cuando el usuario los necesita). Si estamos avisando de que
    // el servidor falló, también se queda visible.
    LaunchedEffect(
        overlayVisible,
        isPlaying,
        state,
        fallbackSuggested,
        showServers,
        overlayHasFocus,
        overlayInteraction,
    ) {
        if (overlayVisible && isPlaying && !fallbackSuggested && !showServers && !overlayHasFocus) {
            delay(6_000)
            overlayVisible = false
        }
    }

    // Si el vídeo acaba cargando (a veces tarda más de 20 s), el aviso de
    // "no cargó" deja de ser cierto y se retira.
    LaunchedEffect(playerReady) {
        if (playerReady) fallbackSuggested = false
    }

    // Al ocultarse el overlay el foco vuelve al reproductor, si no el D-pad
    // se queda apuntando a un botón que ya no está en pantalla.
    LaunchedEffect(overlayVisible) {
        if (!overlayVisible) {
            overlayHasFocus = false
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Vigilante de carga: si en 20 s el WebView no enganchó un <video>, el
    // servidor no sirve y se ofrece cambiar de fuente. `state` cambia al
    // elegir otro servidor, así que el contador se reinicia solo.
    LaunchedEffect(state) {
        state as? PlayerUiState.Ready ?: return@LaunchedEffect
        fallbackSuggested = false
        showServers = false
        playerReady = false
        delay(20_000)
        if (!playerReady) {
            fallbackSuggested = true
            // No tapamos el vídeo ni abrimos el panel automáticamente: el
            // usuario decide cuándo cambiar de fuente desde el botón flotante.
            showServers = false
            overlayVisible = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // onKeyEvent (fase "post", de hijo a padre) y no onPreviewKeyEvent
            // (fase "pre", de padre a hijo): así, si el foco está en un botón
            // de servidor, su propio clickable consume Enter/OK primero y
            // dispara onClick antes de que este Box alterne el overlay. Con
            // onPreviewKeyEvent este Box consumía el evento ANTES de que el
            // botón lo recibiera, y seleccionar un servidor era imposible.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (overlayHasFocus) {
                            false
                        } else {
                            overlayVisible = !overlayVisible
                            true
                        }
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlay()
                        overlayVisible = true
                        overlayInteraction++
                        true
                    }
                    // Buscar con ◀ ▶ muestra los controles (para ver dónde
                    // quedó la reproducción) y reinicia el temporizador, así
                    // se ocultan solos unos segundos después del último seek.
                    Key.DirectionLeft -> {
                        if (overlayHasFocus) {
                            false
                        } else {
                            controller.seekBy(-10)
                            overlayVisible = true
                            overlayInteraction++
                            true
                        }
                    }
                    Key.DirectionRight -> {
                        if (overlayHasFocus) {
                            false
                        } else {
                            controller.seekBy(10)
                            overlayVisible = true
                            overlayInteraction++
                            true
                        }
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (overlayVisible) {
                            // Entrar en los botones del overlay.
                            overlayHasFocus = true
                            overlayInteraction++
                            runCatching { overlayFocus.requestFocus() }
                            true
                        } else {
                            overlayVisible = true
                            overlayInteraction++
                            false
                        }
                    }
                    else -> false
                }
            },
    ) {
        when (val current = state) {
            is PlayerUiState.Loading ->
                LoadingState(message = stringResource(R.string.player_finding_servers))
            is PlayerUiState.Error -> ErrorState(
                message = current.message,
                onRetry = { viewModel.load(force = true) },
            )
            is PlayerUiState.Ready -> {
                // Los players propios del sitio (Desu/Magi) exponen el m3u8 en
                // su embed: se resuelve aquí y se reproduce con ExoPlayer. En
                // la TV el WebView decodifica pero no compone el vídeo (pantalla
                // negra con audio), así que el nativo es el camino principal y
                // el WebView queda como fallback si la resolución falla.
                var streamUrl by remember(current.selected.url) { mutableStateOf<String?>(null) }
                var streamFailed by remember(current.selected.url) { mutableStateOf(false) }

                LaunchedEffect(current.selected.url) {
                    streamUrl = null
                    streamFailed = false
                    val resolved = runCatching {
                        Graph.repository.stream(current.selected.url, referer = episodeUrl)
                    }.getOrNull()
                    if (resolved != null) {
                        streamUrl = resolved
                    } else {
                        streamFailed = true
                    }
                }

                val onProgress: (Long, Long) -> Unit = { pos, dur ->
                    positionMs = pos
                    if (dur > 0) durationMs = dur
                    // "Casi al final" cuenta como visto (evita que el
                    // último 1% de créditos deje el episodio pendiente).
                    val almostDone = dur > 0 && pos >= dur - 5_000
                    if (almostDone || pos - lastSavedMs >= 5_000) {
                        lastSavedMs = pos
                        scope.launch {
                            library.saveProgress(
                                slug = slug,
                                episode = episodeNumber,
                                positionMs = pos,
                                durationMs = dur,
                                completed = almostDone,
                            )
                        }
                    }
                }
                val onEnded: () -> Unit = {
                    overlayVisible = true
                    isPlaying = false
                    scope.launch {
                        library.saveProgress(
                            slug = slug,
                            episode = episodeNumber,
                            positionMs = durationMs,
                            durationMs = durationMs,
                            completed = true,
                        )
                    }
                }

                val resolvedStream = streamUrl
                when {
                    resolvedStream != null -> NativePlayer(
                        url = resolvedStream,
                        referer = episodeUrl,
                        controller = controller,
                        onProgress = onProgress,
                        onEnded = onEnded,
                        onReady = { playerReady = true },
                        onPlayingChanged = { playing -> isPlaying = playing },
                        onError = {
                            // El stream murió o el CDN rechazó: se cae al
                            // WebView con el mismo servidor.
                            streamUrl = null
                            streamFailed = true
                            playerReady = false
                        },
                    )

                    streamFailed -> WebPlayer(
                        url = current.selected.url,
                        controller = controller,
                        onProgress = onProgress,
                        onEnded = onEnded,
                        onReady = { playerReady = true },
                    )

                    else -> LoadingState(message = stringResource(R.string.player_preparing))
                }

                if (overlayVisible) {
                        Overlay(
                         servers = current.servers,
                        selectedUrl = current.selected.url,
                        selectedName = current.selected.displayName,
                        showServers = showServers,
                        fallbackSuggested = fallbackSuggested,
                        focusRequester = overlayFocus,
                         firstServerFocus = firstServerFocus,
                         positionMs = positionMs,
                         durationMs = durationMs,
                         isPlaying = isPlaying,
                         onSeek = { target ->
                             positionMs = target
                             controller.seekTo(target)
                             overlayInteraction++
                         },
                         onTogglePlay = {
                             togglePlay()
                             overlayInteraction++
                         },
                        onToggleServers = {
                            showServers = !showServers
                            overlayInteraction++
                        },
                        onBackToPlayer = {
                            overlayHasFocus = false
                            runCatching { rootFocus.requestFocus() }
                        },
                        onSelect = { server ->
                            viewModel.select(server)
                            showServers = false
                            fallbackSuggested = false
                            playerReady = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Overlay(
    servers: List<com.macastil.tv.model.VideoServer>,
    selectedUrl: String,
    selectedName: String,
    showServers: Boolean,
    fallbackSuggested: Boolean,
    focusRequester: FocusRequester,
    firstServerFocus: FocusRequester,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleServers: () -> Unit,
    onBackToPlayer: () -> Unit,
    onSelect: (com.macastil.tv.model.VideoServer) -> Unit,
) {
    // La lista se acaba de abrir: el foco entra en ella.
    LaunchedEffect(showServers) {
        if (!showServers) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (firstServerFocus.requestFocus()) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Con los servidores ocultos, "arriba" devuelve el foco al
            // reproductor; con la lista abierta, arriba navega entre filas.
            .onKeyEvent { event ->
                if (!showServers &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionUp
                ) {
                    onBackToPlayer()
                    true
                } else {
                    false
                }
            },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (fallbackSuggested && !showServers) {
                TvIconButton(
                    icon = Icons.Filled.List,
                    contentDescription = stringResource(R.string.player_change_server_cd),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 20.dp),
                    onClick = onToggleServers,
                )
            }

            // Pausa/play en el centro del vídeo (antes estaba en la fila de
            // abajo junto a los saltos). Al ocultarse el overlay desaparece.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .focusRequester(focusRequester)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTogglePlay,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) PauseIcon else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) {
                        stringResource(R.string.player_pause_cd)
                    } else {
                        stringResource(R.string.player_play_cd)
                    },
                    tint = JkTextPrimary,
                    modifier = Modifier.size(52.dp),
                )
            }

            if (showServers) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(300.dp)
                        .background(Color(0xF20E0E0E))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.player_servers),
                        style = MaterialTheme.typography.titleMedium,
                        color = JkTextPrimary,
                    )
                    servers
                        .filter { it.kind != ServerKind.DOWNLOAD }
                        .sortedBy { if (it.kind == ServerKind.JKPLAYER) 0 else 1 }
                        .take(8)
                        .forEachIndexed { index, server ->
                            TvActionButton(
                                text = server.displayName,
                                primary = server.url == selectedUrl,
                                compact = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (index == 0) Modifier.focusRequester(firstServerFocus) else Modifier),
                                onClick = { onSelect(server) },
                            )
                        }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xE6000000)),
                    ),
                )
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (fallbackSuggested && !showServers) {
                Text(
                    text = stringResource(R.string.player_server_failed, selectedName),
                    style = MaterialTheme.typography.labelMedium,
                    color = JkOrange,
                )
            }

            SeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onSeek = onSeek,
            )

            // Sin botones de adelantar/retroceder ni de salir: el D-pad
            // (◀ ▶) mueve la barra y BACK sale del reproductor.
            Text(
                text = stringResource(R.string.player_seek_hint),
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary,
            )
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val fraction = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = 10_000L
                when (event.key) {
                    Key.DirectionLeft -> {
                        onSeek((positionMs - step).coerceAtLeast(0L))
                        true
                    }
                    Key.DirectionRight -> {
                        onSeek((positionMs + step).coerceAtMost(durationMs))
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(JkTextSecondary.copy(alpha = 0.45f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(5.dp)
                .background(JkOrange),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 1.dp)
                    .background(JkOrange, androidx.compose.foundation.shape.CircleShape)
                    .padding(6.dp),
            )
        }
        Text(
            text = stringResource(
                R.string.player_seek_position,
                formatTime(positionMs),
                formatTime(durationMs),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = JkTextPrimary,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
