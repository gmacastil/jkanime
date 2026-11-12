package com.macastil.mobile

import android.app.Application

/**
 * Inicialización mínima de app-mobile.
 *
 * Nota: `com.macastil.tv.Graph` vive en el módulo `:app` y no es compartido.
 * Cuando se quiera unificar el DI entre TV y móvil, mover `Graph` y sus
 * dependencias a un módulo `:core:graph` y que tanto `:app` como `:app-mobile`
 * dependan de él. Mientras tanto, cada app inicializa lo suyo aquí.
 */
class JkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Aquí se inicializarían los singletons de app-mobile (Room, Supabase, etc.)
        // usando los módulos compartidos :core:model, :core:network, :data:catalog.
    }
}
