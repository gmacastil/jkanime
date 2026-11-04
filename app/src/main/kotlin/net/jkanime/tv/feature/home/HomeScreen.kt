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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jkanime.tv.Graph
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
fun HomeScreen(onOpenAnime: (String) -> Unit) {
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(Graph.repository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground),
    ) {
        Header(
            selected = section,
            onSelect = viewModel::selectSection,
        )

        when (val current = state) {
            is HomeUiState.Loading -> LoadingState()
            is HomeUiState.Error -> ErrorState(
                message = current.message,
                onRetry = { viewModel.refresh(force = true) },
            )
            is HomeUiState.Success -> ProgramacionGrid(
                cards = current.sections[section].orEmpty(),
                onOpenAnime = onOpenAnime,
            )
        }
    }
}

@Composable
private fun Header(
    selected: ProgramacionSection,
    onSelect: (ProgramacionSection) -> Unit,
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
    cards: List<AnimeCard>,
    onOpenAnime: (String) -> Unit,
) {
    if (cards.isEmpty()) {
        LoadingState(message = "Sin novedades en esta sección")
        return
    }

    val firstCardFocus = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }
    val firstUrl = cards.first().episodeUrl

    LaunchedEffect(cards) {
        if (!focusRequested) {
            focusRequested = true
            runCatching { firstCardFocus.requestFocus() }
        }
    }

    LazyVerticalGrid(
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
                focusRequester = if (card.episodeUrl == firstUrl) firstCardFocus else null,
                onClick = { onOpenAnime(card.slug) },
            )
        }
    }
}
