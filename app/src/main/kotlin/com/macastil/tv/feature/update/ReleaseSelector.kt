package com.macastil.tv.feature.update

/** Asset de un release de GitHub, reducido a lo que necesita el actualizador. */
data class ReleaseAsset(val name: String, val downloadUrl: String)

/** Release de GitHub, reducido a lo que necesita el actualizador. */
data class ReleaseInfo(
    val tag: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>,
)

/** Release elegido para actualizar esta app. */
data class SelectedRelease(
    val versionName: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

/**
 * Elección del release con el que actualizar.
 *
 * Desde 1.4.8 el repositorio publica dos releases por versión:
 * `vX.Y.Z-tv` (APK de Android TV) y `vX.Y.Z-mobile` (APK de teléfono). Como el
 * "último release" del repositorio puede ser el de móvil, consultar
 * `/releases/latest` haría que la TV descargase la APK equivocada (o, peor,
 * ninguna, porque `1.4.8-mobile` no es una versión numérica comparable).
 *
 * Por eso aquí se recorre la lista completa de releases y se acepta solo el
 * que trae el asset de este dispositivo, comparando la versión numérica del
 * tag base (`v1.4.8-tv` y `v1.4.8` cuentan como `1.4.8`).
 */
object ReleaseSelector {

    private val DEVICE_SUFFIXES = listOf("-tv", "-mobile")

    fun select(
        releases: List<ReleaseInfo>,
        assetPrefixes: List<String>,
        currentVersion: String,
    ): SelectedRelease? {
        val candidates = releases.mapNotNull { release ->
            val asset = release.assets.firstOrNull { candidate ->
                candidate.name.endsWith(".apk") &&
                    assetPrefixes.any { prefix -> candidate.name.startsWith(prefix) }
            } ?: return@mapNotNull null

            val version = baseVersion(release.tag) ?: return@mapNotNull null
            Triple(version, release, asset)
        }

        val best = candidates.maxWithOrNull { a, b -> compareVersions(a.first, b.first) }
            ?: return null

        if (!isNewer(best.first, currentVersion)) return null

        return SelectedRelease(
            versionName = best.first,
            downloadUrl = best.third.downloadUrl,
            releaseUrl = best.second.htmlUrl,
        )
    }

    /** `v1.4.8-tv` → `1.4.8`; `v1.4.8` → `1.4.8`. `null` si no es numérica. */
    internal fun baseVersion(tag: String): String? {
        var value = tag.trim().removePrefix("v").removePrefix("V")
        DEVICE_SUFFIXES.firstOrNull { value.endsWith(it) }?.let {
            value = value.removeSuffix(it)
        }
        val parts = value.split('.')
        return value.takeIf { parts.isNotEmpty() && parts.all { part -> part.toIntOrNull() != null } }
    }

    internal fun isNewer(candidate: String, current: String): Boolean {
        val left = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val right = current.split('.').mapNotNull { it.toIntOrNull() }
        if (left.isEmpty() || right.isEmpty()) return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.').mapNotNull { it.toIntOrNull() }
        val b = right.split('.').mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
