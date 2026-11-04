package net.jkanime.tv.catalog.parser

import net.jkanime.tv.catalog.Fixtures
import net.jkanime.tv.model.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoServerParserTest {

    private val episodeUrl = "https://jkanime.net/seitokai-ni-mo-ana-wa-aru/5/"
    private val servers = VideoServerParser.parse(Fixtures.text("episode.html"), episodeUrl)

    @Test
    fun `decodifica los embeds en base64`() {
        val names = servers.map { it.name }
        assertTrue("faltan servidores: $names", servers.size >= 5)
        assertTrue(names.any { it.equals("Streamwish", ignoreCase = true) })

        val streamwish = servers.first { it.name.equals("Streamwish", ignoreCase = true) }
        assertEquals("https://hgcloud.to/e/hcac4lo417hw", streamwish.url)
        assertEquals(ServerKind.STREAM, streamwish.kind)
    }

    @Test
    fun `marca mega y mediafire como descarga`() {
        val mega = servers.firstOrNull { it.name.equals("Mega", ignoreCase = true) }
        assertNotNull("no se encontró Mega", mega)
        assertEquals(ServerKind.DOWNLOAD, mega!!.kind)
        assertTrue(mega.url.startsWith("https://mega.nz/embed/"))
    }

    @Test
    fun `extrae el player propio del sitio`() {
        val jk = servers.filter { it.kind == ServerKind.JKPLAYER }
        assertTrue("no se encontró jkplayer", jk.isNotEmpty())
        assertTrue(jk.first().url.contains("/jkplayer/"))
    }

    @Test
    fun `ordena primero los servidores reproducibles`() {
        val kinds = servers.map { it.kind }
        val firstDownload = kinds.indexOfFirst { it == ServerKind.DOWNLOAD }
        if (firstDownload >= 0) {
            assertTrue(
                "hay un servidor de descarga antes que uno reproducible: $kinds",
                kinds.take(firstDownload).none { it == ServerKind.DOWNLOAD },
            )
        }
    }

    @Test
    fun `sin fuentes devuelve el fallback web`() {
        val fallback = VideoServerParser.parse("<html><body>nada</body></html>", episodeUrl)
        assertEquals(1, fallback.size)
        assertEquals(ServerKind.WEB, fallback.first().kind)
        assertEquals(episodeUrl, fallback.first().url)
    }
}
