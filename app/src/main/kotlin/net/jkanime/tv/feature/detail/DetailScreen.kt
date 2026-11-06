package net.jkanime.tv.feature.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import net.jkanime.tv.Graph
import net.jkanime.tv.data.local.ProgressEntity
import net.jkanime.tv.model.AiringStatus
import net.jkanime.tv.model.AnimeDetail
import net.jkanime.tv.model.Episode
import net.jkanime.tv.ui.component.ChapterRow
import net.jkanime.tv.ui.component.ErrorState
import net.jkanime.tv.ui.component.LoadingState
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkGreen
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Detalle del anime. Requerimiento 3 del brief.
 *
 * Layout: póster + datos a la izquierda, capítulos a la derecha (una sola
 * columna, ordenables). La estrella del título es el favorito.
 */
@Composable
fun DetailScreen(
    slug: String,
    onBack: () -> Unit,
    onPlay: (slug: String, episodeNumber: Int?, episodeUrl: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val library = remember(context) { Graph.library(context) }
    val viewModel: DetailViewModel = viewModel(
        key = slug,
        factory = DetailViewModel.factory(Graph.repository, library, slug),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val lastProgress by viewModel.lastProgress.collectAsStateWithLifecycle()
    val progressByEpisode by viewModel.progressByEpisode.collectAsStateWithLifecycle()
    val descending by viewModel.sortDescending.collectAsStateWithLifecycle()

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
            is DetailUiState.Success -> Content(
                detail = current.detail,
                episodes = current.episodes,
                hasMore = current.hasMore,
                loadingMore = current.loadingMore,
                descending = descending,
                isFavorite = isFavorite,
                progressByEpisode = progressByEpisode,
                resumeEpisode = lastProgress
                    ?.takeIf { !it.completed }
                    ?.let { p -> current.episodes.firstOrNull { it.number.toInt() == p.episode } },
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleSort = viewModel::toggleSort,
                onLoadMore = viewModel::loadMore,
                onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun Content(
    detail: AnimeDetail,
    episodes: List<Episode>,
    hasMore: Boolean,
    loadingMore: Boolean,
    descending: Boolean,
    isFavorite: Boolean,
    progressByEpisode: Map<Int, ProgressEntity>,
    resumeEpisode: Episode?,
    onToggleFavorite: () -> Unit,
    onToggleSort: () -> Unit,
    onLoadMore: () -> Unit,
    onPlay: (String, Int?, String, String) -> Unit,
) {
    val sorted = remember(episodes, descending) {
        if (descending) episodes.sortedByDescending { it.number } else episodes.sortedBy { it.number }
    }

    val firstChapterFocus = remember { FocusRequester() }
    val starFocus = remember { FocusRequester() }

    // El foco inicial va al primer capítulo (acción principal); si no hay
    // capítulos, a la estrella de favoritos.
    LaunchedEffect(sorted, episodes.isEmpty()) {
        repeat(10) {
            withFrameNanos { }
            val ok = if (sorted.isEmpty()) starFocus.requestFocus()
            else firstChapterFocus.requestFocus()
            if (ok) return@LaunchedEffect
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        LeftPanel(detail = detail)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 8.dp, end = 28.dp, top = 20.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = JkTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false: el título ocupa solo lo que mide, así la
                    // estrella queda pegada a él y no en el borde derecho.
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(14.dp))
                FavoriteStar(
                    isFavorite = isFavorite,
                    onToggle = onToggleFavorite,
                    focusRequester = starFocus,
                )
            }

            detail.titleAlt?.let { alt ->
                Text(
                    text = alt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JkTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            detail.synopsis?.let { synopsis ->
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = JkTextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Capítulos (${episodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = JkTextPrimary,
                )
                resumeEpisode?.let { episode ->
                    TvActionButton(
                        text = "Continuar Ep ${episode.numberLabel}",
                        primary = true,
                        onClick = {
                            onPlay(detail.slug, episode.number.toInt(), episode.url, episode.title)
                        },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TvActionButton(
                    text = if (descending) "↓ Más nuevos primero" else "↑ Más antiguos primero",
                    primary = false,
                    onClick = onToggleSort,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (sorted.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Todavía no hay capítulos publicados",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(sorted, key = { _, episode -> episode.id }) { index, episode ->
                        val progress = progressByEpisode[episode.number.toInt()]
                        val watched = progress?.completed == true
                        val inProgress = !watched && (progress?.positionMs ?: 0L) > 0L
                        ChapterRow(
                            number = episode.numberLabel,
                            title = chapterTitle(episode, detail.title),
                            watched = watched,
                            inProgress = inProgress,
                            progressFraction = progress?.fraction ?: 0f,
                            progressLabel = progress
                                ?.takeIf { it.durationMs > 0 && it.fraction >= 0.01f }
                                ?.let { "${(it.fraction * 100).toInt()}%" },
                            focusRequester = if (index == 0) firstChapterFocus else null,
                            onClick = {
                                onPlay(
                                    detail.slug,
                                    episode.number.toInt(),
                                    episode.url,
                                    episode.title,
                                )
                            },
                        )
                    }
                    if (hasMore) {
                        item {
                            Box(modifier = Modifier.padding(top = 8.dp)) {
                                TvActionButton(
                                    text = if (loadingMore) "Cargando…" else "Cargar más",
                                    primary = false,
                                    enabled = !loadingMore,
                                    onClick = onLoadMore,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Título del capítulo sin el nombre del anime delante: el sitio publica
 * "Hyouken no Majutsushi ga Sekai wo Suberu II - 5", y repetir el título del
 * anime en cada fila es ruido (ya está arriba).
 */
private fun chapterTitle(episode: Episode, animeTitle: String): String {
    val raw = episode.title.trim()
    val stripped = listOf("$animeTitle - ", "$animeTitle – ", "$animeTitle: ")
        .firstOrNull { raw.startsWith(it, ignoreCase = true) }
        ?.let { raw.substring(it.length) }
        ?.trim()
        ?: raw
    return when {
        stripped.isBlank() -> "Episodio ${episode.numberLabel}"
        stripped.matches(Regex("""\d+(\.\d+)?""")) -> "Episodio $stripped"
        else -> stripped
    }
}

/** Póster y, debajo, los datos de emisión / tipo / próximo capítulo. */
@Composable
private fun LeftPanel(detail: AnimeDetail) {
    Column(
        modifier = Modifier
            .width(330.dp)
            .fillMaxHeight()
            .padding(start = 28.dp, top = 20.dp, end = 8.dp),
    ) {
        AsyncImage(
            model = detail.posterUrl,
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(230.dp)
                .height(345.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(JkSurfaceHigh),
        )

        Spacer(modifier = Modifier.height(16.dp))

        detail.statusLabel?.let { status ->
            MetaLine(
                label = "Estado",
                value = status,
                valueColor = if (detail.status == AiringStatus.CURRENTLY) {
                    JkGreen
                } else {
                    JkTextPrimary
                },
            )
        }
        detail.type?.let { MetaLine(label = "Tipo", value = it) }
        detail.nextEpisodeLabel?.let { MetaLine(label = "Próx. cap", value = it) }
        detail.totalEpisodes?.takeIf { it > 0 }?.let { MetaLine(label = "Capítulos", value = "$it") }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = buildString {
                detail.genres.take(4).forEach { append(it).append("  ·  ") }
                detail.studios.firstOrNull()?.let { append(it) }
            }.trim().trimEnd('·').trim(),
            style = MaterialTheme.typography.labelSmall,
            color = JkOrange,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

    }
}

@Composable
private fun MetaLine(label: String, value: String, valueColor: Color = JkTextPrimary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = JkTextSecondary,
            modifier = Modifier.width(82.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Estrella de favoritos: rellena si el anime ya está en la lista. */
@Composable
private fun FavoriteStar(
    isFavorite: Boolean,
    onToggle: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .clip(RoundedCornerShape(50))
            .background(if (focused) JkSurfaceHigh else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(50),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(
            text = if (isFavorite) "★" else "☆",
            style = MaterialTheme.typography.headlineMedium,
            color = if (isFavorite) JkOrange else JkTextSecondary,
        )
    }
}
