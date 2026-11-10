package com.macastil.tv.data.local

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Preferencia de idioma de la app ("idioma por defecto" en el perfil).
 *
 * Valores: [SYSTEM] (predeterminado: sigue al locale del dispositivo),
 * [ES] o [EN]. Vive en las mismas SharedPreferences que los perfiles
 * ("jk-profiles") para que `attachBaseContext` pueda leerla de forma
 * síncrona antes de que exista cualquier objeto inyectado.
 *
 * La preferencia no la aplica el sistema (no hay appcompat ni
 * per-app locales pre-API 33): [wrap] envuelve el Context base con
 * `createConfigurationContext` en `MainActivity.attachBaseContext`, y al
 * cambiarla se recrea la activity para que vuelva a pasar por ahí.
 */
object AppLocale {
    const val SYSTEM = "system"
    const val ES = "es"
    const val EN = "en"

    /** Clave en SharedPreferences (las mismas que usa [ProfileManager]). */
    const val KEY_LANG = "lang"

    /** Idioma guardado para este dispositivo ("system" = el del sistema). */
    fun current(context: Context): String =
        context.getSharedPreferences(ProfileManager.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, SYSTEM) ?: SYSTEM

    /**
     * Envuelve el [Context] base con el idioma elegido. Con [SYSTEM]
     * devuelve el mismo objeto (la app sigue al dispositivo).
     *
     * `Locale.setDefault` además alinea a los componentes que leen el
     * locale global (fechas de Coil, formateos, etc.).
     */
    fun wrap(base: Context): Context {
        val code = current(base)
        if (code == SYSTEM) return base
        val locale = Locale(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
