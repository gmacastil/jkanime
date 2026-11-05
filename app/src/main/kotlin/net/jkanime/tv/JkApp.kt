package net.jkanime.tv

import android.app.Application
import net.jkanime.tv.sync.FavoritesSyncWorker

class JkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // F5: sync de favoritos cada 6 h. Se programa una sola vez gracias a
        // ExistingPeriodicWorkPolicy.KEEP.
        FavoritesSyncWorker.schedule(this)
    }
}
