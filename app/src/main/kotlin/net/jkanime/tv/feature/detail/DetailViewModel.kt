package net.jkanime.tv.feature.detail

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
import net.jkanime.tv.catalog.JkanimeRepository
import net.jkanime.tv.data.local.LibraryRepository
import net.jkanime.tv.data.local.ProgressEntity
import net.jkanime.tv.model.AnimeDetail
import net.jkanime.tv.model.Episode
import net.jkanime.tv.util.friendlyMessage

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
        val latest: Episode? get() = episodes.lastOrNull()
        val first: Episode? get() = episodes.firstOrNull()
    }
}

class DetailViewModel(
    private val repository: JkanimeRepository,
    private val library: LibraryRepository,
    private val slug: String,
) : ViewModel() {

    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Requerimiento 4: el corazón del favorito vive en Room, no en el server. */
    val isFavorite: StateFlow<Boolean> = library.isFavorite(slug)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Último episodio con progreso, para el botón "Continuar". */
    val lastProgress: StateFlow<ProgressEntity?> = library.progressFor(slug)
        .map { list -> list.maxByOrNull { it.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                onFailure = { DetailUiState.Error(it.friendlyMessage()) },
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
        ) = viewModelFactory {
            initializer { DetailViewModel(repository, library, slug) }
        }
    }
}
