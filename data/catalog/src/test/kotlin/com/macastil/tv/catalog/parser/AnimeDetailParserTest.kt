package com.macastil.tv.catalog.parser

import com.macastil.tv.catalog.Fixtures
import com.macastil.tv.model.AiringStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeDetailParserTest {

    private val detail = AnimeDetailParser.parse(
        Fixtures.text("anime_detail.html"),
        slug = "seitokai-ni-mo-ana-wa-aru",
    )

    @Test
    fun `extrae el id interno usado por el endpoint de episodios`() {
        assertEquals(4967, detail.animeId)
    }

    @Test
    fun `extrae titulos`() {
        assertEquals("Seitokai ni mo Ana wa Aru!", detail.title)
        assertTrue(detail.titleAlt?.contains("There Is Also a Hole") == true)
    }

    @Test
    fun `extrae sinopsis y poster`() {
        assertNotNull(detail.synopsis)
        assertTrue(detail.synopsis!!.length > 80)
        assertEquals(
            "https://cdn.jkdesa.com/assets/images/animes/image/seitokai-ni-mo-ana-wa-aru.jpg",
            detail.posterUrl,
        )
    }

    @Test
    fun `extrae metadatos del widget`() {
        assertEquals("Serie", detail.type)
        assertEquals(AiringStatus.CURRENTLY, detail.status)
        assertEquals("En emision", detail.statusLabel)
        assertEquals(12, detail.totalEpisodes)
        assertEquals("24 min.", detail.duration)
        assertEquals("1080p", detail.quality)
        assertTrue(detail.genres.contains("Comedia"))
        assertTrue(detail.studios.contains("Passione"))
        assertTrue(detail.demographics.contains("Shounen"))
    }

    @Test
    fun `extrae ultimo episodio y proximo estreno`() {
        assertEquals(5, detail.lastEpisodeNumber)
        assertEquals("https://jkanime.net/seitokai-ni-mo-ana-wa-aru/5/", detail.lastEpisodeUrl)
        assertNotNull(detail.nextEpisodeLabel)
    }
}
