package com.macastil.mobile.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.mobile.JkGraph
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.theme.JkSurfaceHigh
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onOpenFavorites: () -> Unit = {},
    onOpenSync: () -> Unit = {},
) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(JkGraph.repository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state

    when (current) {
        is HomeUiState.Loading -> {
            androidx.compose.material3.CircularProgressIndicator(Modifier.fillMaxSize())
        }
        is HomeUiState.Error -> {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JkTextSecondary.copy(alpha = 0.7f),
                )
            }
        }
        is HomeUiState.Success -> {
            HomeContent(
                cards = current.cards,
                onOpenDetail = onOpenDetail,
                onOpenFavorites = onOpenFavorites,
                onOpenSync = onOpenSync,
                onRefresh = viewModel::refresh,
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun HomeContent(
    cards: List<AnimeCard>,
    onOpenDetail: (String) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSync: () -> Unit,
    onRefresh: () -> Unit,
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
                title = { Text(text = "jkanime") },
                actions = {
                    androidx.compose.material3.IconButton(onClick = onOpenFavorites) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Favorite,
                            contentDescription = "Favoritos",
                            tint = if (favoriteSlugs.isEmpty()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                androidx.compose.ui.graphics.Color(0xFFFF6B35)
                            },
                        )
                    }
                    var menuOpen by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box {
                        androidx.compose.material3.IconButton(onClick = { menuOpen = true }) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Menu,
                                contentDescription = "Más opciones",
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Actualizar") },
                                onClick = {
                                    menuOpen = false
                                    onRefresh()
                                },
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Sincronización") },
                                onClick = {
                                    menuOpen = false
                                    onOpenSync()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp,
            ),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(cards, key = { it.slug }) { card ->
                PosterCard(
                    title = card.title,
                    posterUrl = card.posterUrl ?: card.thumbUrl,
                    badge = card.episodeNumber?.let { "Ep $it" },
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
