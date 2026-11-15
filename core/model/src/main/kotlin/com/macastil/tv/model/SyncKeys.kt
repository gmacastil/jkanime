package com.macastil.tv.model

/**
 * Normalización de las dos credenciales que el usuario teclea a mano.
 *
 * Compartido por la TV y el móvil (`:core:model` es un módulo JVM puro, sin
 * Android) porque el servidor es estricto y un carácter de más se traduce en un
 * error que el usuario no puede interpretar. En `docs/supabase/schema.sql`:
 *
 * - `sync_set_recovery` / `sync_recover` exigen **exactamente 6 caracteres hex**
 *   (`length(trim(key)) = 6 and upper(trim(key)) ~ '^[0-9A-F]+$'`).
 * - `sync_join` espera el **código temporal de 8 dígitos** que genera
 *   `sync_new_pairing`.
 *
 * Por eso la normalización vive aquí y no en cada pantalla: el teclado de la TV
 * (`TvHexPad`), el campo de texto del móvil (`OutlinedTextField`) y los tests
 * usan las mismas reglas.
 */

/** Longitud exacta que exige `sync_set_recovery` / `sync_recover`. */
const val RECOVERY_KEY_LENGTH: Int = 6

/** Longitud del código temporal que genera `sync_new_pairing`. */
const val PAIRING_CODE_LENGTH: Int = 8

/**
 * Código de espacio en grupos de 4: `04895806` → `0489 5806`.
 *
 * Es solo presentación; no recorta ni valida, para que se pueda usar también
 * con códigos ya validados.
 */
fun formatCode(code: String): String =
    code.filter(Char::isLetterOrDigit).chunked(4).joinToString(" ")

/**
 * Clave de recuperación: solo hex, en mayúsculas y con 6 como máximo.
 *
 * Se corta en vez de rechazar porque el usuario puede pegar o teclear de más
 * (el teclado de la TV llega a mandar 10 si se deja); el servidor solo mira los
 * 6 primeros.
 */
fun normalizeRecoveryKey(value: String): String =
    value.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        .uppercase()
        .take(RECOVERY_KEY_LENGTH)

/** `true` cuando la clave ya tiene los 6 caracteres que pide el servidor. */
fun isCompleteRecoveryKey(value: String): Boolean =
    normalizeRecoveryKey(value).length == RECOVERY_KEY_LENGTH

/** Código temporal: solo dígitos, 8 como máximo. */
fun normalizePairingCode(value: String): String =
    value.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)

/** `true` cuando el código ya tiene los 8 dígitos de `sync_join`. */
fun isCompletePairingCode(value: String): Boolean =
    normalizePairingCode(value).length == PAIRING_CODE_LENGTH

/**
 * Clave de recuperación con los huecos libres visibles, para el display de la
 * TV: `AB` → `AB·  ···  ···` (grupos de 3, igual que el teclado hex).
 */
fun formatRecoveryKey(value: String): String =
    value.padEnd(RECOVERY_KEY_LENGTH, '·').chunked(3).joinToString("  ")

/**
 * Código temporal con los huecos libres visibles: `1234` → `1234  ····`.
 */
fun formatPairingCode(value: String): String =
    value.padEnd(PAIRING_CODE_LENGTH, '·').chunked(4).joinToString("  ")
