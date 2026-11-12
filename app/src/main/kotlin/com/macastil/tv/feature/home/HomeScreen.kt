package com.macastil.tv.feature.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.macastil.tv.Graph
import com.macastil.tv.R
import com.macastil.tv.BuildConfig
import com.macastil.tv.data.local.FavoriteEntity
import com.macastil.tv.data.sync.SyncResult
import com.macastil.tv.model.AnimeCard
import com.macastil.tv.model.AnimeSearchHit
import com.macastil.tv.model.ProgramacionSection
import com.macastil.tv.feature.profiles.LanguageOptions
import com.macastil.tv.feature.profiles.ProfileAvatar
import com.macastil.tv.ui.component.ErrorState
import com.macastil.tv.ui.component.LoadingState
import com.macastil.tv.ui.component.PosterCard
import com.macastil.tv.ui.component.TvActionButton
import com.macastil.tv.ui.component.TvIconButton
import com.macastil.tv.ui.component.TvNumberPad
import com.macastil.tv.ui.component.TvRecoveryPad
import com.macastil.tv.ui.component.airingStatusColor
import com.macastil.tv.ui.theme.JkBackground
import com.macastil.tv.ui.theme.JkGreen
import com.macastil.tv.ui.theme.JkOrange
import com.macastil.tv.ui.theme.JkSurface
import com.macastil.tv.ui.theme.JkSurfaceHigh
import com.macastil.tv.ui.theme.JkTextPrimary
import com.macastil.tv.ui.theme.JkTextSecondary
import kotlinx.coroutines.launch

/**
 * Home = "Programación" del sitio, en tarjetas.
 *
 * Cabecera: botón hamburguesa + marca + campo de búsqueda siempre visible.
 * La búsqueda consulta el **buscador del sitio** (`GET /buscar/{q}`), no un
 * filtro local. Los filtros (Animes / Directorio / Favoritos) viven en un
 * menú lateral colapsable para dejarle el ancho a la grilla.
 * El avatar del perfil (dcha.) abre un menú con opciones de sync y cambio
 * de perfil.
 */
