package com.macastil.mobile.ui.player

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.macastil.mobile.R
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.ServerKind
import com.macastil.tv.model.VideoServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Error(@StringRes val messageRes: Int) : PlayerUiState
    data class Ready(
        val servers: List<VideoServer>,
        val selected: VideoServer,
    ) : PlayerUiState
}

/**
 * Carga los servidores del episodio y elige el primero reproducible.
 *
 * En móvil se reproduce siempre en WebView: los embeds de terceros solo
 * entregan un player JS ofuscado, y el player propio (Desu) es el más estable
 * porque no rota de dominio. Se prefiere Desu → JKPlayer → cualquier stream.
 */
class PlayerViewModel(
    private val repository: JkanimeRepository,
    private val episodeUrl: String,
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
                        val pool = playable.ifEmpty { servers }
                        val selected = pool.firstOrNull { it.name == "Desu" }
                            ?: pool.firstOrNull { it.kind == ServerKind.JKPLAYER }
                            ?: pool.firstOrNull { it.kind == ServerKind.STREAM }
                            ?: pool.firstOrNull()
                        if (selected == null) {
                            PlayerUiState.Error(R.string.player_no_servers)
                        } else {
                            PlayerUiState.Ready(servers = pool, selected = selected)
                        }
                    },
                    onFailure = {
                        PlayerUiState.Error(R.string.player_servers_error)
                    },
                )
        }
    }

    fun select(server: VideoServer) {
        val current = _state.value as? PlayerUiState.Ready ?: return
        _state.value = current.copy(selected = server)
    }

    /**
     * Pasa al siguiente servidor reproducible (circular). Lo usa el aviso de
     * "no cargó": en el móvil no hay D-pad para abrir el overlay de la TV, así
     * que se ofrece un botón que cambia de servidor directamente.
     */
    fun selectNext() {
        val current = _state.value as? PlayerUiState.Ready ?: return
        val index = current.servers.indexOfFirst { it.url == current.selected.url }
        val next = current.servers[(index + 1).mod(current.servers.size)]
        _state.value = current.copy(selected = next)
    }

    companion object {
        fun factory(repository: JkanimeRepository, episodeUrl: String) = viewModelFactory {
            initializer { PlayerViewModel(repository, episodeUrl) }
        }
    }
}
