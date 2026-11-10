package net.jkanime.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.jkanime.tv.nav.JkNavHost
import net.jkanime.tv.feature.update.AppUpdate
import net.jkanime.tv.feature.update.UpdateManager
import net.jkanime.tv.ui.component.TvActionButton
import net.jkanime.tv.ui.theme.JkBackground
import net.jkanime.tv.ui.theme.JkOrange
import net.jkanime.tv.ui.theme.JkSurface
import net.jkanime.tv.ui.theme.JkTextPrimary
import net.jkanime.tv.ui.theme.JkTextSecondary
import net.jkanime.tv.data.local.AppLocale
import net.jkanime.tv.ui.theme.JkTheme

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
                var update by remember { mutableStateOf<AppUpdate?>(null) }
                var checking by remember { mutableStateOf(true) }
                var installing by remember { mutableStateOf(false) }
                var updateError by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    delay(3_000)
                    update = UpdateManager.check(this@MainActivity)
                    checking = false
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(JkBackground),
                ) {
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
                                        // `getString` directo: este lambda se
                                        // ejecuta al pulsar, fuera de composición.
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

@androidx.compose.runtime.Composable
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
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f)),
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
