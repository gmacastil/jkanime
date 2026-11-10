package net.jkanime.tv.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.model.ServerKind
import net.jkanime.tv.model.VideoServer
import net.jkanime.tv.util.friendlyMessage

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Error(val message: String) : PlayerUiState
    data class Ready(
        val servers: List<VideoServer>,
        val selected: VideoServer,
    ) : PlayerUiState
}

class PlayerViewModel(
    private val repository: JkanimeRepository,
    private val episodeUrl: String,
    // Solo para localizar mensajes de error fuera de la composición.
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.value = PlayerUiState.Loading
            _state.value = runCatching { repository.servers(episodeUrl, force) }
                .fold(
                    onSuccess = { servers ->
                        val playable = servers.filter { it.kind != ServerKind.DOWNLOAD }
                        // Desu es el player propio del sitio y el más estable
                        // (los embeds de terceros rotan de dominio cada
                        // semana). Si no está, se cae al primer JKPlayer y
                        // luego a cualquier stream.
                        val selected = playable.firstOrNull { it.name == "Desu" }
                            ?: playable.firstOrNull { it.kind == ServerKind.JKPLAYER }
                            ?: playable.firstOrNull { it.kind == ServerKind.STREAM }
                            ?: servers.first()
                        PlayerUiState.Ready(servers = servers, selected = selected)
                    },
                    onFailure = { PlayerUiState.Error(it.friendlyMessage(context)) },
                )
        }
    }

    fun select(server: VideoServer) {
        val current = _state.value as? PlayerUiState.Ready ?: return
        _state.value = current.copy(selected = server)
    }

    companion object {
        fun factory(
            repository: JkanimeRepository,
            episodeUrl: String,
            context: Context,
        ) = viewModelFactory {
            initializer {
                PlayerViewModel(repository, episodeUrl, context.applicationContext)
            }
        }
    }
}
