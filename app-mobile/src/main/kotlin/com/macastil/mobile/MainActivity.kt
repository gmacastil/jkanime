package com.macastil.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.macastil.mobile.data.local.AppLocale
import com.macastil.tv.ui.theme.JkTextSecondary
import com.macastil.mobile.ui.detail.DetailScreen
import com.macastil.mobile.ui.favorites.FavoritesScreen
import com.macastil.mobile.ui.home.HomeScreen
import com.macastil.mobile.ui.player.PlayerScreen
import com.macastil.mobile.ui.sync.SyncScreen
import com.macastil.tv.ui.theme.JkBackground
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkSurface
import com.macastil.tv.ui.theme.JkSurfaceHigh
import com.macastil.tv.ui.theme.JkTextPrimary

/**
 * Pantallas de la app móvil.
 *
 * Navegación por estado propio (sin Navigation Compose): son 5 destinos
 * lineales y así se evita arrastrar el grafo de navegación y sus rutas.
 */
sealed interface Screen {
    data object Home : Screen
    data object Favorites : Screen
    data object Sync : Screen
    data class Detail(val slug: String) : Screen
    data class Player(
        val slug: String,
        val episodeNumber: Int,
        val episodeUrl: String,
        val episodeTitle: String,
    ) : Screen
}

class MainActivity : ComponentActivity() {
    /**
     * Idioma elegido en el perfil (AppLocale): hay que envolver el Context base
     * aquí, antes de que se resuelvan los recursos, igual que en la app TV.
     * Al cambiarlo desde el menú se recrea la activity para volver a pasar.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase?.let { AppLocale.wrap(it) } ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JkGraph.init(applicationContext)
        // Paridad con la TV: al entrar al perfil se sincroniza en segundo plano
        // (en la TV lo dispara el gate de perfiles). Sin esto la biblioteca del
        // teléfono solo se actualizaba con el botón "Sincronizar ahora".
        JkGraph.syncEngine.syncInBackground(applicationContext)
        setContent {
            MaterialTheme(colorScheme = JkMobileColors) {
                MobileApp(
                    onLanguageChanged = {
                        AppLocale.set(applicationContext, it)
                        recreate()
                    },
                )
            }
        }
    }
}

/**
 * Paleta de la app móvil. Se reutilizan los colores de `:core:ui` (los mismos
 * de la TV) sobre un esquema **oscuro** de Material3: `JkTextPrimary` y
 * `JkTextSecondary` están pensados para fondo oscuro y sobre el tema claro por
 * defecto quedaban casi invisibles.
 */
private val JkMobileColors = darkColorScheme(
    primary = JkOrange,
    onPrimary = Color(0xFF141414),
    background = JkBackground,
    onBackground = JkTextPrimary,
    surface = JkSurface,
    onSurface = JkTextPrimary,
    surfaceVariant = JkSurfaceHigh,
    onSurfaceVariant = JkTextSecondary,
)

@Composable
fun MobileApp(onLanguageChanged: (String) -> Unit = {}) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Botón/gesto "atrás" de Android: cierra el reproductor y el detalle en
    // lugar de sacar al usuario de la app (síntoma reportado como "se sale").
    BackHandler(enabled = screen != Screen.Home) {
        screen = when (val current = screen) {
            is Screen.Player -> Screen.Detail(current.slug)
            is Screen.Detail -> Screen.Home
            else -> Screen.Home
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                onOpenDetail = { slug -> screen = Screen.Detail(slug) },
                onOpenFavorites = { screen = Screen.Favorites },
                onOpenSync = { screen = Screen.Sync },
                onLanguageChanged = onLanguageChanged,
            )

            Screen.Favorites -> FavoritesScreen(
                onOpenDetail = { slug -> screen = Screen.Detail(slug) },
                onBack = { screen = Screen.Home },
            )

            Screen.Sync -> SyncScreen(
                onBack = { screen = Screen.Home },
            )

            is Screen.Detail -> DetailScreen(
                slug = current.slug,
                onPlay = { slug, episodeNumber, episodeUrl, episodeTitle ->
                    screen = Screen.Player(slug, episodeNumber, episodeUrl, episodeTitle)
                },
                onBack = { screen = Screen.Home },
            )

            is Screen.Player -> PlayerScreen(
                slug = current.slug,
                episodeNumber = current.episodeNumber,
                episodeUrl = current.episodeUrl,
                episodeTitle = current.episodeTitle,
                onExit = { screen = Screen.Detail(current.slug) },
            )
        }
    }
}
