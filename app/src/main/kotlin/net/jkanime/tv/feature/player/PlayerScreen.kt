package net.jkanime.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import net.jkanime.tv.Graph
import net.jkanime.tv.model.ServerKind
import net.jkanime.tv.ui.component.ErrorState
import net.jkanime.tv.ui.component.LoadingState
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

@Composable
fun PlayerScreen(
    slug: String,
    episodeNumber: Int,
    episodeUrl: String,
    title: String,
    onExit: () -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(
        key = episodeUrl,
        factory = PlayerViewModel.factory(Graph.repository, episodeUrl),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val controller = remember { WebPlayerController() }
    val rootFocus = remember { FocusRequester() }
    var overlayVisible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
    }

    // El overlay se oculta solo: en TV no hay mouse para "quitar el foco".
    LaunchedEffect(overlayVisible, state) {
        if (overlayVisible) {
            delay(6_000)
            overlayVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        overlayVisible = !overlayVisible
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        controller.togglePlayPause()
                        overlayVisible = true
                        true
                    }
                    Key.DirectionLeft -> {
                        if (overlayVisible) {
                            false
                        } else {
                            controller.seekBy(-10)
                            true
                        }
                    }
                    Key.DirectionRight -> {
                        if (overlayVisible) {
                            false
                        } else {
                            controller.seekBy(10)
                            true
                        }
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        overlayVisible = true
                        false
                    }
                    else -> false
                }
            },
    ) {
        when (val current = state) {
            is PlayerUiState.Loading -> LoadingState(message = "Buscando servidores…")
            is PlayerUiState.Error -> ErrorState(
                message = current.message,
                onRetry = { viewModel.load(force = true) },
            )
            is PlayerUiState.Ready -> {
                WebPlayer(
                    url = current.selected.url,
                    controller = controller,
                    onProgress = { _, _ -> },
                    onEnded = { overlayVisible = true },
                    onReady = { },
                )

                if (overlayVisible) {
                    Overlay(
                        title = title,
                        slug = slug,
                        episodeNumber = episodeNumber,
                        servers = current.servers,
                        selectedUrl = current.selected.url,
                        onSelect = viewModel::select,
                        onExit = onExit,
                    )
                }
            }
        }
    }
}

@Composable
private fun Overlay(
    title: String,
    slug: String,
    episodeNumber: Int,
    servers: List<net.jkanime.tv.model.VideoServer>,
    selectedUrl: String,
    onSelect: (net.jkanime.tv.model.VideoServer) -> Unit,
    onExit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC000000), Color.Transparent),
                    ),
                )
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Column {
                Text(
                    text = title.ifBlank { slug },
                    style = MaterialTheme.typography.titleMedium,
                    color = JkTextPrimary,
                )
                Text(
                    text = if (episodeNumber > 0) "Episodio $episodeNumber" else "Episodio",
                    style = MaterialTheme.typography.labelMedium,
                    color = JkTextSecondary,
                )
            }
        }

        Box(modifier = Modifier.weight(1f))

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
            Text(
                text = "Fuentes",
                style = MaterialTheme.typography.labelMedium,
                color = JkTextSecondary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                servers
                    .filter { it.kind != ServerKind.DOWNLOAD }
                    .take(5)
                    .forEach { server ->
                        TvActionButton(
                            text = server.displayName,
                            primary = server.url == selectedUrl,
                            onClick = { onSelect(server) },
                        )
                    }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "OK oculta los controles · ◀ ▶ retrocede/avanza 10 s · Atrás sale",
                    style = MaterialTheme.typography.labelSmall,
                    color = JkOrange,
                )
                TvActionButton(
                    text = "Salir",
                    primary = false,
                    onClick = onExit,
                )
            }
        }
    }
}