@Composable
fun HomeScreen(
    onOpenAnime: (String) -> Unit,
    onOpenDirectorio: () -> Unit = {},
    onOpenProfileSwitch: () -> Unit = {},
) {
    val context = LocalContext.current
    val library = remember(context) { Graph.library(context) }
    val profileManager = remember(context) { Graph.profiles(context) }
    val syncEngine = remember(context) { Graph.sync(context) }
    val scope = rememberCoroutineScope()
    val activeProfile by profileManager.activeProfile.collectAsStateWithLifecycle(initialValue = null)
    val lang by profileManager.lang.collectAsStateWithLifecycle()
    val viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(Graph.repository, library, context),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val showFavorites by viewModel.showFavorites.collectAsStateWithLifecycle()
    val drawerOpen by viewModel.drawerOpen.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()

    // Modal del avatar: opciones de sync + cambiar perfil
    var showAvatarMenu by remember { mutableStateOf(false) }
    var avatarMenuSyncCode by remember { mutableStateOf("") }
    var avatarMenuRecoveryKey by remember { mutableStateOf("") }
    var avatarMenuBusy by remember { mutableStateOf(false) }
    var avatarMenuStatus by remember { mutableStateOf<String?>(null) }
    var avatarMenuMode by remember { mutableStateOf(AvatarMenuMode.MAIN) }

    val searchFocus = remember { FocusRequester() }
    val resultsFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val firstDrawerItemFocus = remember { FocusRequester() }
    val configFirstItemFocus = remember { FocusRequester() }
    val avatarMenuFocus = remember { FocusRequester() }

    // Cada vez que se elige algo en el menú hay que devolver el foco a la
    // grilla: el ítem pulsado desaparece del árbol y el D-pad queda muerto.
    var focusNonce by remember { mutableIntStateOf(0) }
    var drawerWasOpen by remember { mutableStateOf(false) }

    // ▼ desde el campo de búsqueda cuando todavía no llegaron resultados: se
    // recuerda la intención y se enfoca la primera tarjeta en cuanto aparecen.
    var pendingGridFocus by remember { mutableStateOf(false) }

    val searching = query.isNotBlank()
    val sections = (state as? HomeUiState.Success)?.sections
    val contentEmpty = when {
        searching -> search.hits.isEmpty()
        showFavorites -> favorites.isEmpty()
        else -> sections?.get(section).isNullOrEmpty()
    }

    // Con la lista de favoritos vacía no hay ninguna tarjeta que enfocar y el
    // D-pad se quedaba muerto. Se devuelve el foco al botón del menú.
    LaunchedEffect(showFavorites, favorites.isEmpty()) {
        if (!showFavorites || favorites.isNotEmpty()) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (menuFocus.requestFocus()) return@LaunchedEffect
        }
    }

    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            drawerWasOpen = true
            repeat(10) {
                withFrameNanos { }
                if (firstDrawerItemFocus.requestFocus()) return@LaunchedEffect
            }
        } else if (drawerWasOpen) {
            // Se deja un par de frames a la grilla para que tome el foco; si
            // no hay nada que enfocar, al menos el menú queda alcanzable.
            repeat(4) { withFrameNanos { } }
            if (contentEmpty) menuFocus.requestFocus()
        }
    }

    // Los resultados llegan por red con retraso: si el usuario ya había pedido
    // bajar a la grilla, se le lleva al primero en cuanto existe.
    LaunchedEffect(search.hits, pendingGridFocus) {
        if (!pendingGridFocus) return@LaunchedEffect
        // Sin búsqueda activa no hay grilla de resultados: el destino es la
        // grilla de programación (o favoritos), que se re-enfoca al cambiar
        // el nonce. Sin este fallback el foco quedaba atrapado en el campo.
        if (!searching) {
            pendingGridFocus = false
            focusNonce++
            return@LaunchedEffect
        }
        if (search.hits.isEmpty()) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (resultsFocus.requestFocus()) {
                pendingGridFocus = false
                return@LaunchedEffect
            }
        }
    }

    // BACK cierra el menú si está abierto; si no, limpia la búsqueda antes de
    // dejar que el sistema cierre la app.
    BackHandler(enabled = drawerOpen) { viewModel.closeDrawer() }
    BackHandler(enabled = !drawerOpen && query.isNotEmpty()) { viewModel.clearQuery() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JkBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(
                drawerOpen = drawerOpen,
                query = query,
                searching = searching,
                loading = search.loading,
                resultCount = search.hits.size,
                searchFocus = searchFocus,
                menuFocus = menuFocus,
                onToggleDrawer = {
                    if (drawerOpen) viewModel.closeDrawer() else viewModel.openDrawer()
                },
                onQueryChange = viewModel::setQuery,
                onMoveDown = {
                    val moved = runCatching { resultsFocus.requestFocus() }.getOrDefault(false)
                    pendingGridFocus = !moved
                },
            )

            when {
                searching && search.error != null -> ErrorState(
                    message = search.error!!,
                    onRetry = viewModel::retrySearch,
                )

                searching -> SearchGrid(
                    state = search,
                    resetKey = search.query,
                    firstFocusRequester = resultsFocus,
                    favoriteSlugs = favorites.asSequence().map { it.slug }.toSet(),
                    onOpenAnime = onOpenAnime,
                    onToggleFavorite = { hit ->
                        viewModel.toggleFavoriteFromCard(
                            slug = hit.slug,
                            title = hit.title,
                            posterUrl = hit.posterUrl,
                            statusLabel = hit.statusLabel,
                        )
                    },
                )

                showFavorites -> FavoritesGrid(
                    favorites = favorites,
                    focusNonce = focusNonce,
                    onOpenAnime = onOpenAnime,
                    onToggleFavorite = { fav ->
                        viewModel.toggleFavoriteFromCard(
                            slug = fav.slug,
                            title = fav.title,
                            posterUrl = fav.posterUrl,
                            animeId = fav.animeId,
                            statusLabel = fav.statusLabel,
                            lastAiredEpisode = fav.lastAiredEpisode,
                            totalEpisodes = fav.totalEpisodes,
                        )
                    },
                )

                else -> when (val current = state) {
                    is HomeUiState.Loading -> LoadingState()
                    is HomeUiState.Error -> ErrorState(
                        message = current.message,
                        onRetry = { viewModel.refresh(force = true) },
                    )
                    is HomeUiState.Success -> CardsGrid(
                        cards = current.sections[section].orEmpty(),
                        emptyMessage = stringResource(R.string.home_section_empty),
                        resetKey = section,
                        showSection = false,
                        focusNonce = focusNonce,
                        favoriteSlugs = favorites.asSequence().map { it.slug }.toSet(),
                        onOpenAnime = onOpenAnime,
                        onToggleFavorite = { card ->
                            viewModel.toggleFavoriteFromCard(
                                slug = card.slug,
                                title = card.title,
                                posterUrl = card.posterUrl ?: card.thumbUrl,
                            )
                        },
                    )
                }
            }
        }

        if (drawerOpen) {
            // Velo sobre el contenido: deja claro que el menú tiene el foco.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
            )

            SideDrawer(
                selected = section,
                showFavorites = showFavorites,
                profileName = activeProfile?.name,
                profileId = activeProfile?.id ?: 0,
                firstItemFocus = firstDrawerItemFocus,
                onClose = viewModel::closeDrawer,
                configFirstItemFocus = configFirstItemFocus,
                hasSpace = activeProfile?.spaceCode != null,
                busy = avatarMenuBusy,
                onSyncNow = {
                    scope.launch {
                        avatarMenuBusy = true
                        avatarMenuStatus = null
                        avatarMenuStatus = when (val result = syncEngine.syncNow()) {
                            is SyncResult.Success -> context.getString(R.string.sync_result, result.favorites, result.progress)
                            is SyncResult.Failure -> context.getString(R.string.common_error, result.message)
                            SyncResult.NotPaired -> context.getString(R.string.sync_no_space)
                            SyncResult.NotConfigured -> context.getString(R.string.sync_not_configured)
                        }
                        avatarMenuBusy = false
                    }
                },
                onCreateSpace = {
                    scope.launch {
                        avatarMenuBusy = true
                        runCatching { syncEngine.createSpace() }
                            .onSuccess {
                                showAvatarMenu = true
                                avatarMenuMode = AvatarMenuMode.RECOVERY
                                avatarMenuRecoveryKey = ""
                                avatarMenuStatus = context.getString(R.string.sync_space_created, it.code.chunked(4).joinToString(" "))
                            }
                            .onFailure { avatarMenuStatus = context.getString(R.string.common_error, it.message) }
                        avatarMenuBusy = false
                    }
                },
                onGeneratePairing = {
                    scope.launch {
                        avatarMenuBusy = true
                        runCatching { syncEngine.createPairingCode() }
                            .onSuccess { pairing ->
                                avatarMenuStatus = context.getString(R.string.sync_pairing_created, pairing.code.chunked(4).joinToString(" "))
                            }
                            .onFailure { avatarMenuStatus = context.getString(R.string.common_error, it.message) }
                        avatarMenuBusy = false
                    }
                },
                onGenerateRecovery = { showAvatarMenu = true; avatarMenuMode = AvatarMenuMode.RECOVERY; avatarMenuRecoveryKey = ""; avatarMenuStatus = null },
                onUnlink = {
                    scope.launch {
                        avatarMenuBusy = true
                        syncEngine.clearSpace()
                        avatarMenuStatus = context.getString(R.string.sync_unlinked)
                        avatarMenuBusy = false
                    }
                },
                onJoinCode = { showAvatarMenu = true; avatarMenuMode = AvatarMenuMode.JOIN_CODE; avatarMenuSyncCode = ""; avatarMenuStatus = null },
                onRecovery = { showAvatarMenu = true; avatarMenuMode = AvatarMenuMode.RECOVERY; avatarMenuRecoveryKey = ""; avatarMenuStatus = null },
                onProfileSwitch = { viewModel.closeDrawer(); onOpenProfileSwitch() },
                onLanguage = {
                    showAvatarMenu = true
                    avatarMenuMode = AvatarMenuMode.LANGUAGE
                },
                onSelectSection = {
                    viewModel.selectSection(it)
                    focusNonce++
                },
                onFavorites = {
                    viewModel.openFavorites()
                    focusNonce++
                },
                onDirectorio = {
                    viewModel.closeDrawer()
                    onOpenDirectorio()
                },
            )
        }

        // Menú del avatar: overlay a nivel raíz para que cubra toda la
        // pantalla (incluida la cabecera) y no quede recortado por la Column.
        if (showAvatarMenu && activeProfile != null) {
            AvatarMenu(
                profileName = activeProfile!!.name,
                profileId = activeProfile!!.id,
                mode = avatarMenuMode,
                syncCode = avatarMenuSyncCode,
                recoveryKey = avatarMenuRecoveryKey,
                busy = avatarMenuBusy,
                status = avatarMenuStatus,
                focusRequester = avatarMenuFocus,
                onModeChange = { newMode ->
                    avatarMenuMode = newMode
                    // Al entrar al teclado, limpia mensajes del estado anterior.
                    if (newMode == AvatarMenuMode.JOIN_CODE || newMode == AvatarMenuMode.RECOVERY) {
                        avatarMenuStatus = null
                    }
                },
                onCodeChange = { avatarMenuSyncCode = it },
                onRecoveryKeyChange = { avatarMenuRecoveryKey = it },
                onClose = {
                    showAvatarMenu = false
                    avatarMenuMode = AvatarMenuMode.MAIN
                    avatarMenuSyncCode = ""
                    avatarMenuRecoveryKey = ""
                    avatarMenuStatus = null
                },
                onSyncNow = {
                    scope.launch {
                        avatarMenuBusy = true
                        avatarMenuStatus = null
                        val result = syncEngine.syncNow()
                        avatarMenuBusy = false
                        // Fuera de la composición: no hay `stringResource`,
                        // se resuelve con el Context capturado.
                        avatarMenuStatus = when (result) {
                            is SyncResult.Success ->
                                context.getString(
                                    R.string.sync_result,
                                    result.favorites,
                                    result.progress,
                                )
                            is SyncResult.Failure ->
                                context.getString(R.string.common_error, result.message)
                            SyncResult.NotPaired -> context.getString(R.string.sync_no_space)
                            SyncResult.NotConfigured ->
                                context.getString(R.string.sync_not_configured)
                        }
                    }
                },
                onCreateSpace = {
                    scope.launch {
                        avatarMenuBusy = true
                        avatarMenuStatus = null
                        runCatching { syncEngine.createSpace() }
                            .onSuccess { creds ->
                                avatarMenuStatus = context.getString(
                                    R.string.sync_space_created,
                                    creds.code.chunked(4).joinToString(" "),
                                )
                                avatarMenuRecoveryKey = ""
                                avatarMenuMode = AvatarMenuMode.RECOVERY
                            }
                            .onFailure {
                                avatarMenuStatus =
                                    context.getString(R.string.common_error, it.message)
                            }
                        avatarMenuBusy = false
                    }
                },
                onJoinSpace = {
                    scope.launch {
                        avatarMenuBusy = true
                        avatarMenuStatus = null
                        runCatching { syncEngine.joinSpace(avatarMenuSyncCode) }
                            .onSuccess {
                                avatarMenuStatus = context.getString(R.string.sync_device_linked)
                                avatarMenuMode = AvatarMenuMode.MAIN
                                avatarMenuSyncCode = ""
                            }
                            .onFailure {
                                avatarMenuStatus = context.getString(
                                    R.string.sync_link_failed,
                                    it.message,
                                )
                            }
                        avatarMenuBusy = false
                    }
                },
                onRecoverSpace = {
                    scope.launch {
                        avatarMenuBusy = true
                        avatarMenuStatus = null
                        runCatching {
                            if (activeProfile?.spaceCode != null) {
                                syncEngine.setRecoveryKey(avatarMenuRecoveryKey)
                            } else {
                                syncEngine.recoverSpace(avatarMenuRecoveryKey)
                            }
                        }
                            .onSuccess {
                                avatarMenuStatus = context.getString(
                                    if (activeProfile?.spaceCode != null) {
                                        R.string.sync_recovery_saved
                                    } else {
                                        R.string.sync_device_linked
                                    },
                                )
                                avatarMenuMode = AvatarMenuMode.MAIN
                                avatarMenuRecoveryKey = ""
                            }
                            .onFailure {
                                avatarMenuStatus = context.getString(
                                    R.string.sync_link_failed,
                                    it.message,
                                )
                            }
                        avatarMenuBusy = false
                    }
                },
                onUnlink = {
                    scope.launch {
                        avatarMenuBusy = true
                        avatarMenuStatus = null
                        syncEngine.clearSpace()
                        avatarMenuStatus = context.getString(R.string.sync_unlinked)
                        avatarMenuMode = AvatarMenuMode.MAIN
                        avatarMenuBusy = false
                    }
                },
                onGeneratePairing = {
                    scope.launch {
                        avatarMenuBusy = true
                        avatarMenuStatus = null
                        runCatching { syncEngine.createPairingCode() }
                            .onSuccess { pairing ->
                                avatarMenuStatus = context.getString(
                                    R.string.sync_pairing_created,
                                    pairing.code.chunked(4).joinToString(" "),
                                )
                            }
                            .onFailure {
                                avatarMenuStatus =
                                    context.getString(R.string.common_error, it.message)
                            }
                        avatarMenuBusy = false
                    }
                },
                onGenerateRecovery = {
                    avatarMenuRecoveryKey = ""
                    avatarMenuStatus = null
                    avatarMenuMode = AvatarMenuMode.RECOVERY
                },
                onProfileSwitch = {
                    showAvatarMenu = false
                    onOpenProfileSwitch()
                },
                hasSpace = activeProfile?.spaceCode != null,
                spaceCode = activeProfile?.spaceCode.orEmpty(),
                lang = lang,
                onLangSelect = { code ->
                    // Se cierra el cajón y se recrea la activity: quien aplica
                    // el locale es attachBaseContext (AppLocale.wrap).
                    showAvatarMenu = false
                    avatarMenuMode = AvatarMenuMode.MAIN
                    profileManager.setLang(code)
                    (context as? Activity)?.recreate()
                },
            )
        }
    }
}

