package com.macastil.mobile.ui.favorites

import com.macastil.mobile.data.local.FavoriteEntity
import com.macastil.mobile.data.local.ProgressEntity

/**
 * Clasificación de un favorito para la pantalla de Favoritos.
 *
 * @property pendingCount capítulos que faltan de `1..released`. `null` cuando
 *   el sitio no publicó ni total ni último emitido: no se puede afirmar nada.
 * @property upToDate `true` solo con la certeza de que no falta ningún capítulo.
 */
internal data class FavoriteStatus(
    val favorite: FavoriteEntity,
    val pendingCount: Int?,
    val upToDate: Boolean,
)

/**
 * Calcula el estado de un favorito contra el progreso local.
 *
 * No usamos `lastWatchedEpisode` para decidir si está al día: ese valor solo
 * representa el capítulo más alto visto y puede ocultar huecos (por ejemplo,
 * vistos 1 y 3, pero falta el 2). Se comprueba cada capítulo publicado.
 *
 * Para una serie en emisión manda `lastAiredEpisode`: `totalEpisodes` puede
 * ser el total planificado de la serie, no capítulos ya disponibles. Para una
 * serie concluida, cuando no hay `lastAiredEpisode`, se usa `totalEpisodes`.
 *
 * Sin datos (`released <= 0`) el favorito va a "Pendientes" sin badge: es más
 * seguro que decir "Al día" cuando no se sabe cuántos capítulos existen.
 */
internal fun favoriteStatus(
    favorite: FavoriteEntity,
    progress: List<ProgressEntity>,
): FavoriteStatus {
    val released = favorite.lastAiredEpisode?.takeIf { it > 0 }
        ?: favorite.totalEpisodes?.takeIf { it > 0 }
        ?: 0
    if (released <= 0) {
        return FavoriteStatus(favorite, pendingCount = null, upToDate = false)
    }
    val watched = progress.asSequence()
        .filter { it.slug == favorite.slug && it.completed && it.episode > 0 }
        .map { it.episode }
        .toSet()
    val pending = (1..released).count { it !in watched }
    return FavoriteStatus(favorite, pendingCount = pending, upToDate = pending == 0)
}
