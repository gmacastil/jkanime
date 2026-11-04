package net.jkanime.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.model.AnimeCard
import net.jkanime.tv.model.ProgramacionSection
import net.jkanime.tv.util.friendlyMessage

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Success(val sections: Map<ProgramacionSection, List<AnimeCard>>) : HomeUiState
}

class HomeViewModel(private val repository: JkanimeRepository) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _section = MutableStateFlow(ProgramacionSection.ANIMES)
    val section: StateFlow<ProgramacionSection> = _section.asStateFlow()

    init {
        refresh()
    }

    fun selectSection(section: ProgramacionSection) {
        _section.value = section
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
        fun factory(repository: JkanimeRepository) = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