/**
 * Cabecera: hamburguesa y buscador fijo. Sin fila de botones: los filtros y el
 * perfil están en el menú lateral.
 */
@Composable
private fun Header(
    drawerOpen: Boolean,
    query: String,
    searching: Boolean,
    loading: Boolean,
    resultCount: Int,
    searchFocus: FocusRequester,
    menuFocus: FocusRequester,
    onToggleDrawer: () -> Unit,
    onQueryChange: (String) -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // El menú es el caimán de la app en círculo: es a la vez marca y
        // hamburguesa (antes había un icono aparte a su lado; se quitó
        // para que quedara solo este).
        TvIconButton(
            painter = painterResource(R.drawable.caiman_home),
            contentDescription = stringResource(R.string.home_menu_cd),
            active = drawerOpen,
            focusRequester = menuFocus,
            circle = true,
            imageSize = 36.dp,
            onClick = onToggleDrawer,
        )
        Spacer(modifier = Modifier.width(24.dp))

        SearchField(
            query = query,
            searching = searching,
            loading = loading,
            resultCount = resultCount,
            focusRequester = searchFocus,
            onQueryChange = onQueryChange,
            onMoveDown = onMoveDown,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF141414),
            modifier = Modifier
                .padding(start = 12.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(JkOrange)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Fila de perfil del menú lateral: avatar + nombre. Sustituye al avatar de la
 * cabecera (se movió al cajón para dejar todo el ancho al buscador).
 */
@Composable
private fun DrawerProfileRow(
    name: String,
    profileId: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) JkSurfaceHigh else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(name = name, profileId = profileId, size = 36.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) JkTextPrimary else JkTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Campo de búsqueda permanente. Se usa `BasicTextField` y no un `TextField` de
 * Material porque en Compose for TV no existe; al enfocarlo, el teclado en
 * pantalla del sistema (el de Leanback) se abre con el D-pad.
 */
@Composable
private fun SearchField(
    query: String,
    searching: Boolean,
    loading: Boolean,
    resultCount: Int,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(JkSurfaceHigh)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.hasFocus }
            // El BasicTextField de una línea consume DirectionDown para mover
            // el cursor y nunca deja que el foco baje a la grilla. Desde el
            // padre (preview = de padre a hijo) sí llegamos antes que el campo.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onMoveDown()
                    true
                } else {
                    false
                }
            }
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = JkTextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))

        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = JkTextPrimary),
            cursorBrush = SolidColor(JkOrange),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.home_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = JkTextSecondary,
                        )
                    }
                    inner()
                }
            },
        )

        if (searching) {
            Text(
                text = when {
                    loading -> stringResource(R.string.home_searching)
                    resultCount == 0 -> stringResource(R.string.home_search_no_results)
                    else -> "$resultCount"
                },
                style = MaterialTheme.typography.labelSmall,
                color = JkTextSecondary,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}

/** Menú lateral colapsable con el perfil, los filtros y accesos. */
@Composable
private fun SideDrawer(
    selected: ProgramacionSection,
    showFavorites: Boolean,
    profileName: String?,
    profileId: Int,
    firstItemFocus: FocusRequester,
    onClose: () -> Unit,
    configFirstItemFocus: FocusRequester,
    hasSpace: Boolean,
    busy: Boolean,
    onSyncNow: () -> Unit,
    onCreateSpace: () -> Unit,
    onGeneratePairing: () -> Unit,
    onGenerateRecovery: () -> Unit,
    onUnlink: () -> Unit,
    onJoinCode: () -> Unit,
    onRecovery: () -> Unit,
    onProfileSwitch: () -> Unit,
    onLanguage: () -> Unit,
    onSelectSection: (ProgramacionSection) -> Unit,
    onFavorites: () -> Unit,
    onDirectorio: () -> Unit,
) {
    var configExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .background(JkSurface)
            .border(width = 1.dp, color = JkSurfaceHigh)
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (profileName != null) {
            AvatarDrawerHeader(
                profileName = profileName,
                profileId = profileId,
                synced = hasSpace,
                busy = busy,
                compact = false,
                title = null,
                onBack = {},
                onClose = onClose,
            )
        }
        AvatarDrawerDivider()
        AvatarDrawerSection(stringResource(R.string.home_drawer_anime_group))

        // La sección de programación se muestra como "Recientes" (los
        // episodios que acaban de salir), con icono de fecha.
        val animesItem = ProgramacionSection.ANIMES
        DrawerItem(
            label = stringResource(R.string.home_drawer_recientes),
            icon = Icons.Filled.DateRange,
            selected = animesItem == selected && !showFavorites,
            focusRequester = firstItemFocus,
            onClick = { onSelectSection(animesItem) },
        )

        // Directorio: la navegación al catálogo filtrado del sitio
        DrawerItem(
            label = stringResource(R.string.home_drawer_directorio),
            icon = Icons.AutoMirrored.Filled.List,
            selected = false,
            onClick = onDirectorio,
        )

        DrawerItem(
            label = stringResource(R.string.home_drawer_favoritos),
            icon = Icons.Filled.Star,
            selected = showFavorites,
            onClick = onFavorites,
        )

        Spacer(modifier = Modifier.weight(1f))
        AvatarDrawerDivider()
        DrawerItem(
            label = stringResource(R.string.home_drawer_config),
            icon = Icons.Filled.Settings,
            trailingIcon = if (configExpanded) {
                Icons.Filled.KeyboardArrowUp
            } else {
                Icons.Filled.KeyboardArrowDown
            },
            selected = false,
            onClick = { configExpanded = !configExpanded },
        )
        if (configExpanded) {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                AvatarDrawerMain(
                    hasSpace = hasSpace,
                    spaceCode = "",
                    busy = busy,
                    focusRequester = configFirstItemFocus,
                    onSyncNow = onSyncNow,
                    onCreateSpace = onCreateSpace,
                    onGeneratePairing = onGeneratePairing,
                    onGenerateRecovery = onGenerateRecovery,
                    onUnlink = onUnlink,
                    onJoinCode = onJoinCode,
                    onRecovery = onRecovery,
                    onProfileSwitch = onProfileSwitch,
                    onLanguage = onLanguage,
                )
            }
        }
    }
}

