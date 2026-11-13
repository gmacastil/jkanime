package com.macastil.mobile.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.theme.JkSurfaceHigh
import com.macastil.tv.ui.theme.JkTextSecondary
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
        factory = HomeViewModelFactory(JkanimeRepository()),
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
            HomeContent(cards = current.cards, onOpenDetail = onOpenDetail)
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun HomeContent(
    cards: List<AnimeCard>,
    onOpenDetail: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "jkanime mobile") },
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
                    onClick = { onOpenDetail(card.slug) },
                )
            }
        }
    }
}
