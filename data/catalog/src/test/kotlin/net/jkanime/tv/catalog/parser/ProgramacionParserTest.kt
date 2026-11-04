package net.jkanime.tv.catalog.parser

import net.jkanime.tv.catalog.Fixtures
import net.jkanime.tv.model.ProgramacionSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramacionParserTest {

    private val result = ProgramacionParser.parse(Fixtures.text("home.html"))

    @Test
    fun `devuelve las tres secciones soportadas`() {
        assertEquals(
            listOf(ProgramacionSection.ANIMES, ProgramacionSection.DONGHUAS, ProgramacionSection.OVAS),
            result.keys.toList(),
        )
    }

    @Test
    fun `animes tiene tarjetas con episodio`() {
        val animes = result.getValue(ProgramacionSection.ANIMES)
        assertTrue("se esperaban tarjetas en Animes, llegaron ${animes.size}", animes.size >= 8)
    }

    @Test
    fun `donghuas tiene tarjetas`() {
        assertTrue(result.getValue(ProgramacionSection.DONGHUAS).isNotEmpty())
    }

    @Test
    fun `cada tarjeta trae slug episodio poster y fecha`() {
        val cards = result.values.flatten()
        assertTrue(cards.isNotEmpty())

        cards.forEach { card ->
            assertTrue("slug vacío en $card", card.slug.isNotBlank())
            assertNotNull("sin número de episodio en $card", card.episodeNumber)
            assertTrue("poster inválido en $card", card.posterUrl?.startsWith("http") == true)
            assertTrue("url de episodio inválida en $card", card.episodeUrl.startsWith("https://jkanime.net/"))
            assertTrue("título vacío en $card", card.title.isNotBlank())
        }
    }

    @Test
    fun `la tarjeta destacada se parsea completa`() {
        val first = result.getValue(ProgramacionSection.ANIMES).first()

        assertEquals("kikansha-no-mahou-wa-tokubetsu-desu-2nd-season", first.slug)
        assertEquals(5, first.episodeNumber)
        assertEquals("https://jkanime.net/kikansha-no-mahou-wa-tokubetsu-desu-2nd-season/5/", first.episodeUrl)
        assertEquals("Hoy", first.dateLabel)
        assertEquals(
            "https://cdn.jkdesa.com/assets/images/animes/image/kikansha-no-mahou-wa-tokubetsu-desu-2nd-season.jpg",
            first.posterUrl,
        )
        assertTrue(first.title.startsWith("Kikansha no Mahou"))
    }

    @Test
    fun `no hay tarjetas duplicadas dentro de una seccion`() {
        result.forEach { (section, cards) ->
            val urls = cards.map { it.episodeUrl }
            assertEquals("duplicados en $section", urls.distinct().size, urls.size)
        }
    }
}
