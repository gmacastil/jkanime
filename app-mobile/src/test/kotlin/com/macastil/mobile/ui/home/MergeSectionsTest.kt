package com.macastil.mobile.ui.home

import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.ProgramacionSection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regresión del crash en dispositivo real:
 * `IllegalArgumentException: Key "..." was already used`.
 *
 * El `slug` es el `key` de la grilla del Home, y el sitio publica **un card por
 * episodio**, así que un anime con varios episodios nuevos repetía el `slug` y
 * tumbaba la app al componer la lista.
 */
class MergeSectionsTest {

    private fun card(
        slug: String,
        episode: Int?,
        section: ProgramacionSection = ProgramacionSection.ANIMES,
    ) = AnimeCard(
        slug = slug,
        title = slug,
        episodeNumber = episode,
        episodeUrl = "https://jkanime.net/$slug/$episode/",
        thumbUrl = null,
        posterUrl = null,
        dateLabel = null,
        section = section,
    )

    @Test
    fun `aplana las secciones conservando su orden`() {
        val sections = mapOf(
            ProgramacionSection.ANIMES to listOf(card("a", 1)),
            ProgramacionSection.DONGHUAS to listOf(
                card("b", 2, ProgramacionSection.DONGHUAS),
            ),
            ProgramacionSection.OVAS to listOf(card("c", 3, ProgramacionSection.OVAS)),
        )

        assertEquals(listOf("a", "b", "c"), mergeSections(sections).map { it.slug })
    }

    @Test
    fun `deja un solo card por anime con el capitulo mas alto`() {
        // Caso real: bai-jia-jue-zhi-jianghu-gui-shi-lu publicó Ep 11, 13 y 12.
        val sections = mapOf(
            ProgramacionSection.ANIMES to listOf(
                card("bai-jia", 11),
                card("bai-jia", 13),
                card("bai-jia", 12),
            ),
        )

        val merged = mergeSections(sections)

        assertEquals(1, merged.size)
        assertEquals(13, merged.single().episodeNumber)
    }

    @Test
    fun `conserva la posicion de la primera aparicion`() {
        val sections = mapOf(
            ProgramacionSection.ANIMES to listOf(
                card("primero", 1),
                card("segundo", 2),
                card("primero", 5),
            ),
        )

        val merged = mergeSections(sections)

        assertEquals(listOf("primero", "segundo"), merged.map { it.slug })
        assertEquals(5, merged.first().episodeNumber)
    }

    @Test
    fun `elimina el repetido entre secciones distintas`() {
        val sections = mapOf(
            ProgramacionSection.ANIMES to listOf(card("repetido", 4)),
            ProgramacionSection.OVAS to listOf(
                card("repetido", 9, ProgramacionSection.OVAS),
            ),
        )

        val merged = mergeSections(sections)

        assertEquals(1, merged.size)
        assertEquals(9, merged.single().episodeNumber)
    }

    @Test
    fun `un card sin numero de episodio no gana al que si lo tiene`() {
        val sections = mapOf(
            ProgramacionSection.ANIMES to listOf(card("sin-ep", null), card("sin-ep", 7)),
        )

        assertEquals(7, mergeSections(sections).single().episodeNumber)
    }

    @Test
    fun `no colapsa slugs distintos`() {
        val sections = mapOf(
            ProgramacionSection.ANIMES to listOf(card("bai-jia", 1), card("bai-jia-2", 2)),
        )

        assertEquals(2, mergeSections(sections).size)
    }

    @Test
    fun `sin secciones devuelve lista vacia`() {
        assertEquals(emptyList<AnimeCard>(), mergeSections(emptyMap()))
    }

    @Test
    fun `una seccion vacia no afecta al resultado`() {
        val sections = mapOf(
            ProgramacionSection.ANIMES to emptyList(),
            ProgramacionSection.OVAS to listOf(card("ova", 3, ProgramacionSection.OVAS)),
        )

        assertEquals(listOf("ova"), mergeSections(sections).map { it.slug })
    }
}
