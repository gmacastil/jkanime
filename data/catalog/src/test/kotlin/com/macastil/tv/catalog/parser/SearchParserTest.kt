package com.macastil.tv.catalog.parser

import com.macastil.tv.catalog.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParserTest {

    private val hits = SearchParser.parse(Fixtures.text("search.html"))

    @Test
    fun `devuelve los resultados de la busqueda`() {
        assertTrue("se esperaban resultados, llegaron ${hits.size}", hits.size >= 15)
    }

    @Test
    fun `cada resultado trae slug titulo y poster absoluto`() {
        hits.forEach { hit ->
            assertTrue("slug vacío en $hit", hit.slug.isNotBlank())
            assertTrue("título vacío en $hit", hit.title.isNotBlank())
            assertNotNull("sin póster en $hit", hit.posterUrl)
            assertTrue(
                "el póster debe ser absoluto: ${hit.posterUrl}",
                hit.posterUrl!!.startsWith("https://"),
            )
        }
    }

    @Test
    fun `separa tipo y estado del li`() {
        val naruto = hits.first { it.slug == "naruto" }
        assertEquals("Naruto", naruto.title)
        assertEquals("Serie", naruto.type)
        assertEquals("Concluido", naruto.statusLabel)
    }

    @Test
    fun `no hay slugs duplicados`() {
        assertEquals(hits.size, hits.distinctBy { it.slug }.size)
    }

    @Test
    fun `una pagina sin resultados devuelve lista vacia`() {
        assertTrue(SearchParser.parse("<html><body><p>nada</p></body></html>").isEmpty())
    }
}
