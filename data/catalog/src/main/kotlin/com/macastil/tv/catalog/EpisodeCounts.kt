package com.macastil.tv.catalog

import com.macastil.tv.model.AiringStatus
import com.macastil.tv.model.AnimeDetail
import com.macastil.tv.model.EpisodesPage

/**
 * Contadores de emisión deducidos del listado de episodios.
 *
 * @property lastAiredEpisode último capítulo emitido (solo series en emisión).
 * @property totalEpisodes capítulos publicados.
 */
data class EpisodeCounts(
    val lastAiredEpisode: Int?,
    val totalEpisodes: Int?,
)

/**
 * Algunas fichas no publican ni `Episodios: N` ni el enlace `a#uep` (caso real:
 * `bukiyou-na-senpai`, con `Episodios: 0` y sin `a#uep` aunque tiene 12
 * capítulos). El dato sí está en el AJAX de episodios que la propia ficha usa
 * para paginar, así que se deduce de ahí.
 *
 * @param firstPage página 1 del AJAX.
 * @param lastPageMaxNumber número más alto de la última página, si hubo que
 *   pedirla (solo series en emisión con más de una página).
 */
internal fun episodeCountsOf(
    firstPage: EpisodesPage,
    lastPageMaxNumber: Int?,
    status: AiringStatus,
): EpisodeCounts? {
    val total = firstPage.total.takeIf { it > 0 }
        ?: firstPage.episodes.maxOfOrNull { it.number.toInt() }
        ?: return null
    if (total <= 0) return null

    // En emisión el número de capítulos listados es el último emitido; en
    // concluidos dejamos `aired` en null para que mande el total (el sitio
    // tampoco publica `a#uep` en esas fichas).
    val aired = if (status == AiringStatus.CURRENTLY) {
        (lastPageMaxNumber ?: firstPage.episodes.maxOfOrNull { it.number.toInt() })
            ?.takeIf { it > 0 }
    } else {
        null
    }
    return EpisodeCounts(lastAiredEpisode = aired, totalEpisodes = total)
}

/**
 * ¿Hay que deducir los contadores del listado de episodios?
 *
 * Ojo: el parser deja `Episodios: 0` como 0 (no null), así que "sin datos" es
 * `<= 0`, no `== null`.
 */
internal fun needsEpisodeCounts(totalEpisodes: Int?, lastAiredEpisode: Int?): Boolean =
    (totalEpisodes ?: 0) <= 0 && (lastAiredEpisode ?: 0) <= 0

/**
 * Completa los contadores de un detalle que llegó sin ellos. Devuelve `null`
 * cuando el detalle ya traía datos o cuando el sitio no ofrece nada.
 */
suspend fun JkanimeRepository.episodeCountsFallback(detail: AnimeDetail): EpisodeCounts? {
    if (!needsEpisodeCounts(detail.totalEpisodes, detail.lastEpisodeNumber)) return null

    val firstPage = runCatching { episodes(detail.animeId, detail.slug, page = 1) }
        .getOrNull()
        ?: return null

    // Con más de una página el último capítulo de la página 1 no es el emitido.
    val lastPageMax = if (detail.status == AiringStatus.CURRENTLY && firstPage.lastPage > 1) {
        runCatching { episodes(detail.animeId, detail.slug, page = firstPage.lastPage) }
            .getOrNull()
            ?.episodes
            ?.maxOfOrNull { it.number.toInt() }
    } else {
        null
    }
    return episodeCountsOf(firstPage, lastPageMax, detail.status)
}
