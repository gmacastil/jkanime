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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import net.jkanime.tv.Graph
import net.jkanime.tv.R
import net.jkanime.tv.model.AnimeSearchHit
import net.jkanime.tv.model.DirectorioFilter
import net.jkanime.tv.model.DirectorioOption
import net.jkanime.tv.model.DirectorioPage
import net.jkanime.tv.ui.component.ErrorState
import net.jkanime.tv.ui.component.LoadingState
import net.jkanime.tv.ui.component.PosterCard
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.component.TvChipButton
import net.jkanime.tv.ui.component.TvNumberPad
import net.jkanime.tv.ui.component.TvIconButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkSurfaceHigh
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
        factory = DirectorioViewModel.factory(Graph.repository, library, context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedFilters by viewModel.selectedFilters.collectAsStateWithLifecycle()
    // El ViewModel pasa por Loading en cada cambio de filtro. Conservamos las
    // definiciones de opciones de la última página para no desmontar el panel
    // ni perder el foco del D-pad mientras llega la nueva respuesta.
    var availableFilters by remember { mutableStateOf<List<DirectorioFilter>>(emptyList()) }
    LaunchedEffect(state) {
        if (state is DirectorioUiState.Success) {
            availableFilters = (state as DirectorioUiState.Success).page.filters
        }
    }

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
                text = stringResource(R.string.directorio_title),
                style = MaterialTheme.typography.headlineSmall,
                color = JkOrange,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (selectedFilters.any { it.value.isNotBlank() }) {
                TvActionButton(
                    text = stringResource(R.string.directorio_clear_filters),
                    primary = false,
                    onClick = { viewModel.clearFilters() },
                )
            }
        }

        // fillMaxWidth().weight(1f) en vez de fillMaxSize(): si la fila pide
        // toda la altura, empuja la barra de paginado fuera de la pantalla.
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Panel de filtros (lateral izquierdo)
            FiltersPanel(
                filters = availableFilters,
                selected = selectedFilters,
                onFilterChange = { name, value -> viewModel.setFilter(name, value) },
            )

            // Grilla de resultados
            when (val current = state) {
                is DirectorioUiState.Loading -> LoadingState(
                    modifier = Modifier.weight(1f),
                    message = stringResource(R.string.directorio_loading),
                )
                is DirectorioUiState.Error -> ErrorState(
                    message = current.message,
                    onRetry = { viewModel.loadPage(1) },
                    modifier = Modifier.weight(1f),
                )
                is DirectorioUiState.Success -> ResultsGrid(
                    page = current.page,
                    onOpenAnime = onOpenAnime,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        // Barra de paginado: fuera del `when` para que no se desmonte (y se
        // pierda el foco del D-pad) mientras llega la página siguiente.
        PaginationFooter(
            state = state,
            onGoToPage = { target -> viewModel.loadPage(target) },
        )
    }
}

/** Panel lateral angosto: solo orden y género, sin combos — las opciones
 *  quedan visibles y el panel entero hace scroll vertical. */
private val VISIBLE_FILTERS = setOf("filtro", "genero")

@Composable
private fun FiltersPanel(
    filters: List<DirectorioFilter>,
    selected: Map<String, String>,
    onFilterChange: (String, String) -> Unit,
) {
    val visibleFilters = filters.filter { it.name in VISIBLE_FILTERS }

    if (visibleFilters.isEmpty()) {
        LoadingState(message = stringResource(R.string.directorio_loading_filters))
        return
    }

    // Surface MD3 en vez del Box+background suelto de antes: JkSurface y
    // JkBackground son tonos casi idénticos, así que sin un token de
    // contenedor más claro + sombra/borde el panel se fundía con el fondo.
    Surface(
        modifier = Modifier
            .width(230.dp)
            .fillMaxHeight()
            .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(
            containerColor = JkSurfaceHigh,
            contentColor = JkTextPrimary,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // ~50 opciones en total (3 de orden + ~46 de género): sin
                // scroll el panel no cabría en pantalla.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.directorio_filters_title),
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            visibleFilters.forEach { filter ->
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = JkOrange,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp),
                )
                filter.options.forEach { option ->
                    FilterOptionRow(
                        option = option,
                        selected = option.value == (selected[filter.name] ?: ""),
                        onSelect = { onFilterChange(filter.name, option.value) },
                    )
                }
            }
        }
    }
}

/**
 * Opción de filtro como fila siempre visible (antes era un combo que se
 * expandía al enfocar): fondo naranja al enfocar, tinte naranja si está
 * seleccionada.
 */
@Composable
private fun FilterOptionRow(
    option: DirectorioOption,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        focused -> JkOrange
        selected -> JkOrange.copy(alpha = 0.2f)
        else -> Color.Transparent
    }
    // Con el fondo naranja completo el texto/tinte naranja sería ilegible:
    // al enfocar pasan a tinta oscura (mismo contraste que TvIconButton).
    val contentColor = when {
        focused -> Color(0xFF141414)
        selected -> JkOrange
        else -> JkTextPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp).padding(end = 6.dp),
            )
        }
        Text(
            text = option.label,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/** Grilla de resultados del directorio (misma que SearchGrid pero con paginación). */
@Composable
private fun ResultsGrid(
    page: DirectorioPage,
    onOpenAnime: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val firstCardFocus = remember { FocusRequester() }

    // Al cambiar de página la grilla vuelve arriba: sin esto se hereda el
    // scroll de la página anterior y el usuario aterriza a mitad de lista.
    LaunchedEffect(page.page, page.filters) {
        gridState.scrollToItem(0)
    }

    if (page.hits.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.directorio_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = JkTextSecondary,
            )
        }
    } else {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = modifier.fillMaxSize(),
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
}

