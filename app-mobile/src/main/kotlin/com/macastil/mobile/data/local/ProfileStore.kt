package com.macastil.mobile.data.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Perfil único del dispositivo (la app móvil no tiene multiusuario: la TV sí).
 *
 * De momento solo guarda el nombre que se muestra en el menú lateral; la
 * biblioteca (favoritos/progreso) y el espacio de sincronización son los del
 * dispositivo, igual que en un perfil de la TV recién creado.
 *
 * Vacío significa "sin nombre": la UI usa el texto por defecto traducido
 * (`home_drawer_profile_default`), así que el nombre por defecto cambia con el
 * idioma en vez de quedar congelado en el idioma de la primera instalación.
 */
class ProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences(AppLocale.PREFS, Context.MODE_PRIVATE)

    private val _name = MutableStateFlow(prefs.getString(KEY_NAME, "").orEmpty())
    val name: StateFlow<String> = _name.asStateFlow()

    fun rename(value: String) {
        val clean = value.trim().take(24)
        prefs.edit().putString(KEY_NAME, clean).apply()
        _name.value = clean
    }

    private companion object {
        const val KEY_NAME = "profile_name"
    }
}