/** Modos del menú del avatar (sync/perfil/idioma). */
private enum class AvatarMenuMode {
    MAIN,       // Menú principal: sync now, link, unlink, pairing, recovery
    RECOVERY,   // Introduciendo clave de recuperación de 16 caracteres
    JOIN_CODE,  // Introduciendo código de 8 dígitos para vincular
    LANGUAGE,   // Selector de idioma de la app
}

/**
 * Submenú del cajón izquierdo para introducir pairing/recovery. El menú
 * principal ya vive siempre en [SideDrawer]; este Dialog solo se usa para los
 * teclados y se ancla al borde izquierdo.
 *
 * Sigue siendo un `Dialog` para que el D-pad NO se escape a la grilla de
 * detrás: un `Box` overlay en el mismo árbol deja que el foco viaje al fondo.
 */
@Composable
private fun AvatarMenu(
    profileName: String,
    profileId: Int,
    mode: AvatarMenuMode,
    syncCode: String,
    recoveryKey: String,
    busy: Boolean,
    status: String?,
    focusRequester: FocusRequester,
    onModeChange: (AvatarMenuMode) -> Unit,
    onCodeChange: (String) -> Unit,
    onRecoveryKeyChange: (String) -> Unit,
    onClose: () -> Unit,
    onSyncNow: () -> Unit,
    onCreateSpace: () -> Unit,
    onJoinSpace: () -> Unit,
    onRecoverSpace: () -> Unit,
    onUnlink: () -> Unit,
    onGeneratePairing: () -> Unit,
    onGenerateRecovery: () -> Unit,
    onProfileSwitch: () -> Unit,
    hasSpace: Boolean,
    spaceCode: String,
    lang: String,
    onLangSelect: (String) -> Unit,
) {
    // El cajón entra deslizando desde la derecha. El nodo existe desde el
    // primer frame (solo se traslada), así que el foco se puede pedir ya.
    var shown by remember { mutableStateOf(false) }
    val offsetX by animateDpAsState(
        targetValue = if (shown) 0.dp else 460.dp,
        animationSpec = tween(durationMillis = 180),
        label = "avatarDrawerOffset",
    )

    LaunchedEffect(Unit) { shown = true }

    // El foco se pide cuando el cajón ya terminó de entrar y el `Dialog` ya
    // asignó el foco de su ventana: pedirlo en el primer frame hace que el
    // sistema lo pise y acaba enfocada la X de la cabecera.
    LaunchedEffect(mode) {
        repeat(16) { withFrameNanos { } }
        repeat(6) {
            if (focusRequester.requestFocus()) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            when (mode) {
                AvatarMenuMode.JOIN_CODE -> {
                    onModeChange(AvatarMenuMode.MAIN)
                    onCodeChange("")
                }
                AvatarMenuMode.RECOVERY -> {
                    onModeChange(AvatarMenuMode.MAIN)
                    onRecoveryKeyChange("")
                }
                // En idioma, BACK vuelve al menú principal sin cerrar: el
                // cambio de idioma recrea la activity desde la propia fila.
                AvatarMenuMode.LANGUAGE -> onModeChange(AvatarMenuMode.MAIN)
                else -> onClose()
            }
        },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Velo: no es enfocable a propósito (en TV se sale con BACK o con
            // la X del panel, y un scrim clicable se comería el D-pad).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
            )

            // Panel anclado al borde izquierdo, altura completa.
            androidx.tv.material3.Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(AVATAR_DRAWER_WIDTH)
                    .fillMaxHeight()
                    .graphicsLayer { translationX = offsetX.toPx() },
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                colors = androidx.tv.material3.SurfaceDefaults.colors(
                    containerColor = JkSurface,
                    contentColor = JkTextPrimary,
                ),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(vertical = 20.dp)) {
                    AvatarDrawerHeader(
                        profileName = profileName,
                        profileId = profileId,
                        synced = hasSpace,
                        busy = busy,
                        compact = mode != AvatarMenuMode.MAIN,
                        // En compacto el título es el del submenú: vinculación
                        // o idioma según el modo.
                        title = if (mode == AvatarMenuMode.LANGUAGE) {
                            stringResource(R.string.lang_title)
                        } else {
                            null
                        },
                        onBack = {
                            onModeChange(AvatarMenuMode.MAIN)
                            onCodeChange("")
                        },
                        onClose = onClose,
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    AvatarDrawerDivider()
                    Spacer(modifier = Modifier.height(10.dp))

                    when (mode) {
                        AvatarMenuMode.MAIN -> AvatarDrawerMain(
                            hasSpace = hasSpace,
                            spaceCode = spaceCode,
                            busy = busy,
                            focusRequester = focusRequester,
                            onSyncNow = onSyncNow,
                            onCreateSpace = onCreateSpace,
                            onGeneratePairing = onGeneratePairing,
                            onGenerateRecovery = onGenerateRecovery,
                            onUnlink = onUnlink,
                            onJoinCode = { onModeChange(AvatarMenuMode.JOIN_CODE) },
                            onRecovery = { onModeChange(AvatarMenuMode.RECOVERY) },
                            onProfileSwitch = {
                                onClose()
                                onProfileSwitch()
                            },
                            onLanguage = { onModeChange(AvatarMenuMode.LANGUAGE) },
                        )

                        AvatarMenuMode.JOIN_CODE -> Column(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                 text = stringResource(R.string.sync_pairing_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = JkTextSecondary,
                            )
                            Text(
                                text = syncCode.padEnd(8, '·').chunked(4).joinToString("  "),
                                style = MaterialTheme.typography.headlineSmall,
                                color = JkOrange,
                            )
                            SyncKeypad(
                                code = syncCode,
                                onDigit = { if (syncCode.length < 8) onCodeChange(syncCode + it) },
                                onBackspace = { onCodeChange(syncCode.dropLast(1)) },
                                onConfirm = { if (syncCode.length == 8) onJoinSpace() },
                                 busy = busy,
                                 compact = true,
                                 focusRequester = focusRequester,
                            )
                        }

                        AvatarMenuMode.RECOVERY -> Column(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (hasSpace) R.string.sync_recovery_set_title
                                    else R.string.sync_recovery_title,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = JkTextSecondary,
                            )
                            Text(
                                text = recoveryKey.padEnd(10, '·').chunked(5).joinToString("  "),
                                style = MaterialTheme.typography.headlineSmall,
                                color = JkOrange,
                            )
                            SyncKeypad(
                                code = recoveryKey,
                                maxLength = 10,
                                recovery = true,
                                onDigit = { if (recoveryKey.length < 10) onRecoveryKeyChange(recoveryKey + it) },
                                onBackspace = { onRecoveryKeyChange(recoveryKey.dropLast(1)) },
                                onConfirm = { if (recoveryKey.length == 10) onRecoverSpace() },
                                busy = busy,
                                compact = true,
                                focusRequester = focusRequester,
                            )
                        }

                        AvatarMenuMode.LANGUAGE -> Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            LanguageOptions(
                                current = lang,
                                onSelect = onLangSelect,
                                modifier = Modifier.fillMaxWidth(),
                                // El `LaunchedEffect(mode)` del cajón enfoca
                                // este requester: cae en la opción actual.
                                focusRequester = focusRequester,
                            )
                        }
                    }

                    // El estado va al pie del cajón: en el modo código el
                    // teclado ya ocupa casi todo el alto, así que si sobra
                    // sitio queda pegado abajo y si no, no se pinta.
                    status?.let {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = JkTextPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Ancho del cajón del avatar: ~44 % de los 960 dp de una TV 1080p. */
private val AVATAR_DRAWER_WIDTH = 420.dp

/** Cabecera del cajón: avatar, nombre, estado de sync y acciones de cierre. */
@Composable
private fun AvatarDrawerHeader(
    profileName: String,
    profileId: Int,
    synced: Boolean,
    busy: Boolean,
    compact: Boolean,
    /** Título del submenú en modo compacto (por defecto, vinculación). */
    title: String? = null,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (compact) {
            // En los submenús no hay espacio para el avatar: título + atrás.
            Text(
                text = title ?: stringResource(R.string.sync_link_device),
                style = MaterialTheme.typography.titleMedium,
                color = JkTextPrimary,
                modifier = Modifier.weight(1f),
            )
        } else {
            ProfileAvatar(name = profileName, profileId = profileId, size = 52.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.titleLarge,
                    color = JkTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (synced) JkGreen else JkTextSecondary.copy(alpha = 0.45f),
                                CircleShape,
                            ),
                    )
                    Text(
                        text = when {
                            busy -> stringResource(R.string.sync_status_syncing)
                            synced -> stringResource(R.string.sync_status_synced)
                            else -> stringResource(R.string.sync_status_local)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = JkTextSecondary,
                    )
                }
            }
        }

        if (compact) {
            TvIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.home_back_cd),
                onClick = onBack,
            )
        }
        TvIconButton(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.home_close_menu_cd),
            onClick = onClose,
        )
    }
}

