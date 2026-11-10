package com.macastil.tv.feature.directorio

import android.content.Context
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
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.data.local.LibraryRepository
import com.macastil.tv.model.DirectorioFilter
import com.macastil.tv.model.DirectorioPage
import com.macastil.tv.util.friendlyMessage

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
    // Solo para localizar mensajes de error fuera de la composición.
    private val context: Context,
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
        _filters.value = _filters.value.toMutableMap().apply {
            if (value.isBlank()) remove(name) else put(name, value)
        }
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
                _state.value = DirectorioUiState.Error(error.friendlyMessage(context))
            }
        }
    }

    companion object {
        fun factory(
            repository: JkanimeRepository,
            library: LibraryRepository,
            context: Context,
        ) = viewModelFactory {
            initializer { DirectorioViewModel(repository, library, context.applicationContext) }
        }
    }
}
