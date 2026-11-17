package com.macastil.mobile.ui.directorio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.mobile.JkGraph
import com.macastil.mobile.R
import com.macastil.tv.model.DirectorioFilter
import com.macastil.tv.model.DirectorioOption
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.component.airingStatusColor
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch

/**
 * Directorio del sitio (paridad con la TV): los mismos filtros `<select>` del
 * formulario, presentados como chips desplazables, con la grilla de 30 fichas
 * por página y paginado inferior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectorioScreen(
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    val viewModel: DirectorioViewModel = viewModel(
        factory = DirectorioViewModel.factory(JkGraph.repository),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val favorites by JkGraph.library.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteSlugs = remember(favorites) {
        favorites.filter { it.deletedAt == null }.map { it.slug }.toSet()
    }
    val scope = rememberCoroutineScope()

    var openFilter by remember { mutableStateOf<DirectorioFilter?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.page?.let {
                            stringResource(R.string.directorio_title) + " · " +
                                stringResource(R.string.directorio_total, it.total)
                        } ?: stringResource(R.string.directorio_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_volver),
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
            FilterChips(
                filters = viewModel.filters,
                selected = state.selected,
                onOpen = { openFilter = it },
                onClear = viewModel::clearFilters,
            )

            val page = state.page
            Box(modifier = Modifier.weight(1f)) {
                when {
                    page == null && state.loading -> CircularProgressIndicator(
                        Modifier.fillMaxSize(),
                    )

                    page == null && state.failed -> Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.common_error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = JkTextSecondary,
                        )
                        TextButton(onClick = viewModel::retry) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }

                    page == null -> Unit

                    page.hits.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.directorio_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = JkTextSecondary,
                        )
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 16.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(page.hits, key = { it.slug }) { hit ->
                            PosterCard(
                                title = hit.title,
                                posterUrl = hit.posterUrl,
                                badge = hit.type,
                                titleMaxLines = 2,
                                subtitle = hit.statusLabel,
                                subtitleColor = airingStatusColor(hit.statusLabel),
                                width = null,
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

                if (state.loading && page != null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp),
                    )
                }
            }

            if (page != null) {
                Pagination(
                    page = page.page,
                    lastPage = page.lastPage,
                    enabled = !state.loading,
                    onPrev = { viewModel.goToPage(page.page - 1) },
                    onNext = { viewModel.goToPage(page.page + 1) },
                )
            }
        }
    }

    openFilter?.let { filter ->
        FilterDialog(
            filter = filter,
            current = state.selected[filter.name],
            onSelect = { value ->
                viewModel.setFilter(filter.name, value)
                openFilter = null
            },
            onDismiss = { openFilter = null },
        )
    }
}

@Composable
private fun FilterChips(
    filters: List<DirectorioFilter>,
    selected: Map<String, String>,
    onOpen: (DirectorioFilter) -> Unit,
    onClear: () -> Unit,
) {
    if (filters.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected.isNotEmpty()) {
            item {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.directorio_clear))
                }
            }
        }
        items(filters, key = { it.name }) { filter ->
            val value = selected[filter.name]
            FilterChip(
                selected = value != null,
                onClick = { onOpen(filter) },
                label = {
                    Text(
                        text = filter.label.ifBlank { filter.name } ,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = value?.let {
                    {
                        Text(
                            text = filter.labelFor(it) ?: it,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                } ?: {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.padding(0.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun Pagination(
    page: Int,
    lastPage: Int,
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onPrev, enabled = enabled && page > 1) {
            Text(stringResource(R.string.directorio_prev))
        }
        Text(
            text = stringResource(R.string.directorio_page_of, page, lastPage.coerceAtLeast(1)),
            style = MaterialTheme.typography.labelMedium,
            color = JkTextSecondary,
        )
        TextButton(onClick = onNext, enabled = enabled && page < lastPage) {
            Text(stringResource(R.string.directorio_next))
        }
    }
}

/** Opciones de un filtro en un diálogo: "Todos" + la lista del sitio. */
@Composable
private fun FilterDialog(
    filter: DirectorioFilter,
    current: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(filter.label.ifBlank { filter.name }) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    OptionRow(
                        label = stringResource(R.string.directorio_all),
                        selected = current.isNullOrBlank(),
                        onClick = { onSelect("") },
                    )
                }
                items(filter.options.filterNot { it.isDefault }, key = { it.value }) { option ->
                    OptionRow(
                        label = option.label,
                        selected = current == option.value,
                        onClick = { onSelect(option.value) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
