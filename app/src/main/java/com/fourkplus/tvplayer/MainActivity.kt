package com.fourkplus.tvplayer

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fourkplus.tvplayer.ui.theme.*
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.MovieDetailsInfo
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.data.PlaylistKind
import com.fourkplus.tvplayer.data.PlaylistRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        setContent { App() }
    }
}

private enum class Screen { LOADING, ACTIVATION, MANUAL, PLAYLISTS, HOME, LIVE_TV, MOVIES, SERIES, SETTINGS }
internal enum class ThemeChoice { SYSTEM, LIGHT, DARK }

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.LOADING) }
    var themeChoice by remember { mutableStateOf(ThemeChoice.SYSTEM) }
    val useDark = when (themeChoice) {
        ThemeChoice.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val landscapeApp = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(landscapeApp) {
        val activity = context as? Activity
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscapeApp) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (landscapeApp) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val appPreferences = remember { context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE) }
    val playlistRepository = remember { PlaylistRepository(context.applicationContext) }
    var loadedPlaylist by remember { mutableStateOf<LoadedPlaylist?>(null) }
    var savedPlaylists by remember { mutableStateOf(playlistRepository.savedSources()) }
    val message: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }

    LaunchedEffect(Unit) {
        themeChoice = runCatching {
            ThemeChoice.valueOf(appPreferences.getString("theme", ThemeChoice.SYSTEM.name).orEmpty())
        }.getOrDefault(ThemeChoice.SYSTEM)
        val cachedPlaylist = playlistRepository.loadCached()
        if (cachedPlaylist != null) {
            loadedPlaylist = cachedPlaylist
            screen = Screen.HOME
        } else {
            val savedSource = playlistRepository.savedSource()
            if (savedSource == null) {
                screen = Screen.ACTIVATION
            } else {
                playlistRepository.load(savedSource)
                    .onSuccess {
                        loadedPlaylist = it
                        screen = Screen.HOME
                    }
                    .onFailure {
                        screen = Screen.ACTIVATION
                        message("Saved playlist could not be refreshed. Please reconnect.")
                    }
            }
        }
    }

    FourKPlusTheme(darkTheme = useDark) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (landscapeApp) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing,
            modifier = Modifier.fillMaxSize()
        ) { scaffoldPadding ->
            Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            when (screen) {
                Screen.LOADING -> PremiumBackground {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(color = Cyan)
                        Text("Loading your playlist…", fontWeight = FontWeight.SemiBold)
                    }
                }
                Screen.ACTIVATION -> ActivationScreen(
                    themeChoice = themeChoice,
                    onThemeChange = {
                        themeChoice = it
                        appPreferences.edit().putString("theme", it.name).apply()
                    },
                    onManual = { screen = Screen.MANUAL },
                    onMessage = message
                )
                Screen.MANUAL -> ManualPlaylistScreen(
                    onBack = { screen = if (savedPlaylists.isEmpty()) Screen.ACTIVATION else Screen.PLAYLISTS },
                    loadPlaylist = playlistRepository::load,
                    onConnected = {
                        loadedPlaylist = it
                        savedPlaylists = playlistRepository.savedSources()
                        screen = Screen.HOME
                        message("${it.items.size} items loaded")
                    }
                )
                Screen.PLAYLISTS -> PlaylistManagerScreen(
                    sources = savedPlaylists,
                    activeSource = playlistRepository.savedSource(),
                    onBack = { screen = Screen.HOME },
                    onAdd = { screen = Screen.MANUAL },
                    onSelect = { source ->
                        scope.launch {
                            playlistRepository.selectSavedSource(source)
                            playlistRepository.load(source)
                                .onSuccess {
                                    loadedPlaylist = it
                                    savedPlaylists = playlistRepository.savedSources()
                                    screen = Screen.HOME
                                    message("${source.name} selected")
                                }
                                .onFailure { message(it.message ?: "Playlist could not be loaded") }
                        }
                    },
                    onRemove = { source ->
                        scope.launch {
                            playlistRepository.selectSavedSource(source)
                            playlistRepository.clearSavedSource()
                            savedPlaylists = playlistRepository.savedSources()
                            val next = playlistRepository.savedSource()
                            if (next == null) {
                                loadedPlaylist = null
                                screen = Screen.ACTIVATION
                                message("Playlist removed")
                            } else {
                                playlistRepository.load(next)
                                    .onSuccess { loadedPlaylist = it; message("Playlist removed") }
                                    .onFailure { loadedPlaylist = null; screen = Screen.ACTIVATION }
                            }
                        }
                    }
                )
                Screen.HOME -> HomeScreen(
                    playlist = loadedPlaylist,
                    onManage = { screen = Screen.SETTINGS },
                    onPlaylists = { screen = Screen.PLAYLISTS },
                    onOpenLive = { screen = Screen.LIVE_TV },
                    onOpenMovies = { screen = Screen.MOVIES },
                    onOpenSeries = { screen = Screen.SERIES },
                    onMessage = message
                )
                Screen.LIVE_TV -> LiveTvScreen(
                    playlist = loadedPlaylist,
                    onBack = { screen = Screen.HOME },
                    onMessage = message
                )
                Screen.MOVIES -> MoviesScreen(
                    playlist = loadedPlaylist,
                    loadDetails = playlistRepository::movieDetails,
                    onBack = { screen = Screen.HOME }
                )
                Screen.SERIES -> SeriesScreen(
                    playlist = loadedPlaylist,
                    loadDetails = playlistRepository::seriesDetails,
                    onBack = { screen = Screen.HOME }
                )
                Screen.SETTINGS -> SettingsScreen(
                    playlist = loadedPlaylist,
                    source = playlistRepository.savedSource(),
                    themeChoice = themeChoice,
                    onThemeChange = {
                        themeChoice = it
                        appPreferences.edit().putString("theme", it.name).apply()
                    },
                    onBack = { screen = Screen.HOME },
                    onRefresh = {
                        scope.launch {
                            val source = playlistRepository.savedSource()
                            if (source == null) message("No saved playlist to refresh")
                            else playlistRepository.load(source)
                                .onSuccess { loadedPlaylist = it; message("Playlist refreshed") }
                                .onFailure { message(it.message ?: "Playlist refresh failed") }
                        }
                    },
                    onRename = { name ->
                        runCatching { playlistRepository.renameSavedSource(name) }
                            .onSuccess {
                                loadedPlaylist = loadedPlaylist?.copy(name = name)
                                message("Playlist renamed")
                            }
                            .onFailure { message(it.message ?: "Playlist could not be renamed") }
                    },
                    onReplace = { screen = Screen.MANUAL },
                    onRemove = {
                        playlistRepository.clearSavedSource()
                        savedPlaylists = playlistRepository.savedSources()
                        val next = playlistRepository.savedSource()
                        if (next == null) {
                            loadedPlaylist = null
                            screen = Screen.ACTIVATION
                        } else {
                            scope.launch {
                                playlistRepository.load(next)
                                    .onSuccess { loadedPlaylist = it; screen = Screen.HOME }
                                    .onFailure { loadedPlaylist = null; screen = Screen.ACTIVATION }
                            }
                        }
                        message("Playlist removed")
                    },
                    onMessage = message
                )
            }
            }
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Surface(
        modifier.height(58.dp).widthIn(max = 170.dp),
        shape = RoundedCornerShape(15.dp),
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        Image(
            painter = painterResource(R.drawable.logo_4k_plus_tv),
            contentDescription = "4K Plus TV",
            contentScale = ContentScale.Fit,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun PremiumBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.background,
                    MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                )
            )
        )
    ) {
        Box(
            Modifier.size(260.dp).align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(listOf(Cyan.copy(alpha = .18f), Color.Transparent)),
                    RoundedCornerShape(130.dp)
                )
        )
        Box(
            Modifier.size(220.dp).align(Alignment.BottomStart)
                .background(
                    Brush.radialGradient(listOf(Orange.copy(alpha = .11f), Color.Transparent)),
                    RoundedCornerShape(110.dp)
                )
        )
        content()
    }
}

@Composable
private fun ActivationScreen(
    themeChoice: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    onManual: () -> Unit,
    onMessage: (String) -> Unit
) {
    PremiumBackground {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val sidePadding = if (landscape) 34.dp else 20.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = sidePadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.weight(1f))
                ThemeMenu(themeChoice, onThemeChange)
                IconButton(onClick = { onMessage("Language selection will be added next") }) { Icon(Icons.Default.Language, "Language") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Welcome", fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("Your entertainment starts here", color = Cyan, fontWeight = FontWeight.SemiBold)
                Text("Choose the easiest way to add your playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RemoteActivationCard(Modifier.weight(1.15f), onMessage)
                    ManualEntryCard(Modifier.weight(.85f), onManual)
                }
            } else {
                RemoteActivationCard(Modifier.fillMaxWidth(), onMessage)
                ManualEntryCard(Modifier.fillMaxWidth(), onManual)
            }

            Text(
                "4K Plus TV Player is a media player and does not include or provide content.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    }
}

@Composable
private fun ThemeMenu(choice: ThemeChoice, onChange: (ThemeChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.Contrast, "Appearance") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            ThemeChoice.entries.forEach {
                DropdownMenuItem(
                    text = { Text(it.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    leadingIcon = { if (choice == it) Icon(Icons.Default.Check, null) },
                    onClick = { onChange(it); open = false }
                )
            }
        }
    }
}

@Composable
private fun RemoteActivationCard(modifier: Modifier, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 10.dp)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccentIcon(Icons.Default.Devices, Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Activate through 4K Plus TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Recommended", color = Cyan, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("Use these codes in your 4K Plus TV dashboard. Refresh here after a playlist is assigned.")
            DeviceCode("Device ID", "A4:7B:91:2C:8F:30", onMessage)
            DeviceCode("Device Key", "K7P9-X2QM", onMessage)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = Orange.copy(alpha = .14f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Orange.copy(alpha = .28f))
                ) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Orange)
                        Spacer(Modifier.width(7.dp))
                        Text("Waiting for activation", style = MaterialTheme.typography.labelMedium, color = Orange)
                    }
                }
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = {
                        refreshing = true
                        scope.launch {
                            delay(700)
                            refreshing = false
                            onMessage("No playlist assigned yet")
                        }
                    }
                ) {
                    val rotation by animateFloatAsState(if (refreshing) 360f else 0f, tween(650), label = "refresh")
                    Icon(Icons.Default.Refresh, "Refresh activation", Modifier.graphicsLayer(rotationZ = rotation))
                }
            }
        }
    }
}

