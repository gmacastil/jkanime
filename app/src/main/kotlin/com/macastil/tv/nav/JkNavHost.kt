package com.macastil.tv.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.macastil.tv.Graph
import com.macastil.tv.feature.detail.DetailScreen
import com.macastil.tv.feature.directorio.DirectorioScreen
import com.macastil.tv.feature.home.HomeScreen
import com.macastil.tv.feature.player.PlayerActivity
import com.macastil.tv.feature.profiles.ProfileGateScreen

object Routes {
    const val HOME = "home"
    const val DIRECTORIO = "directorio"
    const val PROFILE_GATE = "profile_gate"
    const val PROFILE_SWITCH = "profile_switch"
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
                onOpenDirectorio = { navController.navigate(Routes.DIRECTORIO) },
                onOpenProfileSwitch = { navController.navigate(Routes.PROFILE_SWITCH) },
            )
        }

        // Reingreso al selector de perfiles (F12): se llega saliendo de Home,
        // nunca desde el menú lateral. A diferencia de PROFILE_GATE, siempre
        // arranca en el chooser y BACK vuelve a Home sin tocar el activo.
        composable(Routes.PROFILE_SWITCH) {
            ProfileGateScreen(
                startInChooser = true,
                onReady = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
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
