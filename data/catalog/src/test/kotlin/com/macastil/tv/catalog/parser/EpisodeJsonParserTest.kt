package com.macastil.tv.catalog.parser

import com.macastil.tv.catalog.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeJsonParserTest {

    private val page = EpisodeJsonParser.parse(
        Fixtures.text("episodes_page1.json"),
        animeId = 4967,
        slug = "seitokai-ni-mo-ana-wa-aru",
    )

    @Test
    fun `lee la paginacion`() {
        assertEquals(1, page.currentPage)
        assertEquals(1, page.lastPage)
        assertEquals(5, page.total)
        assertFalse(page.hasNext)
    }

    @Test
    fun `lee los episodios`() {
        assertEquals(5, page.episodes.size)

        val first = page.episodes.first()
        assertEquals(78383L, first.id)
        assertEquals(1.0, first.number, 0.0001)
        assertEquals("Seitokai ni mo Ana wa Aru! - 1", first.title)
        assertEquals("https://jkanime.net/seitokai-ni-mo-ana-wa-aru/1/", first.url)

        val last = page.episodes.last()
        assertEquals(5.0, last.number, 0.0001)
        assertEquals(4967, last.animeId)
    }

    @Test
    fun `compone la url del thumbnail en el cdn`() {
        val thumb = page.episodes.first().thumbUrl
        assertTrue(thumb != null)
        assertTrue(thumb!!.startsWith("https://cdn.jkdesa.com/assets/images/animes/video/image/"))
        assertTrue(thumb.endsWith(".jpg"))
    }

    @Test
    fun `soporta numeros decimales de episodio`() {
        val json = """
            {"current_page":1,"last_page":1,"total":1,
             "data":[{"id":1,"number":12.5,"title":"Especial","image":"x.jpg","timestamp":"2026-01-01"}]}
        """.trimIndent()

        val parsed = EpisodeJsonParser.parse(json, animeId = 10, slug = "demo")
        assertEquals(12.5, parsed.episodes.first().number, 0.0001)
        assertEquals("12.5", parsed.episodes.first().numberLabel)
        assertEquals("https://jkanime.net/demo/12/", parsed.episodes.first().url)
    }
}
