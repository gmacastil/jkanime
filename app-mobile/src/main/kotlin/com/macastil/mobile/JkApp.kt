package com.macastil.mobile

import android.app.Application

/**
 * Arranque de `app-mobile`: inicializa el grafo de dependencias una sola vez.
 *
 * `com.macastil.tv.Graph` vive en el módulo `:app` (TV) y no es compartido; por
 * ahora cada app inicializa lo suyo. Si más adelante se unifica, bastará mover
 * `Graph` a un módulo `:core:graph` y que ambas dependan de él.
 */
class JkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        JkGraph.init(this)
    }
}
