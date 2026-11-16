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
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch

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

    // No usamos lastWatchedEpisode para decidir si está al día: ese valor solo
    // representa el capítulo más alto visto y puede ocultar huecos (por ejemplo,
    // vistos 1 y 3, pero falta el 2). Se comprueba cada capítulo publicado.
    val statuses = remember(visible, progress) {
        visible.map { favorite ->
            val released = (favorite.lastAiredEpisode ?: favorite.totalEpisodes)
                ?.takeIf { it > 0 }
            val watched = progress.asSequence()
                .filter { it.slug == favorite.slug && it.completed && it.episode > 0 }
                .map { it.episode }
                .toSet()
            val pending = released?.let { total ->
                (1..total).count { it !in watched }
            }
            FavoriteStatus(favorite, pending, released != null && pending == 0)
        }
    }
    val pending = statuses.filterNot { it.upToDate }
    val upToDate = statuses.filter { it.upToDate }
    val scope = rememberCoroutineScope()

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
                if (pending.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.favorites_section_pending),
                            style = MaterialTheme.typography.labelLarge,
                            color = JkTextSecondary,
                        )
                    }
                    items(pending, key = { status -> "pending-${status.favorite.slug}" }) { status ->
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

private data class FavoriteStatus(
    val favorite: com.macastil.mobile.data.local.FavoriteEntity,
    val pendingCount: Int?,
    val upToDate: Boolean,
)

@Composable
private fun FavoriteCard(
    status: FavoriteStatus,
    onOpenDetail: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val favorite = status.favorite
    PosterCard(
        title = favorite.title,
        posterUrl = favorite.posterUrl,
        badge = status.pendingCount?.takeIf { it > 0 }?.let { "+$it" }
            ?: if (status.upToDate) stringResource(R.string.favorites_badge_up_to_date) else null,
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
