package com.macastil.mobile.ui.player

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.mobile.JkGraph
import com.macastil.tv.model.VideoServer
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reproductor móvil: WebView sobre el embed del servidor + selector de
 * servidores y guardado de progreso.
 *
 * El progreso sale del puente JS (`onProgress`) que inyecta `HOOK_SCRIPT`,
 * así que "Continuar Ep N" del detalle funciona igual que en la TV.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ep $episodeNumber · ${episodeTitle.ifBlank { slug }}",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load(force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Recargar servidores")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                when (val current = state) {
                    is PlayerUiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }

                    is PlayerUiState.Error -> {
                        Box(
                            Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = current.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                )
                                Spacer(Modifier.height(12.dp))
                                androidx.compose.material3.Button(onClick = { viewModel.load(force = true) }) {
                                    Text("Reintentar")
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
                            onReady = { },
                        )
                    }
                }
            }

            TimeBar(positionMs = positionMs, durationMs = durationMs)

            Text(
                text = "Servidor",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val ready = state as? PlayerUiState.Ready
            if (ready != null) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ready.servers, key = { it.name + it.url }) { server ->
                        ServerChip(
                            server = server,
                            selected = server.url == ready.selected.url,
                            onClick = { viewModel.select(server) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Si el reproductor no carga, prueba otro servidor. Desu es el del propio " +
                    "sitio y el más estable.",
                style = MaterialTheme.typography.bodySmall,
                color = JkTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ServerChip(
    server: VideoServer,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (selected) Color(0xFFFF6B35) else Color(0xFF2A2A2A)
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

@Composable
private fun TimeBar(positionMs: Long, durationMs: Long) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = {
                if (durationMs <= 0L) 0f
                else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = JkTextSecondary,
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
