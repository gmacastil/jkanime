package com.macastil.tv.catalog

import com.macastil.tv.catalog.parser.EpisodeJsonParser
import com.macastil.tv.model.AiringStatus
import com.macastil.tv.model.EpisodesPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fichas sin `Episodios` ni `a#uep` (caso real `bukiyou-na-senpai`): el total
 * solo se puede deducir del AJAX de episodios.
 */
class EpisodeCountsTest {

    private fun page(
        total: Int,
        lastPage: Int = 1,
        numbers: List<Int> = emptyList(),
    ) = EpisodesPage(
        currentPage = 1,
        lastPage = lastPage,
        total = total,
        episodes = numbers.map { number ->
            com.macastil.tv.model.Episode(
                id = number.toLong(),
                animeId = 4368,
                number = number.toDouble(),
                title = "Ep $number",
                thumbUrl = null,
                airedAt = null,
                url = "https://jkanime.net/bukiyou-na-senpai/$number/",
            )
        },
    )

    @Test
    fun `concluido sin aired toma el total del ajax`() {
        val counts = episodeCountsOf(page(total = 12, numbers = (1..12).toList()), null, AiringStatus.FINISHED)
        assertEquals(12, counts?.totalEpisodes)
        assertNull(counts?.lastAiredEpisode)
    }

    @Test
    fun `en emision de una pagina aired y total coinciden`() {
        val counts = episodeCountsOf(page(total = 6, numbers = (1..6).toList()), null, AiringStatus.CURRENTLY)
        assertEquals(6, counts?.totalEpisodes)
        assertEquals(6, counts?.lastAiredEpisode)
    }

    @Test
    fun `en emision con varias paginas usa el ultimo capitulo de la ultima pagina`() {
        val counts = episodeCountsOf(
            page(total = 30, lastPage = 2, numbers = (1..16).toList()),
            lastPageMaxNumber = 30,
            status = AiringStatus.CURRENTLY,
        )
        assertEquals(30, counts?.totalEpisodes)
        assertEquals(30, counts?.lastAiredEpisode)
    }

    @Test
    fun `si el total viene a cero usa el numero mas alto listado`() {
        val counts = episodeCountsOf(page(total = 0, numbers = listOf(1, 2, 3)), null, AiringStatus.FINISHED)
        assertEquals(3, counts?.totalEpisodes)
    }

    @Test
    fun `sin episodios no hay nada que deducir`() {
        assertNull(episodeCountsOf(page(total = 0), null, AiringStatus.FINISHED))
    }

    @Test
    fun `se deduce tambien cuando el detalle trae Episodios cero`() {
        // Caso real: bukiyou-na-senpai devuelve totalEpisodes=0 (no null).
        assertTrue(needsEpisodeCounts(totalEpisodes = 0, lastAiredEpisode = null))
        assertTrue(needsEpisodeCounts(totalEpisodes = null, lastAiredEpisode = null))
        assertFalse(needsEpisodeCounts(totalEpisodes = 12, lastAiredEpisode = null))
        assertFalse(needsEpisodeCounts(totalEpisodes = 0, lastAiredEpisode = 6))
    }

    @Test
    fun `parser y deduccion sobre la ficha real sin Episodios`() {
        // Respuesta real de POST /ajax/episodes/4368/1 (bukiyou-na-senpai).
        val parsed = EpisodeJsonParser.parse(
            com.macastil.tv.catalog.Fixtures.text("episodes_no_episode_count.json"),
            animeId = 4368,
            slug = "bukiyou-na-senpai",
        )
        val counts = episodeCountsOf(parsed, null, AiringStatus.FINISHED)
        assertEquals(12, counts?.totalEpisodes)
        assertEquals(1, parsed.lastPage)
    }
}
