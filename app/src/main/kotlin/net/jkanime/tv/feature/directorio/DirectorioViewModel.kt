package net.jkanime.tv.feature.directorio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.data.local.LibraryRepository
import net.jkanime.tv.model.DirectorioFilter
import net.jkanime.tv.model.DirectorioPage
import net.jkanime.tv.util.friendlyMessage

sealed interface DirectorioUiState {
    data object Loading : DirectorioUiState
    data class Error(val message: String) : DirectorioUiState
    data class Success(
        val page: DirectorioPage,
    ) : DirectorioUiState
}

class DirectorioViewModel(
    private val repository: JkanimeRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<DirectorioUiState>(DirectorioUiState.Loading)
    val state: StateFlow<DirectorioUiState> = _state.asStateFlow()

    /** Filtros seleccionados actualmente (vacío = sin filtro). */
    private val _filters = MutableStateFlow<Map<String, String>>(emptyMap())
    val selectedFilters: StateFlow<Map<String, String>> = _filters.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        loadPage(1)
    }

    fun setFilter(name: String, value: String) {
        _filters.value = _filters.value
            .filterValues { it.isNotBlank() }
            .plus(name to value)
        loadPage(1) // cualquier cambio de filtro reinicia la paginación
    }

    fun clearFilters() {
        _filters.value = emptyMap()
        loadPage(1)
    }

    fun loadPage(page: Int) {
        loadJob?.cancel()
        val currentFilters = _filters.value
        loadJob = viewModelScope.launch {
            delay(300) // debounce rápido si el usuario toca varios filtros seguidos
            _state.value = DirectorioUiState.Loading
            try {
                val loaded = repository.directorio(currentFilters, page)
                _state.value = DirectorioUiState.Success(loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = DirectorioUiState.Error(error.friendlyMessage())
            }
        }
    }

    companion object {
        fun factory(
            repository: JkanimeRepository,
            library: LibraryRepository,
        ) = viewModelFactory {
            initializer { DirectorioViewModel(repository, library) }
        }
    }
}