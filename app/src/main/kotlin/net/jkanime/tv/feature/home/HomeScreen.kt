package net.jkanime.tv.feature.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import net.jkanime.tv.ui.component.TvIconButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary

/**
 * Home = "Programación" del sitio, en tarjetas.
 *
 * Cabecera: botón hamburguesa + marca + campo de búsqueda siempre visible
 * (los resultados se filtran en memoria sobre lo ya cargado). Los filtros
 * (Animes / Donghuas / OVAs / Favoritos / Sync) viven en un menú lateral
 * colapsable para dejarle el ancho a la grilla.
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
    val drawerOpen by viewModel.drawerOpen.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()

    val searchFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val firstDrawerItemFocus = remember { FocusRequester() }

    // Cada vez que se elige algo en el menú hay que devolver el foco a la
    // grilla: el ítem pulsado desaparece del árbol y el D-pad queda muerto.
    var focusNonce by remember { mutableIntStateOf(0) }
    var drawerWasOpen by remember { mutableStateOf(false) }

    val sections = (state as? HomeUiState.Success)?.sections
    val contentEmpty = when {
        query.isNotBlank() -> results.isEmpty()
        showFavorites -> favorites.isEmpty()
        else -> sections?.get(section).isNullOrEmpty()
    }

    // Con la lista de favoritos vacía no hay ninguna tarjeta que enfocar y el
    // D-pad se quedaba muerto. Se devuelve el foco al botón del menú.
    LaunchedEffect(showFavorites, favorites.isEmpty()) {
        if (!showFavorites || favorites.isNotEmpty()) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (menuFocus.requestFocus()) return@LaunchedEffect
        }
    }

    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            drawerWasOpen = true
            repeat(10) {
                withFrameNanos { }
                if (firstDrawerItemFocus.requestFocus()) return@LaunchedEffect
            }
        } else if (drawerWasOpen) {
            // Se deja un par de frames a la grilla para que tome el foco; si
            // no hay nada que enfocar, al menos el menú queda alcanzable.
            repeat(4) { withFrameNanos { } }
            if (contentEmpty) menuFocus.requestFocus()
        }
    }

    BackHandler(enabled = drawerOpen) { viewModel.closeDrawer() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                drawerOpen = drawerOpen,
                query = query,
                resultCount = results.size,
                searchFocus = searchFocus,
                menuFocus = menuFocus,
                onToggleDrawer = {
                    if (drawerOpen) viewModel.closeDrawer() else viewModel.openDrawer()
                },
                onQueryChange = viewModel::setQuery,
                // El foco baja a la primera tarjeta; si aún no existe (aún no
                // hay resultados) no pasa nada.
                onMoveDown = { runCatching { resultsFocus.requestFocus() } },
            )

            when {
                query.isNotBlank() -> CardsGrid(
                    cards = results,
                    emptyMessage = "Sin resultados para \"$query\"",
                    resetKey = query,
                    showSection = true,
                    autoFocusFirst = false,
                    firstFocusRequester = resultsFocus,
                    focusNonce = focusNonce,
                    onOpenAnime = onOpenAnime,
                )

                showFavorites -> FavoritesGrid(
                    favorites = favorites,
                    focusNonce = focusNonce,
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
                        focusNonce = focusNonce,
                        onOpenAnime = onOpenAnime,
                    )
                }
            }
        }

        if (drawerOpen) {
            // Velo sobre el contenido: deja claro que el menú tiene el foco.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
            )

            SideDrawer(
                selected = section,
                showFavorites = showFavorites,
                firstItemFocus = firstDrawerItemFocus,
                onSelectSection = {
                    viewModel.selectSection(it)
                    focusNonce++
                },
                onFavorites = {
                    viewModel.openFavorites()
                    focusNonce++
                },
                onSync = {
                    viewModel.closeDrawer()
                    onOpenSync()
                },
            )
        }
    }
}

/**
 * Cabecera: hamburguesa, marca y buscador fijo. Sin fila de botones: los
 * filtros están en el menú lateral.
 */
