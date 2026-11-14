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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.macastil.mobile.JkGraph
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
    val visible = favorites
        .filter { it.deletedAt == null }
        .distinctBy { it.slug }
        .sortedBy { it.title.lowercase() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favoritos (${visible.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Todavía no tienes favoritos",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Mantén pulsada una tarjeta en Inicio para añadirla.",
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
                items(visible, key = { it.slug }) { favorite ->
                    val pending = favorite.pendingEpisodes
                    PosterCard(
                        title = favorite.title,
                        posterUrl = favorite.posterUrl,
                        badge = pending?.takeIf { it > 0 }?.let { "+$it" },
                        badgeColor = Color(0xFF3DDC84),
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
                                JkGraph.library.toggleFavorite(
                                    slug = favorite.slug,
                                    title = favorite.title,
                                    posterUrl = favorite.posterUrl,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
