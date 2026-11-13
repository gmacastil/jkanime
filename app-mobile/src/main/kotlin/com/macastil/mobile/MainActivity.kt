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
import com.macastil.mobile.ui.home.HomeScreen
import com.macastil.mobile.ui.home.HomeViewModel
import com.macastil.mobile.ui.home.HomeViewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import androidx.lifecycle.viewmodel.compose.viewModel

sealed interface Screen {
    object Home : Screen
    data class Detail(val slug: String) : Screen
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
            )
            is Screen.Detail -> DetailScreen(
                slug = current.slug,
                onPlay = { slug, episodeNumber, episodeUrl, episodeTitle ->
                    // Fase 3: reproducir episodio
                },
            )
        }
    }
}
