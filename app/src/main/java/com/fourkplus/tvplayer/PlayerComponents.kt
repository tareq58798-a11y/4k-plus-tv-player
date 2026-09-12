package com.fourkplus.tvplayer

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.ui.theme.*
import kotlinx.coroutines.delay

/**
 * The single place an ExoPlayer instance gets built for this app. Both [MoviePlayer] and
 * [LiveChannelPreview] previously hand-rolled this identically; consolidated here so any future
 * change to how streams are requested (headers, redirects, etc.) only needs to happen once.
 */
internal fun buildFourKPlusExoPlayer(context: android.content.Context, skipSeconds: Int, muted: Boolean): ExoPlayer {
    val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
        .setAllowCrossProtocolRedirects(true)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
        .setSeekBackIncrementMs(skipSeconds * 1_000L)
        .setSeekForwardIncrementMs(skipSeconds * 1_000L)
        .build()
        .apply { volume = if (muted) 0f else 1f }
}

/** Horizontal strip of sibling items (other episodes of a series, other channels in a category) shown under the player, with the currently-playing one highlighted and every other one tappable to switch directly. */
@Composable
private fun RelatedItemsStrip(
    items: List<PlaylistItem>,
    currentKey: String,
    onSelect: (PlaylistItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.size <= 1) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        items(items, key = { channelKey(it) }) { related ->
            val active = channelKey(related) == currentKey
            Column(
                Modifier.width(88.dp).clickable(enabled = !active) { onSelect(related) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = .1f))
                        .then(if (active) Modifier.border(2.dp, Cyan, RoundedCornerShape(8.dp)) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (!related.logoUrl.isNullOrBlank()) {
                        AsyncImage(related.logoUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(alpha = .6f))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    related.name,
                    color = if (active) Cyan else Color.White,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun MoviePlayer(
    movie: PlaylistItem,
    startPosition: Long,
    onProgress: (Long, Long) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
    relatedItems: List<PlaylistItem> = emptyList(),
    onRelatedItemChange: (PlaylistItem) -> Unit = {}
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
    var controllerVisible by remember { mutableStateOf(true) }
    var relatedStripExpanded by remember { mutableStateOf(false) }
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var externalSubtitle by remember(movie) { mutableStateOf<Uri?>(null) }
    var skipSeconds by remember { mutableIntStateOf(settings.getInt("skip_seconds", 10).takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 10) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    val nextRelatedItem = remember(movie, relatedItems) {
        val currentIndex = relatedItems.indexOfFirst { channelKey(it) == channelKey(movie) }
        relatedItems.getOrNull(currentIndex + 1)
    }
    LaunchedEffect(seekFeedback?.second) {
        if (seekFeedback != null) {
            delay(650)
            seekFeedback = null
        }
    }
    val player = remember(movie.streamUrl, skipSeconds) {
        buildFourKPlusExoPlayer(context, skipSeconds, muted = settings.getBoolean("muted", false))
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
            override fun onPlayerError(playbackException: PlaybackException) { error = playbackFailureMessage(playbackException) }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    nextRelatedItem?.let(onRelatedItemChange)
                }
            }
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
                        setShowPreviousButton(false)
                        setShowNextButton(false)
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                controllerVisible = visibility == android.view.View.VISIBLE
                            }
                        )
                        resizeMode = videoResizeMode
                        applyRequestedAspectRatio(this, videoMode)
                        this.player = player
                        installDoubleTapSeek(
                            this, player, skipSeconds,
                            onSwipeUp = { relatedStripExpanded = true },
                            onSwipeDown = { relatedStripExpanded = false }
                        ) { forward ->
                            seekFeedback = forward to System.nanoTime()
                        }
                    }
                },
                update = {
                    it.player = player
                    it.resizeMode = videoResizeMode
                    applyRequestedAspectRatio(it, videoMode)
                    installDoubleTapSeek(
                        it, player, skipSeconds,
                        onSwipeUp = { relatedStripExpanded = true },
                        onSwipeDown = { relatedStripExpanded = false }
                    ) { forward ->
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
            if (controllerVisible && !relatedStripExpanded && relatedItems.size > 1) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    "Swipe up for other episodes",
                    tint = Color.White.copy(alpha = .6f),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp).size(22.dp)
                )
            }
            if (relatedStripExpanded && relatedItems.size > 1) {
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp)) {
                    Text(
                        "Other episodes",
                        color = Color.White.copy(alpha = .75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                    )
                    RelatedItemsStrip(
                        items = relatedItems,
                        currentKey = channelKey(movie),
                        onSelect = onRelatedItemChange
                    )
                }
            }
        }
    }
    }
    if (fullscreen) {
        Dialog(
            onDismissRequest = onExit,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val portrait = maxHeight > maxWidth
                if (!portrait) AllowDrawingUnderCutout()
                playerContent(
                    Modifier.fillMaxSize().then(
                        if (portrait) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier
                    ),
                    RectangleShape
                )
            }
        }
    } else {
        playerContent(modifier.fillMaxWidth(), RoundedCornerShape(18.dp))
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

/** Compose Dialogs open their own Window, which doesn't inherit the Activity's
 *  cutout mode, leaving a black bar next to the notch in landscape. */
@Composable
private fun AllowDrawingUnderCutout() {
    val view = LocalView.current
    SideEffect {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            dialogWindow.attributes = dialogWindow.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
    onSingleTap: (() -> Unit)? = null,
    onSwipeUp: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    onSeekFeedback: (Boolean) -> Unit
) {
    val detector = android.view.GestureDetector(
        view.context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(event: android.view.MotionEvent): Boolean {
                onSingleTap?.invoke()
                return onSingleTap != null
            }

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
    var dragStartX = 0f
    var dragStartY = 0f
    var dragHandled = false
    view.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartY = event.y
                dragHandled = false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragHandled && (onSwipeUp != null || onSwipeDown != null)) {
                    val deltaY = event.y - dragStartY
                    val deltaX = event.x - dragStartX
                    if (kotlin.math.abs(deltaY) > 60 && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)) {
                        if (deltaY < 0) onSwipeUp?.invoke() else onSwipeDown?.invoke()
                        dragHandled = true
                    }
                }
            }
        }
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

