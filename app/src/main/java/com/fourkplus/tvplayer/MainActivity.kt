package com.fourkplus.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fourkplus.tvplayer.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private enum class Screen { ACTIVATION, MANUAL, HOME }
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

    FourKPlusTheme(darkTheme = useDark) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.ACTIVATION -> ActivationScreen(
                    themeChoice = themeChoice,
                    onThemeChange = { themeChoice = it },
                    onManual = { screen = Screen.MANUAL },
                    onPreview = { screen = Screen.HOME }
                )
                Screen.MANUAL -> ManualPlaylistScreen(
                    onBack = { screen = Screen.ACTIVATION },
                    onConnected = { screen = Screen.HOME }
                )
                Screen.HOME -> HomeScreen(onManage = { screen = Screen.ACTIVATION })
            }
        }
    }
}

@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(
                Brush.linearGradient(listOf(BrandBlue, Cyan))
            ),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.PlayArrow, null, tint = Orange, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("4K", fontSize = 25.sp, fontWeight = FontWeight.Black)
            Text("PLUS TV", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun ActivationScreen(
    themeChoice: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    onManual: () -> Unit,
    onPreview: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.weight(1f))
                ThemeMenu(themeChoice, onThemeChange)
                IconButton(onClick = {}) { Icon(Icons.Default.Language, "Language") }
                IconButton(onClick = {}) { Icon(Icons.Default.SupportAgent, "Support") }
            }

            Text("Welcome", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Choose the easiest way to add your playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RemoteActivationCard(Modifier.weight(1.15f), onPreview)
                    ManualEntryCard(Modifier.weight(.85f), onManual)
                }
            } else {
                RemoteActivationCard(Modifier.fillMaxWidth(), onPreview)
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
private fun RemoteActivationCard(modifier: Modifier, onPreview: () -> Unit) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null, tint = Cyan)
                Spacer(Modifier.width(10.dp))
                Text("Activate through 4K Plus TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text("Send these codes to support. This screen will update automatically after activation.")
            DeviceCode("Device ID", "A4:7B:91:2C:8F:30")
            DeviceCode("Device Key", "K7P9-X2QM")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {}) { Icon(Icons.Default.Chat, null); Spacer(Modifier.width(7.dp)); Text("Contact Support") }
                OutlinedButton(onClick = onPreview) { Text("Check Activation") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Waiting for activation", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DeviceCode(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        IconButton(onClick = {}) { Icon(Icons.Default.ContentCopy, "Copy $label") }
    }
}

@Composable
private fun ManualEntryCard(modifier: Modifier, onManual: () -> Unit) {
    ElevatedCard(modifier.clickable(onClick = onManual)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.PlaylistAdd, null, tint = Orange, modifier = Modifier.size(34.dp))
            Text("Add Playlist Manually", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Use an M3U URL or your authorized provider login.")
            Button(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Add Playlist") }
        }
    }
}

@Composable
private fun ManualPlaylistScreen(onBack: () -> Unit, onConnected: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
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
            trailingIcon = { IconButton(onClick = {}) { Icon(Icons.Default.ContentPaste, "Paste") } },
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
        Button(
            onClick = onConnected,
            enabled = name.isNotBlank() && address.isNotBlank() && (tab == 0 || username.isNotBlank() && password.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Test and Add Playlist") }
    }
}

@Composable
private fun HomeScreen(onManage: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.weight(1f))
                IconButton(onClick = {}) { Icon(Icons.Default.Search, "Search") }
                IconButton(onClick = {}) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onClick = onManage) { Icon(Icons.Default.Settings, "Settings") }
            }
            Text("Good evening", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            ContinueCard()
            if (landscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeTile("Live TV", "Browse your channels", Icons.Default.LiveTv, Cyan, Modifier.weight(1f))
                    HomeTile("Movies", "Find something to watch", Icons.Default.Movie, Orange, Modifier.weight(1f))
                    HomeTile("Series", "Continue your episodes", Icons.Default.VideoLibrary, BrandBlue, Modifier.weight(1f))
                }
            } else {
                HomeTile("Live TV", "Browse your channels", Icons.Default.LiveTv, Cyan, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HomeTile("Movies", "Find something to watch", Icons.Default.Movie, Orange, Modifier.weight(1f))
                    HomeTile("Series", "Continue your episodes", Icons.Default.VideoLibrary, BrandBlue, Modifier.weight(1f))
                }
            }
            Text("Quick access", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistChip(onClick = {}, label = { Text("Favorites") }, leadingIcon = { Icon(Icons.Default.Star, null) })
                AssistChip(onClick = {}, label = { Text("Recently watched") }, leadingIcon = { Icon(Icons.Default.History, null) })
                AssistChip(onClick = onManage, label = { Text("Playlists") }, leadingIcon = { Icon(Icons.Default.PlaylistPlay, null) })
            }
        }
    }
}

@Composable
private fun ContinueCard() {
    Card(colors = CardDefaults.cardColors(containerColor = DeepBlue)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PlayCircle, null, tint = Orange, modifier = Modifier.size(46.dp))
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
private fun HomeTile(title: String, subtitle: String, icon: ImageVector, accent: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    ElevatedCard(modifier.heightIn(min = 145.dp).clickable { }) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(38.dp))
            Column {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