/** Separador tenue del cajón. */
@Composable
private fun AvatarDrawerDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(JkSurfaceHigh),
    )
}

/** Etiqueta de grupo del cajón (SINCRONIZACIÓN, PERFIL…). */
@Composable
private fun AvatarDrawerSection(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = JkTextSecondary,
        modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Opciones del cajón según haya o no espacio de sincronización. */
@Composable
private fun AvatarDrawerMain(
    hasSpace: Boolean,
    spaceCode: String,
    busy: Boolean,
    focusRequester: FocusRequester,
    onSyncNow: () -> Unit,
    onCreateSpace: () -> Unit,
    onGeneratePairing: () -> Unit,
    onGenerateRecovery: () -> Unit,
    onUnlink: () -> Unit,
    onJoinCode: () -> Unit,
    onRecovery: () -> Unit,
    onProfileSwitch: () -> Unit,
    onLanguage: () -> Unit,
) {
    if (!hasSpace) {
        Text(
            text = stringResource(R.string.sync_not_linked_cloud),
            style = MaterialTheme.typography.bodySmall,
            color = JkTextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }

    if (hasSpace) {
        DrawerItem(
            label = stringResource(R.string.home_drawer_sync_now),
            icon = Icons.Filled.Refresh,
            selected = false,
            enabled = !busy,
            compact = true,
            focusRequester = focusRequester,
            onClick = onSyncNow,
        )
        DrawerItem(
            label = stringResource(R.string.home_drawer_generate_pairing),
            icon = Icons.Filled.Add,
            selected = false,
            enabled = !busy,
            compact = true,
            onClick = onGeneratePairing,
        )
        DrawerItem(
            label = stringResource(R.string.home_drawer_set_recovery),
            icon = Icons.Filled.Add,
            selected = false,
            enabled = !busy,
            compact = true,
            onClick = onGenerateRecovery,
        )
        DrawerItem(
            label = stringResource(R.string.home_drawer_unlink),
            icon = Icons.Filled.Clear,
            selected = false,
            enabled = !busy,
            compact = true,
            onClick = onUnlink,
        )
    } else {
        DrawerItem(
            label = stringResource(R.string.sync_create_space),
            icon = Icons.Filled.Add,
            selected = false,
            enabled = !busy,
            compact = true,
            focusRequester = focusRequester,
            onClick = onCreateSpace,
        )
        DrawerItem(
            label = stringResource(R.string.sync_have_pairing_code),
            icon = Icons.Filled.Edit,
            selected = false,
            enabled = !busy,
            compact = true,
            onClick = onJoinCode,
        )
        DrawerItem(
            label = stringResource(R.string.sync_have_recovery_key),
            icon = Icons.Filled.Add,
            selected = false,
            enabled = !busy,
            compact = true,
            onClick = onRecovery,
        )
    }

    AvatarDrawerDivider()
    DrawerItem(
        label = stringResource(R.string.home_drawer_change_profile),
        icon = Icons.Filled.AccountBox,
        selected = false,
        enabled = !busy,
        compact = true,
        onClick = onProfileSwitch,
    )

    AvatarDrawerDivider()
    DrawerItem(
        label = stringResource(R.string.lang_title),
        icon = Icons.Filled.Settings,
        selected = false,
        enabled = !busy,
        compact = true,
        onClick = onLanguage,
    )
}

/**
 * Teclado numérico estilo TV para entrar el código de 8 dígitos.
 */
@Composable
private fun SyncKeypad(
    code: String,
    maxLength: Int = 8,
    recovery: Boolean = false,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    busy: Boolean,
    compact: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        if (recovery) {
            TvRecoveryPad(
                onChar = onDigit,
                onBackspace = onBackspace,
                onConfirm = if (code.length == maxLength && !busy) onConfirm else null,
                enabled = !busy,
                focusRequester = focusRequester,
            )
        } else {
            TvNumberPad(
                onDigit = onDigit,
                onBackspace = onBackspace,
                onConfirm = if (code.length == maxLength && !busy) onConfirm else null,
                enabled = !busy,
                compact = compact,
                focusRequester = focusRequester,
            )
        }
        // Confirmar (solo con 8 dígitos) + volver, en la misma fila.
    }
}

@Composable
private fun DrawerItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    val background = when {
        !enabled -> Color.Transparent
        focused -> JkSurfaceHigh
        selected -> JkOrange
        else -> Color.Transparent
    }
    val contentColor = when {
        !enabled -> JkTextSecondary.copy(alpha = 0.4f)
        focused -> JkOrange
        selected -> Color(0xFF141414)
        else -> JkTextPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) JkOrange else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            // El FocusRequester va antes del focusable (clickable).
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        Text(
            text = label,
            style = if (compact) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Grilla de resultados del buscador del sitio.
 *
 * `autoFocusFirst` no existe aquí a propósito: mientras se escribe el foco debe
 * quedarse en el campo; se baja con ▼.
 */
@Composable
private fun SearchGrid(
    state: SearchUiState,
    resetKey: Any,
    firstFocusRequester: FocusRequester?,
    favoriteSlugs: Set<String>,
    onOpenAnime: (String) -> Unit,
    onToggleFavorite: (AnimeSearchHit) -> Unit,
) {
    when {
        !state.hasQuery -> {
            LoadingState(
                message = stringResource(
                    R.string.home_search_min_query,
                    HomeViewModel.MIN_QUERY_LENGTH,
                ),
            )
            return
        }
        state.loading && state.hits.isEmpty() -> {
            LoadingState(message = stringResource(R.string.home_search_query_loading, state.query))
            return
        }
        state.hits.isEmpty() -> {
            LoadingState(message = stringResource(R.string.home_search_query_none, state.query))
            return
        }
    }

    val firstCardFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var currentPage by remember(resetKey) { mutableIntStateOf(1) }
    var showJumpDialog by remember { mutableStateOf(false) }
    val pageSize = 18
    val lastPage = ((state.hits.size - 1) / pageSize + 1).coerceAtLeast(1)
    val pageHits = state.hits.drop((currentPage - 1) * pageSize).take(pageSize)

    LaunchedEffect(resetKey) {
        currentPage = 1
        gridState.scrollToItem(0)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(pageHits, key = { it.slug }) { hit ->
                PosterCard(
                    title = hit.title,
                    posterUrl = hit.posterUrl,
                    badge = hit.type,
                    subtitle = hit.statusLabel,
                    subtitleColor = airingStatusColor(hit.statusLabel),
                    titleMaxLines = 1,
                    width = null,
                    isFavorite = hit.slug in favoriteSlugs,
                    focusRequester = if (hit === pageHits.firstOrNull()) {
                        firstFocusRequester ?: firstCardFocus
                    } else {
                        null
                    },
                    onClick = { onOpenAnime(hit.slug) },
                    onLongClick = { onToggleFavorite(hit) },
                )
            }
        }

        if (lastPage > 1) {
            SearchPaginationBar(
                currentPage = currentPage,
                lastPage = lastPage,
                onGoToPage = { currentPage = it },
                onJumpToPage = { showJumpDialog = true },
            )
        }
    }

    if (showJumpDialog) {
        SearchJumpToPageDialog(
            currentPage = currentPage,
            lastPage = lastPage,
            onDismiss = { showJumpDialog = false },
            onGo = {
                currentPage = it
                showJumpDialog = false
            },
        )
    }
}

@Composable
private fun SearchPaginationBar(
    currentPage: Int,
    lastPage: Int,
    onGoToPage: (Int) -> Unit,
    onJumpToPage: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvIconButton(
            icon = Icons.Filled.KeyboardArrowLeft,
            contentDescription = "Previous page",
            enabled = currentPage > 1,
            onClick = { onGoToPage(currentPage - 1) },
        )
        searchPageWindow(currentPage, lastPage).forEach { page ->
            if (page == null) {
                Text("…", color = JkTextSecondary, modifier = Modifier.width(20.dp), textAlign = TextAlign.Center)
            } else {
                TvActionButton(
                    text = page.toString(),
                    primary = page == currentPage,
                    compact = true,
                    onClick = { onGoToPage(page) },
                )
            }
        }
        TvIconButton(
            icon = Icons.Filled.KeyboardArrowRight,
            contentDescription = "Next page",
            enabled = currentPage < lastPage,
            onClick = { onGoToPage(currentPage + 1) },
        )
        TvActionButton(
            text = stringResource(R.string.directorio_go_to_page),
            primary = false,
            compact = true,
            onClick = onJumpToPage,
        )
    }
}

