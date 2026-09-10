package com.fourkplus.tvplayer

import android.content.Context
import java.security.MessageDigest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    playlist: LoadedPlaylist?,
    source: PlaylistInput?,
    themeChoice: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRename: (String) -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onMessage: (String) -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val playback = remember { context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE) }
    val parental = remember { context.getSharedPreferences("parental_settings", Context.MODE_PRIVATE) }
    var playlistName by remember(source?.name, playlist?.name) { mutableStateOf(source?.name ?: playlist?.name.orEmpty()) }
    var skipSeconds by remember { mutableIntStateOf(playback.getInt("skip_seconds", 10)) }
    var subtitlesEnabled by remember { mutableStateOf(playback.getBoolean("subtitles_enabled", true)) }
    var muted by remember { mutableStateOf(playback.getBoolean("muted", false)) }
    var preferredSubtitle by remember { mutableStateOf(playback.getString("subtitle_language", "ar,en") ?: "ar,en") }
    var videoMode by remember { mutableStateOf(playback.getString("video_mode", "fit") ?: "fit") }
    var playerEngine by remember { mutableStateOf(playback.getString("player_engine", "default") ?: "default") }
    var pinHash by remember { mutableStateOf(parental.getString("pin_hash", null)) }
    var parentalUnlocked by remember { mutableStateOf(pinHash == null) }
    var hiddenLive by remember {
        mutableStateOf(parental.getStringSet("hidden_live_categories", emptySet()).orEmpty().toSet())
    }
    var hiddenMovies by remember {
        mutableStateOf(parental.getStringSet("hidden_movie_categories", emptySet()).orEmpty().toSet())
    }
    var hiddenSeries by remember {
        mutableStateOf(parental.getStringSet("hidden_series_categories", emptySet()).orEmpty().toSet())
    }
    var visibilitySection by remember { mutableStateOf(MediaKind.LIVE) }
    var categorySearch by remember { mutableStateOf("") }
    var hiddenOnly by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf(if (pinHash == null) "set" else "unlock") }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val categoriesByKind = remember(playlist) {
        MediaKind.entries.associateWith { kind ->
            playlist?.items?.filter { it.kind == kind }?.map { it.group }?.distinct()?.sorted().orEmpty()
        }
    }

    if (showPinDialog) {
        PinDialog(
            mode = pinMode,
            expectedHash = pinHash,
            onDismiss = { showPinDialog = false },
            onSuccess = { newHash ->
                if (pinMode == "set") {
                    pinHash = newHash
                    parental.edit().putString("pin_hash", newHash).apply()
                    onMessage("Parental PIN created")
                }
                parentalUnlocked = true
                showPinDialog = false
            }
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text("Remove playlist?") },
            text = { Text("The saved provider login and cached playlist will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = { showRemoveConfirm = false; onRemove() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Settings", fontSize = 27.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 30.dp)
        ) {
            item {
                SettingsSection("Playlist", Icons.Default.PlaylistPlay) {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onRename(playlistName.trim()) },
                        enabled = playlistName.isNotBlank() && playlistName.trim() != source?.name,
                        modifier = Modifier.fillMaxWidth()
                    ) { Icon(Icons.Default.DriveFileRenameOutline, null); Spacer(Modifier.width(7.dp)); Text("Rename") }
                    SettingsAction(Icons.Default.Refresh, "Refresh playlist", "Download the newest channels and catalog", onRefresh)
                    SettingsAction(Icons.Default.SwapHoriz, "Replace playlist", "Connect a different URL or provider account", onReplace)
                    SettingsAction(
                        Icons.Default.DeleteForever,
                        "Remove playlist",
                        "Delete the saved login and local cache",
                        { showRemoveConfirm = true },
                        destructive = true
                    )
                    source?.let {
                        Text(
                            if (it.username.isNotBlank()) "Provider account: ${it.username}" else "M3U playlist",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                SettingsSection("Playback", Icons.Default.PlayCircle) {
                    Text("Skip interval", fontWeight = FontWeight.Bold)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        listOf(5, 10, 15, 30, 60).forEach { seconds ->
                            FilterChip(
                                selected = skipSeconds == seconds,
                                onClick = {
                                    skipSeconds = seconds
                                    playback.edit().putInt("skip_seconds", seconds).apply()
                                },
                                label = { Text("${seconds}s") }
                            )
                        }
                    }
                    SettingsSwitch(
                        title = "Start muted",
                        description = "Use mute when opening any player",
                        checked = muted,
                        onChecked = {
                            muted = it
                            playback.edit().putBoolean("muted", it).apply()
                        }
                    )
                    SettingsSwitch(
                        title = "Embedded subtitles",
                        description = "Automatically select available subtitle tracks",
                        checked = subtitlesEnabled,
                        onChecked = {
                            subtitlesEnabled = it
                            playback.edit().putBoolean("subtitles_enabled", it).apply()
                        }
                    )
                    Text("Preferred subtitle language", fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("ar,en" to "Arabic", "en,ar" to "English").forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = preferredSubtitle == option.first,
                                onClick = {
                                    preferredSubtitle = option.first
                                    playback.edit().putString("subtitle_language", option.first).apply()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)
                            ) { Text(option.second) }
                        }
                    }
                    Text("Video scaling", fontWeight = FontWeight.Bold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("fit" to "Fit", "zoom" to "Fill", "stretch" to "Stretch").forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = videoMode == option.first,
                                onClick = {
                                    videoMode = option.first
                                    playback.edit().putString("video_mode", option.first).apply()
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 3)
                            ) { Text(option.second) }
                        }
                    }
                    Text(
                        when (videoMode) {
                            "zoom" -> "Fills the screen and may crop the edges."
                            "stretch" -> "Stretches the picture to fill the entire player."
                            else -> "Shows the complete video and may add black bars."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Text("Player engine", fontWeight = FontWeight.Bold)
                    listOf(
                        "default" to ("4K Plus player" to "Full controls, resume, subtitles, and progress"),
                        "vlc" to ("VLC" to "Requires VLC to be installed"),
                        "mx" to ("MX Player" to "Requires MX Player to be installed")
                    ).forEach { option ->
                        RadioSetting(
                            title = option.second.first,
                            description = option.second.second,
                            selected = playerEngine == option.first,
                            onClick = {
                                playerEngine = option.first
                                playback.edit().putString("player_engine", option.first).apply()
                            }
                        )
                    }
                }
            }

            item {
                SettingsSection("Appearance", Icons.Default.Palette) {
                    Text("Theme", fontWeight = FontWeight.Bold)
                    ThemeChoice.entries.forEach { choice ->
                        RadioSetting(
                            title = choice.name.lowercase().replaceFirstChar(Char::uppercase),
                            selected = themeChoice == choice,
                            onClick = { onThemeChange(choice) }
                        )
                    }
                }
            }

            item {
                SettingsSection("Privacy and history", Icons.Default.History) {
                    SettingsAction(Icons.Default.Movie, "Clear movie activity", "Favorites, recent movies, and progress", {
                        context.getSharedPreferences("movie_library", Context.MODE_PRIVATE).edit().clear().apply()
                        onMessage("Movie activity cleared")
                    })
                    SettingsAction(Icons.Default.VideoLibrary, "Clear series activity", "Favorites, recent series, and episode progress", {
                        context.getSharedPreferences("series_library", Context.MODE_PRIVATE).edit().clear().apply()
                        onMessage("Series activity cleared")
                    })
                    SettingsAction(Icons.Default.LiveTv, "Clear Live TV activity", "Favorite and recently watched channels", {
                        context.getSharedPreferences("favorite_channels", Context.MODE_PRIVATE).edit().clear().apply()
                        onMessage("Live TV activity cleared")
                    })
                }
            }

            item {
                SettingsSection("Hide categories", Icons.Default.VisibilityOff) {
                    Text(
                        "Choose which categories appear on the main screens. You can restore anything hidden here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            MediaKind.LIVE to "Live TV",
                            MediaKind.MOVIE to "Movies",
                            MediaKind.SERIES to "Series"
                        ).forEach { (kind, label) ->
                            FilterChip(
                                selected = visibilitySection == kind,
                                onClick = { visibilitySection = kind; categorySearch = "" },
                                label = { Text(label) },
                                leadingIcon = {
                                    Icon(
                                        when (kind) {
                                            MediaKind.LIVE -> Icons.Default.LiveTv
                                            MediaKind.MOVIE -> Icons.Default.Movie
                                            MediaKind.SERIES -> Icons.Default.VideoLibrary
                                        },
                                        null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = categorySearch,
                        onValueChange = { categorySearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search categories") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (categorySearch.isNotEmpty()) {
                                IconButton(onClick = { categorySearch = "" }) {
                                    Icon(Icons.Default.Close, "Clear")
                                }
                            }
                        }
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Show hidden only", Modifier.weight(1f))
                        Switch(checked = hiddenOnly, onCheckedChange = { hiddenOnly = it })
                    }
                    val activeHidden = when (visibilitySection) {
                        MediaKind.LIVE -> hiddenLive
                        MediaKind.MOVIE -> hiddenMovies
                        MediaKind.SERIES -> hiddenSeries
                    }
                    val activeKey = when (visibilitySection) {
                        MediaKind.LIVE -> "hidden_live_categories"
                        MediaKind.MOVIE -> "hidden_movie_categories"
                        MediaKind.SERIES -> "hidden_series_categories"
                    }
                    val displayedCategories = categoriesByKind[visibilitySection].orEmpty().filter { category ->
                        (!hiddenOnly || category in activeHidden) &&
                            (categorySearch.isBlank() || category.contains(categorySearch.trim(), ignoreCase = true))
                    }
                    if (displayedCategories.isEmpty()) {
                        Text(
                            if (hiddenOnly) "No hidden categories." else "No categories match your search.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                    displayedCategories.forEach { category ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (category in activeHidden) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f)
                            } else {
                                Cyan.copy(alpha = .11f)
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(category, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (category in activeHidden) "Hidden" else "Visible",
                                        color = if (category in activeHidden) MaterialTheme.colorScheme.onSurfaceVariant else Cyan,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Switch(
                                    checked = category !in activeHidden,
                                    onCheckedChange = { visible ->
                                        val updated = if (visible) activeHidden - category else activeHidden + category
                                        when (visibilitySection) {
                                            MediaKind.LIVE -> hiddenLive = updated
                                            MediaKind.MOVIE -> hiddenMovies = updated
                                            MediaKind.SERIES -> hiddenSeries = updated
                                        }
                                        parental.edit().putStringSet(activeKey, updated).apply()
                                    }
                                )
                            }
                        }
                    }
                    if (activeHidden.isNotEmpty()) {
                        Button(
                            onClick = {
                                when (visibilitySection) {
                                    MediaKind.LIVE -> hiddenLive = emptySet()
                                    MediaKind.MOVIE -> hiddenMovies = emptySet()
                                    MediaKind.SERIES -> hiddenSeries = emptySet()
                                }
                                parental.edit().remove(activeKey).apply()
                                onMessage("All categories are visible again")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Visibility, null)
                            Spacer(Modifier.width(7.dp))
                            Text("Show all hidden categories")
                        }
                    }
                }
            }

            item {
                SettingsSection("Parental controls", Icons.Default.AdminPanelSettings) {
                    if (pinHash == null) {
                        SettingsAction(Icons.Default.Pin, "Create parental PIN", "Protect restricted content settings", {
                            pinMode = "set"
                            showPinDialog = true
                        })
                    } else if (!parentalUnlocked) {
                        SettingsAction(Icons.Default.LockOpen, "Unlock parental controls", "Enter your PIN to manage parental controls", {
                            pinMode = "unlock"
                            showPinDialog = true
                        })
                    } else {
                        Text("Parental controls are unlocked.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = { parentalUnlocked = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, null)
                            Spacer(Modifier.width(7.dp))
                            Text("Lock controls")
                        }
                        TextButton(
                            onClick = {
                                parental.edit().remove("pin_hash").apply()
                                pinHash = null
                                parentalUnlocked = true
                                onMessage("Parental PIN removed")
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Remove PIN", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            item {
                Text(
                    "4K Plus TV Player • v0.11.1",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f))
    ) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = Cyan)
                Spacer(Modifier.width(9.dp))
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun SettingsAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (destructive) MaterialTheme.colorScheme.error else Cyan)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun RadioSetting(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(title)
                description?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PinDialog(
    mode: String,
    expectedHash: String?,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Pin, null) },
        title = { Text(if (mode == "set") "Create parental PIN" else "Enter parental PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                    label = { Text("4–6 digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (mode == "set") {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmation = it },
                        label = { Text("Confirm PIN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length !in 4..6 -> error = "Use a 4–6 digit PIN."
                    mode == "set" && pin != confirmation -> error = "PINs do not match."
                    mode != "set" && pinSha256(pin) != expectedHash -> error = "Incorrect PIN."
                    else -> onSuccess(pinSha256(pin))
                }
            }) { Text(if (mode == "set") "Create" else "Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun pinSha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
