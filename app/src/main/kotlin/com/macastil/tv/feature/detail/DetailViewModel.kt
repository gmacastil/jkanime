package com.macastil.tv.feature.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.data.local.LibraryRepository
import com.macastil.tv.data.local.ProgressEntity
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.Episode
import com.macastil.tv.util.friendlyMessage

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Error(val message: String) : DetailUiState
    data class Success(
        val detail: AnimeDetail,
        val episodes: List<Episode>,
        val page: Int,
        val lastPage: Int,
        val loadingMore: Boolean = false,
    ) : DetailUiState {
        val hasMore: Boolean get() = page < lastPage
    }
}

class DetailViewModel(
    private val repository: JkanimeRepository,
    private val library: LibraryRepository,
    private val slug: String,
    // Solo para localizar mensajes de error fuera de la composición.
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Requerimiento 4: el corazón del favorito vive en Room, no en el server. */
    val isFavorite: StateFlow<Boolean> = library.isFavorite(slug)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Último episodio empezado y **sin terminar**, para el botón "Continuar".
     * Se excluyen los completados: si acabas de terminar el 4, "Continuar"
     * no debe llevarte otra vez al 4.
     */
    val lastProgress: StateFlow<ProgressEntity?> = library.progressFor(slug)
        .map { list ->
            list.filter { !it.completed && it.positionMs > 0 }
                .maxByOrNull { it.updatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Progreso indexado por número de episodio: con esto la lista marca en
     * verde los capítulos ya vistos y deja una barra en los empezados.
     */
    val progressByEpisode: StateFlow<Map<Int, ProgressEntity>> = library.progressFor(slug)
        .map { list -> list.associateBy { it.episode } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Orden de la lista de capítulos: por defecto el más nuevo arriba. */
    private val _sortDescending = MutableStateFlow(true)
    val sortDescending: StateFlow<Boolean> = _sortDescending.asStateFlow()

    fun toggleSort() {
        _sortDescending.value = !_sortDescending.value
    }

    fun toggleFavorite() {
        val current = _state.value as? DetailUiState.Success ?: return
        val detail = current.detail
        viewModelScope.launch {
            library.toggleFavorite(
                slug = detail.slug,
                animeId = detail.animeId,
                title = detail.title,
                posterUrl = detail.posterUrl,
                totalEpisodes = detail.totalEpisodes?.takeIf { it > 0 },
                statusLabel = detail.statusLabel ?: detail.status.label,
                lastAiredEpisode = detail.lastEpisodeNumber,
            )
        }
    }

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.value = DetailUiState.Loading
            _state.value = runCatching {
                val detail = repository.detail(slug, force)
                val page = repository.episodes(detail.animeId, slug, page = 1, force = force)
                detail to page
            }.fold(
                onSuccess = { (detail, page) ->
                    DetailUiState.Success(
                        detail = detail,
                        episodes = page.episodes.sortedBy { it.number },
                        page = page.currentPage,
                        lastPage = page.lastPage,
                    )
                },
                onFailure = { DetailUiState.Error(it.friendlyMessage(context)) },
            )
        }
    }

    fun loadMore() {
        val current = _state.value as? DetailUiState.Success ?: return
        if (!current.hasMore || current.loadingMore) return

        viewModelScope.launch {
            _state.value = current.copy(loadingMore = true)
            val nextPage = current.page + 1
            runCatching {
                repository.episodes(current.detail.animeId, slug, page = nextPage)
            }.fold(
                onSuccess = { page ->
                    _state.value = current.copy(
                        episodes = (current.episodes + page.episodes).distinctBy { it.id }
                            .sortedBy { it.number },
                        page = page.currentPage,
                        lastPage = page.lastPage,
                        loadingMore = false,
                    )
                },
                onFailure = { _state.value = current.copy(loadingMore = false) },
            )
        }
    }

    companion object {
        fun factory(
            repository: JkanimeRepository,
            library: LibraryRepository,
            slug: String,
            context: Context,
        ) = viewModelFactory {
            initializer {
                DetailViewModel(repository, library, slug, context.applicationContext)
            }
        }
    }
}
