package com.macastil.mobile.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary

@Composable
fun PlayerScreen(
    episodeUrl: String,
    referer: String?,
    onExit: () -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModelFactory(JkanimeRepository(), episodeUrl, referer),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state

    var isPlaying by mutableStateOf(true)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (current) {
            is PlayerUiState.Loading -> {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is PlayerUiState.Ready -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Reproduciendo",
                        style = MaterialTheme.typography.headlineMedium,
                        color = JkTextPrimary,
                    )
                    Text(
                        text = "URL del episodio: $episodeUrl",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                    )
                    androidx.compose.foundation.layout.Spacer(
                        modifier = Modifier.height(24.dp),
                    )
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        IconButton(
                            onClick = { isPlaying = !isPlaying },
                        ) {
                            Text(
                                text = if (isPlaying) "⏸" else "▶",
                                color = JkTextPrimary,
                            )
                        }
                        IconButton(
                            onClick = onExit,
                        ) {
                            Text(text = "✕", color = JkTextPrimary)
                        }
                    }
                    Text(
                        text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = JkTextPrimary,
                    )
                }
            }
            is PlayerUiState.Error -> {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
