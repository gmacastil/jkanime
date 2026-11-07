package net.jkanime.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.data.local.FavoriteEntity
import net.jkanime.tv.data.local.LibraryRepository
import net.jkanime.tv.model.AnimeCard
import net.jkanime.tv.model.AnimeSearchHit
import net.jkanime.tv.model.ProgramacionSection
import net.jkanime.tv.util.friendlyMessage

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Success(val sections: Map<ProgramacionSection, List<AnimeCard>>) : HomeUiState
}

/**
 * Estado del buscador del sitio. Se mantiene la lista anterior mientras llega
 * la nueva (`loading = true`) para que la grilla no parpadee en cada letra.
 */
data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<AnimeSearchHit> = emptyList(),
    val error: String? = null,
) {
    /** ¿Hay algo que pintar para esta consulta? */
    val hasQuery: Boolean get() = query.length >= HomeViewModel.MIN_QUERY_LENGTH
}

class HomeViewModel(
    private val repository: JkanimeRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _section = MutableStateFlow(ProgramacionSection.ANIMES)
    val section: StateFlow<ProgramacionSection> = _section.asStateFlow()

    /** Requerimiento 4: fila de favoritos, reactiva desde Room. */
    val favorites: StateFlow<List<FavoriteEntity>> = library.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _showFavorites = MutableStateFlow(false)
    val showFavorites: StateFlow<Boolean> = _showFavorites.asStateFlow()

    /** Entrada "Favoritos" del menú: siempre deja la vista en favoritos. */
    fun openFavorites() {
        _showFavorites.value = true
        clearQuery()
        _drawerOpen.value = false
    }

    // ---------------------------------------------------------------------
    // Menú lateral (hamburguesa): sustituye a la fila de botones que antes
    // vivía en la cabecera. Animes/Donghuas/OVAs viven en `section`; Favoritos
    // y Sync son entradas propias del menú.
    // ---------------------------------------------------------------------

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen.asStateFlow()

    fun openDrawer() {
        _drawerOpen.value = true
    }

    fun closeDrawer() {
        _drawerOpen.value = false
    }

    // ---------------------------------------------------------------------
    // Búsqueda: usa el buscador del sitio (`GET /buscar/{q}`), no un filtro
    // local. Va con debounce porque cada pulsación sería una petición y el
    // cliente HTTP ya limita a 1 req / 700 ms.
    // ---------------------------------------------------------------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private var searchJob: Job? = null

    fun setQuery(value: String) {
        _query.value = value
        if (value.isNotBlank()) _showFavorites.value = false

        // Cancelar la búsqueda anterior también cancela su petición: si no, al
        // teclear rápido llegarían respuestas viejas y pisarían las nuevas.
        searchJob?.cancel()

        val trimmed = value.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            _search.value = SearchUiState(query = trimmed)
            return
        }

        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _search.value = _search.value.copy(query = trimmed, loading = true, error = null)
            try {
                val hits = repository.search(trimmed)
                _search.value = SearchUiState(query = trimmed, hits = hits)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _search.value = _search.value.copy(
                    query = trimmed,
                    loading = false,
                    error = error.friendlyMessage(),
                )
            }
        }
    }

    /** Reintenta la última consulta (botón de error). */
    fun retrySearch() {
        val current = _query.value.trim()
        if (current.length < MIN_QUERY_LENGTH) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _search.value = _search.value.copy(query = current, loading = true, error = null)
            try {
                val hits = repository.search(current, force = true)
                _search.value = SearchUiState(query = current, hits = hits)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _search.value = _search.value.copy(
                    query = current,
                    loading = false,
                    error = error.friendlyMessage(),
                )
            }
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        _query.value = ""
        _search.value = SearchUiState()
    }

    init {
        refresh()
    }

    /** Elegir una sección sale de Favoritos, limpia la búsqueda y cierra el menú. */
    fun selectSection(section: ProgramacionSection) {
        _section.value = section
        _showFavorites.value = false
        clearQuery()
        _drawerOpen.value = false
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            _state.value = runCatching { repository.programacion(force) }
                .fold(
                    onSuccess = { HomeUiState.Success(it) },
                    onFailure = { HomeUiState.Error(it.friendlyMessage()) },
                )
        }
    }

    companion object {
        /** Debounce del buscador: por debajo el rate limit del sitio se nota. */
        const val DEBOUNCE_MS = 550L

        /** El sitio devuelve 30 fichas ya con una sola letra; no vale la pena. */
        const val MIN_QUERY_LENGTH = 2

        fun factory(repository: JkanimeRepository, library: LibraryRepository) =
            viewModelFactory {
                initializer { HomeViewModel(repository, library) }
            }
    }
}
