package net.jkanime.tv

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.jkanime.tv.sync.FavoritesSyncWorker

class JkApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // F5: sync de favoritos cada 6 h. Se programa una sola vez gracias a
        // ExistingPeriodicWorkPolicy.KEEP.
        FavoritesSyncWorker.schedule(this)

        // F6: un pull/push al arrancar, para que al abrir la app en otro
        // dispositivo se vean los cambios hechos en el resto sin esperar al
        // ciclo de 6 h. Es best-effort: si no hay red o no está vinculado,
        // no pasa nada.
        appScope.launch {
            runCatching { Graph.sync(this@JkApp).syncNow() }
        }
    }
}
