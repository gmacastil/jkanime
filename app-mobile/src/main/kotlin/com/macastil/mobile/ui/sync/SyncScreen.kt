package com.macastil.mobile.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macastil.tv.ui.theme.JkTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit = {},
) {
    val viewModel: SyncViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var joinCode by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sincronización") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Comparte favoritos y progreso entre el televisor y el teléfono.",
                style = MaterialTheme.typography.bodyMedium,
                color = JkTextSecondary,
            )

            val code = state.code
            if (code == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Sin espacio vinculado",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(
                            onClick = viewModel::createSpace,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Crear espacio nuevo")
                        }

                        Text(
                            text = "¿Ya tienes un espacio en otro dispositivo?",
                            style = MaterialTheme.typography.bodySmall,
                            color = JkTextSecondary,
                        )
                        OutlinedTextField(
                            value = joinCode,
                            onValueChange = { joinCode = it.filter(Char::isDigit).take(8) },
                            label = { Text("Código de 8 dígitos") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { viewModel.join(joinCode) },
                            enabled = !state.busy && joinCode.length == 8,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Vincular este dispositivo")
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Espacio",
                            style = MaterialTheme.typography.labelLarge,
                            color = JkTextSecondary,
                        )
                        Text(
                            text = code,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Button(
                            onClick = viewModel::syncNow,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Sincronizar ahora")
                        }

                        state.lastSync?.let {
                            Text(
                                text = "Última sincronización: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF3DDC84),
                            )
                        }

                        OutlinedButton(
                            onClick = viewModel::newPairingCode,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Generar código para otro dispositivo")
                        }

                        val pairing = state.pairing
                        if (pairing != null) {
                            Text(
                                text = "Código: ${pairing.code}",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFFFF6B35),
                            )
                            Text(
                                text = "Escríbelo en el otro dispositivo. Caduca en 15 minutos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = JkTextSecondary,
                            )
                        }

                        OutlinedButton(
                            onClick = viewModel::clearSpace,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Desvincular espacio")
                        }
                    }
                }
            }

            val message = state.message
            if (message != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = JkTextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
