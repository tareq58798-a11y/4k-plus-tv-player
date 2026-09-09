package com.fourkplus.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.fourkplus.tvplayer.ui.theme.*
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
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

private enum class Screen { ACTIVATION, MANUAL, HOME, LIVE_TV }
private enum class ThemeChoice { SYSTEM, LIGHT, DARK }

@Composable
private fun App() {
    var screen by remember { mutableStateOf(Screen.ACTIVATION) }
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

    FourKPlusTheme(darkTheme = useDark) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
        ) { scaffoldPadding ->
            Box(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            when (screen) {
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
                    onMessage = message
                )
                Screen.LIVE_TV -> LiveTvScreen(
                    playlist = loadedPlaylist,
                    onBack = { screen = Screen.HOME },
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
private fun HomeScreen(
    playlist: LoadedPlaylist?,
    onManage: () -> Unit,
    onOpenLive: () -> Unit,
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
            ) { ContinueCard { onMessage("Nothing to continue yet") } }
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeTile("Live TV", playlist?.let { "${it.liveCount} channels" } ?: "Browse your channels", Icons.Default.LiveTv, Cyan, Modifier.weight(1f), onOpenLive)
                    HomeTile("Movies", playlist?.let { "${it.movieCount} movies" } ?: "Find something to watch", Icons.Default.Movie, Orange, Modifier.weight(1f)) { onMessage("Movie browsing is the next milestone") }
                    HomeTile("Series", playlist?.let { "${it.seriesCount} series" } ?: "Continue your episodes", Icons.Default.VideoLibrary, BrandBlue, Modifier.weight(1f)) { onMessage("Series browsing is the next milestone") }
                }
            } else {
                HomeTile("Live TV", playlist?.let { "${it.liveCount} channels" } ?: "Browse your channels", Icons.Default.LiveTv, Cyan, Modifier.fillMaxWidth(), onOpenLive)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeTile("Movies", playlist?.let { "${it.movieCount} movies" } ?: "Find something to watch", Icons.Default.Movie, Orange, Modifier.weight(1f)) { onMessage("Movie browsing is the next milestone") }
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

@Composable
private fun LiveTvScreen(playlist: LoadedPlaylist?, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val channels = remember(playlist) { playlist?.items?.filter { it.kind == MediaKind.LIVE }.orEmpty() }
    // Preserve the order supplied by the provider so the first server category
    // is also the category shown when Live TV opens.
    val categories = remember(channels) { channels.map { it.group }.distinct() }
    val recentlyWatched = "Recently watched"
    val favorites = "Favorites"
    var selectedCategory by remember(playlist) { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var query by remember { mutableStateOf("") }
    var categoryQuery by remember { mutableStateOf("") }
    val context = LocalContext.current
    val favoritesStore = remember { context.getSharedPreferences("favorite_channels", android.content.Context.MODE_PRIVATE) }
    var favoriteIds by remember { mutableStateOf(favoritesStore.getStringSet("ids", emptySet()).orEmpty().toSet()) }
    var recentIds by remember {
        mutableStateOf(
            favoritesStore.getString("recent_ids", "").orEmpty()
                .split('\u001F').filter(String::isNotBlank)
        )
    }
    val channelByKey = remember(channels) { channels.associateBy(::channelKey) }
    val visibleCategories = remember(categories, categoryQuery) {
        if (categoryQuery.isBlank()) categories
        else categories.filter { it.contains(categoryQuery.trim(), ignoreCase = true) }
    }
    val categoryChannels = remember(channels, channelByKey, selectedCategory, favoriteIds, recentIds) {
        when (selectedCategory) {
            recentlyWatched -> recentIds.mapNotNull(channelByKey::get)
            favorites -> channels.filter { channelKey(it) in favoriteIds }
            else -> channels.filter { it.group == selectedCategory }
        }
    }
    val filtered = remember(channels, categoryChannels, query) {
        // Channel search is global. The selected category is only applied when
        // the search box is empty.
        if (query.isBlank()) categoryChannels
        else channels.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    PremiumBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val sidePadding = if (landscape) 34.dp else 20.dp
            Column(
                Modifier.fillMaxSize().padding(horizontal = sidePadding, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text("Live TV", fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("${filtered.size} channels", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
                    },
                    placeholder = { Text("Search channels") }
                )

                OutlinedTextField(
                    value = categoryQuery,
                    onValueChange = { categoryQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Default.Category, null) },
                    trailingIcon = {
                        if (categoryQuery.isNotEmpty()) IconButton(onClick = { categoryQuery = "" }) {
                            Icon(Icons.Default.Close, "Clear category search")
                        }
                    },
                    placeholder = { Text("Search categories") }
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (categoryQuery.isBlank()) {
                        item {
                            FilterChip(
                                selected = selectedCategory == recentlyWatched,
                                onClick = { selectedCategory = recentlyWatched },
                                label = { Text(recentlyWatched) },
                                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(17.dp)) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedCategory == favorites,
                                onClick = { selectedCategory = favorites },
                                label = { Text(favorites) },
                                leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(17.dp)) }
                            )
                        }
                    }
                    items(visibleCategories) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(category, maxLines = 1) }
                        )
                    }
                }

                when {
                    channels.isEmpty() -> EmptyLiveState("No live channels were found in this playlist.")
                    filtered.isEmpty() -> EmptyLiveState(
                        when {
                            query.isNotBlank() -> "No channels match your search."
                            selectedCategory == recentlyWatched -> "Channels you watch will appear here."
                            selectedCategory == favorites -> "Star channels to add them to Favorites."
                            else -> "No channels are available in this category."
                        }
                    )
                    else -> LazyColumn(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 18.dp)
                    ) {
                        itemsIndexed(
                            items = filtered,
                            key = { index, item -> "${item.channelId ?: item.name}-$index" }
                        ) { _, channel ->
                            val favoriteKey = channelKey(channel)
                            ChannelRow(
                                channel = channel,
                                favorite = favoriteKey in favoriteIds,
                                onFavorite = {
                                    val updated = if (favoriteKey in favoriteIds) favoriteIds - favoriteKey else favoriteIds + favoriteKey
                                    favoriteIds = updated
                                    favoritesStore.edit().putStringSet("ids", updated).apply()
                                },
                                onClick = {
                                    val updatedRecent = (listOf(favoriteKey) + recentIds.filterNot { it == favoriteKey }).take(20)
                                    recentIds = updatedRecent
                                    favoritesStore.edit().putString("recent_ids", updatedRecent.joinToString("\u001F")).apply()
                                    onMessage("${channel.name} selected — playback is next")
                                }
                            )
                        }
                    }
                }
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
