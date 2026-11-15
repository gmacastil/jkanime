package com.macastil.mobile.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.macastil.mobile.R
import com.macastil.mobile.data.local.AppLocale
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary

/**
 * Menú lateral de la app móvil.
 *
 * La TV tiene multiperfil + cajón con secciones; en el teléfono el usuario
 * pidió **un perfil único** pero con las mismas opciones que en la TV:
 * idioma y sincronización (más favoritos y refresco). Es lo que hay aquí.
 */
@Composable
fun AppDrawerContent(
    profileName: String,
    languageCode: String,
    syncSpace: String?,
    versionName: String,
    onRenameProfile: () -> Unit,
    onChangeLanguage: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenFavorites: () -> Unit,
    onRefresh: () -> Unit,
) {
    val defaultName = stringResource(R.string.home_drawer_profile_default)
    val name = profileName.ifBlank { defaultName }
    val languageLabel = languageLabel(languageCode)

    ModalDrawerSheet {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            // ----------------------------------------------------- perfil
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(JkOrange),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFF141414),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = JkTextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.home_drawer_profile_single_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = JkTextSecondary,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.home_drawer_rename),
                    tint = JkTextSecondary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = onRenameProfile),
                )
            }

            HorizontalDivider(color = JkTextSecondary.copy(alpha = 0.2f))
            DrawerSection(stringResource(R.string.home_drawer_group_profile))
            DrawerItem(
                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                label = stringResource(R.string.lang_title),
                trailing = languageLabel,
                onClick = onChangeLanguage,
            )
            DrawerItem(
                icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                label = stringResource(R.string.sync_title),
                trailing = syncSpace
                    ?.let { stringResource(R.string.home_drawer_sync_linked, it) }
                    ?: stringResource(R.string.home_drawer_sync_unlinked),
                onClick = onOpenSync,
            )

            HorizontalDivider(color = JkTextSecondary.copy(alpha = 0.2f))
            DrawerSection(stringResource(R.string.home_drawer_group_library))
            DrawerItem(
                icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                label = stringResource(R.string.home_drawer_favorites),
                onClick = onOpenFavorites,
            )
            DrawerItem(
                icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                label = stringResource(R.string.home_drawer_refresh),
                onClick = onRefresh,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_drawer_version, versionName),
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = JkTextSecondary,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun DrawerItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = icon,
        selected = false,
        onClick = onClick,
        badge = trailing?.let { { Text(it) } },
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** "Sistema (predeterminado)" / "Español" / "English" para la fila del cajón. */
@Composable
fun languageLabel(code: String): String = when (code) {
    AppLocale.ES -> stringResource(R.string.lang_es)
    AppLocale.EN -> stringResource(R.string.lang_en)
    else -> stringResource(R.string.lang_system)
}

/** Selector de idioma: Sistema / Español / English. */
@Composable
fun LanguageDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        AppLocale.SYSTEM to stringResource(R.string.lang_system),
        AppLocale.ES to stringResource(R.string.lang_es),
        AppLocale.EN to stringResource(R.string.lang_en),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lang_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.lang_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = JkTextSecondary,
                )
                options.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelect(code) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == code, onClick = { onSelect(code) })
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Cambiar el nombre del perfil único. */
@Composable
fun RenameProfileDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(24) },
                singleLine = true,
                label = { Text(stringResource(R.string.profile_rename_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value) },
                enabled = value.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
