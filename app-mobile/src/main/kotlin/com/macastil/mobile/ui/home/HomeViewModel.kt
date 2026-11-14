package com.macastil.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.ProgramacionSection
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
                    HomeUiState.Success(mergeSections(sections))
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

/**
 * Aplana las secciones de Programación en una lista plana **con un card por
 * anime**.
 *
 * El sitio publica un card por **episodio**, así que un anime con tres
 * episodios nuevos aparece tres veces con el mismo `slug` (caso real:
 * `bai-jia-jue-zhi-jianghu-gui-shi-lu` con Ep 11, 13 y 12). Como el `slug` es
 * el `key` de la grilla, el duplicado hacía que Compose lanzara
 * `IllegalArgumentException: Key "..." was already used` y cerrara la app en
 * un dispositivo real; en el emulador no se veía porque depende del contenido
 * del día.
 *
 * Se conserva la posición de la primera aparición y, entre varios capítulos
 * del mismo anime, el de número más alto (el más reciente).
 */
internal fun mergeSections(
    sections: Map<ProgramacionSection, List<AnimeCard>>,
): List<AnimeCard> {
    val bySlug = LinkedHashMap<String, AnimeCard>()
    sections.values.flatten().forEach { card ->
        val previous = bySlug[card.slug]
        bySlug[card.slug] = if (previous == null) card else latestEpisode(previous, card)
    }
    return bySlug.values.toList()
}

/** De dos cards del mismo anime devuelve el del capítulo más alto. */
private fun latestEpisode(first: AnimeCard, second: AnimeCard): AnimeCard {
    val a = first.episodeNumber ?: Int.MIN_VALUE
    val b = second.episodeNumber ?: Int.MIN_VALUE
    return if (b > a) second else first
}
