package com.macastil.mobile.ui.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.macastil.tv.ui.theme.JkTextSecondary

class SyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SyncScreen()
        }
    }
}

@Composable
fun SyncScreen(
    onBack: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Sincronización",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.height(24.dp),
        )

        Text(
            text = "Funcionalidad de sincronización en desarrollo",
            style = MaterialTheme.typography.bodyMedium,
            color = JkTextSecondary,
        )

        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier.height(16.dp),
        )

        androidx.compose.material3.Button(
            onClick = {},
        ) {
            Text(text = "Crear espacio (próximamente)")
        }
        androidx.compose.material3.Button(
            onClick = {},
        ) {
            Text(text = "Vincular dispositivo (próximamente)")
        }
    }
}
