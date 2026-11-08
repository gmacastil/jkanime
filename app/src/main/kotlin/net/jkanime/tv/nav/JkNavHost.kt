package net.jkanime.tv.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.jkanime.tv.feature.detail.DetailScreen
import net.jkanime.tv.feature.directorio.DirectorioScreen
import net.jkanime.tv.feature.home.HomeScreen
import net.jkanime.tv.feature.player.PlayerActivity
import net.jkanime.tv.feature.profiles.ProfilesScreen
import net.jkanime.tv.feature.sync.SyncScreen

object Routes {
    const val HOME = "home"
    const val SYNC = "sync"
    const val DIRECTORIO = "directorio"
    const val PROFILES = "profiles"
    const val DETAIL = "detail/{slug}"
    fun detail(slug: String) = "detail/$slug"
}

@Composable
fun JkNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
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
