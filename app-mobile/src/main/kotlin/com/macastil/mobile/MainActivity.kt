package com.macastil.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.macastil.mobile.ui.home.HomeScreen
import com.macastil.mobile.ui.home.HomeViewModel
import com.macastil.mobile.ui.home.HomeViewModelFactory
import com.macastil.tv.catalog.JkanimeRepository
import androidx.lifecycle.viewmodel.compose.viewModel

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
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModelFactory(repository),
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        HomeScreen(
            onOpenDetail = { slug ->
                // Fase 2: navegar al detalle
            },
        )
    }
}