private fun searchPageWindow(current: Int, last: Int): List<Int?> {
    val pages = sortedSetOf(1, last)
    for (page in (current - 2)..(current + 2)) {
        if (page in 1..last) pages.add(page)
    }
    val result = mutableListOf<Int?>()
    var previous: Int? = null
    for (page in pages) {
        if (previous != null && page - previous!! > 1) result += null
        result += page
        previous = page
    }
    return result
}

@Composable
private fun SearchJumpToPageDialog(
    currentPage: Int,
    lastPage: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    val padFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            if (padFocus.requestFocus()) return@LaunchedEffect
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.36f),
                shape = RoundedCornerShape(16.dp),
                colors = SurfaceDefaults.colors(containerColor = JkSurface, contentColor = JkTextPrimary),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.directorio_go_to_page), color = JkOrange, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.directorio_page_current_of, currentPage, lastPage),
                        color = JkTextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Box(
                        modifier = Modifier.width(140.dp).height(46.dp).clip(RoundedCornerShape(12.dp)).background(JkSurfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digits.ifEmpty { "—" }, color = if (digits.isEmpty()) JkTextSecondary else JkTextPrimary, style = MaterialTheme.typography.titleLarge)
                    }
                    TvNumberPad(
                        onDigit = { digit ->
                            val next = digits + digit
                            if (next.length <= 3 && next.toIntOrNull()!! <= lastPage) digits = next
                        },
                        onBackspace = { digits = digits.dropLast(1) },
                        onConfirm = { if (digits.isNotEmpty()) onGo(digits.toInt().coerceIn(1, lastPage)) },
                        confirmText = stringResource(R.string.common_go),
                        confirmEnabled = digits.isNotEmpty(),
                        compact = true,
                        focusRequester = padFocus,
                    )
                }
            }
        }
    }
}

