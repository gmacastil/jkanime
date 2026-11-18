package com.macastil.mobile.ui.favorites

import com.macastil.mobile.data.local.FavoriteEntity
import com.macastil.mobile.data.local.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión de la clasificación "Pendientes" vs "Al día" del móvil, alineada
 * con la TV desde 1.5.14/1.5.15.
 */
class FavoriteStatusTest {

    private fun favorite(
        slug: String = "anime",
        aired: Int? = null,
        total: Int? = null,
        lastWatched: Int? = null,
    ) = FavoriteEntity(
        slug = slug,
        title = slug,
        lastAiredEpisode = aired,
        totalEpisodes = total,
        lastWatchedEpisode = lastWatched,
    )

    private fun watched(slug: String, vararg episodes: Int) =
        episodes.map { ProgressEntity(slug = slug, episode = it, completed = true) }

    @Test
    fun `concluido sin a#uep y todo visto queda al dia`() {
        val status = favorite(total = 12).let { it to watched("anime", *(1..12).toList().toIntArray()) }
        val result = favoriteStatus(status.first, status.second)
        assertEquals(0, result.pendingCount)
        assertTrue(result.upToDate)
    }

    @Test
    fun `concluido sin a#uep con un capitulo menos queda pendiente`() {
        val result = favoriteStatus(favorite(total = 12), watched("anime", *(1..11).toList().toIntArray()))
        assertEquals(1, result.pendingCount)
        assertFalse(result.upToDate)
    }

    @Test
    fun `en emision usa el ultimo emitido cuando el total es cero`() {
        val result = favoriteStatus(favorite(aired = 6, total = 0), watched("anime", 1, 2, 3, 4, 5))
        assertEquals(1, result.pendingCount)
        assertFalse(result.upToDate)
    }

    @Test
    fun `sin datos ni aired ni total va a pendientes sin badge`() {
        val result = favoriteStatus(favorite(), watched("anime", 1, 2, 3))
        assertNull(result.pendingCount)
        assertFalse(result.upToDate)
    }

    @Test
    fun `total cero sin aired tampoco se puede clasificar`() {
        // Caso bukiyou-na-senpai: el sitio publica "Episodios: 0" y no trae a#uep.
        val result = favoriteStatus(favorite(total = 0), watched("anime", *(1..12).toList().toIntArray()))
        assertNull(result.pendingCount)
        assertFalse(result.upToDate)
    }

    @Test
    fun `cuenta huecos y no solo el capitulo mas alto`() {
        // Vistos 1 y 3 de 5: lastWatchedEpisode diría 3, pero faltan 2, 4 y 5.
        val result = favoriteStatus(favorite(total = 5, lastWatched = 3), watched("anime", 1, 3))
        assertEquals(3, result.pendingCount)
        assertFalse(result.upToDate)
    }

    @Test
    fun `el progreso de otro slug no cuenta`() {
        val result = favoriteStatus(favorite(total = 2), watched("otro", 1, 2))
        assertEquals(2, result.pendingCount)
        assertFalse(result.upToDate)
    }

    @Test
    fun `el progreso sin completar no cuenta`() {
        val progress = listOf(
            ProgressEntity(slug = "anime", episode = 1, completed = false, positionMs = 500),
            ProgressEntity(slug = "anime", episode = 2, completed = true),
        )
        val result = favoriteStatus(favorite(total = 2), progress)
        assertEquals(1, result.pendingCount)
        assertFalse(result.upToDate)
    }
}
