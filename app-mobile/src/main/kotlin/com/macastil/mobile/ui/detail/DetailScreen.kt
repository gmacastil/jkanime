package com.macastil.mobile.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.Episode
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    slug: String,
    onPlay: (String, Int, String, String) -> Unit,
) {
    val viewModel: DetailViewModel = viewModel(
        factory = DetailViewModelFactory(JkanimeRepository(), slug),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state

    when (current) {
        is DetailUiState.Loading -> {
            androidx.compose.material3.CircularProgressIndicator(Modifier.fillMaxSize())
        }
        is DetailUiState.Error -> {
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
        is DetailUiState.Success -> {
            DetailContent(
                detail = current.detail,
                episodes = current.episodes,
                onPlay = onPlay,
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun DetailContent(
    detail: AnimeDetail,
    episodes: List<Episode>,
    onPlay: (String, Int, String, String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = detail.title) },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.headlineMedium,
                color = JkTextPrimary,
            )
            detail.synopsis?.let { synopsis ->
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JkTextSecondary,
                )
            }

            Text(
                text = "Capítulos (${episodes.size})",
                style = MaterialTheme.typography.titleMedium,
                color = JkTextPrimary,
                modifier = Modifier.padding(top = 24.dp),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp),
            ) {
                itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                    PosterCard(
                        title = episode.numberLabel,
                        posterUrl = episode.thumbUrl,
                        subtitle = episode.title,
                        width = null,
                        onClick = {
                            onPlay(detail.slug, episode.number.toInt(), episode.url, episode.title)
                        },
                    )
                }
            }
        }
    }
}
