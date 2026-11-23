package com.macastil.mobile.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macastil.mobile.JkGraph
import com.macastil.mobile.R
import com.macastil.tv.catalog.episodeCountsFallback
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch

/**
 * Antigüedad a partir de la cual se vuelve a pedir el detalle de un favorito al
 * abrir Favoritos. Igual que la TV: sin esta guarda se pedían las 60 fichas en
 * cada entrada a la pantalla.
 */
private const val STALE_AFTER_MS = 10 * 60 * 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    val favorites by JkGraph.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val progress by JkGraph.library.progress.collectAsStateWithLifecycle(initialValue = emptyList())
    val visible = favorites
        .filter { it.deletedAt == null }
        .distinctBy { it.slug }
        .sortedBy { it.title.lowercase() }

    // Ver [favoriteStatus]: cuenta cada capítulo publicado (detecta huecos) y
    // usa el mayor entre total y último emitido. Sin datos → Pendientes.
    val statuses = remember(visible, progress) {
        visible.map { favoriteStatus(it, progress) }
    }
    val pending = statuses.filterNot { it.upToDate }
    val latestProgress = progress
        .asSequence()
        .filter { it.updatedAt > 0L && (it.positionMs > 0L || it.completed) }
        .groupBy { it.slug }
        .mapValues { (_, entries) -> entries.maxOf { it.updatedAt } }
    val continueWatching = pending
        .filter { latestProgress.containsKey(it.favorite.slug) }
        .sortedWith(
            compareByDescending<FavoriteStatus> {
                latestProgress[it.favorite.slug] ?: 0L
            }.thenBy { it.favorite.title.lowercase() },
        )
    val pendingWithoutProgress = pending
        .filterNot { latestProgress.containsKey(it.favorite.slug) }
        .sortedBy { it.favorite.title.lowercase() }
    val upToDate = statuses.filter { it.upToDate }
    val scope = rememberCoroutineScope()

    // La única actualización de contadores ocurre al entrar a esta pantalla.
    // No se ejecuta desde Home ni desde cada tarjeta: así evitamos peticiones
    // repetidas y los grupos se recalculan con los datos actuales del sitio.
    //
    // Solo se piden los que llevan más de [STALE_AFTER_MS] sin sincronizar: con
    // el rate limit del cliente (1 petición / 700 ms) refrescar los 60 favoritos
    // en cada entrada costaba casi un minuto de red para nada.
    LaunchedEffect(Unit) {
        val now = System.currentTimeMillis()
        JkGraph.library.getAllFavorites()
            .filter { it.deletedAt == null && now - it.syncedAt > STALE_AFTER_MS }
            .forEach { favorite ->
                    runCatching { JkGraph.repository.detail(favorite.slug, force = true) }
                    .onSuccess { detail ->
                        // Fichas sin "Episodios" ni `a#uep`: el total solo está
                        // en el AJAX de episodios (ver [episodeCountsFallback]).
                        val fallback = runCatching {
                            JkGraph.repository.episodeCountsFallback(detail)
                        }.getOrNull()
                        JkGraph.library.updateFavoriteCounters(
                            slug = favorite.slug,
                            lastAiredEpisode = detail.lastEpisodeNumber?.takeIf { it > 0 }
                                ?: fallback?.lastAiredEpisode,
                            totalEpisodes = detail.totalEpisodes?.takeIf { it > 0 }
                                ?: fallback?.totalEpisodes
                                ?: favorite.totalEpisodes,
                            statusLabel = detail.statusLabel,
                        )
                    }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title, visible.size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_volver))
                    }
                },
            )
        },
    ) { padding ->
        if (statuses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.favorites_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.favorites_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.favorites_section_continue_watching),
                            style = MaterialTheme.typography.labelLarge,
                            color = JkTextSecondary,
                        )
                    }
                    items(
                        continueWatching,
                        key = { status -> "continue-${status.favorite.slug}" },
                    ) { status ->
                        FavoriteCard(status, onOpenDetail, scope)
                    }
                }
                if (pendingWithoutProgress.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.favorites_section_pending),
                            style = MaterialTheme.typography.labelLarge,
                            color = JkTextSecondary,
                        )
                    }
                    items(
                        pendingWithoutProgress,
                        key = { status -> "pending-${status.favorite.slug}" },
                    ) { status ->
                        FavoriteCard(status, onOpenDetail, scope)
                    }
                }
                if (pending.isNotEmpty() && upToDate.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("", modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                if (upToDate.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.favorites_section_up_to_date),
                            style = MaterialTheme.typography.labelLarge,
                            color = JkTextSecondary,
                        )
                    }
                    items(upToDate, key = { status -> "uptodate-${status.favorite.slug}" }) { status ->
                        FavoriteCard(status, onOpenDetail, scope)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(
    status: FavoriteStatus,
    onOpenDetail: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val favorite = status.favorite
    // Sin datos de emisión no hay badge de pendientes; la TV muestra en su
    // lugar el último capítulo visto ("Ep N"), así que replicamos ese criterio.
    val badge = when {
        status.pendingCount == null ->
            favorite.lastWatchedEpisode?.let { stringResource(R.string.favorites_badge_episode, it) }
        status.pendingCount > 0 -> "+${status.pendingCount}"
        else -> stringResource(R.string.favorites_badge_up_to_date)
    }
    PosterCard(
        title = favorite.title,
        posterUrl = favorite.posterUrl,
        badge = badge,
        badgeColor = if (status.upToDate) Color(0xFF3DDC84) else Color(0xFFFF6B35),
        subtitle = favorite.statusLabel,
        subtitleColor = when {
            favorite.statusLabel?.contains("emisi", true) == true -> Color(0xFFFF6B35)
            favorite.statusLabel?.contains("conclu", true) == true -> Color(0xFF3DDC84)
            else -> null
        },
        width = null,
        isFavorite = true,
        onClick = { onOpenDetail(favorite.slug) },
        onLongClick = {
            scope.launch {
                JkGraph.library.toggleFavorite(favorite.slug, favorite.title, favorite.posterUrl)
            }
        },
    )
}
