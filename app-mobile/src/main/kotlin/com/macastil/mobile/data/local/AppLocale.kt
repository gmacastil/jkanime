package com.macastil.mobile.data.local

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Idioma de la app móvil ("Sistema" / "Español" / "English").
 *
 * Mismo patrón que `AppLocale` de la TV: la preferencia vive en las
 * SharedPreferences de ajustes del módulo, [wrap] envuelve el Context base en
 * `MainActivity.attachBaseContext` y, al cambiarla, la activity se recrea para
 * que vuelva a pasar por ahí y Android resuelva `values-*` de nuevo.
 *
 * Sin appcompat no hay per-app locale (API 33+ `LocaleManager` sí, pero
 * `minSdk` es 24), así que `createConfigurationContext` es lo que hace el
 * trabajo en todas las versiones.
 */
object AppLocale {
    const val SYSTEM = "system"
    const val ES = "es"
    const val EN = "en"

    /** Ajustes del módulo (idioma y nombre del perfil). */
    const val PREFS = "jk-mobile-settings"
    const val KEY_LANG = "lang"

    fun current(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, SYSTEM) ?: SYSTEM

    fun set(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, code)
            .apply()
    }

    /** Con [SYSTEM] devuelve el mismo Context (la app sigue al dispositivo). */
    fun wrap(base: Context): Context {
        val code = current(base)
        if (code == SYSTEM) {
            // Al volver a "Sistema" hay que devolver el locale del teléfono:
            // `Locale.setDefault` pudo quedarse con el anterior.
            val system = Locale.getDefault()
            Locale.setDefault(system)
            return base
        }
        val locale = Locale(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