/**
 * Grilla de tarjetas de Programación. `showSection` añade el origen
 * (Animes/Donghuas/OVAs) al subtítulo.
 *
 * `focusNonce` cambia al elegir algo en el menú lateral; obliga a repedir el
 * foco aunque las tarjetas sean las mismas (el ítem del menú desaparece del
 * árbol y el D-pad quedaría muerto).
 */
@Composable
private fun CardsGrid(
    cards: List<AnimeCard>,
    emptyMessage: String,
    resetKey: Any,
    showSection: Boolean,
    focusNonce: Int = 0,
    favoriteSlugs: Set<String>,
    onOpenAnime: (String) -> Unit,
    onToggleFavorite: (AnimeCard) -> Unit,
) {
    if (cards.isEmpty()) {
        LoadingState(message = emptyMessage)
        return
    }

    val firstCardFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    // Al cambiar de sección la grilla vuelve arriba: si no, el scroll anterior
    // se arrastra y la primera fila queda cortada.
    LaunchedEffect(resetKey) {
        gridState.scrollToItem(0)
    }

    // En TV el foco es obligatorio: sin él la pantalla queda muerta. Se pide
    // DESPUÉS del primer frame (los items del LazyGrid se crean durante la
    // medición) y se reintenta: requestFocus() devuelve false, no lanza, si el
    // nodo aún no está listo.
    LaunchedEffect(cards, focusNonce) {
        repeat(10) {
            withFrameNanos { }
            if (firstCardFocus.requestFocus()) return@LaunchedEffect
        }
        android.util.Log.w("JkFocus", "no se pudo enfocar la primera tarjeta")
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(cards, key = { it.section.name + it.episodeUrl }) { card ->
            PosterCard(
                title = card.title,
                posterUrl = card.posterUrl ?: card.thumbUrl,
                badge = card.episodeNumber?.let { stringResource(R.string.episode_badge, it) },
                subtitle = listOfNotNull(
                    // La sección es un dato del enum; aquí se traduce solo la
                    // etiqueta visible (el enum no se toca: lo usan los tests).
                    card.section.takeIf { showSection }?.let { s ->
                        when (s) {
                            ProgramacionSection.ANIMES ->
                                stringResource(R.string.section_animes)
                            ProgramacionSection.DONGHUAS ->
                                stringResource(R.string.section_donghuas)
                            ProgramacionSection.OVAS ->
                                stringResource(R.string.section_ovas)
                        }
                    },
                    card.dateLabel,
                ).joinToString(" · ").ifBlank { null },
                width = null,
                isFavorite = card.slug in favoriteSlugs,
                focusRequester = if (card === cards.first()) firstCardFocus else null,
                onClick = { onOpenAnime(card.slug) },
                onLongClick = { onToggleFavorite(card) },
            )
        }
    }
}

