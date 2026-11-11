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
        /** Página cargada que hace de cursor (no siempre es la 1). */
        val page: Int,
        val lastPage: Int,
        /**
         * Se calcula al cargar porque depende del sentido: el sitio pagina
         * del capítulo 1 hacia arriba, así que "más nuevos primero" arranca
         * en la última página y avanza hacia la 1.
         */
        val hasMore: Boolean,
        val loadingMore: Boolean = false,
    ) : DetailUiState
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
        // El paginado del sitio va del capítulo 1 hacia arriba, así que al
        // cambiar de sentido hay que reanclar el cursor al extremo que
        // corresponde (última página si se ven los más nuevos primero) o la
        // lista se quedaría mostrando el tramo del sentido anterior.
        val current = _state.value as? DetailUiState.Success ?: return
        viewModelScope.launch { loadEpisodes(current, force = false) }
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

    /** Pulsación sostenida en una fila: alterna visto / no visto. */
    fun toggleWatched(episodeNumber: Int, watched: Boolean) {
        viewModelScope.launch {
            if (watched) {
                library.unmarkWatched(slug, episodeNumber)
            } else {
                library.markWatched(slug, episodeNumber)
            }
        }
    }

    /** Alterna el estado visto de todos los capítulos cargados. */
    fun toggleAllWatched(episodes: List<Episode>, allWatched: Boolean) {
        val numbers = episodes.map { it.number.toInt() }
        if (numbers.isEmpty()) return
        viewModelScope.launch {
            if (allWatched) {
                library.unmarkAllWatched(slug, numbers)
            } else {
                library.markAllWatched(slug, numbers)
            }
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
                // Primero la página 1: es la única que informa de `lastPage`.
                val first = repository.episodes(detail.animeId, slug, page = 1, force = force)
                detail to first
            }.fold(
                onSuccess = { (detail, first) ->
                    val target = startPage(first)
                    val page = if (target != first.currentPage) {
                        runCatching {
                            repository.episodes(detail.animeId, slug, page = target, force = force)
                        }.getOrElse { first }
                    } else {
                        first
                    }
                    DetailUiState.Success(
                        detail = detail,
                        episodes = page.episodes.sortedBy { it.number },
                        page = page.currentPage,
                        lastPage = first.lastPage,
                        hasMore = hasMoreFrom(page.currentPage.coerceAtLeast(1), first.lastPage),
                    )
                },
                onFailure = { DetailUiState.Error(it.friendlyMessage(context)) },
            )
        }
    }

    /** Página con la que arranca el listado según el sentido elegido. */
    private fun startPage(first: com.macastil.tv.model.EpisodesPage): Int =
        if (_sortDescending.value) first.lastPage.coerceAtLeast(1) else first.currentPage

    /** ¿Quedan páginas en la dirección de avance actual? */
    private fun hasMoreFrom(page: Int, lastPage: Int): Boolean =
        if (_sortDescending.value) page > 1 else page < lastPage

    /**
     * Reancla la lista de capítulos sin volver a pedir el detalle (se usa al
     * cambiar el orden). La página 1 sale de la cache TTL del repositorio, así
     * que normalmente no hay red de por medio.
     */
    private suspend fun loadEpisodes(current: DetailUiState.Success, force: Boolean) {
        val first = runCatching {
            repository.episodes(current.detail.animeId, slug, page = 1, force = force)
        }.getOrNull() ?: return
        val target = startPage(first)
        val page = if (target != first.currentPage) {
            runCatching { repository.episodes(current.detail.animeId, slug, page = target) }
                .getOrElse { first }
        } else {
            first
        }
        _state.value = current.copy(
            episodes = page.episodes.sortedBy { it.number },
            page = page.currentPage,
            lastPage = first.lastPage,
            hasMore = hasMoreFrom(page.currentPage.coerceAtLeast(1), first.lastPage),
            loadingMore = false,
        )
    }

    fun loadMore() {
        val current = _state.value as? DetailUiState.Success ?: return
        if (!current.hasMore || current.loadingMore) return

        // El sitio pagina del capítulo 1 hacia arriba: con "más nuevos
        // primero" se retrocede (p-1); con "más antiguos primero" se avanza
        // (p+1). Antes siempre se pedía p+1 y, como el endpoint ignora
        // `?page=` (usa `?p=`), volvía la misma página: el botón parecía
        // repetir el mismo contenido sin fin.
        val nextPage = if (_sortDescending.value) current.page - 1 else current.page + 1
        if (nextPage < 1 || nextPage > current.lastPage) return

        viewModelScope.launch {
            _state.value = current.copy(loadingMore = true)
            runCatching {
                repository.episodes(current.detail.animeId, slug, page = nextPage)
            }.fold(
                onSuccess = { page ->
                    val merged = (current.episodes + page.episodes)
                        .distinctBy { it.id }
                        .sortedBy { it.number }
                    // Red de seguridad: si una página no aporta nada nuevo, el
                    // paginado se cierra en vez de repetirse eternamente.
                    val advanced = merged.size > current.episodes.size
                    val lastPage = maxOf(current.lastPage, page.lastPage)
                    _state.value = current.copy(
                        episodes = merged,
                        page = if (advanced) nextPage else current.page,
                        lastPage = lastPage,
                        hasMore = advanced && hasMoreFrom(nextPage, lastPage),
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
