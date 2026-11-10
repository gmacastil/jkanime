package com.macastil.tv.catalog.parser

import com.macastil.tv.model.Jk
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Utilidades compartidas por los parsers. Todo lo frágil vive aquí. */
internal object JkDom {

    private val RESERVED_SEGMENTS = setOf(
        "genero", "studio", "idioma", "directorio", "horario", "top", "estrenos",
        "buscar", "ajax", "jkplayer", "usuario", "notificaciones", "guardado",
        "historial", "comunidad", "pedidos", "aplicacion", "dash", "salir", "login",
    )

    fun document(html: String): Document = Jsoup.parse(html, Jk.BASE_URL)

    /**
     * `/seitokai-ni-mo-ana-wa-aru/5/` -> ("seitokai-ni-mo-ana-wa-aru", 5)
     * `/one-piece/`               -> ("one-piece", null)
     */
    fun parseAnimePath(url: String): Pair<String, Int?>? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        val slug = segments.first()
        if (slug.lowercase() in RESERVED_SEGMENTS) return null
        return slug to segments.getOrNull(1)?.toIntOrNull()
    }

    /** `<li><span>Tipo:</span> Serie</li>` -> ese `li`. */
    fun liByLabel(root: Element, label: String): Element? =
        root.select("li").firstOrNull { li ->
            li.selectFirst("span")?.text()?.trim()?.startsWith(label, ignoreCase = true) == true
        }

    /** Texto del `li` sin su etiqueta. */
    fun liValue(li: Element?): String? {
        if (li == null) return null
        val label = li.selectFirst("span")?.text()
        val full = li.text()
        val value = if (label != null) full.removePrefix(label).trim() else full.trim()
        return value.ifBlank { null }
    }

    fun metaContent(doc: Document, key: String): String? =
        doc.selectFirst("""meta[property="$key"], meta[name="$key"]""")
            ?.attr("content")
            ?.trim()
            ?.ifBlank { null }
}