/**
 * @param autoAdvanceOnFailure When true, a playback failure on the currently auto-selected
 * channel silently advances to the next entry in [channelList] (bounded, see `autoAdvanceAttempts`
 * below) instead of showing the error card. Only meant for previews still on their auto-selected
 * first channel — call sites where the user deliberately picked a channel should leave this false
 * so a failure there surfaces the normal per-channel error banner.
 */
@Composable
internal fun LiveChannelPreview(
    channel: PlaylistItem?,
    modifier: Modifier = Modifier,
    externalPlayback: Boolean = false,
    channelList: List<PlaylistItem> = emptyList(),
    onChannelChange: (PlaylistItem) -> Unit = {},
    autoAdvanceOnFailure: Boolean = false,
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
    var controllerShownAt by remember { mutableLongStateOf(System.nanoTime()) }
    var stripExpanded by remember { mutableStateOf(false) }
    fun showControllerBriefly() {
        controllerVisible = true
        controllerShownAt = System.nanoTime()
    }
    var subtitlesEnabled by remember { mutableStateOf(settings.getBoolean("subtitles_enabled", true)) }
    var externalSubtitle by remember(channel?.streamUrl) { mutableStateOf<Uri?>(null) }
    var skipSeconds by remember { mutableIntStateOf(settings.getInt("skip_seconds", 10).takeIf { it in listOf(5, 10, 15, 30, 60) } ?: 10) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Long>?>(null) }
    // Persists for this composable's lifetime (not reset per channel) so a chain of consecutive
    // auto-advances is bounded overall, not just per hop.
    var autoAdvanceAttempts by remember { mutableIntStateOf(0) }
    LaunchedEffect(seekFeedback?.second) {
        if (seekFeedback != null) {
            delay(650)
            seekFeedback = null
        }
    }
    LaunchedEffect(controllerShownAt, controllerVisible) {
        if (controllerVisible) {
            delay(4_000)
            controllerVisible = false
        }
    }
    val player = remember(skipSeconds) {
        buildFourKPlusExoPlayer(context, skipSeconds, muted = settings.getBoolean("muted", false)).apply { playWhenReady = true }
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
                val advanceTo = if (autoAdvanceOnFailure && channel != null && autoAdvanceAttempts < 3) {
                    val currentIndex = channelList.indexOfFirst { channelKey(it) == channelKey(channel) }
                    channelList.getOrNull(currentIndex + 1)
                } else null
                if (advanceTo != null) {
                    autoAdvanceAttempts++
                    onChannelChange(advanceTo)
                } else {
                    playbackError = playbackFailureMessage(error)
                }
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
                            useController = false
                            resizeMode = videoResizeMode
                            this.player = player
                        }
                    },
                    update = {
                        it.player = player
                        it.resizeMode = videoResizeMode
                        applyRequestedAspectRatio(it, videoMode)
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(channel.streamUrl, skipSeconds, fullscreenDoubleTapExit) {
                            detectTapGestures(
                                onTap = {
                                    if (controllerVisible) controllerVisible = false else showControllerBriefly()
                                },
                                onDoubleTap = { offset ->
                                    if (fullscreenDoubleTapExit != null) {
                                        fullscreenDoubleTapExit()
                                    } else if (player.isCurrentMediaItemSeekable) {
                                        val intervalMs = skipSeconds * 1_000L
                                        val forward = offset.x >= size.width / 2f
                                        val destination = if (forward) {
                                            val target = player.currentPosition + intervalMs
                                            if (player.duration > 0L) target.coerceAtMost(player.duration) else target
                                        } else {
                                            (player.currentPosition - intervalMs).coerceAtLeast(0L)
                                        }
                                        player.seekTo(destination)
                                        seekFeedback = forward to System.nanoTime()
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            var accumulated = 0f
                            detectVerticalDragGestures(
                                onDragStart = { accumulated = 0f },
                                onVerticalDrag = { change, dragAmount ->
                                    accumulated += dragAmount
                                    if (accumulated < -60) {
                                        stripExpanded = true
                                        change.consume()
                                    } else if (accumulated > 60) {
                                        stripExpanded = false
                                        change.consume()
                                    }
                                }
                            )
                        }
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
                if (controllerVisible && !stripExpanded && channelList.size > 1) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        "Swipe up for other channels",
                        tint = Color.White.copy(alpha = .6f),
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .then(if (fullscreen || hostedFullscreen) Modifier.navigationBarsPadding() else Modifier)
                            .padding(bottom = 8.dp)
                            .size(22.dp)
                    )
                }
                if (stripExpanded && channelList.size > 1) {
                    Column(
                        Modifier.align(Alignment.BottomCenter)
                            .then(if (fullscreen || hostedFullscreen) Modifier.navigationBarsPadding() else Modifier)
                            .padding(bottom = 8.dp)
                    ) {
                        Text(
                            "More in this category",
                            color = Color.White.copy(alpha = .75f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp, bottom = 2.dp)
                        )
                        RelatedItemsStrip(
                            items = channelList,
                            currentKey = channel.let(::channelKey),
                            onSelect = onChannelChange
                        )
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
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
                val portrait = maxHeight > maxWidth
                if (!portrait) AllowDrawingUnderCutout()
                playerContent(
                    Modifier.fillMaxSize().then(
                        if (portrait) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier
                    ),
                    RectangleShape
                )
            }
        }
    } else {
        playerContent(modifier, RoundedCornerShape(18.dp))
    }
}

/** Only expose structured codes; exception messages may contain account URLs. */
private fun playbackFailureMessage(error: PlaybackException): String {
    var cause: Throwable? = error
    repeat(12) {
        val current = cause ?: return@repeat
        if (current is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            return "Playback failed: HTTP ${current.responseCode} (code ${error.errorCode})."
        }
        cause = current.cause
    }
    return "Playback failed: ${error.errorCodeName} (code ${error.errorCode})."
}
