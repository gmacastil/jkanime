package com.macastil.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.macastil.tv.Graph
import com.macastil.tv.nav.JkNavHost
import com.macastil.tv.feature.update.AppUpdate
import com.macastil.tv.feature.update.UpdateManager
import com.macastil.tv.ui.component.TvActionButton
import com.macastil.tv.ui.theme.JkBackground
import com.macastil.tv.ui.theme.JkGreen
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkSurface
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary
import com.macastil.tv.data.local.AppLocale
import com.macastil.tv.ui.theme.JkTheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

class MainActivity : ComponentActivity() {

    /**
     * Idioma elegido en el perfil (AppLocale): hay que envolver el Context
     * ANTES de que el Activity cree recursos, o `stringResource` seguiría
     * resolviendo con el locale del sistema.
     */
    override fun attachBaseContext(newBase: android.content.Context?) {
        super.attachBaseContext(newBase?.let { AppLocale.wrap(it) } ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JkTheme {
                var showSplash by remember { mutableStateOf(true) }
                var update by remember { mutableStateOf<AppUpdate?>(null) }
                var checking by remember { mutableStateOf(true) }
                var installing by remember { mutableStateOf(false) }
                var updateError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                // Splash: 2 segundos con la imagen de la app; luego pasa a la UI
                // normal (JkNavHost). El delay es mínimo y permite que Room
                // termine de abrir la DB antes de la primera composición.
                LaunchedEffect(Unit) {
                    delay(2_000)
                    showSplash = false
                }

                // Comprobación de actualizaciones en paralelo (tras el splash).
                LaunchedEffect(Unit) {
                    delay(3_000)
                    update = UpdateManager.check(this@MainActivity)
                    checking = false
                }

                // Refresh de favoritos (aired episodes) en background, solo si
                // hay perfil activo y sync configurado. No bloquea la UI.
                LaunchedEffect(Unit) {
                    val sync = Graph.sync(this@MainActivity)
                    scope.launch {
                        // Pequeño retardo para que la navegación y Room estén listos.
                        delay(1_500)
                        runCatching { sync.syncFavoritesIfConfigured() }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(JkBackground),
                ) {
                    if (showSplash) {
                        SplashScreen()
                    } else {
                        JkNavHost()

                        if (!checking && update != null) {
                            UpdatePrompt(
                                update = update!!,
                                installing = installing,
                                error = updateError,
                                onInstall = {
                                    val candidate = update ?: return@UpdatePrompt
                                    installing = true
                                    updateError = null
                                    scope.launch {
                                        runCatching {
                                            UpdateManager.downloadAndInstall(this@MainActivity, candidate)
                                        }.onFailure {
                                            installing = false
                                            updateError = it.message
                                                ?: getString(R.string.update_start_error)
                                        }
                                    }
                                },
                                onDismiss = { update = null },
                            )
                        }

                    }
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.splash_background),
            contentDescription = stringResource(R.string.home_brand_cd),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        VersionLabel(modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/** Versión visible permanentemente en la esquina inferior derecha. */
@Composable
private fun VersionLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.update_version_label, BuildConfig.VERSION_NAME),
        color = Color.White.copy(alpha = 0.72f),
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .padding(end = 18.dp, bottom = 14.dp),
    )
}

@Composable
private fun UpdatePrompt(
    update: AppUpdate,
    installing: Boolean,
    error: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val installFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { installFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(560.dp),
            colors = SurfaceDefaults.colors(
                containerColor = JkSurface,
                contentColor = JkTextPrimary,
            ),
        ) {
            Column(modifier = Modifier.padding(30.dp)) {
                Text(
                    stringResource(R.string.update_new_version),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.update_version_label, update.versionName),
                    color = JkOrange,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = if (installing) {
                        stringResource(R.string.update_downloading_body)
                    } else {
                        stringResource(R.string.update_body)
                    },
                    color = JkTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        color = JkOrange,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 24.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    TvActionButton(
                        text = if (installing) {
                            stringResource(R.string.update_downloading)
                        } else {
                            stringResource(R.string.update_action)
                        },
                        primary = true,
                        enabled = !installing,
                        modifier = Modifier.focusRequester(installFocus),
                        onClick = onInstall,
                    )
                    TvActionButton(
                        text = stringResource(R.string.common_now),
                        primary = false,
                        enabled = !installing,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}