/**
 * Favoritos en tarjetas. El badge "N pend." sale de comparar el último
 * episodio emitido (refrescado por el sync de F5) con el último visto.
 *
 * Dos grupos separados por línea horizontal:
 * 1) Con episodios pendientes (+N)
 * 2) "Al día" (pending == 0) + sin datos (fallback a último visto)
 */
@Composable
private fun FavoritesGrid(
    favorites: List<FavoriteEntity>,
    focusNonce: Int = 0,
    onOpenAnime: (String) -> Unit,
    onToggleFavorite: (FavoriteEntity) -> Unit,
) {
    if (favorites.isEmpty()) {
        LoadingState(message = stringResource(R.string.home_favorites_empty))
        return
    }

    // Partición: pendientes | al día.
    //
    // Sin datos de emisión (`pendingEpisodes == null`) NO se puede afirmar que
    // el anime esté al día: se trata como pendiente y la tarjeta no muestra el
    // badge verde. Antes caía en "Al día" y cualquier favorito recién añadido
    // desde el buscador aparecía ahí por error.
    val (pendientes, alDia) = favorites.partition { (it.pendingEpisodes ?: 1) > 0 }

    val sortedPendientes = pendientes.sortedBy { it.title.lowercase() }
    val sortedAlDia = alDia.sortedWith(
        compareByDescending<FavoriteEntity> { it.pendingEpisodes ?: -1 }
            .thenBy { it.title.lowercase() },
    )

    val all = sortedPendientes + sortedAlDia
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(all.firstOrNull()?.slug, focusNonce) {
        repeat(10) {
            withFrameNanos { }
            if (firstFocus.requestFocus()) return@LaunchedEffect
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (sortedPendientes.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.favorites_section_pending))
            }
            items(sortedPendientes, key = { it.slug }) { fav ->
                FavoriteCard(
                    favorite = fav,
                    focusRequester = if (fav === all.first()) firstFocus else null,
                    onOpenAnime = onOpenAnime,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }

        if (sortedPendientes.isNotEmpty() && sortedAlDia.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .height(1.dp)
                        .background(JkSurfaceHigh),
                )
            }
        }

        if (sortedAlDia.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.favorites_section_up_to_date))
            }
            items(sortedAlDia, key = { it.slug }) { fav ->
                FavoriteCard(
                    favorite = fav,
                    focusRequester = if (fav === all.first()) firstFocus else null,
                    onOpenAnime = onOpenAnime,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = JkTextSecondary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun FavoriteCard(
    favorite: FavoriteEntity,
    focusRequester: FocusRequester?,
    onOpenAnime: (String) -> Unit,
    onToggleFavorite: (FavoriteEntity) -> Unit,
) {
    val pending = favorite.pendingEpisodes
    // Verde solo con la certeza de que no hay nada pendiente.
    val badge = when {
        pending == null -> favorite.lastWatchedEpisode?.let {
            stringResource(R.string.episode_badge, it)
        }
        pending > 0 -> "+$pending"
        else -> stringResource(R.string.home_badge_up_to_date)
    }
        PosterCard(
        title = favorite.title,
        posterUrl = favorite.posterUrl,
        badge = badge,
            badgeColor = if (pending == 0) JkGreen else JkOrange,
            isFavorite = true,
        subtitle = favorite.statusLabel,
        subtitleColor = airingStatusColor(favorite.statusLabel),
        width = null,
        focusRequester = focusRequester,
        onClick = { onOpenAnime(favorite.slug) },
        onLongClick = { onToggleFavorite(favorite) },
    )
}
