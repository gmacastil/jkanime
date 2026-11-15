package com.macastil.mobile.ui.sync

/**
 * Normalización de las dos credenciales que se teclean a mano.
 *
 * Van aparte del ViewModel (y con tests) porque son la diferencia entre un
 * error legible y un `invalid_recovery_key_format` del servidor: la RPC exige
 * **exactamente 6 caracteres hex** en mayúsculas para la clave de recuperación
 * y **8 dígitos** para el código temporal de vinculación.
 */

/** Código de espacio en grupos de 4: `04895806` → `0489 5806`. */
fun formatCode(code: String): String =
    code.filter(Char::isLetterOrDigit).chunked(4).joinToString(" ")

/** Código temporal: solo dígitos, 8 como máximo. */
fun normalizePairingCode(value: String): String = value.filter(Char::isDigit).take(8)

/** Clave de recuperación: solo hex, en mayúsculas, 6 como máximo. */
fun normalizeRecoveryKey(value: String): String =
    value.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        .uppercase()
        .take(6)

/** `true` cuando la clave ya tiene los 6 caracteres que pide el servidor. */
fun isCompleteRecoveryKey(value: String): Boolean = normalizeRecoveryKey(value).length == 6
