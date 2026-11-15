package com.macastil.mobile.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La clave de recuperación la valida el servidor con
 * `length(trim(key)) <> 6 or upper(trim(key)) !~ '^[0-9A-F]+$'`
 * (`sync_set_recovery` en `docs/supabase/schema.sql`), así que normalizar antes
 * de enviarla evita un `invalid_recovery_key_format` que el usuario no puede
 * interpretar.
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
    fun `el codigo de espacio se muestra en grupos de cuatro`() {
        assertEquals("0489 5806", formatCode("04895806"))
        assertEquals("1234", formatCode("1234"))
    }
}
