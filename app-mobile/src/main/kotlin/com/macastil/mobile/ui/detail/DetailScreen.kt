package com.macastil.mobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.macastil.mobile.JkGraph
import com.macastil.mobile.data.local.ProgressEntity
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.Episode
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    slug: String,
    onPlay: (slug: String, episodeNumber: Int, episodeUrl: String, episodeTitle: String) -> Unit,
    onBack: () -> Unit = {},
) {
    val viewModel: DetailViewModel = viewModel(
        key = "detail-$slug",
        factory = DetailViewModelFactory(JkGraph.repository, slug),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is DetailUiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is DetailUiState.Error -> {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                    )
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.Button(onClick = viewModel::load) {
                        Text("Reintentar")
                    }
                }
            }
        }

        is DetailUiState.Success -> DetailContent(
            detail = current.detail,
            episodes = current.episodes,
            onPlay = onPlay,
            onBack = onBack,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailContent(
    detail: AnimeDetail,
    episodes: List<Episode>,
    onPlay: (String, Int, String, String) -> Unit,
    onBack: () -> Unit,
) {
    val library = JkGraph.library
    val scope = rememberCoroutineScope()

    val favorites by library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by library.observeProgressFor(detail.slug)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val favorite = favorites.firstOrNull { it.slug == detail.slug && it.deletedAt == null }
    val progressByEpisode = remember(progress) {
        progress.associateBy { it.episode }
    }
    val resume = remember(progress) {
        progress.filter { !it.completed && it.positionMs > 0 }.maxByOrNull { it.updatedAt }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detail.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Header(
                    detail = detail,
                    episodes = episodes,
                    isFavorite = favorite != null,
                    resumeEpisode = resume,
                    onToggleFavorite = {
                        scope.launch {
                            library.toggleFavorite(
                                slug = detail.slug,
                                title = detail.title,
                                posterUrl = detail.posterUrl,
                                animeId = detail.animeId,
                                statusLabel = detail.statusLabel,
                            )
                        }
                    },
                    onResume = { episode ->
                        onPlay(detail.slug, episode.number.toInt(), episode.url, episode.title)
                    },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Capítulos (${episodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = JkTextPrimary,
                    )
                    Text(
                        text = "Toca para reproducir",
                        style = MaterialTheme.typography.labelSmall,
                        color = JkTextSecondary,
                    )
                }
            }

            items(episodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    progress = progressByEpisode[episode.number.toInt()],
                    onClick = {
                        onPlay(detail.slug, episode.number.toInt(), episode.url, episode.title)
                    },
                )
            }
        }
    }
}

@Composable
private fun Header(
    detail: AnimeDetail,
    episodes: List<Episode>,
    isFavorite: Boolean,
    resumeEpisode: ProgressEntity?,
    onToggleFavorite: () -> Unit,
    onResume: (Episode) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row {
            AsyncImage(
                model = detail.posterUrl,
                contentDescription = detail.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .height(170.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = JkTextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                detail.statusLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor(it),
                    )
                }
                detail.type?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = JkTextSecondary,
                    )
                }
                detail.totalEpisodes?.let {
                    Text(
                        text = "Episodios: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = JkTextSecondary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = if (isFavorite) "Quitar favorito" else "Añadir favorito",
                            tint = if (isFavorite) Color(0xFFFF6B35) else JkTextSecondary,
                        )
                    }
                    Text(
                        text = if (isFavorite) "En favoritos" else "Añadir a favoritos",
                        style = MaterialTheme.typography.bodySmall,
                        color = JkTextSecondary,
                    )
                }
            }
        }

        val resume = resumeEpisode
        if (resume != null) {
            val episode = episodes.firstOrNull { it.number.toInt() == resume.episode }
            if (episode != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Button(
                    onClick = { onResume(episode) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Continuar Ep ${episodes.firstOrNull { it.number.toInt() == resume.episode }?.numberLabel ?: resume.episode}")
                }
            }
        }

        detail.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = synopsis,
                style = MaterialTheme.typography.bodyMedium,
                color = JkTextSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: ProgressEntity?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(112.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = episode.thumbUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val label = if (progress?.completed == true) {
                "Ep ${episode.numberLabel} · Visto"
            } else {
                "Ep ${episode.numberLabel}"
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (progress?.completed == true) Color(0xFF3DDC84) else JkTextPrimary,
            )
            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodySmall,
                color = JkTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val fraction = progress?.fraction ?: 0f
            if (fraction > 0f && progress?.completed != true) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
    }
}

private fun statusColor(label: String): Color = when {
    label.contains("emisi", ignoreCase = true) -> Color(0xFFFF6B35)
    label.contains("conclu", ignoreCase = true) -> Color(0xFF3DDC84)
    else -> JkTextSecondary
}