@Composable
private fun Header(
    drawerOpen: Boolean,
    query: String,
    resultCount: Int,
    searchFocus: FocusRequester,
    menuFocus: FocusRequester,
    onToggleDrawer: () -> Unit,
    onQueryChange: (String) -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIconButton(
            icon = "☰",
            active = drawerOpen,
            focusRequester = menuFocus,
            onClick = onToggleDrawer,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "JK TV",
            style = MaterialTheme.typography.headlineSmall,
            color = JkOrange,
        )
        Spacer(modifier = Modifier.width(24.dp))

        SearchField(
            query = query,
            resultCount = resultCount,
            focusRequester = searchFocus,
            onQueryChange = onQueryChange,
            onMoveDown = onMoveDown,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Campo de búsqueda permanente, con forma de píldora (estilo widget de
 * búsqueda). Se usa `BasicTextField` y no un `TextField` de Material porque en
 * Compose for TV no existe; al enfocarlo, el teclado en pantalla del sistema
 * (el de Leanback) se abre con el D-pad.
 */
@Composable
private fun SearchField(
    query: String,
    resultCount: Int,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(JkSurfaceHigh)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(26.dp),
            )
            .onFocusChanged { focused = it.hasFocus }
            // El BasicTextField de una línea consume DirectionDown para mover
            // el cursor y nunca deja que el foco baje a la grilla. Desde el
            // padre (preview = de padre a hijo) sí llegamos antes que el campo.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "🔍",
            style = MaterialTheme.typography.bodyMedium,
            color = JkTextSecondary,
        )
        Spacer(modifier = Modifier.width(12.dp))

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = JkTextPrimary),
            cursorBrush = SolidColor(JkOrange),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Buscar anime…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = JkTextSecondary,
                        )
                    }
                    inner()
                }
            },
        )

        if (query.isNotEmpty()) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (resultCount == 0) "sin resultados" else "$resultCount",
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary,
            )
        }
    }
}

/** Menú lateral colapsable con los filtros y accesos. */
@Composable
private fun SideDrawer(
    selected: ProgramacionSection,
    showFavorites: Boolean,
    firstItemFocus: FocusRequester,
    onSelectSection: (ProgramacionSection) -> Unit,
    onFavorites: () -> Unit,
    onSync: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(JkSurface)
            .border(width = 1.dp, color = JkSurfaceHigh)
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "MENÚ",
            style = MaterialTheme.typography.labelSmall,
            color = JkTextSecondary,
            modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
        )

        ProgramacionSection.entries.forEachIndexed { index, item ->
            DrawerItem(
                label = item.label,
                selected = item == selected && !showFavorites,
                focusRequester = if (index == 0) firstItemFocus else null,
                onClick = { onSelectSection(item) },
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(JkSurfaceHigh),
        )
        Spacer(modifier = Modifier.height(6.dp))

        DrawerItem(
            label = "★  Favoritos",
            selected = showFavorites,
            onClick = onFavorites,
        )
        DrawerItem(
            label = "⇄  Sync",
            selected = false,
            onClick = onSync,
        )
    }
}

@Composable
private fun DrawerItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        focused -> JkSurfaceHigh
        selected -> JkOrange
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            // El FocusRequester va antes del focusable (clickable).
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                focused -> JkOrange
                selected -> Color(0xFF141414)
                else -> JkTextPrimary
            },
        )
    }
}

/**
 * Grilla de tarjetas de Programación. `showSection` añade el origen
 * (Animes/Donghuas/OVAs) al subtítulo, útil en los resultados de búsqueda.
 *
 * `autoFocusFirst` se desactiva mientras se escribe: si la grilla robara el
 * foco al cambiar los resultados, el campo de búsqueda dejaría de recibir
 * teclas a la segunda letra.
 *
 * `focusNonce` cambia al elegir algo en el menú lateral; obliga a repedir el
 * foco aunque las tarjetas sean las mismas (el ítem del menú desaparece del
 * árbol y el D-pad quedaría muerto).
 */
@Composable
private fun CardsGrid(
    cards: List<AnimeCard>,
    emptyMessage: String,
    resetKey: Any,
    showSection: Boolean,
    autoFocusFirst: Boolean = true,
    firstFocusRequester: FocusRequester? = null,
    focusNonce: Int = 0,
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
    LaunchedEffect(cards, autoFocusFirst, focusNonce) {
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
    focusNonce: Int = 0,
    onOpenAnime: (String) -> Unit,
) {
    if (favorites.isEmpty()) {
        LoadingState(message = "Aún no tienes favoritos")
        return
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(favorites.first().slug, focusNonce) {
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
