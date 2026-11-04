package net.jkanime.tv

import net.jkanime.tv.catalog.JkanimeRepository

/**
 * DI manual. Deliberado: la app tiene 3 dependencias reales y Hilt solo
 * añadiría tiempo de build. Cuando entren Room + WorkManager se reevalúa.
 */
object Graph {
    val repository: JkanimeRepository by lazy { JkanimeRepository() }
}
