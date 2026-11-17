package com.macastil.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.AnimeSearchHit
import com.macastil.tv.model.ProgramacionSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HomeUiState {
    data object Loading : HomeUiState
    /** [message] null = no hay texto del servidor: la pantalla usa el genérico. */
    data class Error(val message: String? = null) : HomeUiState
    data class Success(val cards: List<AnimeCard>) : HomeUiState
}

/**
 * Estado del buscador de la cabecera.
 *
 * `query` es lo que hay escrito (se actualiza en cada pulsación, sin red);
 * `hits` la última respuesta del sitio. Cuando `query` tiene menos de
 * [MIN_QUERY] caracteres el buscador está inactivo y la pantalla muestra la
 * Programación normal.
 */
data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<AnimeSearchHit> = emptyList(),
    val failed: Boolean = false,
) {
    val active: Boolean get() = query.trim().length >= MIN_QUERY

    companion object {
        /** El sitio devuelve resultados muy ruidosos con una sola letra. */
        const val MIN_QUERY = 2
    }
}

class HomeViewModel(
    private val repository: JkanimeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            _state.value = runCatching {
                repository.programacion()
            }.fold(
                onSuccess = { sections ->
                    HomeUiState.Success(mergeSections(sections))
                },
                onFailure = { HomeUiState.Error(it.message) },
            )
        }
    }

    /**
     * Búsqueda contra el buscador del sitio (misma llamada que la TV).
     *
     * Debounce de 450 ms y cancelación del trabajo anterior: sin esto, una
     * respuesta vieja podía llegar después de la nueva y pisarla. El
     * `CancellationException` del trabajo cancelado se relanza para que no se
     * muestre como error de red.
     */
    fun onQueryChange(value: String) {
        val query = value.take(MAX_QUERY_LENGTH)
        _search.value = _search.value.copy(query = query)
        searchJob?.cancel()

        if (query.trim().length < SearchUiState.MIN_QUERY) {
            _search.value = SearchUiState(query = query)
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _search.value = _search.value.copy(loading = true, failed = false)
            try {
                val hits = repository.search(query)
                _search.value = _search.value.copy(
                    loading = false,
                    hits = hits,
                    failed = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _search.value = _search.value.copy(
                    loading = false,
                    hits = emptyList(),
                    failed = true,
                )
            }
        }
    }

    /** Botón de la x del campo: vuelve a la Programación ya cargada. */
    fun clearQuery() {
        searchJob?.cancel()
        _search.value = SearchUiState()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 450L
        const val MAX_QUERY_LENGTH = 60
    }
}

fun HomeViewModelFactory(repository: JkanimeRepository) =
    viewModelFactory {
        initializer { HomeViewModel(repository) }
    }

/**
 * Aplana las secciones de Programación en una lista plana **con un card por
 * anime**.
 *
 * El sitio publica un card por **episodio**, así que un anime con tres
 * episodios nuevos aparece tres veces con el mismo `slug` (caso real:
 * `bai-jia-jue-zhi-jianghu-gui-shi-lu` con Ep 11, 13 y 12). Como el `slug` es
 * el `key` de la grilla, el duplicado hacía que Compose lanzara
 * `IllegalArgumentException: Key "..." was already used` y cerrara la app en
 * un dispositivo real; en el emulador no se veía porque depende del contenido
 * del día.
 *
 * Se conserva la posición de la primera aparición y, entre varios capítulos
 * del mismo anime, el de número más alto (el más reciente).
 */
internal fun mergeSections(
    sections: Map<ProgramacionSection, List<AnimeCard>>,
): List<AnimeCard> {
    val bySlug = LinkedHashMap<String, AnimeCard>()
    sections.values.flatten().forEach { card ->
        val previous = bySlug[card.slug]
        bySlug[card.slug] = if (previous == null) card else latestEpisode(previous, card)
    }
    return bySlug.values.toList()
}

/** De dos cards del mismo anime devuelve el del capítulo más alto. */
private fun latestEpisode(first: AnimeCard, second: AnimeCard): AnimeCard {
    val a = first.episodeNumber ?: Int.MIN_VALUE
    val b = second.episodeNumber ?: Int.MIN_VALUE
    return if (b > a) second else first
}
