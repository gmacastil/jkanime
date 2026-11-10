package com.macastil.tv.catalog.parser

import com.macastil.tv.catalog.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectorioParserTest {

    private val page = DirectorioParser.parse(Fixtures.text("directorio.html"))

    @Test
    fun `trae los filtros del formulario del sitio`() {
        val names = page.filters.map { it.name }
        assertEquals(
            listOf("filtro", "genero", "letra", "demografia", "categoria", "tipo", "estado", "fecha", "temporada", "orden"),
            names,
        )
    }

    @Test
    fun `cada filtro trae una etiqueta legible y al menos dos opciones`() {
        page.filters.forEach { filter ->
            assertTrue("sin etiqueta en ${filter.name}", filter.label.isNotBlank())
            assertTrue("muy pocas opciones en ${filter.name}: ${filter.options.size}", filter.options.size >= 2)
        }
    }

    @Test
    fun `letra y fecha usan el texto como valor al no traer atributo value`() {
        val letra = page.filters.first { it.name == "letra" }
        assertTrue(letra.options.any { it.value == "A" && it.label == "A" })

        val fecha = page.filters.first { it.name == "fecha" }
        assertTrue(fecha.options.any { it.value.length == 4 && it.value.all(Char::isDigit) })
    }

    @Test
    fun `extrae los resultados embebidos en var animes`() {
        assertEquals(30, page.hits.size)
        assertEquals(1, page.page)
        assertTrue("se esperaba more de una pagina", page.lastPage > 1)
        assertTrue("se esperaba un total grande", page.total > 1000)
    }

    @Test
    fun `cada resultado trae slug titulo y poster absoluto`() {
        page.hits.forEach { hit ->
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
    fun `no hay slugs duplicados`() {
        assertEquals(page.hits.size, page.hits.distinctBy { it.slug }.size)
    }

    @Test
    fun `una pagina sin filtros ni resultados devuelve listas vacias`() {
        val empty = DirectorioParser.parse("<html><body><p>nada</p></body></html>")
        assertTrue(empty.filters.isEmpty())
        assertTrue(empty.hits.isEmpty())
        assertEquals(1, empty.page)
        assertEquals(1, empty.lastPage)
    }
}
