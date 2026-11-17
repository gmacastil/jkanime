package com.macastil.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.material.icons.filled.Close
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
    // Con el aviso de "servidor caído" el foco inicial no puede ir a
    // play/pausa: lo que el usuario necesita es cambiar de fuente.
    val changeServerFocus = remember { FocusRequester() }
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
    // Incrementa cada vez que se quiere llevar el foco al play/pausa. El
    // overlay se compone y se destruye con `overlayVisible`, así que pedir el
    // foco dentro de su `LaunchedEffect(Unit)` lo robaría también cuando los
    // controles aparecen solos (seek con ◀ ▶, aviso de servidor caído). Con un
    // contador explícito solo se enfoca cuando el usuario lo pide.
    var overlayFocusRequest by remember { mutableIntStateOf(0) }

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

    // Muestra los controles. `focusControls` decide si además se lleva el foco
    // al play/pausa (OK, Arriba/Abajo) o no (seek con ◀ ▶, aviso de caída).
    val showControls: (Boolean) -> Unit = { focusControls ->
        overlayVisible = true
        overlayInteraction++
        if (focusControls) overlayFocusRequest++
    }

    // Lleva el foco al play/pausa cuando se ha pedido. Se reintenta unos
    // frames porque el nodo del overlay acaba de entrar en la composición y
    // hasta que no se coloca `requestFocus()` devuelve false en silencio (o
    // lanza si el FocusRequester aún no está enlazado).
    LaunchedEffect(overlayFocusRequest) {
        if (overlayFocusRequest == 0) return@LaunchedEffect
        // Si el servidor no arrancó, lo útil es el botón "cambiar servidor".
        val target = if (fallbackSuggested && !showServers) changeServerFocus else overlayFocus
        repeat(12) {
            withFrameNanos { }
            val focused = runCatching { target.requestFocus() }.getOrDefault(false)
            if (focused) {
                overlayHasFocus = true
                return@LaunchedEffect
            }
        }
    }

    // Pausa/play desde el overlay o con la tecla del mando.
    //
    // El estado NO se invierte a ciegas: `NativePlayer` ya emite el estado
    // real por `onPlayingChanged` (que se dispara de forma síncrona dentro de
    // `pause()`/`play()`). Invertir después pisaba ese valor y el icono
    // quedaba al revés (se pausaba el vídeo y el botón seguía en "Pausa").
    // El WebView, en cambio, no avisa, así que ahí sí se invierte a mano.
    val togglePlay: () -> Boolean = {
        val wasPlaying = controller.player?.isPlaying
        controller.togglePlayPause()
        val nowPlaying = if (wasPlaying == null) !isPlaying else !wasPlaying
        isPlaying = nowPlaying
        nowPlaying
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
        // El WebView nativo puede tardar un frame en colocarse: si el foco no
        // se puede pedir al primer intento se reintenta.
        repeat(6) {
            withFrameNanos { }
            if (runCatching { rootFocus.requestFocus() }.getOrDefault(false)) {
                return@LaunchedEffect
            }
        }
    }

    // El overlay se oculta solo mientras se reproduce: en pausa los controles
    // se quedan (es cuando el usuario los necesita). Si estamos avisando de que
    // el servidor falló o hay una lista de fuentes abierta, también se queda
    // visible. Ya NO se exige que el foco esté fuera de los controles: al
    // pulsar Play el overlay se ocultaba de golpe y el foco se quedaba en el
    // aire (el siguiente OK solo volvía a abrirlo sin enfocar play/pausa). Ahora
    // se desvanece a los 6 s de la última interacción, como un reproductor de
    // TV normal. `overlayHasFocus` se mantiene como clave para que entrar o
    // salir de los controles reinicie el temporizador.
    LaunchedEffect(
        overlayVisible,
        isPlaying,
        state,
        fallbackSuggested,
        showServers,
        overlayHasFocus,
        overlayInteraction,
    ) {
        if (overlayVisible && isPlaying && !fallbackSuggested && !showServers) {
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
            // Reintento multi-frame: el foco no se puede pedir mientras el
            // árbol se está recomponiendo (falla en silencio).
            repeat(6) {
                withFrameNanos { }
                if (runCatching { rootFocus.requestFocus() }.getOrDefault(false)) {
                    return@LaunchedEffect
                }
            }
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
            // Los controles aparecen sin robar el foco (el vídeo puede estar
            // reproduciendo igual y el aviso no requiere interacción).
            showServers = false
            showControls(false)
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
                    // OK sin foco en los controles: los muestra y entra en
                    // play/pausa. Antes solo alternaba la visibilidad, así que
                    // tras reanudar el vídeo el overlay reaparecía sin foco y
                    // el siguiente OK lo ocultaba en vez de pausar.
                    Key.DirectionCenter, Key.Enter -> {
                        if (overlayHasFocus) {
                            false
                        } else {
                            showControls(true)
                            true
                        }
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlay()
                        showControls(false)
                        true
                    }
                    // Buscar con ◀ ▶ muestra los controles (para ver dónde
                    // quedó la reproducción) y reinicia el temporizador, así
                    // se ocultan solos unos segundos después del último seek.
                    // No se roba el foco: se sigue buscando con las flechas.
                    Key.DirectionLeft -> {
                        if (overlayHasFocus) {
                            false
                        } else {
                            controller.seekBy(-10)
                            showControls(false)
                            true
                        }
                    }
                    Key.DirectionRight -> {
                        if (overlayHasFocus) {
                            false
                        } else {
                            controller.seekBy(10)
                            showControls(false)
                            true
                        }
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (overlayHasFocus) {
                            // Ya hay un control enfocado: navegar entre ellos.
                            false
                        } else {
                            showControls(true)
                            true
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
                        changeServerFocus = changeServerFocus,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        onSeek = { target ->
                            positionMs = target
                            controller.seekTo(target)
                            overlayInteraction++
                        },
                        onTogglePlay = {
                            // Un clic táctil sobre el botón debe dejar el foco
                            // lógico en el mismo control: sin esto el siguiente
                            // D-pad caía en el Box raíz.
                            overlayHasFocus = true
                            runCatching { overlayFocus.requestFocus() }
                            togglePlay()
                            // Al reanudar NO se oculta el overlay de golpe: se
                            // deja que lo haga el auto-ocultado a los 6 s, así
                            // el foco nunca se pierde entre pausa y play.
                            overlayInteraction++
                        },
                        onToggleServers = {
                            val closing = showServers
                            showServers = !showServers
                            overlayInteraction++
                            // Al cerrar la lista el botón de cierre desaparece:
                            // el foco vuelve al play/pausa para no dejarlo
                            // huérfano.
                            if (closing) overlayFocusRequest++
                        },
                        onBackToPlayer = {
                            // Arriba desde los controles = volver al vídeo.
                            overlayVisible = false
                        },
                        onOverlayFocused = { overlayHasFocus = true },
                        onSelect = { server ->
                            viewModel.select(server)
                            showServers = false
                            fallbackSuggested = false
                            playerReady = false
                            // El botón elegido desaparece con la lista: sin
                            // esto el foco se quedaba huérfano y el D-pad
                            // muerto. Se vuelve al vídeo y OK restaura los
                            // controles.
                            overlayVisible = false
                            overlayHasFocus = false
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
    changeServerFocus: FocusRequester,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onToggleServers: () -> Unit,
    onBackToPlayer: () -> Unit,
    onOverlayFocused: () -> Unit,
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
                    focusRequester = changeServerFocus,
                    onClick = onToggleServers,
                )
            }

            // Pausa/play en el centro del vídeo (antes estaba en la fila de
            // abajo junto a los saltos). Al ocultarse el overlay desaparece.
            // El foco se marca con anillo blanco + relleno naranja + escala:
            // antes el botón era idéntico con y sin foco, así que "perder el
            // foco" era indistinguible de tenerlo.
            val playInteraction = remember { MutableInteractionSource() }
            val playFocused by playInteraction.collectIsFocusedAsState()
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .scale(if (playFocused) 1.06f else 1f)
                    .clip(CircleShape)
                    .background(if (playFocused) JkOrange else Color.Black.copy(alpha = 0.45f))
                    .border(
                        width = if (playFocused) 3.dp else 1.dp,
                        color = if (playFocused) {
                            JkTextPrimary
                        } else {
                            Color.White.copy(alpha = 0.25f)
                        },
                        shape = CircleShape,
                    )
                    .focusRequester(focusRequester)
                    .onFocusChanged { overlayFocused ->
                        // Mantener la bandera sincronizada también cuando el
                        // foco llega por D-pad, no solo por el clic.
                        // `onKeyEvent` del Box raíz depende de este estado.
                        if (overlayFocused.isFocused) onOverlayFocused()
                    }
                    .clickable(
                        interactionSource = playInteraction,
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
                    tint = if (playFocused) Color(0xFF141414) else JkTextPrimary,
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
                    // Cabecera con botón de cierre: con la lista abierta no
                    // había forma de volver al vídeo salvo eligiendo una
                    // fuente (el botón que la abre solo se pinta con la lista
                    // cerrada). Arriba desde el primer servidor llega aquí.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.player_servers),
                            style = MaterialTheme.typography.titleMedium,
                            color = JkTextPrimary,
                        )
                        TvIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.player_close_servers_cd),
                            active = true,
                            size = 40.dp,
                            iconSize = 18.dp,
                            onClick = onToggleServers,
                        )
                    }
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
                onFocused = onOverlayFocused,
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
    onFocused: () -> Unit = {},
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val fraction = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .focusable()
            .onFocusChanged {
                focused = it.isFocused
                // La barra también forma parte del overlay: el Box raíz no
                // debe buscar con ◀ ▶ (lo haría dos veces) mientras está
                // enfocada.
                if (it.isFocused) onFocused()
            }
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
                .height(if (focused) 7.dp else 5.dp)
                .background(
                    if (focused) {
                        JkTextPrimary.copy(alpha = 0.55f)
                    } else {
                        JkTextSecondary.copy(alpha = 0.45f)
                    },
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(if (focused) 7.dp else 5.dp)
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
                    // Punto de la barra: crece y se rodea de blanco al enfocar
                    // para que el D-pad tenga una referencia visible.
                    .size(if (focused) 18.dp else 12.dp)
                    .background(JkOrange, CircleShape)
                    .border(
                        width = if (focused) 2.dp else 0.dp,
                        color = JkTextPrimary,
                        shape = CircleShape,
                    ),
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
