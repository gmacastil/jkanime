package net.jkanime.tv.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.jkanime.tv.Graph
import net.jkanime.tv.feature.detail.DetailScreen
import net.jkanime.tv.feature.directorio.DirectorioScreen
import net.jkanime.tv.feature.home.HomeScreen
import net.jkanime.tv.feature.player.PlayerActivity
import net.jkanime.tv.feature.profiles.ProfileGateScreen
import net.jkanime.tv.feature.profiles.ProfilesScreen
import net.jkanime.tv.feature.sync.SyncScreen

object Routes {
    const val HOME = "home"
    const val SYNC = "sync"
    const val DIRECTORIO = "directorio"
    const val PROFILES = "profiles"
    const val PROFILE_GATE = "profile_gate"
    const val DETAIL = "detail/{slug}"
    fun detail(slug: String) = "detail/$slug"
}

@Composable
fun JkNavHost() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Si no hay perfil configurado (F11), la app arranca en la puerta
    // tipo Netflix en vez de en Home.
    val startDestination = remember {
        if (Graph.profiles(context).activeId.value == 0) {
            Routes.PROFILE_GATE
        } else {
            Routes.HOME
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.PROFILE_GATE) {
            ProfileGateScreen(
                onReady = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PROFILE_GATE) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onOpenAnime = { slug -> navController.navigate(Routes.detail(slug)) },
                onOpenSync = { navController.navigate(Routes.SYNC) },
                onOpenDirectorio = { navController.navigate(Routes.DIRECTORIO) },
                onOpenProfiles = { navController.navigate(Routes.PROFILES) },
            )
        }

        composable(Routes.SYNC) {
            SyncScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PROFILES) {
            ProfilesScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DIRECTORIO) {
            DirectorioScreen(
                onOpenAnime = { slug -> navController.navigate(Routes.detail(slug)) },
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("slug") { type = NavType.StringType }),
        ) { entry ->
            val slug = entry.arguments?.getString("slug").orEmpty()
            DetailScreen(
                slug = slug,
                onBack = { navController.popBackStack() },
                onPlay = { animeSlug, number, url, title ->
                    navController.context.startActivity(
                        PlayerActivity.intent(
                            context = navController.context,
                            slug = animeSlug,
                            episodeNumber = number,
                            episodeUrl = url,
                            title = title,
                        ),
                    )
                },
            )
        }
    }
}
