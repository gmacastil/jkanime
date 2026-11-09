package net.jkanime.tv

import android.app.Application
import net.jkanime.tv.sync.FavoritesSyncWorker

class JkApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // F5: sync de favoritos cada 6 h. Se programa una sola vez gracias a
        // ExistingPeriodicWorkPolicy.KEEP.
        FavoritesSyncWorker.schedule(this)

        // F6: un pull/push al arrancar con el perfil que estuviera activo en
        // ese instante (si ninguno lo está, NotPaired y no hace nada). Si la
        // puerta de entrada (F11.1) activa/crea un perfil después, dispara su
        // propio syncInBackground() para ese perfil.
        Graph.syncInBackground(this)
    }
}
