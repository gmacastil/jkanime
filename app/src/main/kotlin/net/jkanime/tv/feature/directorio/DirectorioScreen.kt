package net.jkanime.tv.feature.directorio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jkanime.tv.Graph
import net.jkanime.tv.model.AnimeSearchHit
import net.jkanime.tv.model.DirectorioFilter
import net.jkanime.tv.model.DirectorioOption
import net.jkanime.tv.model.DirectorioPage
import net.jkanime.tv.ui.component.ErrorState
import net.jkanime.tv.ui.component.LoadingState
import net.jkanime.tv.ui.component.PosterCard
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.component.TvIconButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star

/**
 * Pantalla del Directorio: el formulario de filtros del sitio a la izquierda,
 * la grilla de resultados a la derecha.
 *
 * La navegación llega desde el menú lateral (HomeScreen) via `onOpenDirectorio`.
 */
@Composable
fun DirectorioScreen(
    onOpenAnime: (String) -> Unit,
) {
    val context = LocalContext.current
    val library = remember(context) { Graph.library(context) }
    val viewModel: DirectorioViewModel = viewModel(
        factory = DirectorioViewModel.factory(Graph.repository, library),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedFilters by viewModel.selectedFilters.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Barra superior: título + botón limpiar filtros
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Directorio",
                style = MaterialTheme.typography.headlineSmall,
                color = JkOrange,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (selectedFilters.any { it.value.isNotBlank() }) {
                TvActionButton(
                    text = "Limpiar filtros",
                    primary = false,
                    onClick = { viewModel.clearFilters() },
                )
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // Panel de filtros (lateral izquierdo)
            FiltersPanel(
                state = state,
                selected = selectedFilters,
                onFilterChange = { name, value -> viewModel.setFilter(name, value) },
            )

            // Grilla de resultados
            when (val current = state) {
                is DirectorioUiState.Loading -> LoadingState(message = "Cargando directorio…")
                is DirectorioUiState.Error -> ErrorState(
                    message = current.message,
                    onRetry = { viewModel.loadPage(1) },
                )
                is DirectorioUiState.Success -> ResultsGrid(
                    page = current.page,
                    onOpenAnime = onOpenAnime,
                    onPrevPage = { viewModel.loadPage(current.page.page - 1) },
                    onNextPage = { viewModel.loadPage(current.page.page + 1) },
                )
            }
        }
    }
}

/** Panel lateral con los `<select>` del formulario del sitio. */
@Composable
private fun FiltersPanel(
    state: DirectorioUiState,
    selected: Map<String, String>,
    onFilterChange: (String, String) -> Unit,
) {
    val filters = (state as? DirectorioUiState.Success)?.page?.filters ?: emptyList()

    if (filters.isEmpty()) {
        LoadingState(message = "Cargando filtros…")
        return
    }

    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .background(JkSurface)
            .border(width = 1.dp, color = JkSurfaceHigh),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "FILTROS",
            style = MaterialTheme.typography.labelSmall,
            color = JkTextSecondary,
        )

        filters.forEach { filter ->
            FilterDropdown(
                filter = filter,
                selectedValue = selected[filter.name] ?: "",
                onValueChange = { value -> onFilterChange(filter.name, value) },
            )
        }
    }
}

@Composable
private fun FilterDropdown(
    filter: DirectorioFilter,
    selectedValue: String,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = filter.label,
            style = MaterialTheme.typography.labelLarge,
            color = JkTextPrimary,
        )
        TvDropdown(
            options = filter.options,
            selectedValue = selectedValue,
            onSelect = onValueChange,
        )
    }
}

/**
 * Dropdown estilo TV: lista de opciones en un `Column` que se expande al
 * recibir foco. Usa `TvIconButton` como trigger.
 */
@Composable
private fun TvDropdown(
    options: List<DirectorioOption>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val label = options.firstOrNull { it.value == selectedValue }?.label
        ?: options.first { it.isDefault }.label

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused ->
                // En TV: expandir al recibir foco, colapsar al perderlo
                expanded.value = focused.hasFocus
            },
    ) {
        val buttonInteractionSource = remember { MutableInteractionSource() }
        val buttonFocused by buttonInteractionSource.collectIsFocusedAsState()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (buttonFocused) JkSurfaceHigh else JkSurface)
                .border(
                    width = if (buttonFocused) 2.dp else 0.dp,
                    color = if (buttonFocused) JkOrange else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .focusRequester(focusRequester)
                .clickable(
                    interactionSource = buttonInteractionSource,
                    indication = null,
                    onClick = { expanded.value = !expanded.value },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = JkTextPrimary,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (buttonFocused) JkOrange else JkTextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (expanded.value) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(JkSurfaceHigh)
                    .clip(RoundedCornerShape(8.dp))
                    .border(width = 2.dp, color = JkOrange, shape = RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp),
            ) {
                options.forEach { option ->
                    val isSelected = option.value == selectedValue
                    val optInteractionSource = remember { MutableInteractionSource() }
                    val optFocused by optInteractionSource.collectIsFocusedAsState()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                when {
                                    optFocused -> JkOrange
                                    isSelected -> JkOrange.copy(alpha = 0.2f)
                                    else -> Color.Transparent
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clickable(
                                interactionSource = optInteractionSource,
                                indication = null,
                                onClick = {
                                    onSelect(option.value)
                                    expanded.value = false
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = JkOrange,
                                modifier = Modifier.size(20.dp).padding(end = 12.dp),
                            )
                        }
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) JkOrange else JkTextPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** Grilla de resultados del directorio (misma que SearchGrid pero con paginación). */
@Composable
private fun ResultsGrid(
    page: DirectorioPage,
    onOpenAnime: (String) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val firstCardFocus = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize()) {
        if (page.hits.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Sin resultados para los filtros actuales",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JkTextSecondary,
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize().weight(1f),
            ) {
                items(page.hits, key = { it.slug }) { hit ->
                    PosterCard(
                        title = hit.title,
                        posterUrl = hit.posterUrl,
                        badge = hit.type,
                        subtitle = hit.statusLabel,
                        width = null,
                        focusRequester = if (hit === page.hits.first()) firstCardFocus else null,
                        onClick = { onOpenAnime(hit.slug) },
                    )
                }
            }
        }

        // Paginación simple (anterior/siguiente)
        if (page.lastPage > 1) {
            PaginationBar(
                currentPage = page.page,
                lastPage = page.lastPage,
                onPrev = onPrevPage,
                onNext = onNextPage,
            )
        }
    }
}

@Composable
private fun PaginationBar(
    currentPage: Int,
    lastPage: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvActionButton(
            text = "Anterior",
            primary = false,
            enabled = currentPage > 1,
            onClick = onPrev,
        )
        Text(
            text = "Página $currentPage de $lastPage",
            style = MaterialTheme.typography.labelMedium,
            color = JkTextSecondary,
        )
        TvActionButton(
            text = "Siguiente",
            primary = false,
            enabled = currentPage < lastPage,
            onClick = onNext,
        )
    }
}