package com.macastil.mobile.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data object Ready : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}

class PlayerViewModel(
    private val repository: JkanimeRepository,
    private val episodeUrl: String,
    private val referer: String?,
) : ViewModel() {

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var _streamUrl: String = ""
    val streamUrl: String? get() = if (_streamUrl.isEmpty()) null else _streamUrl

    init {
        resolveStream()
    }

    private fun resolveStream() {
        viewModelScope.launch {
            _state.value = PlayerUiState.Loading
            try {
                val streamRaw = repository.stream(episodeUrl, referer = referer.orEmpty())
                streamRaw?.let { url ->
                    _streamUrl = url
                    _state.value = PlayerUiState.Ready
                } ?: run {
                    _state.value = PlayerUiState.Error("No stream URL available")
                }
            } catch (e: Exception) {
                _state.value = PlayerUiState.Error(e.message ?: "Error al cargar el stream")
            }
        }
    }
}

fun PlayerViewModelFactory(
    repository: JkanimeRepository,
    episodeUrl: String,
    referer: String?,
) =
    viewModelFactory {
        initializer { PlayerViewModel(repository, episodeUrl, referer) }
    }
