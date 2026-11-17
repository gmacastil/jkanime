package com.macastil.mobile.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
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
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onOpenFavorites: () -> Unit = {},
    onOpenSync: () -> Unit = {},
    onLanguageChanged: (String) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(JkGraph.repository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

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
        when (val current = state) {
            is HomeUiState.Loading -> {
                androidx.compose.material3.CircularProgressIndicator(Modifier.fillMaxSize())
            }

            is HomeUiState.Error -> {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = current.message
                                ?: stringResource(R.string.common_error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = JkTextSecondary.copy(alpha = 0.7f),
                        )
                        androidx.compose.material3.TextButton(onClick = viewModel::refresh) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            }

            is HomeUiState.Success -> {
                HomeContent(
                    cards = current.cards,
                    onOpenDetail = onOpenDetail,
                    onOpenFavorites = onOpenFavorites,
                    onOpenMenu = { scope.launch { drawerState.open() } },
                )
            }
        }
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
    cards: List<AnimeCard>,
    onOpenDetail: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val favorites by JkGraph.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteSlugs = remember(favorites) {
        favorites.filter { it.deletedAt == null }.map { it.slug }.toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.home_menu_cd),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenFavorites) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.home_favorites_cd),
                            tint = if (favoriteSlugs.isEmpty()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                androidx.compose.ui.graphics.Color(0xFFFF6B35)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        // El `padding` del Scaffold es el alto de la TopAppBar: sin aplicarlo
        // la grilla empieza en y=0 y la primera fila de pósters queda tapada
        // por la barra (el "los primeros salen cortados" que reportó el
        // usuario).
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
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
}
