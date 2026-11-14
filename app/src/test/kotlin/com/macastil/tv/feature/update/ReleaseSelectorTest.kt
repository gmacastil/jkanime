package com.macastil.tv.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El actualizador de la TV debe ignorar los releases de móvil: desde 1.4.8 el
 * repositorio tiene `vX.Y.Z-tv` y `vX.Y.Z-mobile`, y el "latest" puede ser el
 * de móvil.
 */
class ReleaseSelectorTest {

    private val prefixes = listOf("jkanime-tv-")

    private fun release(tag: String, vararg assets: String) = ReleaseInfo(
        tag = tag,
        htmlUrl = "https://github.com/gmacastil/jkanime/releases/tag/$tag",
        assets = assets.map { ReleaseAsset(it, "https://example.com/$it") },
    )

    @Test
    fun `ignora el release de movil aunque sea el ultimo`() {
        val releases = listOf(
            release("v1.4.8-mobile", "jkanime-mobile-1.4.8.apk"),
            release("v1.4.8-tv", "jkanime-tv-1.4.8.apk"),
            release("v1.4.7", "jkanime-tv-1.4.7.apk", "jkanime-mobile-1.4.7.apk"),
        )

        val selected = ReleaseSelector.select(releases, prefixes, currentVersion = "1.4.6")

        assertEquals("1.4.8", selected?.versionName)
        assertEquals("https://example.com/jkanime-tv-1.4.8.apk", selected?.downloadUrl)
    }

    @Test
    fun `sin releases de tv no ofrece nada`() {
        val releases = listOf(release("v1.5.0-mobile", "jkanime-mobile-1.5.0.apk"))

        assertNull(ReleaseSelector.select(releases, prefixes, currentVersion = "1.4.8"))
    }

    @Test
    fun `no ofrece la version instalada ni una anterior`() {
        val releases = listOf(
            release("v1.4.8-tv", "jkanime-tv-1.4.8.apk"),
            release("v1.4.7-tv", "jkanime-tv-1.4.7.apk"),
        )

        assertNull(ReleaseSelector.select(releases, prefixes, currentVersion = "1.4.8"))
    }

    @Test
    fun `elige la version mayor, no la primera de la lista`() {
        val releases = listOf(
            release("v1.4.7-tv", "jkanime-tv-1.4.7.apk"),
            release("v1.4.10-tv", "jkanime-tv-1.4.10.apk"),
            release("v1.4.9-tv", "jkanime-tv-1.4.9.apk"),
        )

        val selected = ReleaseSelector.select(releases, prefixes, currentVersion = "1.4.7")

        assertEquals("1.4.10", selected?.versionName)
    }

    @Test
    fun `acepta tags historicos sin sufijo de dispositivo`() {
        val releases = listOf(release("v1.4.5", "app-release.apk"))

        // Los releases antiguos no llevaban prefijo en el asset, así que con
        // los prefijos actuales no son candidatos: es intencionado, para no
        // arriesgar instalar la APK equivocada.
        assertNull(ReleaseSelector.select(releases, prefixes, currentVersion = "1.4.4"))

        val legacyPrefixes = listOf("app-release.apk")
        val legacy = ReleaseSelector.select(releases, legacyPrefixes, currentVersion = "1.4.4")
        assertEquals("1.4.5", legacy?.versionName)
    }

    @Test
    fun `baseVersion quita el prefijo v y el sufijo de dispositivo`() {
        assertEquals("1.4.8", ReleaseSelector.baseVersion("v1.4.8-tv"))
        assertEquals("1.4.8", ReleaseSelector.baseVersion("v1.4.8-mobile"))
        assertEquals("1.4.8", ReleaseSelector.baseVersion("v1.4.8"))
        assertNull(ReleaseSelector.baseVersion("v1.4.8-mobile-extra"))
        assertNull(ReleaseSelector.baseVersion("no-version"))
    }

    @Test
    fun `compara versiones numericamente, no como texto`() {
        assertTrue(ReleaseSelector.isNewer("1.4.10", "1.4.9"))
        assertTrue(ReleaseSelector.isNewer("1.5", "1.4.9"))
        assertTrue(!ReleaseSelector.isNewer("1.4.9", "1.4.10"))
        assertTrue(!ReleaseSelector.isNewer("1.4.8", "1.4.8"))
    }
}
