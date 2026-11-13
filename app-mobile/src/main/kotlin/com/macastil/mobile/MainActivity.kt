package com.macastil.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.macastil.mobile.ui.detail.DetailScreen
import com.macastil.mobile.ui.favorites.FavoritesScreen
import com.macastil.mobile.ui.home.HomeScreen
import com.macastil.mobile.ui.home.HomeViewModel
import com.macastil.mobile.ui.home.HomeViewModelFactory
import com.macastil.mobile.ui.player.PlayerScreen
import com.macastil.mobile.ui.sync.SyncScreen
import com.macastil.tv.catalog.JkanimeRepository
import androidx.lifecycle.viewmodel.compose.viewModel

sealed interface Screen {
    object Home : Screen
    object Favorites : Screen
    object Sync : Screen
    data class Detail(val slug: String) : Screen
    data class Player(
        val slug: String,
        val episodeNumber: Int,
        val episodeUrl: String,
        val episodeTitle: String,
    ) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MobileApp()
        }
    }
}

@Composable
fun MobileApp() {
    val repository = JkanimeRepository()
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(repository),
    )

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                onOpenDetail = { slug ->
                    screen = Screen.Detail(slug)
                },
                onOpenFavorites = {
                    screen = Screen.Favorites
                },
                onOpenSync = {
                    screen = Screen.Sync
                },
            )
            Screen.Favorites -> FavoritesScreen(
                onBack = {
                    screen = Screen.Home
                },
            )
            Screen.Sync -> SyncScreen(
                onBack = {
                    screen = Screen.Home
                },
            )
            is Screen.Detail -> DetailScreen(
                slug = current.slug,
                onPlay = { slug, episodeNumber, episodeUrl, episodeTitle ->
                    screen = Screen.Player(slug, episodeNumber, episodeUrl, episodeTitle)
                },
                onBack = {
                    screen = Screen.Home
                },
            )
            is Screen.Player -> PlayerScreen(
                episodeUrl = current.episodeUrl,
                referer = null,
                onExit = {
                    screen = Screen.Detail(current.slug)
                },
            )
        }
    }
}