@Composable
private fun AccentIcon(icon: ImageVector, color: Color) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(color.copy(alpha = .28f), color.copy(alpha = .08f)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun DeviceCode(label: String, value: String, onMessage: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary.copy(alpha = .12f))
                )
            ).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            onMessage("$label copied")
        }) { Icon(Icons.Default.ContentCopy, "Copy $label") }
    }
}

@Composable
private fun ManualEntryCard(modifier: Modifier, onManual: () -> Unit) {
    val interactive = pressFeedback(onManual)
    ElevatedCard(
        modifier.then(interactive),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentIcon(Icons.Default.PlaylistAdd, Orange)
            Text("Add Playlist Manually", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Use an M3U URL or your authorized provider login.")
            Button(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Add Playlist") }
        }
    }
}

@Composable
private fun ManualPlaylistScreen(
    onBack: () -> Unit,
    loadPlaylist: suspend (PlaylistInput) -> Result<LoadedPlaylist>,
    onConnected: (LoadedPlaylist) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (landscape) 34.dp else 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Add Playlist", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("M3U URL") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Provider Login") })
        }
        OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            address, { address = it.trim() },
            label = { Text(if (tab == 0) "M3U/M3U8 URL" else "Server address") },
            trailingIcon = {
                IconButton(onClick = { address = clipboard.getText()?.text.orEmpty().trim() }) {
                    Icon(Icons.Default.ContentPaste, "Paste")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        AnimatedVisibility(tab == 1) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Text("Your details are stored securely on this device.", style = MaterialTheme.typography.bodySmall)
        AnimatedVisibility(error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null)
                    Spacer(Modifier.width(10.dp))
                    Text(error.orEmpty(), modifier = Modifier.weight(1f))
                }
            }
        }
        AnimatedVisibility(loading) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Connecting and organizing your playlist…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = {
                loading = true
                error = null
                val input = PlaylistInput(
                    name = name,
                    kind = if (tab == 0) PlaylistKind.M3U_URL else PlaylistKind.PROVIDER_LOGIN,
                    address = address,
                    username = username,
                    password = password
                )
                scope.launch {
                    loadPlaylist(input)
                        .onSuccess(onConnected)
                        .onFailure { error = it.message ?: "The playlist could not be loaded." }
                    loading = false
                }
            },
            enabled = !loading && name.isNotBlank() && address.isNotBlank() && (tab == 0 || username.isNotBlank() && password.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            else Text("Test and Add Playlist")
        }
        }
    }
}

@Composable
private fun PlaylistManagerScreen(
    sources: List<PlaylistInput>,
    activeSource: PlaylistInput?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: (PlaylistInput) -> Unit,
    onRemove: (PlaylistInput) -> Unit
) {
    var removing by remember { mutableStateOf<PlaylistInput?>(null) }
    removing?.let { source ->
        AlertDialog(
            onDismissRequest = { removing = null },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text("Remove ${source.name}?") },
            text = { Text("The saved login for this playlist will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { removing = null; onRemove(source) }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
    PremiumBackground {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Text("Playlists", Modifier.weight(1f), fontSize = 27.sp, fontWeight = FontWeight.Black)
                Button(onClick = onAdd) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
            Text(
                "Switch between saved playlists without replacing or deleting the others.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(sources) { source ->
                    val active = source.kind == activeSource?.kind &&
                        source.address == activeSource.address &&
                        source.username == activeSource.username
                    ElevatedCard(
                        onClick = { if (!active) onSelect(source) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (active) Cyan.copy(alpha = .14f) else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (source.kind == PlaylistKind.PROVIDER_LOGIN) Icons.Default.AccountCircle else Icons.Default.Link,
                                null,
                                tint = if (active) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(source.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    if (active) "Active playlist" else if (source.username.isNotBlank()) "Provider login • ${source.username}" else "M3U playlist",
                                    color = if (active) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!active) {
                                TextButton(onClick = { onSelect(source) }) { Text("Switch") }
                            }
                            IconButton(onClick = { removing = source }) {
                                Icon(Icons.Default.DeleteOutline, "Remove playlist", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.AddCircleOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add another playlist")
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    playlist: LoadedPlaylist?,
    onManage: () -> Unit,
    onPlaylists: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit,
    onMessage: (String) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    PremiumBackground {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val sidePadding = if (landscape) 34.dp else 20.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = sidePadding, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.weight(1f))
                IconButton(onClick = { onMessage("Add a playlist to start searching") }) { Icon(Icons.Default.Search, "Search") }
                IconButton(onClick = { onMessage("Playlist refreshed") }) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = onManage) { Icon(Icons.Default.Settings, "Settings") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Good evening", fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text(
                    playlist?.let { "${it.name} • ${it.items.size} items ready" } ?: "What would you like to watch?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 4 }
            ) { ContinueCard { if ((playlist?.movieCount ?: 0) > 0) onOpenMovies() else onMessage("Nothing to continue yet") } }
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeTile("Live TV", playlist?.let { "${it.liveCount} channels" } ?: "Browse your channels", Icons.Default.LiveTv, Cyan, Modifier.weight(1f), onOpenLive)
                    HomeTile("Movies", playlist?.let { "${it.movieCount} movies" } ?: "Find something to watch", Icons.Default.Movie, Orange, Modifier.weight(1f), onOpenMovies)
                    HomeTile("Series", playlist?.let { "${it.seriesCount} series" } ?: "Continue your episodes", Icons.Default.VideoLibrary, BrandBlue, Modifier.weight(1f), onOpenSeries)
                }
            } else {
                HomeTile("Live TV", playlist?.let { "${it.liveCount} channels" } ?: "Browse your channels", Icons.Default.LiveTv, Cyan, Modifier.fillMaxWidth(), onOpenLive)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeTile("Movies", playlist?.let { "${it.movieCount} movies" } ?: "Find something to watch", Icons.Default.Movie, Orange, Modifier.weight(1f), onOpenMovies)
                    HomeTile("Series", playlist?.let { "${it.seriesCount} series" } ?: "Continue your episodes", Icons.Default.VideoLibrary, BrandBlue, Modifier.weight(1f), onOpenSeries)
                }
            }
            Text("Quick access", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AssistChip(onClick = { onMessage("No favorites yet") }, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Default.Star, null) })
                AssistChip(onClick = { onMessage("No viewing history yet") }, label = { Text("Recently watched") }, leadingIcon = { Icon(Icons.Default.History, null) })
                AssistChip(onClick = onPlaylists, label = { Text("Playlists") }, leadingIcon = { Icon(Icons.Default.PlaylistPlay, null) })
            }
            HomeDeviceInfoBar(playlist = playlist)
        }
    }
    }
}

