package com.macastil.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las reglas salen de `docs/supabase/schema.sql`:
 * `length(trim(key)) <> 6 or upper(trim(key)) !~ '^[0-9A-F]+$'` en
 * `sync_set_recovery` / `sync_recover`, y 8 dígitos en `sync_join`.
 */
class SyncKeysTest {

    @Test
    fun `la clave de recuperacion se filtra a hex en mayusculas`() {
        assertEquals("AB12CD", normalizeRecoveryKey("ab12cd"))
        assertEquals("FF00FF", normalizeRecoveryKey("FF00FF"))
    }

    @Test
    fun `la clave descarta lo que no es hexadecimal`() {
        assertEquals("AB12", normalizeRecoveryKey("a-b 1_2zz"))
        assertEquals("", normalizeRecoveryKey("ñç!·"))
    }

    @Test
    fun `la clave se corta a 6 caracteres`() {
        assertEquals("ABCDEF", normalizeRecoveryKey("abcdef123456"))
        // El gate de la TV llegaba a mandar 10 y el servidor lo rechazaba.
        assertEquals("FF00AA", normalizeRecoveryKey("FF00AA1234"))
    }

    @Test
    fun `una clave solo esta completa con 6 caracteres`() {
        assertFalse(isCompleteRecoveryKey("ABCDE"))
        assertTrue(isCompleteRecoveryKey("abcde1"))
        assertFalse(isCompleteRecoveryKey(""))
    }

    @Test
    fun `el codigo de vinculacion solo admite 8 digitos`() {
        assertEquals("12345678", normalizePairingCode("1234567890"))
        assertEquals("1234", normalizePairingCode("12-34 ab"))
        assertEquals("", normalizePairingCode("abcd"))
    }

    @Test
    fun `el codigo de vinculacion solo esta completo con 8 digitos`() {
        assertFalse(isCompletePairingCode("1234567"))
        assertTrue(isCompletePairingCode("1234 5678"))
        assertFalse(isCompletePairingCode("1234567a"))
    }

    @Test
    fun `el codigo de espacio se muestra en grupos de cuatro`() {
        assertEquals("0489 5806", formatCode("04895806"))
        assertEquals("1234", formatCode("1234"))
    }

    @Test
    fun `los huecos libres se ven en el display de la TV`() {
        assertEquals("AB·  ···", formatRecoveryKey("AB"))
        assertEquals("ABC  DEF", formatRecoveryKey("ABCDEF"))
        assertEquals("1234  ····", formatPairingCode("1234"))
        assertEquals("1234  5678", formatPairingCode("12345678"))
    }
}
