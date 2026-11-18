package com.macastil.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regresión de la clasificación Pendientes / "Al día" de la pantalla de
 * Favoritos de la TV.
 *
 * El caso que motivó el fix: el sitio NO publica el bloque "Último episodio"
 * (`a#uep`) en las fichas de animes concluidos — solo "Episodios: N". Antes
 * el contador de emitidos se tomaba únicamente de `last_aired_episode`, así
 * que un anime terminado y visto entero quedaba sin datos y caía siempre en
 * "Pendientes".
 */
class PendingEpisodesTest {

    private fun favorite(
        aired: Int?,
        total: Int?,
        watched: Int?,
    ) = FavoriteEntity(
        owner = 1,
        slug = "test",
        animeId = 1,
        title = "Test",
        posterUrl = null,
        addedAt = 0,
        lastWatchedEpisode = watched,
        lastAiredEpisode = aired,
        totalEpisodes = total,
        statusLabel = null,
        syncedAt = 0,
    )

    @Test
    fun `concluido sin ultimo emitido usa el total y queda al dia`() {
        assertEquals(0, favorite(aired = null, total = 12, watched = 12).pendingEpisodes)
    }

    @Test
    fun `concluido con total y visto a medias cuenta los que faltan`() {
        assertEquals(4, favorite(aired = null, total = 12, watched = 8).pendingEpisodes)
    }

    @Test
    fun `en emision usa el ultimo emitido`() {
        assertEquals(3, favorite(aired = 8, total = null, watched = 5).pendingEpisodes)
    }

    @Test
    fun `el ultimo emitido manda sobre un total planificado mayor`() {
        // Sitios que publican "Episodios: 24" en una serie aún en emisión con
        // 8 capítulos fuera: los 16 restantes todavía no existen.
        assertEquals(0, favorite(aired = 8, total = 24, watched = 8).pendingEpisodes)
    }

    @Test
    fun `sin datos de emision no se puede afirmar nada`() {
        assertNull(favorite(aired = null, total = null, watched = 5).pendingEpisodes)
    }

    @Test
    fun `sin nada visto todos los emitidos estan pendientes`() {
        assertEquals(12, favorite(aired = 12, total = null, watched = null).pendingEpisodes)
    }

    @Test
    fun `haber visto de mas no da pendientes negativos`() {
        assertEquals(0, favorite(aired = 12, total = null, watched = 15).pendingEpisodes)
    }
}
