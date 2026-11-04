package net.jkanime.tv.feature.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import net.jkanime.tv.Graph
import net.jkanime.tv.model.AnimeDetail
import net.jkanime.tv.model.Episode
import net.jkanime.tv.ui.component.ErrorState
import net.jkanime.tv.ui.component.EpisodeTile
import net.jkanime.tv.ui.component.InfoChip
import net.jkanime.tv.ui.component.LoadingState
import net.jkanime.tv.ui.component.SectionTitle
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Detalle del anime + capítulos. Requerimiento 3 del brief
 * (descripción y capítulos; la reproducción la abre el PlayerActivity).
 */
@Composable
fun DetailScreen(
    slug: String,
    onBack: () -> Unit,
    onPlay: (slug: String, episodeNumber: Int?, episodeUrl: String, title: String) -> Unit,
) {
    val viewModel: DetailViewModel = viewModel(
        key = slug,
        factory = DetailViewModel.factory(Graph.repository, slug),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground),
    ) {
        when (val current = state) {
            is DetailUiState.Loading -> LoadingState()
            is DetailUiState.Error -> ErrorState(
                message = current.message,
                onRetry = { viewModel.load(force = true) },
            )
            is DetailUiState.Success -> {
                Header(
                    detail = current.detail,
                    latest = current.latest,
                    first = current.first,
                    onPlay = onPlay,
                )
                SectionTitle(text = "Capítulos (${current.episodes.size})")
                EpisodesGrid(
                    episodes = current.episodes,
                    slug = slug,
                    detailTitle = current.detail.title,
                    onPlay = onPlay,
                    onLoadMore = viewModel::loadMore,
                )
            }
        }
    }
}

@Composable
private fun Header(
    detail: AnimeDetail,
    latest: Episode?,
    first: Episode?,
    onPlay: (String, Int?, String, String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        AsyncImage(
            model = detail.posterUrl,
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(190.dp)
                .height(285.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(JkSurfaceHigh),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.headlineSmall,
                color = JkTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            detail.titleAlt?.let { alt ->
                Text(
                    text = alt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JkTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.statusLabel?.let { InfoChip(text = it, highlight = true) }
                detail.type?.let { InfoChip(text = it) }
                detail.totalEpisodes?.let { InfoChip(text = "$it eps") }
                detail.quality?.let { InfoChip(text = it) }
                detail.nextEpisodeLabel?.let { InfoChip(text = "Próx: $it") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            detail.synopsis?.let { synopsis ->
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = JkTextSecondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (latest != null) {
                    TvActionButton(
                        text = "Ver último (Ep ${latest.numberLabel})",
                        primary = true,
                        onClick = {
                            onPlay(detail.slug, latest.number.toInt(), latest.url, latest.title)
                        },
                    )
                }
                if (first != null && first.id != latest?.id) {
                    TvActionButton(
                        text = "Primer episodio",
                        primary = false,
                        onClick = {
                            onPlay(detail.slug, first.number.toInt(), first.url, first.title)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = buildString {
                    detail.genres.take(5).forEach { append(it).append("  ·  ") }
                    detail.studios.firstOrNull()?.let { append(it) }
                }.trim().trimEnd('·').trim(),
                style = MaterialTheme.typography.labelSmall,
                color = JkOrange,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EpisodesGrid(
    episodes: List<Episode>,
    slug: String,
    detailTitle: String,
    onPlay: (String, Int?, String, String) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (episodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Todavía no hay capítulos publicados",
                style = MaterialTheme.typography.bodyMedium,
                color = JkTextSecondary,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(episodes, key = { it.id }) { episode ->
            EpisodeTile(
                number = episode.numberLabel,
                title = episode.title.ifBlank { "$detailTitle - ${episode.numberLabel}" },
                onClick = { onPlay(slug, episode.number.toInt(), episode.url, episode.title) },
            )
        }
        if (episodes.isNotEmpty()) {
            item {
                TvActionButton(
                    text = "Cargar más",
                    primary = false,
                    onClick = onLoadMore,
                )
            }
        }
    }
}
