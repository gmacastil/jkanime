package com.macastil.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.mobile.BuildConfig
import com.macastil.mobile.JkGraph
import com.macastil.mobile.R
import com.macastil.mobile.data.local.AppLocale
import com.macastil.mobile.ui.menu.AppDrawerContent
import com.macastil.mobile.ui.menu.LanguageDialog
import com.macastil.mobile.ui.menu.RenameProfileDialog
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.AnimeSearchHit
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.component.airingStatusColor
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onOpenFavorites: () -> Unit = {},
    onOpenDirectorio: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onLanguageChanged: (String) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(JkGraph.repository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showLanguage by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    val profileName by JkGraph.profile.name.collectAsStateWithLifecycle()
    val credentials by JkGraph.library.credentials.collectAsStateWithLifecycle(initialValue = null)
    val language = remember(showLanguage) { AppLocale.current(context) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawerContent(
                profileName = profileName,
                languageCode = language,
                syncSpace = credentials?.code,
                versionName = BuildConfig.VERSION_NAME,
                onRenameProfile = {
                    scope.launch { drawerState.close() }
                    showRename = true
                },
                onChangeLanguage = {
                    scope.launch { drawerState.close() }
                    showLanguage = true
                },
                onOpenSync = {
                    scope.launch { drawerState.close() }
                    onOpenSync()
                },
                onOpenDirectorio = {
                    scope.launch { drawerState.close() }
                    onOpenDirectorio()
                },
                onOpenFavorites = {
                    scope.launch { drawerState.close() }
                    onOpenFavorites()
                },
                onRefresh = {
                    scope.launch { drawerState.close() }
                    viewModel.refresh()
                },
                onCheckUpdate = {
                    scope.launch { drawerState.close() }
                    onCheckUpdate()
                },
            )
        },
    ) {
        HomeContent(
            catalog = state,
            search = search,
            onQueryChange = viewModel::onQueryChange,
            onClearQuery = viewModel::clearQuery,
            onRetryCatalog = viewModel::refresh,
            onOpenDetail = onOpenDetail,
            onOpenFavorites = onOpenFavorites,
            onOpenMenu = { scope.launch { drawerState.open() } },
        )
    }

    if (showLanguage) {
        LanguageDialog(
            current = AppLocale.current(context),
            onSelect = { code ->
                showLanguage = false
                AppLocale.set(context, code)
                onLanguageChanged(code)
            },
            onDismiss = { showLanguage = false },
        )
    }

    if (showRename) {
        RenameProfileDialog(
            current = profileName,
            onSave = { value ->
                JkGraph.profile.rename(value)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeContent(
    catalog: HomeUiState,
    search: SearchUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRetryCatalog: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val favorites by JkGraph.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteSlugs = remember(favorites) {
        favorites.filter { it.deletedAt == null }.map { it.slug }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.home_menu_cd),
                        )
                    }
                },
                // El campo de búsqueda vive en la cabecera, igual que en la TV:
                // al escribir se consulta el buscador real del sitio y la
                // grilla pasa a mostrar los resultados.
                title = {
                    SearchField(
                        query = search.query,
                        onQueryChange = onQueryChange,
                        onClear = onClearQuery,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenFavorites) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.home_favorites_cd),
                            tint = if (favoriteSlugs.isEmpty()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color(0xFFFF6B35)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (search.active) {
                SearchStatus(search)
            } else if (search.query.isNotBlank()) {
                // Menos de MIN_QUERY letras: se avisa en vez de mostrar la
                // Programación como si la búsqueda hubiera fallado.
                Text(
                    text = stringResource(
                        R.string.home_search_min_query,
                        SearchUiState.MIN_QUERY,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = JkTextSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }

            when {
                search.active -> SearchResults(
                    search = search,
                    favoriteSlugs = favoriteSlugs,
                    onOpenDetail = onOpenDetail,
                )

                catalog is HomeUiState.Loading -> CircularProgressIndicator(
                    Modifier.fillMaxSize(),
                )

                catalog is HomeUiState.Error -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = catalog.message
                                ?: stringResource(R.string.common_error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = JkTextSecondary.copy(alpha = 0.7f),
                        )
                        TextButton(onClick = onRetryCatalog) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }

                catalog is HomeUiState.Success -> CatalogGrid(
                    cards = catalog.cards,
                    favoriteSlugs = favoriteSlugs,
                    onOpenDetail = onOpenDetail,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                text = stringResource(R.string.home_search_placeholder),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.home_search_clear_cd),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Línea de estado del buscador: buscando, N resultados o sin resultados. */
@Composable
private fun SearchStatus(search: SearchUiState) {
    val text = when {
        search.loading -> stringResource(R.string.home_search_loading)
        search.failed -> stringResource(R.string.common_error_generic)
        search.hits.isEmpty() -> stringResource(R.string.home_search_none, search.query)
        else -> stringResource(R.string.home_search_count, search.hits.size)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = JkTextSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun SearchResults(
    search: SearchUiState,
    favoriteSlugs: Set<String>,
    onOpenDetail: (String) -> Unit,
) {
    if (search.loading && search.hits.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val scope = rememberCoroutineScope()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(search.hits, key = { it.slug }) { hit ->
            SearchCard(
                hit = hit,
                isFavorite = hit.slug in favoriteSlugs,
                onClick = { onOpenDetail(hit.slug) },
                onLongClick = {
                    scope.launch {
                        JkGraph.library.toggleFavorite(
                            slug = hit.slug,
                            title = hit.title,
                            posterUrl = hit.posterUrl,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchCard(
    hit: AnimeSearchHit,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    PosterCard(
        title = hit.title,
        posterUrl = hit.posterUrl,
        badge = hit.type,
        // En las grillas de búsqueda los títulos largos parten las filas.
        titleMaxLines = 1,
        subtitle = hit.statusLabel,
        subtitleColor = airingStatusColor(hit.statusLabel),
        width = null,
        isFavorite = isFavorite,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun CatalogGrid(
    cards: List<AnimeCard>,
    favoriteSlugs: Set<String>,
    onOpenDetail: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // El `padding` del Scaffold ya lo aplica el contenedor de arriba: aquí solo
    // queda el margen interno de la grilla.
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(cards, key = { it.slug }) { card ->
            PosterCard(
                title = card.title,
                posterUrl = card.posterUrl ?: card.thumbUrl,
                badge = card.episodeNumber?.let {
                    stringResource(R.string.detail_episode, it)
                },
                subtitle = card.dateLabel,
                width = null,
                isFavorite = card.slug in favoriteSlugs,
                onClick = { onOpenDetail(card.slug) },
                onLongClick = {
                    scope.launch {
                        JkGraph.library.toggleFavorite(
                            slug = card.slug,
                            title = card.title,
                            posterUrl = card.posterUrl ?: card.thumbUrl,
                        )
                    }
                },
            )
        }
    }
}