/**
 * Envoltorio de la barra de paginado que recuerda la última página conocida:
 * durante los `Loading` la barra sigue en pantalla con los datos anteriores.
 */
@Composable
private fun PaginationFooter(
    state: DirectorioUiState,
    onGoToPage: (Int) -> Unit,
) {
    var lastPage by remember { mutableStateOf<DirectorioPage?>(null) }
    val success = (state as? DirectorioUiState.Success)?.page
    LaunchedEffect(success) {
        if (success != null) lastPage = success
    }

    val page = success ?: lastPage ?: return
    if (page.lastPage <= 1) return

    PaginationBar(
        currentPage = page.page,
        lastPage = page.lastPage,
        onGoToPage = onGoToPage,
    )
}

/**
 * Barra de paginado del Directorio: flechas + ventana de chips alrededor de la
 * página actual + «Ir a página» con teclado numérico.
 *
 * Vive fuera del `when` del estado para que no se desmonte mientras carga la
 * página siguiente (si desapareciera se perdería el foco del D-pad).
 */
@Composable
private fun PaginationBar(
    currentPage: Int,
    lastPage: Int,
    onGoToPage: (Int) -> Unit,
) {
    var showJump by remember { mutableStateOf(false) }
    // Al cerrar el diálogo el foco se devuelve al botón que lo abrió: si no,
    // el D-pad se queda sin foco hasta la siguiente pulsación.
    val jumpFocus = remember { FocusRequester() }
    var openedJump by remember { mutableStateOf(false) }

    LaunchedEffect(showJump) {
        if (showJump) {
            openedJump = true
            return@LaunchedEffect
        }
        if (!openedJump) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (jumpFocus.requestFocus()) return@LaunchedEffect
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIconButton(
            icon = Icons.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.directorio_prev_page_cd),
            enabled = currentPage > 1,
            onClick = { onGoToPage(currentPage - 1) },
        )

        pageWindow(currentPage, lastPage).forEach { entry ->
            if (entry == null) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.labelLarge,
                    color = JkTextSecondary,
                    modifier = Modifier.width(20.dp),
                    textAlign = TextAlign.Center,
                )
            } else {
                TvChipButton(
                    text = entry.toString(),
                    selected = entry == currentPage,
                    onClick = { onGoToPage(entry) },
                )
            }
        }

        TvIconButton(
            icon = Icons.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.directorio_next_page_cd),
            enabled = currentPage < lastPage,
            onClick = { onGoToPage(currentPage + 1) },
        )

        Spacer(modifier = Modifier.width(8.dp))

        TvActionButton(
            text = stringResource(R.string.directorio_go_to_page),
            primary = false,
            compact = true,
            modifier = Modifier.focusRequester(jumpFocus),
            onClick = { showJump = true },
        )
    }

    if (showJump) {
        JumpToPageDialog(
            currentPage = currentPage,
            lastPage = lastPage,
            onDismiss = { showJump = false },
            onGo = {
                showJump = false
                onGoToPage(it)
            },
        )
    }
}

/**
 * Ventana de páginas a mostrar: siempre la primera y la última, más un radio
 * alrededor de la actual. `null` representa el hueco «…».
 *
 * Con 169 páginas, pintarlas todas es imposible en una TV; el salto directo por
 * teclado es lo que hace navegable el resto.
 */
private fun pageWindow(current: Int, last: Int, radius: Int = 2): List<Int?> {
    val pages = sortedSetOf(1, last)
    for (page in (current - radius)..(current + radius)) {
        if (page in 1..last) pages.add(page)
    }
    val out = mutableListOf<Int?>()
    var previous: Int? = null
    for (page in pages) {
        if (previous != null && page - previous!! > 1) out.add(null)
        out.add(page)
        previous = page
    }
    return out
}

/** Diálogo «Ir a página»: campo de solo lectura + teclado numérico del D-pad. */
@Composable
private fun JumpToPageDialog(
    currentPage: Int,
    lastPage: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    val padFocus = remember { FocusRequester() }

    // El teclado pide el foco en cuanto la ventana existe; sin el reintento
    // multi-frame el Dialog todavía no tiene nodo enfocable.
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos {}
            if (padFocus.requestFocus()) return@LaunchedEffect
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.36f),
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = JkSurface,
                    contentColor = JkTextPrimary,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.directorio_go_to_page),
                        style = MaterialTheme.typography.titleMedium,
                        color = JkOrange,
                    )
                    Text(
                        text = stringResource(
                            R.string.directorio_page_current_of,
                            currentPage,
                            lastPage,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = JkTextSecondary,
                    )

                    // Campo de solo lectura: el valor entra por el teclado.
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .height(58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(JkSurfaceHigh)
                            .border(1.dp, JkSurfaceHigh, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = digits.ifEmpty { "—" },
                            style = MaterialTheme.typography.displaySmall,
                            color = if (digits.isEmpty()) JkTextSecondary else JkTextPrimary,
                        )
                    }

                    TvNumberPad(
                        onDigit = { digit ->
                            val next = digits + digit
                            // 4 dígitos alcanzan de sobra: el sitio no pasa de ~170.
                            if (next.length <= 4 && next.toIntOrNull()!! <= lastPage) digits = next
                        },
                        onBackspace = { digits = digits.dropLast(1) },
                        focusRequester = padFocus,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvActionButton(
                            text = stringResource(R.string.common_cancel),
                            primary = false,
                            onClick = onDismiss,
                        )
                        TvActionButton(
                            text = stringResource(R.string.common_go),
                            primary = true,
                            enabled = digits.isNotEmpty(),
                            onClick = { onGo(digits.toInt().coerceIn(1, lastPage)) },
                        )
                    }
                }
            }
        }
    }
}
