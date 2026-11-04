package net.jkanime.tv.catalog

import kotlinx.coroutines.runBlocking
import net.jkanime.tv.model.ProgramacionSection
import net.jkanime.tv.model.ServerKind
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Smoke test contra el sitio real. Se salta por defecto para no depender de la
 * red en CI.
 *
 * ```bash
 * JK_LIVE=1 ./gradlew :data:catalog:test --tests "*LiveSmokeTest*"
 * ```
 */
class LiveSmokeTest {

    private val live = System.getProperty("jk.live") == "1"

    @Test
    fun `cadena completa programacion detalle episodios y fuentes`() = runBlocking {
        assumeTrue("definir JK_LIVE=1 para ejecutar el smoke test", live)

        val dataSource = JkanimeCatalogDataSource()

        val programacion = dataSource.loadProgramacion()
        val animes = programacion.getValue(ProgramacionSection.ANIMES)
        assertTrue("Programación vacía", animes.isNotEmpty())

        val card = animes.first()
        val detail = dataSource.loadDetail(card.slug)
        assertTrue("animeId inválido", detail.animeId > 0)
        assertTrue("título vacío", detail.title.isNotBlank())

        val episodes = dataSource.loadEpisodes(detail.animeId, detail.slug)
        assertTrue("sin episodios para ${detail.slug}", episodes.episodes.isNotEmpty())

        val episode = episodes.episodes.first()
        val servers = dataSource.loadServers(episode.url)
        assertTrue("sin fuentes para ${episode.url}", servers.isNotEmpty())
        assertTrue(
            "no hay ninguna fuente reproducible: ${servers.map { it.name to it.kind }}",
            servers.any { it.kind != ServerKind.DOWNLOAD },
        )
    }
}
