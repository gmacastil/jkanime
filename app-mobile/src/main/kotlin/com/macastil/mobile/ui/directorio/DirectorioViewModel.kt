package com.macastil.mobile.ui.directorio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.DirectorioFilter
import com.macastil.tv.model.DirectorioPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Estado del Directorio en el móvil.
 *
 * [`page`] se conserva mientras llega la siguiente respuesta: si se vaciara en
 * cada cambio de filtro, la grilla parpadearía y los chips de filtro
 * desaparecerían (las definiciones viajan dentro de la propia página).
 */
data class DirectorioUiState(
    val page: DirectorioPage? = null,
    val selected: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val failed: Boolean = false,
)

class DirectorioViewModel(
    private val repository: JkanimeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DirectorioUiState())
    val state: StateFlow<DirectorioUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load(page = 1)
    }

    /** Definiciones de filtro de la última página cargada. */
    val filters: List<DirectorioFilter> get() = _state.value.page?.filters.orEmpty()

    fun setFilter(name: String, value: String) {
        val selected = _state.value.selected.toMutableMap().apply {
            if (value.isBlank()) remove(name) else put(name, value)
        }
        _state.value = _state.value.copy(selected = selected)
        // Cualquier cambio de filtro reinicia la paginación, como en el sitio.
        load(page = 1)
    }

    fun clearFilters() {
        _state.value = _state.value.copy(selected = emptyMap())
        load(page = 1)
    }

    fun goToPage(page: Int) {
        val current = _state.value.page ?: return
        if (page !in 1..current.lastPage || page == current.page) return
        load(page = page)
    }

    fun retry() {
        load(page = _state.value.page?.page ?: 1)
    }

    private fun load(page: Int) {
        loadJob?.cancel()
        val selected = _state.value.selected
        loadJob = viewModelScope.launch {
            // Debounce corto: mover varios filtros seguidos no debe lanzar una
            // petición por cada toque (el sitio limita a 1 req/700 ms).
            delay(250)
            _state.value = _state.value.copy(loading = true, failed = false)
            try {
                val loaded = repository.directorio(selected, page)
                _state.value = DirectorioUiState(
                    page = loaded,
                    selected = selected,
                    loading = false,
                    failed = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, failed = true)
            }
        }
    }

    companion object {
        fun factory(repository: JkanimeRepository) = viewModelFactory {
            initializer { DirectorioViewModel(repository) }
        }
    }
}
