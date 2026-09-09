package com.fourkplus.tvplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.fourkplus.tvplayer.data.LoadedPlaylist
import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistItem
import com.fourkplus.tvplayer.data.SeriesDetailsInfo
import com.fourkplus.tvplayer.data.SeriesEpisode
import com.fourkplus.tvplayer.ui.theme.*

private enum class SeriesView { BROWSE, CATEGORY, DETAILS, PLAYER }

@Composable
internal fun SeriesScreen(
    playlist: LoadedPlaylist?,
    loadDetails: suspend (PlaylistItem) -> Result<SeriesDetailsInfo>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val parental = remember { context.getSharedPreferences("parental_settings", Context.MODE_PRIVATE) }
    var hiddenCategories by remember {
        mutableStateOf(parental.getStringSet("hidden_series_categories", emptySet()).orEmpty().toSet())
    }
    val seriesItems = remember(playlist, hiddenCategories) {
        playlist?.items?.filter { it.kind == MediaKind.SERIES && it.group !in hiddenCategories }.orEmpty()
    }
    val categories = remember(seriesItems) { seriesItems.map { it.group }.distinct() }
    val store = remember { context.getSharedPreferences("series_library", Context.MODE_PRIVATE) }
    var view by remember { mutableStateOf(SeriesView.BROWSE) }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var selectedSeries by remember { mutableStateOf<PlaylistItem?>(null) }
    var selectedEpisode by remember { mutableStateOf<SeriesEpisode?>(null) }
    var details by remember { mutableStateOf<SeriesDetailsInfo?>(null) }
    var detailsLoading by remember { mutableStateOf(false) }
    var detailsError by remember { mutableStateOf<String?>(null) }
    var selectedSeason by remember { mutableIntStateOf(1) }
    var search by remember { mutableStateOf("") }
    var favoriteIds by remember {
        mutableStateOf(store.getStringSet("favorites", emptySet()).orEmpty().toSet())
    }
    var recentIds by remember {
        mutableStateOf(store.getString("recent_v1", "").orEmpty().split('\u001F').filter(String::isNotBlank))
    }
    var continueSeriesIds by remember {
        mutableStateOf(store.getStringSet("continue_series", emptySet()).orEmpty().toSet())
    }
    var progress by remember {
        mutableStateOf(
            store.all.mapNotNull { (key, value) ->
                if (key.startsWith("episode_progress_") && value is Long && value > 0L) {
                    key.removePrefix("episode_progress_") to value
                } else null
            }.toMap()
        )
    }

    val byId = remember(seriesItems) { seriesItems.associateBy(::channelKey) }
    val favorites = remember(seriesItems, favoriteIds) { seriesItems.filter { channelKey(it) in favoriteIds } }
    val recent = remember(byId, recentIds) { recentIds.mapNotNull(byId::get) }
    val continueWatching = remember(seriesItems, continueSeriesIds) {
        seriesItems.filter { channelKey(it) in continueSeriesIds }
    }

    fun toggleFavorite(series: PlaylistItem) {
        val id = channelKey(series)
        val updated = if (id in favoriteIds) favoriteIds - id else favoriteIds + id
        favoriteIds = updated
        store.edit().putStringSet("favorites", updated).apply()
    }

    fun openDetails(series: PlaylistItem) {
        selectedSeries = series
        selectedEpisode = null
        details = null
        detailsError = null
        selectedSeason = store.getInt("last_season_${channelKey(series)}", 1)
        view = SeriesView.DETAILS
    }

    fun recordRecent(series: PlaylistItem) {
        val id = channelKey(series)
        val updated = (listOf(id) + recentIds.filterNot { it == id }).take(30)
        recentIds = updated
        store.edit().putString("recent_v1", updated.joinToString("\u001F")).apply()
    }

    fun saveEpisodeProgress(series: PlaylistItem, episode: SeriesEpisode, position: Long, duration: Long) {
        val normalized = if (duration > 0L && position >= duration - 20_000L) 0L else position.coerceAtLeast(0L)
        progress = if (normalized == 0L) progress - episode.id else progress + (episode.id to normalized)
        val updatedContinue = if (normalized > 0L) continueSeriesIds + channelKey(series) else {
            val otherEpisodeInProgress = details?.episodes.orEmpty().any { it.id != episode.id && (progress[it.id] ?: 0L) > 0L }
            if (otherEpisodeInProgress) continueSeriesIds else continueSeriesIds - channelKey(series)
        }
        continueSeriesIds = updatedContinue
        store.edit()
            .putLong("episode_progress_${episode.id}", normalized)
            .putStringSet("continue_series", updatedContinue)
            .putString("last_episode_${channelKey(series)}", episode.id)
            .apply()
    }

    fun goBack() {
        when (view) {
            SeriesView.BROWSE -> onBack()
            SeriesView.CATEGORY -> { search = ""; view = SeriesView.BROWSE }
            SeriesView.DETAILS -> view = SeriesView.BROWSE
            SeriesView.PLAYER -> view = SeriesView.DETAILS
        }
    }
    BackHandler(onBack = ::goBack)

    LaunchedEffect(selectedSeries, view) {
        val series = selectedSeries
        if (series != null && view == SeriesView.DETAILS && details == null && !detailsLoading) {
            detailsLoading = true
            loadDetails(series)
                .onSuccess { loaded ->
                    details = loaded
                    val available = loaded.seasons
                    if (selectedSeason !in available) selectedSeason = available.firstOrNull() ?: 1
                }
                .onFailure { detailsError = it.message }
            detailsLoading = false
        }
    }

    BoxWithConstraints(
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
        val landscape = maxWidth > maxHeight
        if (landscape && view in setOf(SeriesView.BROWSE, SeriesView.CATEGORY)) {
            LandscapeSeriesBrowser(
                seriesItems = seriesItems,
                categories = categories,
                selectedCategory = selectedCategory,
                search = search,
                favoriteIds = favoriteIds,
                recent = recent,
                favorites = favorites,
                continueWatching = continueWatching,
                onCategory = { selectedCategory = it; search = ""; view = SeriesView.CATEGORY },
                onSearch = { search = it },
                onFavorite = ::toggleFavorite,
                onSeries = ::openDetails,
                onHide = { category ->
                    val updated = hiddenCategories + category
                    hiddenCategories = updated
                    parental.edit().putStringSet("hidden_series_categories", updated).apply()
                    selectedCategory = categories.firstOrNull { it != category }.orEmpty()
                },
                onBack = {
                    if (view == SeriesView.CATEGORY) view = SeriesView.BROWSE else onBack()
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
                Text(
                    when (view) {
                        SeriesView.BROWSE -> "Series"
                        SeriesView.CATEGORY -> selectedCategory
                        else -> details?.originalTitle ?: selectedSeries?.name ?: "Series"
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = if (landscape) 23.sp else 22.sp,
                    lineHeight = if (landscape) 27.sp else 26.sp,
                    fontWeight = FontWeight.Black
                )
                if (view == SeriesView.CATEGORY && selectedCategory !in setOf("Continue watching", "Recently watched", "Favorites")) {
                    TextButton(onClick = {
                        hiddenCategories = hiddenCategories + selectedCategory
                        parental.edit().putStringSet("hidden_series_categories", hiddenCategories).apply()
                        search = ""
                        view = SeriesView.BROWSE
                    }) {
                        Icon(Icons.Default.VisibilityOff, null)
                        Spacer(Modifier.width(5.dp))
                        Text("Hide")
                    }
                }
                if (view == SeriesView.DETAILS && selectedSeries != null) {
                    IconButton(onClick = { toggleFavorite(selectedSeries!!) }) {
                        Icon(
                            if (channelKey(selectedSeries!!) in favoriteIds) Icons.Default.Star else Icons.Default.StarBorder,
                            "Favorite",
                            tint = if (channelKey(selectedSeries!!) in favoriteIds) Orange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            when (view) {
                SeriesView.BROWSE -> {
                    SeriesSearch(search, { search = it })
                    if (search.isNotBlank()) {
                        val results = seriesItems.filter { it.name.contains(search.trim(), true) }
                        SeriesGrid(results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape)
                    } else {
                        val sections = buildList {
                            if (continueWatching.isNotEmpty()) add("Continue watching" to continueWatching)
                            add("Recently watched" to recent)
                            add("Favorites" to favorites)
                            categories.forEach { category -> add(category to seriesItems.filter { it.group == category }) }
                        }
                        if (sections.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("No series were found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(sections) { (title, sectionItems) ->
                                    SeriesShelf(
                                        title = title,
                                        seriesItems = sectionItems,
                                        favoriteIds = favoriteIds,
                                        onSeeAll = { selectedCategory = title; view = SeriesView.CATEGORY },
                                        onHide = if (title in setOf("Continue watching", "Recently watched", "Favorites")) null else {{
                                            val updated = hiddenCategories + title
                                            hiddenCategories = updated
                                            parental.edit().putStringSet("hidden_series_categories", updated).apply()
                                        }},
                                        onFavorite = ::toggleFavorite,
                                        onSeries = ::openDetails
                                    )
                                }
                            }
                        }
                    }
                }

                SeriesView.CATEGORY -> {
                    SeriesSearch(search, { search = it })
                    val base = when (selectedCategory) {
                        "Continue watching" -> continueWatching
                        "Recently watched" -> recent
                        "Favorites" -> favorites
                        else -> seriesItems.filter { it.group == selectedCategory }
                    }
                    val results = if (search.isBlank()) base else seriesItems.filter { it.name.contains(search.trim(), true) }
                    SeriesGrid(results, favoriteIds, ::toggleFavorite, ::openDetails, Modifier.weight(1f), landscape)
                }

                SeriesView.DETAILS -> selectedSeries?.let { series ->
                    SeriesDetails(
                        series = series,
                        details = details,
                        loading = detailsLoading,
                        error = detailsError,
                        selectedSeason = selectedSeason,
                        onSeason = {
                            selectedSeason = it
                            store.edit().putInt("last_season_${channelKey(series)}", it).apply()
                        },
                        favorite = channelKey(series) in favoriteIds,
                        progress = progress,
                        onFavorite = { toggleFavorite(series) },
                        onEpisode = { episode ->
                            selectedEpisode = episode
                            selectedSeason = episode.seasonNumber
                            recordRecent(series)
                            view = SeriesView.PLAYER
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                SeriesView.PLAYER -> {
                    val series = selectedSeries
                    val episode = selectedEpisode
                    if (series != null && episode != null) {
                        val episodeItem = PlaylistItem(
                            name = "${series.name} • S${episode.seasonNumber} E${episode.episodeNumber} • ${episode.title}",
                            streamUrl = episode.streamUrl,
                            group = series.name,
                            logoUrl = episode.thumbnailUrl ?: series.logoUrl,
                            channelId = episode.id,
                            kind = MediaKind.SERIES,
                            description = episode.description,
                            duration = episode.duration
                        )
                        MoviePlayer(
                            movie = episodeItem,
                            startPosition = progress[episode.id] ?: 0L,
                            onProgress = { position, duration ->
                                saveEpisodeProgress(series, episode, position, duration)
                            },
                            onExit = { view = SeriesView.DETAILS },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeSeriesBrowser(
    seriesItems: List<PlaylistItem>,
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
    onSeries: (PlaylistItem) -> Unit,
    onHide: (String) -> Unit,
    onBack: () -> Unit
) {
    val special = listOf("Continue watching", "Recently watched", "Favorites")
    val allCategories = special + categories
    val base = when (selectedCategory) {
        "Continue watching" -> continueWatching
        "Recently watched" -> recent
        "Favorites" -> favorites
        else -> seriesItems.filter { it.group == selectedCategory }
    }
    val displayed = if (search.isBlank()) base else seriesItems.filter { it.name.contains(search.trim(), true) }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            Modifier.width(250.dp).fillMaxHeight(),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = .34f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                    Text("Series", fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                SeriesSearch(search, onSearch)
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
            Text(selectedCategory.ifBlank { "Series" }, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            SeriesGrid(displayed, favoriteIds, onFavorite, onSeries, Modifier.weight(1f), true)
        }
    }
}

@Composable
private fun SeriesSearch(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(15.dp),
        placeholder = { Text("Search all series") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) {
                Icon(Icons.Default.Close, "Clear")
            }
        }
    )
}

@Composable
private fun SeriesShelf(
    title: String,
    seriesItems: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onSeeAll: () -> Unit,
    onHide: (() -> Unit)?,
    onFavorite: (PlaylistItem) -> Unit,
    onSeries: (PlaylistItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            onHide?.let {
                IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.VisibilityOff, "Hide $title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onSeeAll) {
                Text("See all", color = Cyan)
                Icon(Icons.Default.ChevronRight, null, tint = Cyan)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            if (seriesItems.isEmpty()) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape = RoundedCornerShape(13.dp)) {
                        Text(
                            if (title == "Favorites") "Series you star will appear here." else "Series you watch will appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                        )
                    }
                }
            } else {
                items(seriesItems.take(16)) { series ->
                    SeriesPoster(
                        series,
                        channelKey(series) in favoriteIds,
                        { onFavorite(series) },
                        { onSeries(series) },
                        Modifier.width(128.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesGrid(
    seriesItems: List<PlaylistItem>,
    favoriteIds: Set<String>,
    onFavorite: (PlaylistItem) -> Unit,
    onSeries: (PlaylistItem) -> Unit,
    modifier: Modifier,
    landscape: Boolean
) {
    if (seriesItems.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No series match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (landscape) 6 else 3),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            gridItems(seriesItems) { series ->
                SeriesPoster(
                    series,
                    channelKey(series) in favoriteIds,
                    { onFavorite(series) },
                    { onSeries(series) }
                )
            }
        }
    }
}

@Composable
private fun SeriesPoster(
    series: PlaylistItem,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Surface(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VideoLibrary, null, tint = BrandBlue.copy(alpha = .55f), modifier = Modifier.size(38.dp))
                if (!series.logoUrl.isNullOrBlank()) {
                    AsyncImage(series.logoUrl, series.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp)
                        .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        "Favorite",
                        tint = if (favorite) Orange else Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(series.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun SeriesDetails(
    series: PlaylistItem,
    details: SeriesDetailsInfo?,
    loading: Boolean,
    error: String?,
    selectedSeason: Int,
    onSeason: (Int) -> Unit,
    favorite: Boolean,
    progress: Map<String, Long>,
    onFavorite: () -> Unit,
    onEpisode: (SeriesEpisode) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val poster = details?.posterUrl ?: series.logoUrl
    val backdrop = details?.backdropUrl ?: poster
    val episodes = details?.episodes.orEmpty().filter { it.seasonNumber == selectedSeason }
    val seasons = details?.seasons.orEmpty()
    val displayTitle = details?.originalTitle ?: series.name
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
                    AsyncImage(backdrop, series.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f)))))
                Surface(
                    Modifier.align(Alignment.BottomStart).offset(x = 14.dp).width(104.dp).aspectRatio(2f / 3f),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 12.dp,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = .18f))
                ) {
                    if (!poster.isNullOrBlank()) {
                        AsyncImage(poster, series.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Text(
                    displayTitle,
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 3,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 132.dp, end = 14.dp, bottom = 16.dp)
                )
            }
        }

        if (!details?.originalTitle.isNullOrBlank() && details?.originalTitle != series.name) {
            Text(series.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            details?.rating?.takeIf { it != "0" && it != "0.0" }?.let { SeriesPill("★ $it/10", Orange) }
            details?.year?.takeIf(String::isNotBlank)?.let { SeriesPill(it, Cyan) }
            details?.genre?.takeIf(String::isNotBlank)?.let { SeriesPill(it, BrandBlue) }
            SeriesPill(series.group, Cyan)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    val query = listOfNotNull(details?.originalTitle ?: series.name, details?.year, "official trailer")
                        .joinToString(" ")
                    val uri = Uri.parse("https://www.youtube.com/results").buildUpon()
                        .appendQueryParameter("search_query", query).build()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                },
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Icon(Icons.Default.SmartDisplay, null)
                Spacer(Modifier.width(7.dp))
                Text("Trailer")
            }
            FilledTonalIconButton(onClick = onFavorite, modifier = Modifier.size(52.dp)) {
                Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, "Favorite", tint = if (favorite) Orange else Cyan)
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Loading seasons and episodes…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                details?.description?.takeIf(String::isNotBlank)
                    ?: "Detailed information was not supplied for this series.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
            details?.cast?.takeIf(String::isNotBlank)?.let { SeriesCredit(Icons.Default.Groups, "Cast", it) }
            details?.director?.takeIf(String::isNotBlank)?.let { SeriesCredit(Icons.Default.MovieCreation, "Director", it) }

            if (seasons.isNotEmpty()) {
                Text("Seasons", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    seasons.forEach { season ->
                        FilterChip(
                            selected = selectedSeason == season,
                            onClick = { onSeason(season) },
                            label = { Text("Season $season") }
                        )
                    }
                }
                Text("Episodes", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                episodes.forEach { episode ->
                    EpisodeRow(
                        episode = episode,
                        progress = progress[episode.id] ?: 0L,
                        onClick = { onEpisode(episode) }
                    )
                }
            } else if (error != null) {
                Text("Episodes are unavailable from this playlist.", color = MaterialTheme.colorScheme.error)
            } else {
                Text("No episodes were supplied for this series.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun EpisodeRow(episode: SeriesEpisode, progress: Long, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .95f))
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.width(112.dp).aspectRatio(16f / 9f),
                shape = RoundedCornerShape(10.dp),
                color = Color.Black
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayCircle, null, tint = Cyan, modifier = Modifier.size(30.dp))
                    if (!episode.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(episode.thumbnailUrl, episode.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "E${episode.episodeNumber} • ${episode.title}",
                    fontWeight = FontWeight.Bold,
                    maxLines = 3
                )
                val detail = buildList {
                    episode.duration?.takeIf(String::isNotBlank)?.let(::add)
                    if (progress > 0L) add("Resume ${seriesProgressTime(progress)}")
                }.joinToString(" • ")
                if (detail.isNotBlank()) {
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Icon(Icons.Default.PlayArrow, "Play", tint = Cyan)
        }
    }
}

@Composable
private fun SeriesPill(text: String, accent: Color) {
    Surface(
        color = accent.copy(alpha = .14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, accent.copy(alpha = .35f))
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
    }
}

@Composable
private fun SeriesCredit(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        }
    }
}

private fun seriesProgressTime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    return if (totalMinutes >= 60L) "${totalMinutes / 60L}h ${totalMinutes % 60L}m" else "${totalMinutes}m"
}
