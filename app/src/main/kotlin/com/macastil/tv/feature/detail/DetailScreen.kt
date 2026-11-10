package com.macastil.tv.feature.detail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.macastil.tv.Graph
import com.macastil.tv.R
import com.macastil.tv.data.local.ProgressEntity
import com.macastil.tv.model.AiringStatus
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.Episode
import com.macastil.tv.ui.component.ChapterRow
import com.macastil.tv.ui.component.ErrorState
import com.macastil.tv.ui.component.LoadingState
import com.macastil.tv.ui.component.SortArrowsIcon
import com.macastil.tv.ui.component.TvActionButton
import com.macastil.tv.ui.theme.JkBackground
import com.macastil.tv.ui.theme.JkGreen
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkSurface
import com.macastil.tv.ui.theme.JkSurfaceHigh
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary

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
        factory = DetailViewModel.factory(Graph.repository, library, slug, context),
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
    val sortFocus = remember { FocusRequester() }

    // Calcula el índice del capítulo en el que enfocar:
    // 1) Si hay resumeEpisode (empezado y sin terminar), ése.
    // 2) Si no, el capítulo MÁS ALTO que esté visto (completed) o en curso.
    // 3) Si no hay ninguno, el primero (index 0).
    val initialFocusIndex = remember(sorted, resumeEpisode, progressByEpisode, descending) {
        if (resumeEpisode != null) {
            sorted.indexOfFirst { it.id == resumeEpisode.id }
        } else {
            // Buscar el episodio con número más alto que tenga progreso
            val withProgress = sorted.filterIndexed { _, ep ->
                val prog = progressByEpisode[ep.number.toInt()]
                (prog?.completed == true) || ((prog?.positionMs ?: 0L) > 0L)
            }
            if (withProgress.isNotEmpty()) {
                // El más alto (último en la lista ascendente = número más alto)
                sorted.indexOf(withProgress.maxByOrNull { it.number }!!)
            } else {
                0
            }
        }
    }.takeIf { it >= 0 } ?: 0

    // El foco inicial va al capítulo calculado; si no hay capítulos, a la
    // estrella de favoritos. La clave NO incluye el orden: al tocar ⇅ el
    // foco debe quedarse en el icono para poder volver a invertir.
    LaunchedEffect(detail.slug, sorted.isEmpty(), initialFocusIndex) {
        repeat(10) {
            withFrameNanos { }
            val ok = if (sorted.isEmpty()) starFocus.requestFocus()
            else {
                // El primer capítulo ya está compuesto al entrar al detalle.
                // Pedirlo explícitamente evita que el foco caiga en la
                // estrella y hace que DPAD_UP llegue al botón de orden.
                firstChapterFocus.requestFocus()
            }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    text = stringResource(R.string.detail_chapters, episodes.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = JkTextPrimary,
                )
                // Ordenamiento: flechas arriba/abajo pegadas al título de la
                // lista (antes era un botón con texto que ocupaba media fila).
                SortButton(
                    descending = descending,
                    focusRequester = sortFocus,
                    downFocusRequester = firstChapterFocus,
                    onClick = {
                        onToggleSort()
                        // La lista cambia de composición al instante. Pedir
                        // de nuevo el foco mantiene el D-pad sobre el control
                        // y permite invertir el orden varias veces seguidas.
                        sortFocus.requestFocus()
                    },
                )
                resumeEpisode?.let { episode ->
                    TvActionButton(
                        text = stringResource(R.string.detail_episode_resume, episode.numberLabel),
                        primary = true,
                        compact = true,
                        icon = Icons.Filled.PlayArrow,
                        onClick = {
                            onPlay(detail.slug, episode.number.toInt(), episode.url, episode.title)
                        },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (descending) {
                        stringResource(R.string.detail_sort_newest_first)
                    } else {
                        stringResource(R.string.detail_sort_oldest_first)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = JkTextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (sorted.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.detail_no_chapters),
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
                            modifier = if (index == 0) {
                                Modifier.focusProperties { up = sortFocus }
                            } else {
                                Modifier
                            },
                            focusRequester = if (index == initialFocusIndex) firstChapterFocus else null,
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
                                    text = if (loadingMore) {
                                        stringResource(R.string.common_loading)
                                    } else {
                                        stringResource(R.string.detail_load_more)
                                    },
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
 *
 * Es `@Composable` (y no una función pura) para poder resolver "Episodio …"
 * desde recursos; se llama desde el contenido de `itemsIndexed`, que sí está
 * en contexto de composición.
 */
@Composable
private fun chapterTitle(episode: Episode, animeTitle: String): String {
    val raw = episode.title.trim()
    val stripped = listOf("$animeTitle - ", "$animeTitle – ", "$animeTitle: ")
        .firstOrNull { raw.startsWith(it, ignoreCase = true) }
        ?.let { raw.substring(it.length) }
        ?.trim()
        ?: raw
    return when {
        stripped.isBlank() -> stringResource(R.string.chapter_episode, episode.numberLabel)
        stripped.matches(Regex("""\d+(\.\d+)?""")) ->
            stringResource(R.string.chapter_episode, stripped)
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
                label = stringResource(R.string.detail_meta_status),
                value = status,
                valueColor = if (detail.status == AiringStatus.CURRENTLY) {
                    JkGreen
                } else {
                    JkTextPrimary
                },
            )
        }
        detail.type?.let {
            MetaLine(label = stringResource(R.string.detail_meta_type), value = it)
        }
        detail.nextEpisodeLabel?.let {
            MetaLine(label = stringResource(R.string.detail_meta_next_episode), value = it)
        }
        detail.totalEpisodes?.takeIf { it > 0 }?.let {
            MetaLine(label = stringResource(R.string.detail_meta_episodes), value = "$it")
        }

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

/** Botón de orden: flechas Material apiladas, con el estado en el color. */
@Composable
private fun SortButton(
    descending: Boolean,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val tint = when {
        focused -> JkOrange
        descending -> JkOrange
        else -> JkTextPrimary
    }

    Box(
        modifier = Modifier
            // Debe ir antes de clickable: clickable crea el focus target.
            .focusRequester(focusRequester)
            .focusProperties { down = downFocusRequester }
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) JkSurfaceHigh else JkSurface)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        SortArrowsIcon(tint = tint)
    }
}

/** Corazón de favoritos, compacto y alineado con el título. */
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
            .size(38.dp)
            .clip(CircleShape)
            .background(if (focused) JkSurfaceHigh else Color.Transparent)
            .border(
                width = 2.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = if (isFavorite) {
                stringResource(R.string.detail_favorite_remove_cd)
            } else {
                stringResource(R.string.detail_favorite_add_cd)
            },
            tint = if (isFavorite) JkOrange else JkTextSecondary.copy(alpha = 0.8f),
            modifier = Modifier.size(24.dp),
        )
    }
}
