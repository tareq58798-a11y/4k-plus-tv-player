package com.fourkplus.tvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
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
        enableEdgeToEdge()
        setContent { App() }
    }
}

private enum class Screen { LOADING, ACTIVATION, MANUAL, HOME, LIVE_TV, MOVIES }
private enum class ThemeChoice { SYSTEM, LIGHT, DARK }

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
    val playlistRepository = remember { PlaylistRepository(context.applicationContext) }
    var loadedPlaylist by remember { mutableStateOf<LoadedPlaylist?>(null) }
    val message: (String) -> Unit = { scope.launch { snackbar.showSnackbar(it) } }

    LaunchedEffect(Unit) {
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
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
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
                    onThemeChange = { themeChoice = it },
                    onManual = { screen = Screen.MANUAL },
                    onMessage = message
                )
                Screen.MANUAL -> ManualPlaylistScreen(
                    onBack = { screen = Screen.ACTIVATION },
                    loadPlaylist = playlistRepository::load,
                    onConnected = {
                        loadedPlaylist = it
                        screen = Screen.HOME
                        message("${it.items.size} items loaded")
                    }
                )
                Screen.HOME -> HomeScreen(
                    playlist = loadedPlaylist,
                    onManage = { screen = Screen.ACTIVATION },
                    onOpenLive = { screen = Screen.LIVE_TV },
                    onOpenMovies = { screen = Screen.MOVIES },
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
private fun HomeScreen(
    playlist: LoadedPlaylist?,
    onManage: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenMovies: () -> Unit,
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
                    HomeTile("Series", playlist?.let { "${it.seriesCount} series" } ?: "Continue your episodes", Icons.Default.VideoLibrary, BrandBlue, Modifier.weight(1f)) { onMessage("Series browsing is the next milestone") }
                }
            } else {
                HomeTile("Live TV", playlist?.let { "${it.liveCount} channels" } ?: "Browse your channels", Icons.Default.LiveTv, Cyan, Modifier.fillMaxWidth(), onOpenLive)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeTile("Movies", playlist?.let { "${it.movieCount} movies" } ?: "Find something to watch", Icons.Default.Movie, Orange, Modifier.weight(1f), onOpenMovies)
                    HomeTile("Series", playlist?.let { "${it.seriesCount} series" } ?: "Continue your episodes", Icons.Default.VideoLibrary, BrandBlue, Modifier.weight(1f)) { onMessage("Series browsing is the next milestone") }
                }
            }
            Text("Quick access", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AssistChip(onClick = { onMessage("No favorites yet") }, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Default.Star, null) })
                AssistChip(onClick = { onMessage("No viewing history yet") }, label = { Text("Recently watched") }, leadingIcon = { Icon(Icons.Default.History, null) })
                AssistChip(onClick = onManage, label = { Text("Playlists") }, leadingIcon = { Icon(Icons.Default.PlaylistPlay, null) })
            }
        }
    }
    }
}

private enum class MovieView { BROWSE, CATEGORY, DETAILS, PLAYER }

@Composable
private fun MoviesScreen(
    playlist: LoadedPlaylist?,
    loadDetails: suspend (PlaylistItem) -> Result<MovieDetailsInfo>,
    onBack: () -> Unit
) {
    val movies = remember(playlist) { playlist?.items?.filter { it.kind == MediaKind.MOVIE }.orEmpty() }
    val categories = remember(movies) { movies.map { it.group }.distinct() }
    val context = LocalContext.current
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
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
                                else -> selectedMovie?.name ?: "Movies"
                            },
                            fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 1
                        )
                        if (view == MovieView.BROWSE) Text("${movies.size} movies", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            modifier = Modifier.weight(1f)
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
    onFavorite: (PlaylistItem) -> Unit,
    onMovie: (PlaylistItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
            columns = GridCells.Fixed(if (landscape) 6 else 3), modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
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
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(20.dp),
            color = Color.Black,
            shadowElevation = 10.dp
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!backdrop.isNullOrBlank()) {
                    AsyncImage(backdrop, movie.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f)))))
                Text(
                    movie.name,
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 132.dp, end = 14.dp, bottom = 16.dp)
                )
                Surface(
                    Modifier.align(Alignment.BottomStart).offset(x = 14.dp).width(104.dp).aspectRatio(2f / 3f),
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
                val query = listOfNotNull(movie.name, year, "official trailer").joinToString(" ")
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
private fun MoviePlayer(
    movie: PlaylistItem,
    startPosition: Long,
    onProgress: (Long, Long) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    var error by remember(movie) { mutableStateOf<String?>(null) }
    val player = remember(movie.streamUrl) {
        val factory = DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory)).build()
    }
    LaunchedEffect(player, movie.streamUrl) {
        error = null
        player.setMediaItem(MediaItem.fromUri(movie.streamUrl))
        if (startPosition > 0L) player.seekTo(startPosition)
        player.prepare()
        player.playWhenReady = true
        while (true) {
            delay(2_000)
            if (player.currentPosition > 0L) onProgress(player.currentPosition, player.duration)
        }
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
    Surface(modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { PlayerView(it).apply { useController = true; this.player = player } },
                update = { it.player = player }, modifier = Modifier.fillMaxSize()
            )
            error?.let {
                Surface(Modifier.align(Alignment.Center).padding(20.dp), RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = .84f)) {
                    Text(it, color = Color.White, modifier = Modifier.padding(16.dp))
                }
            }
        }
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
    val channels = remember(playlist) { playlist?.items?.filter { it.kind == MediaKind.LIVE }.orEmpty() }
    val categories = remember(channels) { channels.map { it.group }.distinct() }
    val recentlyWatched = "Recently watched"
    val favorites = "Favorites"
    var view by remember { mutableStateOf(LiveView.BROWSE) }
    var selectedCategory by remember(playlist) { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var categoryQuery by remember { mutableStateOf("") }
    var channelQuery by remember { mutableStateOf("") }
    var showRecentInPlayer by remember { mutableStateOf(false) }
    var previewChannel by remember(playlist) { mutableStateOf(channels.firstOrNull()) }
    val context = LocalContext.current
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
            val sidePadding = if (landscape) 34.dp else 18.dp
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
                        LiveChannelPreview(
                            channel = previewChannel,
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
                        val playerChannels = if (showRecentInPlayer) recentChannels else channels.filter {
                            it.group == (previewChannel?.group ?: selectedCategory)
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
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Black, maxLines = 1)
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
    onChannel: (PlaylistItem) -> Unit,
    onFavorite: (PlaylistItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
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
private fun LiveChannelPreview(channel: PlaylistItem?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playbackError by remember { mutableStateOf<String?>(null) }
    val player = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
            .apply { playWhenReady = true }
    }

    LaunchedEffect(channel?.streamUrl) {
        playbackError = null
        if (channel == null) {
            player.clearMediaItems()
        } else {
            runCatching {
                player.setMediaItem(MediaItem.fromUri(channel.streamUrl))
                player.prepare()
                player.play()
            }.onFailure {
                playbackError = "This channel could not be previewed."
                player.clearMediaItems()
            }
        }
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

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black,
        shadowElevation = 8.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            if (channel == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LiveTv, null, tint = Cyan, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(7.dp))
                    Text("Choose a channel", color = Color.White.copy(alpha = .75f))
                }
            } else {
                AndroidView(
                    factory = { PlayerView(it).apply { useController = true; this.player = player } },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    color = Color.Black.copy(alpha = .68f),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(channel.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
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

private fun channelKey(channel: PlaylistItem): String = channel.channelId ?: "${channel.group}:${channel.name}"

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
