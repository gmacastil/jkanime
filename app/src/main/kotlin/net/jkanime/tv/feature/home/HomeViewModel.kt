package net.jkanime.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.data.local.FavoriteEntity
import net.jkanime.tv.data.local.LibraryRepository
import net.jkanime.tv.model.AnimeCard
import net.jkanime.tv.model.ProgramacionSection
import net.jkanime.tv.util.friendlyMessage

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Success(val sections: Map<ProgramacionSection, List<AnimeCard>>) : HomeUiState
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

    fun toggleFavorites() {
        _showFavorites.value = !_showFavorites.value
        if (_showFavorites.value) _searchOpen.value = false
    }

    // ---------------------------------------------------------------------
    // Búsqueda: filtra en memoria lo que ya está cargado en "Programación"
    // (animes + donghuas + ovas), así que responde al instante y no gasta
    // red. No busca en todo el catálogo del sitio, solo en lo visible.
    // ---------------------------------------------------------------------

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val searchResults: StateFlow<List<AnimeCard>> =
        combine(_state, _query) { state, query ->
            val success = state as? HomeUiState.Success ?: return@combine emptyList()
            val needle = query.searchKey()
            if (needle.isEmpty()) return@combine emptyList()
            success.sections.values
                .flatten()
                .filter { it.title.searchKey().contains(needle) }
                .distinctBy { it.slug }
                .sortedBy { it.title.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openSearch() {
        _showFavorites.value = false
        _searchOpen.value = true
    }

    fun closeSearch() {
        _searchOpen.value = false
        _query.value = ""
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    init {
        refresh()
    }

    /** Elegir una sección sale de Favoritos y de la búsqueda. */
    fun selectSection(section: ProgramacionSection) {
        _section.value = section
        _showFavorites.value = false
        if (_searchOpen.value) closeSearch()
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
        fun factory(repository: JkanimeRepository, library: LibraryRepository) =
            viewModelFactory {
                initializer { HomeViewModel(repository, library) }
            }
    }
}

/**
 * Clave de búsqueda: minúsculas y sin acentos, para que "pii" encuentre
 * "Sasaki to Pii-chan" y "pokemon" encuentre "Pokémon".
 */
private fun String.searchKey(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .trim()
