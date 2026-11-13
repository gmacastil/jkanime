package com.macastil.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.AnimeCard
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Error(val message: String) : HomeUiState
    data class Success(val cards: List<AnimeCard>) : HomeUiState
}

class HomeViewModel(
    private val repository: JkanimeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

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
                    val all = sections.values.flatten()
                    HomeUiState.Success(all)
                },
                onFailure = { HomeUiState.Error(it.message ?: "Error") },
            )
        }
    }
}

fun HomeViewModelFactory(repository: JkanimeRepository) =
    viewModelFactory {
        initializer { HomeViewModel(repository) }
    }
