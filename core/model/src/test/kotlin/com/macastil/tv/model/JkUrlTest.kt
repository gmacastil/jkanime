package com.macastil.tv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class JkUrlTest {

    @Test
    fun `directorio sin filtros no agrega query`() {
        assertEquals("https://jkanime.net/directorio", Jk.directorioUrl())
    }

    @Test
    fun `directorio omite filtros vacios`() {
        assertEquals(
            "https://jkanime.net/directorio?genero=accion",
            Jk.directorioUrl(
                linkedMapOf(
                    "genero" to "accion",
                    "tipo" to "",
                    "estado" to "   ",
                ),
            ),
        )
    }

    @Test
    fun `directorio codifica valores y pagina`() {
        assertEquals(
            "https://jkanime.net/directorio?temporada=oto%C3%B1o&fecha=2025&p=3",
            Jk.directorioUrl(
                linkedMapOf(
                    "temporada" to "otoño",
                    "fecha" to "2025",
                ),
                page = 3,
            ),
        )
    }

    @Test
    fun `directorio no agrega pagina uno`() {
        assertEquals(
            "https://jkanime.net/directorio?genero=aventura",
            Jk.directorioUrl(mapOf("genero" to "aventura"), page = 1),
        )
    }
}
