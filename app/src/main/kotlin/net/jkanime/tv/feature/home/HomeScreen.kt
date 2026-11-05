package net.jkanime.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jkanime.tv.Graph
import net.jkanime.tv.data.local.FavoriteEntity
import net.jkanime.tv.model.AnimeCard
import net.jkanime.tv.model.ProgramacionSection
import net.jkanime.tv.ui.component.ErrorState
import net.jkanime.tv.ui.component.LoadingState
import net.jkanime.tv.ui.component.PosterCard
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Home = "Programación" del sitio, en tarjetas.
 * Requerimiento 1 y 2 del brief.
 */
@Composable
fun HomeScreen(onOpenAnime: (String) -> Unit, onOpenSync: () -> Unit = {}) {
    val context = LocalContext.current
    val library = remember(context) { Graph.library(context) }
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(Graph.repository, library),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val showFavorites by viewModel.showFavorites.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground),
    ) {
        Header(
            selected = section,
            showFavorites = showFavorites,
            onSelect = viewModel::selectSection,
            onToggleFavorites = viewModel::toggleFavorites,
            onOpenSync = onOpenSync,
        )

        if (showFavorites) {
            FavoritesGrid(
                favorites = favorites,
                onOpenAnime = onOpenAnime,
            )
            return@Column
        }

        when (val current = state) {
            is HomeUiState.Loading -> LoadingState()
            is HomeUiState.Error -> ErrorState(
                message = current.message,
                onRetry = { viewModel.refresh(force = true) },
            )
            is HomeUiState.Success -> ProgramacionGrid(
                section = section,
                cards = current.sections[section].orEmpty(),
                onOpenAnime = onOpenAnime,
            )
        }
    }
}

@Composable
private fun Header(
    selected: ProgramacionSection,
    showFavorites: Boolean,
    onSelect: (ProgramacionSection) -> Unit,
    onToggleFavorites: () -> Unit,
    onOpenSync: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "JK TV",
            style = MaterialTheme.typography.headlineSmall,
            color = JkOrange,
        )
        Spacer(modifier = Modifier.width(28.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgramacionSection.entries.forEach { item ->
                TvActionButton(
                    text = item.label,
                    primary = item == selected,
                    onClick = { onSelect(item) },
                )
            }
        }

        Spacer(modifier = Modifier.width(18.dp))

        TvActionButton(
            text = "★ Favoritos",
            primary = showFavorites,
            onClick = onToggleFavorites,
        )

        Spacer(modifier = Modifier.width(10.dp))

        TvActionButton(
            text = "⇄ Sync",
            primary = false,
            onClick = onOpenSync,
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Programación",
            style = MaterialTheme.typography.labelMedium,
            color = JkTextSecondary,
        )
    }
}

@Composable
private fun ProgramacionGrid(
    section: ProgramacionSection,
    cards: List<AnimeCard>,
    onOpenAnime: (String) -> Unit,
) {
    if (cards.isEmpty()) {
        LoadingState(message = "Sin novedades en esta sección")
        return
    }

    val firstCardFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // Al cambiar de sección la grilla vuelve arriba: si no, el scroll de la
    // sección anterior se arrastra y la primera fila queda cortada.
    LaunchedEffect(section) {
        gridState.scrollToItem(0)
    }

    // En TV el foco es obligatorio: sin él la pantalla queda muerta. Se pide
    // DESPUÉS del primer frame (los items del LazyGrid se crean durante la
    // medición) y se reintenta: requestFocus() devuelve false, no lanza, si el
    // nodo aún no está listo.
    LaunchedEffect(cards) {
        repeat(10) {
            withFrameNanos { }
            if (firstCardFocus.requestFocus()) return@LaunchedEffect
        }
        android.util.Log.w("JkFocus", "no se pudo enfocar la primera tarjeta")
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(cards, key = { it.episodeUrl }) { card ->
            PosterCard(
                title = card.title,
                posterUrl = card.posterUrl ?: card.thumbUrl,
                badge = card.episodeNumber?.let { "Ep $it" },
                subtitle = card.dateLabel,
                width = null,
                focusRequester = if (card === cards.first()) firstCardFocus else null,
                onClick = { onOpenAnime(card.slug) },
            )
        }
    }
}

/**
 * Favoritos en tarjetas. El badge "N pend." sale de comparar el último
 * episodio emitido (refrescado por el sync de F5) con el último visto.
 */
@Composable
private fun FavoritesGrid(
    favorites: List<FavoriteEntity>,
    onOpenAnime: (String) -> Unit,
) {
    if (favorites.isEmpty()) {
        LoadingState(message = "Aún no tienes favoritos")
        return
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(favorites.first().slug) {
        repeat(10) {
            withFrameNanos { }
            if (firstFocus.requestFocus()) return@LaunchedEffect
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(favorites, key = { it.slug }) { fav ->
            val pending = fav.pendingEpisodes
            PosterCard(
                title = fav.title,
                posterUrl = fav.posterUrl,
                badge = pending?.takeIf { it > 0 }?.let { "+$it" }
                    ?: fav.lastWatchedEpisode?.let { "Ep $it" },
                subtitle = fav.statusLabel,
                width = null,
                focusRequester = if (fav === favorites.first()) firstFocus else null,
                onClick = { onOpenAnime(fav.slug) },
            )
        }
    }
}
