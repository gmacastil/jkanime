package net.jkanime.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
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
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Home = "Programación" del sitio, en tarjetas.
 * Requerimiento 1 y 2 del brief, más búsqueda sobre lo ya cargado.
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
    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    val searchFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val favoritesTabFocus = remember { FocusRequester() }

    // Con la lista de favoritos vacía no hay ninguna tarjeta que enfocar y el
    // D-pad se quedaba muerto. Se devuelve el foco a la pestaña "Favoritos".
    LaunchedEffect(showFavorites, favorites.isEmpty()) {
        if (!showFavorites || favorites.isNotEmpty()) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (favoritesTabFocus.requestFocus()) return@LaunchedEffect
        }
    }
    LaunchedEffect(searchOpen) {
        if (!searchOpen) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (searchFocus.requestFocus()) return@LaunchedEffect
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground),
    ) {
        Header(
            selected = section,
            showFavorites = showFavorites,
            searchOpen = searchOpen,
            favoritesFocus = favoritesTabFocus,
            onSelect = viewModel::selectSection,
            onToggleFavorites = viewModel::toggleFavorites,
            onToggleSearch = {
                if (searchOpen) viewModel.closeSearch() else viewModel.openSearch()
            },
            onOpenSync = onOpenSync,
        )

        if (searchOpen) {
            SearchBar(
                query = query,
                resultCount = results.size,
                focusRequester = searchFocus,
                onQueryChange = viewModel::setQuery,
                // El foco baja a la primera tarjeta; si aún no existe (aún no
                // hay resultados) no pasa nada.
                onMoveDown = { runCatching { resultsFocus.requestFocus() } },
                onClose = viewModel::closeSearch,
            )
        }

        when {
            searchOpen -> CardsGrid(
                cards = results,
                emptyMessage = if (query.isBlank()) {
                    "Escribe para buscar en Programación"
                } else {
                    "Sin resultados para \"$query\""
                },
                resetKey = query,
                showSection = true,
                autoFocusFirst = false,
                firstFocusRequester = resultsFocus,
                onOpenAnime = onOpenAnime,
            )

            showFavorites -> FavoritesGrid(
                favorites = favorites,
                onOpenAnime = onOpenAnime,
            )

            else -> when (val current = state) {
                is HomeUiState.Loading -> LoadingState()
                is HomeUiState.Error -> ErrorState(
                    message = current.message,
                    onRetry = { viewModel.refresh(force = true) },
                )
                is HomeUiState.Success -> CardsGrid(
                    cards = current.sections[section].orEmpty(),
                    emptyMessage = "Sin novedades en esta sección",
                    resetKey = section,
                    showSection = false,
                    onOpenAnime = onOpenAnime,
                )
            }
        }
    }
}

@Composable
private fun Header(
    selected: ProgramacionSection,
    showFavorites: Boolean,
    searchOpen: Boolean,
    favoritesFocus: FocusRequester,
    onSelect: (ProgramacionSection) -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleSearch: () -> Unit,
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
        Spacer(modifier = Modifier.width(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgramacionSection.entries.forEach { item ->
                TvActionButton(
                    text = item.label,
                    primary = item == selected && !searchOpen && !showFavorites,
                    onClick = { onSelect(item) },
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        TvActionButton(
            text = "🔍 Buscar",
            primary = searchOpen,
            onClick = onToggleSearch,
        )

        Spacer(modifier = Modifier.width(10.dp))

        TvActionButton(
            text = "★ Favoritos",
            primary = showFavorites,
            modifier = Modifier.focusRequester(favoritesFocus),
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

/**
 * Campo de búsqueda. Se usa `BasicTextField` y no un `TextField` de Material
 * porque en Compose for TV no existe; al enfocarlo, el teclado en pantalla del
 * sistema (el de Leanback) se abre con el D-pad.
 */
@Composable
private fun SearchBar(
    query: String,
    resultCount: Int,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onMoveDown: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // El Box intercepta la flecha abajo en fase "preview": un
        // BasicTextField de una línea consume DirectionDown para mover el
        // cursor y nunca deja que el foco baje a la grilla. Desde el padre
        // (preview = de padre a hijo) sí llegamos antes que el campo.
        Box(
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            },
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = JkTextPrimary),
                cursorBrush = SolidColor(JkOrange),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .width(560.dp)
                    .focusRequester(focusRequester)
                    .clip(RoundedCornerShape(8.dp))
                    .background(JkSurfaceHigh)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = "Buscar anime…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JkTextSecondary,
                        )
                    }
                    inner()
                },
            )
        }

        Text(
            text = if (query.isBlank()) {
                "Escribe y los resultados se filtran al instante"
            } else {
                "$resultCount resultado(s) · ▼ para ir a la grilla"
            },
            style = MaterialTheme.typography.labelSmall,
            color = JkTextSecondary,
        )

        Spacer(modifier = Modifier.weight(1f))

        TvActionButton(text = "Cerrar", primary = false, onClick = onClose)
    }
}

/**
 * Grilla de tarjetas de Programación. `showSection` añade el origen
 * (Animes/Donghuas/OVAs) al subtítulo, útil en los resultados de búsqueda.
 *
 * `autoFocusFirst` se desactiva mientras se escribe: si la grilla robara el
 * foco al cambiar los resultados, el campo de búsqueda dejaría de recibir
 * teclas a la segunda letra.
 */
@Composable
private fun CardsGrid(
    cards: List<AnimeCard>,
    emptyMessage: String,
    resetKey: Any,
    showSection: Boolean,
    autoFocusFirst: Boolean = true,
    firstFocusRequester: FocusRequester? = null,
    onOpenAnime: (String) -> Unit,
) {
    if (cards.isEmpty()) {
        LoadingState(message = emptyMessage)
        return
    }

    val firstCardFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // Al cambiar de sección (o de búsqueda) la grilla vuelve arriba: si no, el
    // scroll anterior se arrastra y la primera fila queda cortada.
    LaunchedEffect(resetKey) {
        gridState.scrollToItem(0)
    }

    // En TV el foco es obligatorio: sin él la pantalla queda muerta. Se pide
    // DESPUÉS del primer frame (los items del LazyGrid se crean durante la
    // medición) y se reintenta: requestFocus() devuelve false, no lanza, si el
    // nodo aún no está listo.
    LaunchedEffect(cards, autoFocusFirst) {
        if (!autoFocusFirst) return@LaunchedEffect
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
        items(cards, key = { it.section.name + it.episodeUrl }) { card ->
            PosterCard(
                title = card.title,
                posterUrl = card.posterUrl ?: card.thumbUrl,
                badge = card.episodeNumber?.let { "Ep $it" },
                subtitle = listOfNotNull(
                    card.section.label.takeIf { showSection },
                    card.dateLabel,
                ).joinToString(" · ").ifBlank { null },
                width = null,
                focusRequester = if (card === cards.first()) {
                    firstFocusRequester ?: firstCardFocus
                } else {
                    null
                },
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
