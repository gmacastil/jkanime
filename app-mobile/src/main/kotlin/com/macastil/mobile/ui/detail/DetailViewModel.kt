package com.macastil.mobile.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.Episode
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Error(val message: String) : DetailUiState
    data class Success(val detail: AnimeDetail, val episodes: List<Episode>) : DetailUiState
}

class DetailViewModel(
    private val repository: JkanimeRepository,
    private val slug: String,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = DetailUiState.Loading
            _state.value = runCatching {
                val detail = repository.detail(slug)
                val first = repository.episodes(detail.animeId, slug, page = 1)
                detail to first.episodes
            }.fold(
                onSuccess = { (detail, episodes) ->
                    DetailUiState.Success(detail, episodes)
                },
                onFailure = { DetailUiState.Error(it.message ?: "Error") },
            )
        }
    }
}

fun DetailViewModelFactory(repository: JkanimeRepository, slug: String) =
    androidx.lifecycle.viewmodel.viewModelFactory {
        initializer { DetailViewModel(repository, slug) }
    }