@Composable
private fun HomeDeviceInfoBar(playlist: LoadedPlaylist?) {
    val context = LocalContext.current
    val deviceIdentity = remember {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ).orEmpty().ifBlank { "4k-plus-tv-player" }
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8))
    }
    val appMac = remember(deviceIdentity) {
        deviceIdentity.take(6).joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }
    val deviceKey = remember(deviceIdentity) {
        val value = deviceIdentity.take(4).fold(0L) { result, byte ->
            (result shl 8) or (byte.toLong() and 0xFF)
        }
        "%06d".format(value % 1_000_000L)
    }
    val expiryText = remember(playlist?.expiryEpochSeconds) {
        playlist?.expiryEpochSeconds?.let { epochSeconds ->
            runCatching {
                val date = java.time.Instant.ofEpochSecond(epochSeconds)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                val days = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.now(),
                    date
                )
                when {
                    days > 0 -> "${date} (${days} days)"
                    days == 0L -> "${date} (today)"
                    else -> "${date} (expired)"
                }
            }.getOrNull()
        } ?: "Not provided"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Cyan.copy(alpha = 0.32f))
    ) {
        BoxWithConstraints {
            val wide = maxWidth >= 650.dp
            if (wide) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HomeInfoValue("App MAC", appMac, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(34.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    HomeInfoValue("Device key", deviceKey, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(34.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    HomeInfoValue("Playlist expires", expiryText, Modifier.weight(1.25f))
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HomeInfoValue("App MAC", appMac, Modifier.weight(1.45f))
                        VerticalDivider(
                            Modifier.height(38.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                        )
                        HomeInfoValue("Device key", deviceKey, Modifier.weight(1f))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    HomeInfoValue(
                        "Playlist expires",
                        expiryText,
                        Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeInfoValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

private enum class MovieView { BROWSE, CATEGORY, DETAILS, PLAYER }

@Composable
private fun MoviesScreen(
    playlist: LoadedPlaylist?,
    loadDetails: suspend (PlaylistItem) -> Result<MovieDetailsInfo>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val parental = remember { context.getSharedPreferences("parental_settings", android.content.Context.MODE_PRIVATE) }
    var hiddenCategories by remember {
        mutableStateOf(parental.getStringSet("hidden_movie_categories", emptySet()).orEmpty().toSet())
    }
    val movies = remember(playlist, hiddenCategories) {
        playlist?.items?.filter { it.kind == MediaKind.MOVIE && it.group !in hiddenCategories }.orEmpty()
    }
    val categories = remember(movies) { movies.map { it.group }.distinct() }
    val store = remember { context.getSharedPreferences("movie_library", android.content.Context.MODE_PRIVATE) }
    var view by remember { mutableStateOf(MovieView.BROWSE) }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var selectedMovie by remember { mutableStateOf<PlaylistItem?>(null) }
    var details by remember { mutableStateOf<MovieDetailsInfo?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }
    var detailsError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var favoriteIds by remember { mutableStateOf(store.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var recentIds by remember {
        mutableStateOf(store.getString("recent_v1", "").orEmpty().split('\u001F').filter(String::isNotBlank))
    }
    var progress by remember {
        mutableStateOf(
            store.all.mapNotNull { (key, value) ->
                if (key.startsWith("progress_") && value is Long && value > 0L) key.removePrefix("progress_") to value else null
            }.toMap()
        )
    }
    val byId = remember(movies) { movies.associateBy(::channelKey) }
    val favorites = remember(movies, favoriteIds) { movies.filter { channelKey(it) in favoriteIds } }
    val recent = remember(byId, recentIds) { recentIds.mapNotNull(byId::get) }
    val continueWatching = remember(movies, progress) {
        movies.filter { (progress[channelKey(it)] ?: 0L) >= 30_000L }
            .sortedByDescending { progress[channelKey(it)] ?: 0L }
    }

    fun toggleFavorite(movie: PlaylistItem) {
        val id = channelKey(movie)
        val updated = if (id in favoriteIds) favoriteIds - id else favoriteIds + id
        favoriteIds = updated
        store.edit().putStringSet("favorites", updated).apply()
    }
    fun openDetails(movie: PlaylistItem) {
        selectedMovie = movie
        details = null
        detailsError = null
        view = MovieView.DETAILS
    }
    fun recordRecent(movie: PlaylistItem) {
        val id = channelKey(movie)
        val updated = (listOf(id) + recentIds.filterNot { it == id }).take(30)
        recentIds = updated
        store.edit().putString("recent_v1", updated.joinToString("\u001F")).apply()
    }
    fun saveProgress(movie: PlaylistItem, position: Long, duration: Long) {
        val id = channelKey(movie)
        val normalized = if (duration > 0L && position >= duration - 20_000L) 0L else position.coerceAtLeast(0L)
        progress = if (normalized == 0L) progress - id else progress + (id to normalized)
        store.edit().putLong("progress_$id", normalized).apply()
    }
    fun goBack() {
        when (view) {
            MovieView.BROWSE -> onBack()
            MovieView.CATEGORY -> { search = ""; view = MovieView.BROWSE }
            MovieView.DETAILS -> view = MovieView.BROWSE
            MovieView.PLAYER -> view = MovieView.DETAILS
        }
    }
    BackHandler(onBack = ::goBack)

    LaunchedEffect(selectedMovie, view) {
        val movie = selectedMovie
        if (movie != null && view == MovieView.DETAILS && details == null && !detailsLoading) {
            detailsLoading = true
            loadDetails(movie)
                .onSuccess { details = it }
                .onFailure { detailsError = it.message }
            detailsLoading = false
        }
    }

    PremiumBackground {
        if (view == MovieView.DETAILS) {
            val pageBackdrop = details?.backdropUrl ?: details?.posterUrl ?: selectedMovie?.logoUrl
            if (!pageBackdrop.isNullOrBlank()) {
                AsyncImage(
                    model = pageBackdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = .56f),
                                MaterialTheme.colorScheme.background.copy(alpha = .78f),
                                MaterialTheme.colorScheme.background.copy(alpha = .96f)
                            )
                        )
                    )
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape && view in setOf(MovieView.BROWSE, MovieView.CATEGORY)) {
                LandscapeMovieBrowser(
                    movies = movies,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    search = search,
                    favoriteIds = favoriteIds,
                    recent = recent,
                    favorites = favorites,
                    continueWatching = continueWatching,
                    onCategory = { selectedCategory = it; search = ""; view = MovieView.CATEGORY },
                    onSearch = { search = it },
                    onFavorite = ::toggleFavorite,
                    onMovie = ::openDetails,
                    onHide = { category ->
                        val updated = hiddenCategories + category
                        hiddenCategories = updated
                        parental.edit().putStringSet("hidden_movie_categories", updated).apply()
                        selectedCategory = categories.firstOrNull { it != category }.orEmpty()
                    },
                    onBack = {
                        if (view == MovieView.CATEGORY) view = MovieView.BROWSE else onBack()
                    }
                )
                return@BoxWithConstraints
            }
            Column(
                Modifier.fillMaxSize().padding(horizontal = if (landscape) 34.dp else 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::goBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (view) {
                                MovieView.BROWSE -> "Movies"
                                MovieView.CATEGORY -> selectedCategory
                                else -> details?.originalTitle ?: selectedMovie?.name ?: "Movies"
                            },
                            fontSize = if (landscape) 23.sp else 22.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = if (landscape) 27.sp else 26.sp
                        )
                        if (view == MovieView.BROWSE) Text("${movies.size} movies", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (view == MovieView.CATEGORY && selectedCategory !in setOf("Continue watching", "Recently watched", "Favorites")) {
                        TextButton(onClick = {
                            hiddenCategories = hiddenCategories + selectedCategory
                            parental.edit().putStringSet("hidden_movie_categories", hiddenCategories).apply()
                            search = ""
                            view = MovieView.BROWSE
                        }) {
                            Icon(Icons.Default.VisibilityOff, null)
                            Spacer(Modifier.width(5.dp))
                            Text("Hide")
                        }
                    }
                    if (view == MovieView.DETAILS && selectedMovie != null) {
                        IconButton(onClick = { toggleFavorite(selectedMovie!!) }) {
                            Icon(
                                if (channelKey(selectedMovie!!) in favoriteIds) Icons.Default.Star else Icons.Default.StarBorder,
                                "Favorite",
                                tint = if (channelKey(selectedMovie!!) in favoriteIds) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                when (view) {
                    MovieView.BROWSE -> {
                        SearchField(search, { search = it }, "Search all movies")
                        if (search.isNotBlank()) {
                            val results = remember(movies, search) { movies.filter { it.name.contains(search.trim(), true) } }
                            MovieGrid(results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape)
                        } else {
                            val sections = buildList {
                                if (continueWatching.isNotEmpty()) add("Continue watching" to continueWatching)
                                add("Recently watched" to recent)
                                add("Favorites" to favorites)
                                categories.forEach { category -> add(category to movies.filter { it.group == category }) }
                            }
                            if (sections.isEmpty()) {
                                MovieEmptyState("No movies were found.")
                            } else {
                                LazyColumn(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(18.dp),
                                    contentPadding = PaddingValues(bottom = 20.dp)
                                ) {
                                    items(sections) { (title, sectionMovies) ->
                                        MovieShelf(
                                            title, sectionMovies, favoriteIds,
                                            onSeeAll = { selectedCategory = title; view = MovieView.CATEGORY },
                                            onHide = if (title in setOf("Continue watching", "Recently watched", "Favorites")) null else {{
                                                val updated = hiddenCategories + title
                                                hiddenCategories = updated
                                                parental.edit().putStringSet("hidden_movie_categories", updated).apply()
                                            }},
                                            onFavorite = ::toggleFavorite,
                                            onMovie = ::openDetails
                                        )
                                    }
                                }
                            }
                        }
                    }
                    MovieView.CATEGORY -> {
                        SearchField(search, { search = it }, "Search all movies")
                        val base = when (selectedCategory) {
                            "Continue watching" -> continueWatching
                            "Recently watched" -> recent
                            "Favorites" -> favorites
                            else -> movies.filter { it.group == selectedCategory }
                        }
                        val results = if (search.isBlank()) base else movies.filter { it.name.contains(search.trim(), true) }
                        MovieGrid(results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape)
                    }
                    MovieView.DETAILS -> selectedMovie?.let { movie ->
                        MovieDetails(
                            movie = movie,
                            details = details,
                            loading = detailsLoading,
                            detailsError = detailsError,
                            favorite = channelKey(movie) in favoriteIds,
                            resumePosition = progress[channelKey(movie)] ?: 0L,
                            onFavorite = { toggleFavorite(movie) },
                            onPlay = { recordRecent(movie); view = MovieView.PLAYER },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    MovieView.PLAYER -> selectedMovie?.let { movie ->
                        MoviePlayer(
                            movie = movie,
                            startPosition = progress[channelKey(movie)] ?: 0L,
                            onProgress = { position, duration -> saveProgress(movie, position, duration) },
                            onExit = { view = MovieView.DETAILS },
                            modifier = if (landscape) {
                                Modifier.weight(1f)
                            } else {
                                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeMovieBrowser(
    movies: List<PlaylistItem>,
    categories: List<String>,
    selectedCategory: String,
    search: String,
    favoriteIds: Set<String>,
    recent: List<PlaylistItem>,
    favorites: List<PlaylistItem>,
    continueWatching: List<PlaylistItem>,
    onCategory: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFavorite: (PlaylistItem) -> Unit,
    onMovie: (PlaylistItem) -> Unit,
    onHide: (String) -> Unit,
    onBack: () -> Unit
) {
    val special = listOf("Continue watching", "Recently watched", "Favorites")
    val allCategories = special + categories
    val base = when (selectedCategory) {
        "Continue watching" -> continueWatching
        "Recently watched" -> recent
        "Favorites" -> favorites
        else -> movies.filter { it.group == selectedCategory }
    }
    val displayed = if (search.isBlank()) base else movies.filter { it.name.contains(search.trim(), true) }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            Modifier.width(205.dp).fillMaxHeight(),
            shape = RoundedCornerShape(15.dp),
            color = Color.Black.copy(alpha = .34f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Column(Modifier.fillMaxSize().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    Text("Movies", fontSize = 19.sp, fontWeight = FontWeight.Black)
                }
                SearchField(search, onSearch, "Search movies")
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(allCategories) { category ->
                        Surface(
                            onClick = { onCategory(category) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(11.dp),
                            color = if (category == selectedCategory) Cyan.copy(alpha = .24f) else Color.Transparent
                        ) {
                            Row(Modifier.padding(start = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(category, Modifier.weight(1f), maxLines = 1, fontWeight = if (category == selectedCategory) FontWeight.Bold else FontWeight.Normal)
                                if (category !in special) {
                                    IconButton(onClick = { onHide(category) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.VisibilityOff, "Hide $category", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(selectedCategory.ifBlank { "Movies" }, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            MovieGrid(displayed, favoriteIds, onFavorite, onMovie, Modifier.weight(1f), true)
        }
    }
}

@Composable
private fun LandscapeLiveBrowser(
    categories: List<String>,
    selectedCategory: String,
    channels: List<PlaylistItem>,
    selectedChannel: PlaylistItem?,
    favoriteIds: Set<String>,
    onCategory: (String) -> Unit,
    onChannel: (PlaylistItem) -> Unit,
    onChannelFullscreen: (PlaylistItem) -> Unit,
    onFavorite: (PlaylistItem) -> Unit,
    onHide: (String) -> Unit,
    onBack: () -> Unit
) {
    var fullscreenChannel by remember { mutableStateOf<PlaylistItem?>(null) }
    fullscreenChannel?.let { active ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            LiveChannelPreview(
                channel = active,
                modifier = Modifier.fillMaxSize(),
                channelList = channels,
                onChannelChange = { next ->
                    onChannel(next)
                    fullscreenChannel = next
                },
                hostedFullscreen = true,
                onFullscreenDoubleTap = { fullscreenChannel = null }
            )
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LiveChannelPreview(
            channel = selectedChannel,
            modifier = Modifier.fillMaxSize(),
            onRequestFullscreen = { selectedChannel?.let { fullscreenChannel = it } }
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .36f)))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                Modifier.width(210.dp).fillMaxHeight(),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = .62f)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                        Text("Live TV", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(categories) { category ->
                            Surface(
                                onClick = { onCategory(category) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(9.dp),
                                color = if (category == selectedCategory) Orange.copy(alpha = .88f) else Color.Transparent
                            ) {
                                Row(Modifier.padding(start = 11.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(category, Modifier.weight(1f), color = Color.White, maxLines = 1)
                                    if (category !in setOf("Recently watched", "Favorites")) {
                                        IconButton(onClick = { onHide(category) }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Default.VisibilityOff, "Hide", tint = Color.White, modifier = Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                Modifier.width(310.dp).fillMaxHeight(),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = .54f)
            ) {
                LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(channels) { channel ->
                        val selected = channelKey(channel) == selectedChannel?.let(::channelKey)
                        Surface(
                            modifier = Modifier.fillMaxWidth().pointerInput(channelKey(channel)) {
                                detectTapGestures(
                                    onTap = { onChannel(channel) },
                                    onDoubleTap = {
                                        onChannelFullscreen(channel)
                                        fullscreenChannel = channel
                                    }
                                )
                            },
                            shape = RoundedCornerShape(9.dp),
                            color = if (selected) Cyan.copy(alpha = .32f) else Color.Transparent
                        ) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(channel.logoUrl, null, Modifier.size(28.dp), contentScale = ContentScale.Fit)
                                Spacer(Modifier.width(7.dp))
                                Text(channel.name, Modifier.weight(1f), color = Color.White, fontSize = 12.sp, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                IconButton(onClick = { onFavorite(channel) }, modifier = Modifier.size(30.dp)) {
                                    Icon(
                                        if (channelKey(channel) in favoriteIds) Icons.Default.Star else Icons.Default.StarBorder,
                                        "Favorite",
                                        tint = if (channelKey(channel) in favoriteIds) Orange else Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                selectedChannel?.let {
                    Surface(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        color = Color.Black.copy(alpha = .64f),
                        shape = RoundedCornerShape(11.dp)
                    ) {
                        Text(
                            it.name,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieShelf(
    title: String,
    movies: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onSeeAll: () -> Unit,
    onHide: (() -> Unit)?,
    onFavorite: (PlaylistItem) -> Unit,
    onMovie: (PlaylistItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            onHide?.let {
                IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.VisibilityOff, "Hide $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onSeeAll) { Text("See all", color = Cyan); Icon(Icons.Default.ChevronRight, null, tint = Cyan) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (movies.isEmpty()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape = RoundedCornerShape(13.dp)) {
                        Text(
                            if (title == "Favorites") "Movies you star will appear here." else "Movies you play will appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                        )
                    }
                }
            } else {
                items(movies.take(16)) { movie ->
                    MoviePoster(movie, channelKey(movie) in favoriteIds, { onFavorite(movie) }, { onMovie(movie) }, Modifier.width(128.dp))
                }
            }
        }
    }
}

@Composable
private fun MovieGrid(
    movies: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onFavorite: (PlaylistItem) -> Unit,
    onMovie: (PlaylistItem) -> Unit,
    modifier: Modifier,
    landscape: Boolean
) {
    if (movies.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("No movies match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (landscape) 7 else 3), modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 7.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (landscape) 9.dp else 16.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            gridItems(movies) { movie ->
                MoviePoster(movie, channelKey(movie) in favoriteIds, { onFavorite(movie) }, { onMovie(movie) })
            }
        }
    }
}

@Composable
private fun MoviePoster(
    movie: PlaylistItem,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Surface(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f), RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Movie, null, tint = Orange.copy(alpha = .5f), modifier = Modifier.size(38.dp))
                if (!movie.logoUrl.isNullOrBlank()) AsyncImage(movie.logoUrl, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp))
                ) {
                    Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (favorite) Orange else Color.White, modifier = Modifier.size(19.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(movie.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        if (!movie.year.isNullOrBlank()) Text(movie.year, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MovieDetails(
    movie: PlaylistItem,
    details: MovieDetailsInfo?,
    loading: Boolean,
    detailsError: String?,
    favorite: Boolean,
    resumePosition: Long,
    onFavorite: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val poster = details?.posterUrl ?: movie.logoUrl
    val backdrop = details?.backdropUrl ?: poster
    val description = details?.description ?: movie.description
    val year = details?.year ?: movie.year
    val rating = validMovieRating(details?.rating ?: movie.rating)
    val duration = readableMovieDuration(details?.duration ?: movie.duration)
    val displayTitle = details?.originalTitle ?: movie.name
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 14.dp)
    ) {
        Surface(
            Modifier.fillMaxWidth().then(if (landscape) Modifier.height(118.dp) else Modifier.aspectRatio(16f / 9f)),
            shape = RoundedCornerShape(if (landscape) 14.dp else 20.dp),
            color = if (landscape) Color.Black.copy(alpha = .28f) else Color.Black,
            shadowElevation = 10.dp
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!landscape && !backdrop.isNullOrBlank()) {
                    AsyncImage(backdrop, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                if (!landscape) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f)))))
                Text(
                    displayTitle,
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = if (landscape) 98.dp else 132.dp, end = 14.dp, bottom = if (landscape) 36.dp else 16.dp)
                )
                Surface(
                    Modifier.align(Alignment.BottomStart).offset(x = 14.dp).width(if (landscape) 70.dp else 104.dp).aspectRatio(2f / 3f),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 12.dp,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = .18f))
                ) {
                    if (!poster.isNullOrBlank()) AsyncImage(poster, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Spacer(Modifier.height(2.dp))

        if (!details?.originalTitle.isNullOrBlank() && details?.originalTitle != movie.name) {
            Text(movie.name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 2)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rating?.let { MovieInfoPill("★ $it/10", Orange) }
            year?.takeIf(String::isNotBlank)?.let { MovieInfoPill(it, Cyan) }
            duration?.let { MovieInfoPill(it, BrandBlue) }
            details?.genre?.takeIf(String::isNotBlank)?.let { MovieInfoPill(it, Cyan) }
            MovieInfoPill(movie.group, BrandBlue)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPlay, modifier = Modifier.weight(1f).height(54.dp)) {
                Icon(if (resumePosition > 0L) Icons.Default.Replay else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(7.dp))
                Text(if (resumePosition > 0L) "Resume ${formatPlaybackTime(resumePosition)}" else "Play")
            }
            FilledTonalIconButton(onClick = onFavorite, modifier = Modifier.size(54.dp)) {
                Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (favorite) Orange else Cyan)
            }
        }
        OutlinedButton(
            onClick = {
                val query = listOfNotNull(details?.originalTitle ?: movie.name, year, "official trailer").joinToString(" ")
                val trailerSearch = Uri.parse("https://www.youtube.com/results").buildUpon()
                    .appendQueryParameter("search_query", query).build()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, trailerSearch)) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.SmartDisplay, null)
            Spacer(Modifier.width(8.dp))
            Text("Watch trailer")
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Loading movie information…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                description?.takeIf(String::isNotBlank)
                    ?: "Detailed information was not supplied for this movie.",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            details?.cast?.takeIf(String::isNotBlank)?.let { MovieCreditRow(Icons.Default.Groups, "Cast", it) }
            details?.director?.takeIf(String::isNotBlank)?.let { MovieCreditRow(Icons.Default.MovieCreation, "Director", it) }
            if (detailsError != null && description.isNullOrBlank() && details?.cast.isNullOrBlank()) {
                Text("Additional information is unavailable from this playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun MovieInfoPill(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = .14f), shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, accent.copy(alpha = .35f))
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
    }
}

@Composable
private fun MovieCreditRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }
}

private fun validMovieRating(value: String?): String? = value?.trim()?.takeIf {
    it.isNotBlank() && it != "0" && it != "0.0" && !it.equals("null", true)
}

private fun readableMovieDuration(value: String?): String? {
    val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val seconds = text.toLongOrNull()
    if (seconds != null && seconds > 300L) {
        val minutes = seconds / 60L
        return if (minutes >= 60L) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
    return text
}

@Composable
internal fun MoviePlayer(
    movie: PlaylistItem,
    startPosition: Long,
    onProgress: (Long, Long) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE) }
    val subtitleLanguages = settings.getString("subtitle_language", "ar,en").orEmpty()
        .split(',').map(String::trim).filter(String::isNotBlank)
    var videoMode by remember { mutableStateOf(settings.getString("video_mode", "fit") ?: "fit") }
    val videoResizeMode = when (videoMode) {
        "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    var selectedPlayer by remember(movie) {
        mutableStateOf(settings.getString("player_engine", "default") ?: "default")
    }
    var missingExternalPlayer by remember(movie) { mutableStateOf<String?>(null) }
    if (selectedPlayer != "default") {
        LaunchedEffect(movie.streamUrl, selectedPlayer) {
            if (launchExternalPlayer(context, movie.streamUrl, selectedPlayer)) {
                onExit()
            } else {
                missingExternalPlayer = selectedPlayer
            }
        }
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (missingExternalPlayer == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Cyan)
                    Spacer(Modifier.height(10.dp))
                    Text("Opening external player…")
                }
            }
        }
        missingExternalPlayer?.let { missing ->
            val playerName = externalPlayerName(missing)
            AlertDialog(
                onDismissRequest = onExit,
                icon = { Icon(Icons.Default.InstallMobile, null) },
                title = { Text("$playerName is not installed") },
                text = { Text("Install $playerName from Google Play, then return and press Watch again.") },
                confirmButton = {
                    Button(onClick = {
                        openExternalPlayerStore(context, missing)
                        missingExternalPlayer = null
                        onExit()
                    }) { Text("Install $playerName") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        settings.edit().putString("player_engine", "default").apply()
                        selectedPlayer = "default"
                        missingExternalPlayer = null
                    }) { Text("Use default player") }
                }
            )
        }
        return
    }
    var error by remember(movie) { mutableStateOf<String?>(null) }
    var fullscreen by remember(movie) { mutableStateOf(true) }
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var externalSubtitle by remember(movie) { mutableStateOf<Uri?>(null) }
    var skipSeconds by remember { mutableIntStateOf(settings.getInt("skip_seconds", 10).takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 10) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    LaunchedEffect(seekFeedback?.second) {
        if (seekFeedback != null) {
            delay(650)
            seekFeedback = null
        }
    }
    val player = remember(movie.streamUrl, skipSeconds) {
        val factory = DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory))
            .setSeekBackIncrementMs(skipSeconds * 1_000L)
            .setSeekForwardIncrementMs(skipSeconds * 1_000L)
            .build()
            .apply { volume = if (settings.getBoolean("muted", false)) 0f else 1f }
    }
    LaunchedEffect(player, movie.streamUrl, externalSubtitle) {
        error = null
        val resumeAt = player.currentPosition.takeIf { it > 0L } ?: startPosition
        player.setMediaItem(mediaItemWithSubtitle(context, movie.streamUrl, externalSubtitle))
        if (resumeAt > 0L) player.seekTo(resumeAt)
        player.prepare()
        player.playWhenReady = true
        while (true) {
            delay(2_000)
            if (player.currentPosition > 0L) onProgress(player.currentPosition, player.duration)
        }
    }
    LaunchedEffect(player, subtitlesEnabled) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .setPreferredTextLanguages(*subtitleLanguages.toTypedArray())
            .setSelectUndeterminedTextLanguage(subtitlesEnabled)
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playbackException: PlaybackException) { error = "This movie could not be played." }
        }
        player.addListener(listener)
        onDispose {
            if (player.currentPosition > 0L) onProgress(player.currentPosition, player.duration)
            player.removeListener(listener)
            player.release()
        }
    }
    val playerContent: @Composable (Modifier, Shape) -> Unit = { contentModifier, shape ->
        Surface(contentModifier, shape, color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = true
                        resizeMode = videoResizeMode
                        applyRequestedAspectRatio(this, videoMode)
                        this.player = player
                        installDoubleTapSeek(this, player, skipSeconds) { forward ->
                            seekFeedback = forward to System.nanoTime()
                        }
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = videoResizeMode
                    applyRequestedAspectRatio(it, videoMode)
                    installDoubleTapSeek(it, player, skipSeconds) { forward ->
                        seekFeedback = forward to System.nanoTime()
                    }
                }, modifier = Modifier.fillMaxSize()
            )
            seekFeedback?.let { feedback ->
                DoubleTapSeekFeedback(
                    forward = feedback.first,
                    seconds = skipSeconds,
                    eventId = feedback.second,
                    modifier = Modifier
                        .align(if (feedback.first) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 34.dp)
                )
            }
            if (seekFeedback == null) PlaybackOptionsOverlay(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                player = player,
                fullscreen = fullscreen,
                onFullscreenChange = { enabled ->
                    if (enabled) fullscreen = true else onExit()
                },
                subtitlesEnabled = subtitlesEnabled,
                onSubtitlesEnabledChange = {
                    subtitlesEnabled = it
                    settings.edit().putBoolean("subtitles_enabled", it).apply()
                },
                externalSubtitle = externalSubtitle,
                onExternalSubtitleChange = { externalSubtitle = it },
                skipSeconds = skipSeconds,
                onSkipSecondsChange = {
                    skipSeconds = it
                    settings.edit().putInt("skip_seconds", it).apply()
                },
                videoMode = videoMode,
                onVideoModeChange = {
                    videoMode = it
                    settings.edit().putString("video_mode", it).apply()
                }
            )
            error?.let {
                Surface(Modifier.align(Alignment.Center).padding(20.dp), RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = .84f)) {
                    Text(it, color = Color.White, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
    }
    if (fullscreen) {
        Dialog(
            onDismissRequest = onExit,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) { playerContent(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 56.dp), RectangleShape) }
    } else {
        playerContent(modifier.fillMaxWidth(), RoundedCornerShape(18.dp))
    }
}

@Composable
private fun ColumnScope.MovieEmptyState(message: String) {
    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AccentIcon(Icons.Default.Movie, Orange)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return if (totalMinutes >= 60) "${totalMinutes / 60}h ${totalMinutes % 60}m" else "${totalMinutes}m"
}

private enum class LiveView { BROWSE, CATEGORY, PLAYER }

@Composable
private fun LiveTvScreen(playlist: LoadedPlaylist?, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val parental = remember { context.getSharedPreferences("parental_settings", android.content.Context.MODE_PRIVATE) }
    var hiddenCategories by remember {
        mutableStateOf(parental.getStringSet("hidden_live_categories", emptySet()).orEmpty().toSet())
    }
    val channels = remember(playlist, hiddenCategories) {
        playlist?.items?.filter { it.kind == MediaKind.LIVE && it.group !in hiddenCategories }.orEmpty()
    }
    val categories = remember(channels) { channels.map { it.group }.distinct() }
    val recentlyWatched = "Recently watched"
    val favorites = "Favorites"
    var view by remember { mutableStateOf(LiveView.BROWSE) }
    var selectedCategory by remember(playlist) { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var categoryQuery by remember { mutableStateOf("") }
    var channelQuery by remember { mutableStateOf("") }
    var showRecentInPlayer by remember { mutableStateOf(false) }
    var previewChannel by remember(playlist) { mutableStateOf(channels.firstOrNull()) }
    val store = remember { context.getSharedPreferences("favorite_channels", android.content.Context.MODE_PRIVATE) }
    var favoriteIds by remember { mutableStateOf(store.getStringSet("ids", emptySet()).orEmpty().toSet()) }
    var recentIds by remember {
        // v3 starts clean because v2 provider history used non-unique EPG IDs,
        // which could collapse many watched channels into one unrelated item.
        mutableStateOf(store.getString("recent_ids_v3", "").orEmpty().split('\u001F').filter(String::isNotBlank))
    }
    val channelByKey = remember(channels) { channels.associateBy(::channelKey) }
    val recentChannels = remember(channelByKey, recentIds) { recentIds.mapNotNull(channelByKey::get) }
    val favoriteChannels = remember(channels, favoriteIds) { channels.filter { channelKey(it) in favoriteIds } }
    val serverCategories = remember(categories, categoryQuery) {
        if (categoryQuery.isBlank()) categories
        else categories.filter { it.contains(categoryQuery.trim(), ignoreCase = true) }
    }
    val browseSections = remember(serverCategories, categoryQuery, recentChannels, favoriteChannels, channels) {
        buildList {
            if (categoryQuery.isBlank()) {
                add(recentlyWatched to recentChannels)
                add(favorites to favoriteChannels)
            }
            serverCategories.forEach { group -> add(group to channels.filter { it.group == group }) }
        }
    }
    val selectedChannels = remember(selectedCategory, recentChannels, favoriteChannels, channels) {
        when (selectedCategory) {
            recentlyWatched -> recentChannels
            favorites -> favoriteChannels
            else -> channels.filter { it.group == selectedCategory }
        }
    }
    val searchedChannels = remember(selectedChannels, channelQuery) {
        if (channelQuery.isBlank()) selectedChannels
        else selectedChannels.filter { it.name.contains(channelQuery.trim(), ignoreCase = true) }
    }

    fun rememberChannel(channel: PlaylistItem) {
        previewChannel = channel
        val key = channelKey(channel)
        val updated = (listOf(key) + recentIds.filterNot { it == key }).take(20)
        recentIds = updated
        store.edit().putString("recent_ids_v3", updated.joinToString("\u001F")).apply()
    }
    fun toggleFavorite(channel: PlaylistItem) {
        val key = channelKey(channel)
        val updated = if (key in favoriteIds) favoriteIds - key else favoriteIds + key
        favoriteIds = updated
        store.edit().putStringSet("ids", updated).apply()
    }

    LaunchedEffect(view, selectedCategory) {
        if (view == LiveView.CATEGORY) {
            // Entering a category previews its first channel, but does not add
            // it to history until the user deliberately selects a channel.
            previewChannel = selectedChannels.firstOrNull()
        }
    }

    PremiumBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                LandscapeLiveBrowser(
                    categories = buildList {
                        add(recentlyWatched)
                        add(favorites)
                        addAll(categories)
                    },
                    selectedCategory = selectedCategory,
                    channels = selectedChannels,
                    selectedChannel = previewChannel,
                    favoriteIds = favoriteIds,
                    onCategory = { category ->
                        selectedCategory = category
                        channelQuery = ""
                        previewChannel = when (category) {
                            recentlyWatched -> recentChannels.firstOrNull()
                            favorites -> favoriteChannels.firstOrNull()
                            else -> channels.firstOrNull { it.group == category }
                        } ?: previewChannel
                    },
                    onChannel = { rememberChannel(it) },
                    onChannelFullscreen = { rememberChannel(it) },
                    onFavorite = ::toggleFavorite,
                    onHide = { category ->
                        val updated = hiddenCategories + category
                        hiddenCategories = updated
                        parental.edit().putStringSet("hidden_live_categories", updated).apply()
                        selectedCategory = categories.firstOrNull { it != category }.orEmpty()
                        previewChannel = channels.firstOrNull { it.group == selectedCategory }
                    },
                    onBack = onBack
                )
                return@BoxWithConstraints
            }
            val sidePadding = 18.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = sidePadding, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiveHeader(
                    title = when (view) {
                        LiveView.BROWSE -> "Live TV"
                        LiveView.CATEGORY -> selectedCategory
                        LiveView.PLAYER -> previewChannel?.name ?: "Live TV"
                    },
                    subtitle = if (view == LiveView.BROWSE) "${channels.size} channels" else null,
                    onBack = {
                        when (view) {
                            LiveView.BROWSE -> onBack()
                            LiveView.CATEGORY -> { channelQuery = ""; view = LiveView.BROWSE }
                            LiveView.PLAYER -> view = LiveView.BROWSE
                        }
                    }
                )

                when (view) {
                    LiveView.BROWSE -> {
                        LiveChannelPreview(
                            channel = previewChannel,
                            modifier = if (landscape) Modifier.fillMaxWidth().height(150.dp)
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        SearchField(categoryQuery, { categoryQuery = it }, "Search categories")
                        if (browseSections.isEmpty()) {
                            EmptyLiveState(if (categoryQuery.isBlank()) "No live categories were found." else "No categories match your search.")
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                contentPadding = PaddingValues(bottom = 18.dp)
                            ) {
                                items(browseSections) { (title, sectionChannels) ->
                                    ChannelCategorySection(
                                        title = title,
                                        channels = sectionChannels,
                                        favoriteIds = favoriteIds,
                                        onSeeAll = { selectedCategory = title; channelQuery = ""; view = LiveView.CATEGORY },
                                        onHide = if (title in setOf(recentlyWatched, favorites)) null else {{
                                            val updated = hiddenCategories + title
                                            hiddenCategories = updated
                                            parental.edit().putStringSet("hidden_live_categories", updated).apply()
                                        }},
                                        onChannel = {
                                            selectedCategory = it.group
                                            showRecentInPlayer = false
                                            rememberChannel(it)
                                            view = LiveView.PLAYER
                                        },
                                        onFavorite = ::toggleFavorite
                                    )
                                }
                            }
                        }
                    }
                    LiveView.CATEGORY -> {
                        if (selectedCategory !in setOf(recentlyWatched, favorites)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    hiddenCategories = hiddenCategories + selectedCategory
                                    parental.edit().putStringSet("hidden_live_categories", hiddenCategories).apply()
                                    channelQuery = ""
                                    view = LiveView.BROWSE
                                }) {
                                    Icon(Icons.Default.VisibilityOff, null)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Hide category")
                                }
                            }
                        }
                        LiveChannelPreview(
                            channel = previewChannel,
                            modifier = if (landscape) Modifier.fillMaxWidth().height(150.dp)
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        SearchField(channelQuery, { channelQuery = it }, "Search channels")
                        if (searchedChannels.isEmpty()) {
                            EmptyLiveState("No channels match your search.")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(if (landscape) 5 else 3),
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                verticalArrangement = Arrangement.spacedBy(13.dp),
                                contentPadding = PaddingValues(bottom = 18.dp)
                            ) {
                                gridItems(searchedChannels) { channel ->
                                    ChannelPoster(
                                        channel = channel,
                                        favorite = channelKey(channel) in favoriteIds,
                                        onFavorite = { toggleFavorite(channel) },
                                        onClick = {
                                            rememberChannel(channel)
                                            showRecentInPlayer = false
                                            view = LiveView.PLAYER
                                        }
                                    )
                                }
                            }
                        }
                    }
                    LiveView.PLAYER -> {
                        val playerChannels = if (showRecentInPlayer) recentChannels else channels.filter {
                            it.group == (previewChannel?.group ?: selectedCategory)
                        }
                        LiveChannelPreview(
                            channel = previewChannel,
                            channelList = playerChannels,
                            onChannelChange = { rememberChannel(it) },
                            hostedFullscreen = false,
                            onFullscreenDoubleTap = { view = LiveView.BROWSE },
                            externalPlayback = true,
                            modifier = if (landscape) Modifier.fillMaxWidth().height(230.dp)
                            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !showRecentInPlayer,
                                onClick = { showRecentInPlayer = false },
                                label = { Text(previewChannel?.group ?: selectedCategory, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.Category, null, Modifier.size(17.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = showRecentInPlayer,
                                onClick = { showRecentInPlayer = true },
                                label = { Text("Recently watched", maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(17.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 18.dp)
                        ) {
                            items(playerChannels) { channel ->
                                CompactChannelRow(
                                    channel = channel,
                                    selected = channel == previewChannel,
                                    favorite = channelKey(channel) in favoriteIds,
                                    onFavorite = { toggleFavorite(channel) },
                                    onClick = { rememberChannel(channel) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Black
            )
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = Cyan.copy(alpha = .13f),
            border = BorderStroke(1.dp, Cyan.copy(alpha = .28f))
        ) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFF3B4F)))
                Spacer(Modifier.width(7.dp))
                Text("LIVE", color = Cyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Default.Close, "Clear search")
            }
        },
        placeholder = { Text(placeholder) }
    )
}

@Composable
private fun ChannelCategorySection(
    title: String,
    channels: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onSeeAll: () -> Unit,
    onHide: (() -> Unit)?,
    onChannel: (PlaylistItem) -> Unit,
    onFavorite: (PlaylistItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
            onHide?.let {
                IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.VisibilityOff, "Hide $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onSeeAll) {
                Text("See all", color = Cyan)
                Icon(Icons.Default.ChevronRight, null, tint = Cyan, modifier = Modifier.size(18.dp))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (channels.isEmpty()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .7f), shape = RoundedCornerShape(12.dp)) {
                        Text("No channels yet", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp))
                    }
                }
            } else {
                items(channels.take(12)) { channel ->
                    ChannelPoster(
                        channel = channel,
                        favorite = channelKey(channel) in favoriteIds,
                        onFavorite = { onFavorite(channel) },
                        onClick = { onChannel(channel) },
                        modifier = Modifier.width(118.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelPoster(
    channel: PlaylistItem,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Surface(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.LiveTv, null, tint = Cyan.copy(alpha = .55f), modifier = Modifier.size(36.dp))
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(channel.logoUrl, null, Modifier.fillMaxSize().padding(7.dp), contentScale = ContentScale.Fit)
                }
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp).background(Color.Black.copy(alpha = .42f), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        if (favorite) "Remove favorite" else "Add favorite",
                        tint = if (favorite) Orange else Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(channel.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2)
    }
}

@Composable
private fun DoubleTapSeekFeedback(
    forward: Boolean,
    seconds: Int,
    eventId: Long,
    modifier: Modifier = Modifier
) {
    val movement = remember(eventId) { Animatable(0f) }
    LaunchedEffect(eventId) {
        movement.animateTo(1f, animationSpec = tween(480))
    }
    Surface(
        modifier = modifier.graphicsLayer {
            translationX = (if (forward) 1f else -1f) * movement.value * 22f
            alpha = 1f - movement.value * .35f
        },
        color = Color.Black.copy(alpha = .55f),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!forward) {
                Text("−${seconds}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(3.dp))
            }
            repeat(3) {
                Icon(
                    if (forward) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (forward) {
                Spacer(Modifier.width(3.dp))
                Text("+${seconds}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

private fun applyRequestedAspectRatio(view: PlayerView, mode: String) {
    val targetRatio = when (mode) {
        "16:9" -> 16f / 9f
        "4:3" -> 4f / 3f
        "21:9" -> 21f / 9f
        "1:1" -> 1f
        else -> null
    }
    val surface = view.videoSurfaceView ?: return
    if (targetRatio == null) {
        surface.scaleX = 1f
        surface.scaleY = 1f
        return
    }
    view.post {
        val width = view.width.toFloat().coerceAtLeast(1f)
        val height = view.height.toFloat().coerceAtLeast(1f)
        val containerRatio = width / height
        if (targetRatio > containerRatio) {
            surface.scaleX = 1f
            surface.scaleY = containerRatio / targetRatio
        } else {
            surface.scaleX = targetRatio / containerRatio
            surface.scaleY = 1f
        }
    }
}

private fun installDoubleTapSeek(
    view: PlayerView,
    player: Player,
    skipSeconds: Int,
    onDoubleTapExit: (() -> Unit)? = null,
    onSeekFeedback: (Boolean) -> Unit
) {
    val detector = android.view.GestureDetector(
        view.context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent): Boolean = true

            override fun onDoubleTap(event: android.view.MotionEvent): Boolean {
                if (onDoubleTapExit != null) {
                    view.hideController()
                    onDoubleTapExit()
                    return true
                }
                if (!player.isCurrentMediaItemSeekable) return false
                val intervalMs = skipSeconds * 1_000L
                val destination = if (event.x < view.width / 2f) {
                    (player.currentPosition - intervalMs).coerceAtLeast(0L)
                } else {
                    val forward = player.currentPosition + intervalMs
                    if (player.duration > 0L) forward.coerceAtMost(player.duration) else forward
                }
                val forward = event.x >= view.width / 2f
                player.seekTo(destination)
                view.postDelayed({ view.hideController() }, 80L)
                onSeekFeedback(forward)
                return true
            }
        }
    )
    view.setOnTouchListener { _, event ->
        detector.onTouchEvent(event)
        false
    }
}

@Composable
private fun PlaybackOptionsOverlay(
    modifier: Modifier = Modifier,
    player: Player,
    fullscreen: Boolean,
    showFullscreen: Boolean = true,
    onFullscreenChange: (Boolean) -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitlesEnabledChange: (Boolean) -> Unit,
    externalSubtitle: Uri?,
    onExternalSubtitleChange: (Uri?) -> Unit,
    skipSeconds: Int,
    onSkipSecondsChange: (Int) -> Unit,
    videoMode: String,
    onVideoModeChange: (String) -> Unit
) {
    val context = LocalContext.current
    var subtitleMenu by remember { mutableStateOf(false) }
    var skipMenu by remember { mutableStateOf(false) }
    var sizeMenu by remember { mutableStateOf(false) }
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onExternalSubtitleChange(uri)
            onSubtitlesEnabledChange(true)
        }
    }
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = .68f),
        shape = RoundedCornerShape(13.dp)
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    muted = !muted
                    player.volume = if (muted) 0f else 1f
                    context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("muted", muted).apply()
                },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    if (muted) "Unmute" else "Mute",
                    tint = if (muted) Cyan else Color.White
                )
            }
            Box {
                IconButton(onClick = { subtitleMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Subtitles, "Subtitles", tint = if (subtitlesEnabled) Cyan else Color.White)
                }
                DropdownMenu(subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (subtitlesEnabled) "Turn subtitles off" else "Turn subtitles on") },
                        leadingIcon = { Icon(Icons.Default.Subtitles, null) },
                        onClick = { onSubtitlesEnabledChange(!subtitlesEnabled); subtitleMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Load SRT or VTT file") },
                        leadingIcon = { Icon(Icons.Default.NoteAdd, null) },
                        onClick = {
                            subtitleMenu = false
                            subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream"))
                        }
                    )
                    if (externalSubtitle != null) {
                        DropdownMenuItem(
                            text = { Text("Remove external subtitles") },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                            onClick = { onExternalSubtitleChange(null); subtitleMenu = false }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { skipMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.MoreTime, "Skip interval", tint = Color.White)
                }
                DropdownMenu(skipMenu, onDismissRequest = { skipMenu = false }) {
                    listOf(5, 10, 15, 30, 60).forEach { seconds ->
                        DropdownMenuItem(
                            text = { Text("Skip $seconds seconds") },
                            leadingIcon = { if (seconds == skipSeconds) Icon(Icons.Default.Check, null) },
                            onClick = { onSkipSecondsChange(seconds); skipMenu = false }
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { sizeMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.AspectRatio, "Screen dimensions", tint = Cyan)
                }
                DropdownMenu(sizeMenu, onDismissRequest = { sizeMenu = false }) {
                    listOf(
                        "fit" to "Fit video",
                        "stretch" to "Stretch to screen",
                        "zoom" to "Fill and crop",
                        "16:9" to "16:9 Standard",
                        "4:3" to "4:3 Traditional",
                        "21:9" to "21:9 Ultrawide",
                        "1:1" to "1:1 Square"
                    ).forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { if (videoMode == mode) Icon(Icons.Default.Check, null) },
                            onClick = { onVideoModeChange(mode); sizeMenu = false }
                        )
                    }
                }
            }
            if (showFullscreen) {
                IconButton(onClick = { onFullscreenChange(!fullscreen) }, modifier = Modifier.size(38.dp)) {
                    Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                }
            }
        }
    }
}

private fun launchExternalPlayer(
    context: android.content.Context,
    streamUrl: String,
    preference: String
): Boolean {
    val packages = when (preference) {
        "vlc" -> listOf("org.videolan.vlc")
        "mx" -> listOf("com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro")
        else -> emptyList()
    }
    for (packageName in packages) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), "video/*")
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return true
    }
    return false
}

private fun externalPlayerName(preference: String): String =
    if (preference == "vlc") "VLC" else "MX Player"

private fun openExternalPlayerStore(context: android.content.Context, preference: String) {
    val packageName = if (preference == "vlc") "org.videolan.vlc" else "com.mxtech.videoplayer.ad"
    val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (!runCatching { context.startActivity(playStoreIntent); true }.getOrDefault(false)) {
        val browserIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(browserIntent) }
    }
}

private fun mediaItemWithSubtitle(context: android.content.Context, streamUrl: String, subtitle: Uri?): MediaItem {
    val builder = MediaItem.Builder().setUri(streamUrl)
    if (subtitle != null) {
        val detected = context.contentResolver.getType(subtitle).orEmpty()
        val mime = if (detected.contains("vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
        builder.setSubtitleConfigurations(
            listOf(MediaItem.SubtitleConfiguration.Builder(subtitle).setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
        )
    }
    return builder.build()
}

@Composable
private fun LiveChannelPreview(
    channel: PlaylistItem?,
    modifier: Modifier = Modifier,
    externalPlayback: Boolean = false,
    channelList: List<PlaylistItem> = emptyList(),
    onChannelChange: (PlaylistItem) -> Unit = {},
    hostedFullscreen: Boolean = false,
    onFullscreenDoubleTap: (() -> Unit)? = null,
    onRequestFullscreen: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val settings = remember { context.getSharedPreferences("playback_settings", android.content.Context.MODE_PRIVATE) }
    val subtitleLanguages = settings.getString("subtitle_language", "ar,en").orEmpty()
        .split(',').map(String::trim).filter(String::isNotBlank)
    var videoMode by remember { mutableStateOf(settings.getString("video_mode", "fit") ?: "fit") }
    val videoResizeMode = when (videoMode) {
        "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    val selectedPlayer = settings.getString("player_engine", "default") ?: "default"
    var externalAttempt by remember(channel?.streamUrl) { mutableIntStateOf(0) }
    var externalFailed by remember(channel?.streamUrl) { mutableStateOf(false) }
    if (externalPlayback && channel != null && selectedPlayer != "default") {
        LaunchedEffect(channel.streamUrl, selectedPlayer, externalAttempt) {
            externalFailed = !launchExternalPlayer(context, channel.streamUrl, selectedPlayer)
        }
        Box(
            modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (externalFailed) Icons.Default.ErrorOutline else Icons.Default.OpenInNew,
                    null,
                    tint = if (externalFailed) MaterialTheme.colorScheme.error else Cyan,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (externalFailed) "${externalPlayerName(selectedPlayer)} is not installed." else "Stream opened in external player.",
                    color = Color.White
                )
                Spacer(Modifier.height(10.dp))
                if (externalFailed) {
                    Button(onClick = { openExternalPlayerStore(context, selectedPlayer) }) {
                        Icon(Icons.Default.InstallMobile, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Install ${externalPlayerName(selectedPlayer)}")
                    }
                    TextButton(onClick = {
                        settings.edit().putString("player_engine", "default").apply()
                        externalAttempt++
                    }) { Text("Use default player", color = Color.White) }
                } else {
                    OutlinedButton(onClick = { externalAttempt++ }) {
                        Text("Open again")
                    }
                }
            }
        }
        return
    }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var controllerVisible by remember { mutableStateOf(true) }
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var externalSubtitle by remember(channel?.streamUrl) { mutableStateOf<Uri?>(null) }
    var skipSeconds by remember { mutableIntStateOf(settings.getInt("skip_seconds", 10).takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 10) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    LaunchedEffect(seekFeedback?.second) {
        if (seekFeedback != null) {
            delay(650)
            seekFeedback = null
        }
    }
    val player = remember(skipSeconds) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .setSeekBackIncrementMs(skipSeconds * 1_000L)
            .setSeekForwardIncrementMs(skipSeconds * 1_000L)
            .build()
            .apply {
                playWhenReady = true
                volume = if (settings.getBoolean("muted", false)) 0f else 1f
            }
    }

    LaunchedEffect(channel?.streamUrl, externalSubtitle) {
        playbackError = null
        if (channel == null) {
            player.clearMediaItems()
        } else {
            runCatching {
                player.setMediaItem(mediaItemWithSubtitle(context, channel.streamUrl, externalSubtitle))
                player.prepare()
                player.play()
            }.onFailure {
                playbackError = "This channel could not be previewed."
                player.clearMediaItems()
            }
        }
    }
    LaunchedEffect(player, subtitlesEnabled) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .setPreferredTextLanguages(*subtitleLanguages.toTypedArray())
            .setSelectUndeterminedTextLanguage(subtitlesEnabled)
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playbackError = "This stream is unavailable. Choose another channel."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val fullscreenDoubleTapExit: (() -> Unit)? = if ((fullscreen || hostedFullscreen) && onFullscreenDoubleTap != null) {
        { fullscreen = false; onFullscreenDoubleTap() }
    } else null
    val playerContent: @Composable (Modifier, Shape) -> Unit = { contentModifier, shape ->
        Surface(modifier = contentModifier, shape = shape, color = Color.Black, shadowElevation = 8.dp) {
            Box(Modifier.fillMaxSize()) {
            if (channel == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LiveTv, null, tint = Cyan, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(7.dp))
                    Text("Choose a channel", color = Color.White.copy(alpha = .75f))
                }
            } else {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            useController = true
                            controllerShowTimeoutMs = 4_000
                            setControllerVisibilityListener(
                                PlayerView.ControllerVisibilityListener { visibility ->
                                    controllerVisible = visibility == android.view.View.VISIBLE
                                }
                            )
                            resizeMode = videoResizeMode
                            this.player = player
                            if (hostedFullscreen) {
                                findViewById<android.view.View>(androidx.media3.ui.R.id.exo_center_controls)?.visibility =
                                    android.view.View.GONE
                            }
                            installDoubleTapSeek(this, player, skipSeconds, fullscreenDoubleTapExit) { forward ->
                            seekFeedback = forward to System.nanoTime()
                        }
                        }
                    },
                    update = {
                        it.player = player
                        it.resizeMode = videoResizeMode
                        it.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_center_controls)?.visibility =
                            if (hostedFullscreen) android.view.View.GONE else android.view.View.VISIBLE
                        applyRequestedAspectRatio(it, videoMode)
                        installDoubleTapSeek(it, player, skipSeconds, fullscreenDoubleTapExit) { forward ->
                        seekFeedback = forward to System.nanoTime()
                    }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                seekFeedback?.let { feedback ->
                    DoubleTapSeekFeedback(
                        forward = feedback.first,
                        seconds = skipSeconds,
                        eventId = feedback.second,
                        modifier = Modifier
                            .align(if (feedback.first) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(horizontal = 34.dp)
                    )
                }
                if (controllerVisible && seekFeedback == null) PlaybackOptionsOverlay(
                    modifier = Modifier.align(Alignment.TopEnd)
                        .then(
                            if (fullscreen || hostedFullscreen) Modifier
                                .windowInsetsPadding(WindowInsets.displayCutout)
                                .padding(horizontal = 16.dp)
                            else Modifier
                        )
                        .padding(8.dp),
                    player = player,
                    fullscreen = fullscreen,
                    showFullscreen = !hostedFullscreen && onRequestFullscreen == null,
                    onFullscreenChange = { requested ->
                        if (requested && onRequestFullscreen != null) onRequestFullscreen() else fullscreen = requested
                    },
                    subtitlesEnabled = subtitlesEnabled,
                    onSubtitlesEnabledChange = {
                        subtitlesEnabled = it
                        settings.edit().putBoolean("subtitles_enabled", it).apply()
                    },
                    externalSubtitle = externalSubtitle,
                    onExternalSubtitleChange = { externalSubtitle = it },
                    skipSeconds = skipSeconds,
                    onSkipSecondsChange = {
                        skipSeconds = it
                        settings.edit().putInt("skip_seconds", it).apply()
                    },
                    videoMode = videoMode,
                    onVideoModeChange = {
                        videoMode = it
                        settings.edit().putString("video_mode", it).apply()
                    }
                )
                if (controllerVisible && seekFeedback == null) {
                    val currentIndex = channelList.indexOfFirst { channelKey(it) == channelKey(channel) }
                    val previous = channelList.getOrNull(currentIndex - 1)
                    val next = channelList.getOrNull(currentIndex + 1)
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart)
                            .then(
                                if (fullscreen || hostedFullscreen) Modifier
                                    .windowInsetsPadding(WindowInsets.displayCutout)
                                    .padding(
                                        start = if (hostedFullscreen) 64.dp else 16.dp,
                                        end = 16.dp
                                    )
                                else Modifier
                            )
                            .padding(8.dp)
                            .fillMaxWidth(.58f),
                        color = Color.Black.copy(alpha = .72f),
                        shape = RoundedCornerShape(11.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { previous?.let(onChannelChange) },
                                enabled = previous != null
                            ) {
                                Icon(Icons.Default.SkipPrevious, "Previous channel", tint = Color.White)
                            }
                            Text(
                                channel.name,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(enabled = onFullscreenDoubleTap != null) {
                                        onFullscreenDoubleTap?.invoke()
                                    }
                                    .padding(vertical = 10.dp)
                            )
                            IconButton(
                                onClick = { next?.let(onChannelChange) },
                                enabled = next != null
                            ) {
                                Icon(Icons.Default.SkipNext, "Next channel", tint = Color.White)
                            }
                        }
                    }
                }
                if (playbackError != null) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center).padding(18.dp),
                        color = Color.Black.copy(alpha = .82f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF5A67))
                            Spacer(Modifier.width(8.dp))
                            Text(playbackError.orEmpty(), color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    }
    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) { playerContent(Modifier.fillMaxSize(), RectangleShape) }
    } else {
        playerContent(modifier, RoundedCornerShape(18.dp))
    }
}

@Composable
private fun CategoryRailItem(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        color = if (selected) BrandBlue.copy(alpha = .9f) else MaterialTheme.colorScheme.surface.copy(alpha = .82f),
        border = BorderStroke(1.dp, if (selected) Cyan.copy(alpha = .55f) else Color.White.copy(alpha = .08f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (selected) Color.White else Cyan)
                Spacer(Modifier.width(6.dp))
            }
            Text(label, maxLines = 2, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun CompactChannelRow(
    channel: PlaylistItem,
    selected: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        color = if (selected) BrandBlue.copy(alpha = .34f) else MaterialTheme.colorScheme.surface.copy(alpha = .9f),
        border = BorderStroke(1.dp, if (selected) Cyan.copy(alpha = .55f) else Color.Transparent),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LiveTv, null, tint = Cyan.copy(alpha = .65f), modifier = Modifier.size(22.dp))
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(channel.logoUrl, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                Text(channel.group, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
            }
            IconButton(onClick = onFavorite, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (favorite) "Remove favorite" else "Add favorite",
                    tint = if (favorite) Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

internal fun channelKey(channel: PlaylistItem): String = channel.channelId ?: "${channel.group}:${channel.name}"

@Composable
private fun ChannelRow(channel: PlaylistItem, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 64.dp, height = 52.dp).clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LiveTv, null, tint = Cyan.copy(alpha = .7f))
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(channel.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (favorite) "Remove favorite" else "Add favorite",
                    tint = if (favorite) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ColumnScope.EmptyLiveState(message: String) {
    Column(
        Modifier.fillMaxWidth().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AccentIcon(Icons.Default.LiveTv, Cyan)
        Spacer(Modifier.height(12.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ContinueCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().then(pressFeedback(onClick))
    ) {
        Row(
            Modifier.fillMaxWidth().background(
                Brush.horizontalGradient(listOf(DeepBlue, BrandBlue.copy(alpha = .92f), Cyan.copy(alpha = .72f)))
            ).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .14f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.PlayArrow, null, tint = Orange, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue Watching", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                Text("Your recent content will appear here", color = androidx.compose.ui.graphics.Color.White.copy(alpha = .72f))
            }
            Icon(Icons.Default.ChevronRight, null, tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}

@Composable
private fun HomeTile(title: String, subtitle: String, icon: ImageVector, accent: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(
        modifier.heightIn(min = 150.dp).then(pressFeedback(onClick)),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 7.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                AccentIcon(icon, accent)
                Icon(Icons.Default.ArrowOutward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f), modifier = Modifier.size(18.dp))
            }
            Column {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun pressFeedback(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, tween(90), label = "press")
    val haptic = LocalHapticFeedback.current
    return Modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .clickable(interactionSource = interaction, indication = LocalIndication.current) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}
