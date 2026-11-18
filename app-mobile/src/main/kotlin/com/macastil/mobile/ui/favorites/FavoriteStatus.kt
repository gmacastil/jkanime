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
 * El total es el mayor entre `totalEpisodes` y `lastAiredEpisode`: el sitio no
 * publica `a#uep` en fichas de animes concluidos (solo "Episodios: N") y en los
 * que siguen en emisión el total suele venir a 0, con el emitido en `a#uep`.
 *
 * Sin datos (`released <= 0`) el favorito va a "Pendientes" sin badge: es más
 * seguro que decir "Al día" cuando no se sabe cuántos capítulos existen.
 */
internal fun favoriteStatus(
    favorite: FavoriteEntity,
    progress: List<ProgressEntity>,
): FavoriteStatus {
    val released = maxOf(favorite.totalEpisodes ?: 0, favorite.lastAiredEpisode ?: 0)
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
