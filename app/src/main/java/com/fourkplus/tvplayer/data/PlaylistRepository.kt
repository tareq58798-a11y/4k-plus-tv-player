package com.fourkplus.tvplayer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PlaylistRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cacheFile = appContext.filesDir.resolve("playlist_cache_v1.bin.gz")
    private val preferences = EncryptedSharedPreferences.create(
        context, "playlist_source",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    suspend fun load(input: PlaylistInput): Result<LoadedPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val playlist = when (input.kind) {
                PlaylistKind.M3U_URL -> loadM3u(input)
                PlaylistKind.PROVIDER_LOGIN -> loadProvider(input)
            }
            saveSource(input)
            saveCache(input, playlist)
            playlist
        }.recoverCatching { throw friendlyError(it) }
    }

    fun savedSources(): List<PlaylistInput> {
        val stored = preferences.getString("sources_json", null)
        if (!stored.isNullOrBlank()) {
            return runCatching {
                val array = JSONArray(stored)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val kind = PlaylistKind.valueOf(item.getString("kind"))
                        add(
                            PlaylistInput(
                                name = item.getString("name"),
                                kind = kind,
                                address = item.getString("address"),
                                username = item.optString("username"),
                                password = item.optString("password")
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        val legacy = legacySavedSource() ?: return emptyList()
        writeSources(listOf(legacy), sourceId(legacy))
        return listOf(legacy)
    }

    fun savedSource(): PlaylistInput? {
        val sources = savedSources()
        if (sources.isEmpty()) return null
        val activeId = preferences.getString("active_source_id", null)
        return sources.firstOrNull { sourceId(it) == activeId } ?: sources.first()
    }

    fun selectSavedSource(input: PlaylistInput) {
        require(savedSources().any { sourceId(it) == sourceId(input) }) { "Playlist is no longer saved." }
        writeActiveSource(input)
    }

    fun renameSavedSource(name: String) {
        val cleaned = name.trim()
        require(cleaned.isNotBlank()) { "Playlist name cannot be empty." }
        val active = savedSource() ?: throw IllegalStateException("No saved playlist.")
        val renamed = active.copy(name = cleaned)
        val updated = savedSources().map { if (sourceId(it) == sourceId(active)) renamed else it }
        writeSources(updated, sourceId(renamed))
        writeActiveSource(renamed)
    }

    fun clearSavedSource() {
        val active = savedSource()
        val remaining = if (active == null) emptyList() else savedSources().filterNot { sourceId(it) == sourceId(active) }
        preferences.edit().remove("name").remove("kind").remove("address").remove("username").remove("password").apply()
        if (remaining.isEmpty()) {
            preferences.edit().remove("sources_json").remove("active_source_id").apply()
        } else {
            writeSources(remaining, sourceId(remaining.first()))
            writeActiveSource(remaining.first())
        }
        active?.let { sourceCacheFile(it).delete() }
        if (cacheFile.exists()) cacheFile.delete()
    }

    private fun legacySavedSource(): PlaylistInput? {
        val name = preferences.getString("name", null) ?: return null
        val kind = preferences.getString("kind", null)?.let {
            runCatching { PlaylistKind.valueOf(it) }.getOrNull()
        } ?: return null
        val address = preferences.getString("address", null) ?: return null
        return PlaylistInput(
            name = name,
            kind = kind,
            address = address,
            username = preferences.getString("username", "").orEmpty(),
            password = preferences.getString("password", "").orEmpty()
        )
    }

    private fun sourceId(input: PlaylistInput): String =
        "${input.kind.name}|${input.address.trim()}|${input.username.trim()}"

    private fun sourceCacheFile(input: PlaylistInput) = appContext.filesDir.resolve(
        "playlist_cache_" + MessageDigest.getInstance("SHA-256")
            .digest(sourceId(input).toByteArray(StandardCharsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) } + ".bin.gz"
    )

    private fun writeSources(sources: List<PlaylistInput>, activeId: String) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(JSONObject().apply {
                put("name", source.name)
                put("kind", source.kind.name)
                put("address", source.address)
                put("username", source.username)
                put("password", source.password)
            })
        }
        preferences.edit().putString("sources_json", array.toString())
            .putString("active_source_id", activeId).apply()
    }

    private fun writeActiveSource(input: PlaylistInput) {
        preferences.edit().putString("active_source_id", sourceId(input))
            .putString("name", input.name.trim()).putString("kind", input.kind.name)
            .putString("address", input.address.trim()).putString("username", input.username)
            .putString("password", input.password).apply()
    }

    suspend fun loadCached(source: PlaylistInput? = savedSource()): LoadedPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val selectedSource = source ?: return@runCatching null
            val specificCache = sourceCacheFile(selectedSource)
            val selectedFile = when {
                specificCache.exists() -> specificCache
                cacheFile.exists() -> cacheFile
                else -> return@runCatching null
            }
            val loaded = DataInputStream(GZIPInputStream(selectedFile.inputStream().buffered())).use { input ->
                require(input.readInt() == CACHE_VERSION) { "Unsupported playlist cache." }
                val name = input.readSizedString()
                val accountStatus = input.readNullableString()
                val expiryEpochSeconds = input.readLong().takeIf { it > 0L }
                val itemCount = input.readInt()
                require(itemCount in 0..500_000) { "Invalid playlist cache." }
                val items = ArrayList<PlaylistItem>(itemCount)
                repeat(itemCount) {
                    items += PlaylistItem(
                        name = input.readSizedString(),
                        streamUrl = input.readSizedString(),
                        group = input.readSizedString(),
                        logoUrl = input.readNullableString(),
                        channelId = input.readNullableString(),
                        kind = MediaKind.valueOf(input.readSizedString()),
                        description = input.readNullableString(),
                        year = input.readNullableString(),
                        rating = input.readNullableString(),
                        duration = input.readNullableString()
                    )
                }
                LoadedPlaylist(
                    name = name,
                    items = items,
                    groups = items.map { it.group }.distinct(),
                    accountStatus = accountStatus,
                    expiryEpochSeconds = expiryEpochSeconds
                )
            }
            if (selectedFile == cacheFile && loaded.name != selectedSource.name) return@runCatching null
            if (selectedFile == cacheFile) saveCache(selectedSource, loaded)
            loaded
        }.getOrNull()
    }

    suspend fun movieDetails(movie: PlaylistItem): Result<MovieDetailsInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val source = savedSource() ?: throw IllegalArgumentException("No saved provider is available.")
            val movieId = movie.channelId
            require(source.kind == PlaylistKind.PROVIDER_LOGIN && !movieId.isNullOrBlank()) {
                "Detailed information is not available for this playlist."
            }
            var lastError: Exception? = null
            for (server in addressCandidates(source.address).map(::normalizeServerBase)) {
                try {
                    val root = JSONObject(download(apiUrl(server, source, "get_vod_info") + "&vod_id=${encode(movieId)}"))
                    val info = root.optJSONObject("info") ?: root
                    val movieData = root.optJSONObject("movie_data")
                    val backdrop = info.optJSONArray("backdrop_path")?.let { array ->
                        (0 until array.length()).asSequence().map { array.optString(it) }.firstOrNull(String::isNotBlank)
                    } ?: info.optString("backdrop_path").takeIf { it.startsWith("http", true) }
                    val trailer = info.optString("youtube_trailer").takeIf(String::isNotBlank)?.let { value ->
                        if (value.startsWith("http", true)) value else "https://www.youtube.com/watch?v=$value"
                    }
                    val originalTitle = (
                        firstText(info, "o_name", "original_name", "original_title", "title", "name")
                            ?: movieData?.let { firstText(it, "o_name", "original_name", "original_title", "name") }
                        )?.takeIf(::containsLatinText)
                    return@runCatching MovieDetailsInfo(
                        originalTitle = originalTitle,
                        description = firstText(info, "plot", "description"),
                        year = firstText(info, "year", "releasedate", "releaseDate")?.take(4),
                        rating = firstText(info, "rating")?.takeUnless { it == "0" || it == "0.0" },
                        duration = firstText(info, "duration", "duration_secs"),
                        genre = firstText(info, "genre"),
                        cast = firstText(info, "cast", "actors"),
                        director = firstText(info, "director"),
                        backdropUrl = backdrop,
                        posterUrl = firstText(info, "movie_image", "cover_big", "cover")
                            ?: movieData?.optString("stream_icon")?.takeIf(String::isNotBlank),
                        trailerUrl = trailer
                    )
                } catch (error: Exception) { lastError = error }
            }
            throw lastError ?: IllegalArgumentException("Movie information could not be loaded.")
        }.recoverCatching { throw friendlyError(it) }
    }

    suspend fun seriesDetails(series: PlaylistItem): Result<SeriesDetailsInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val source = savedSource() ?: throw IllegalArgumentException("No saved provider is available.")
            val seriesId = series.channelId
            require(source.kind == PlaylistKind.PROVIDER_LOGIN && !seriesId.isNullOrBlank()) {
                "Series information is not available for this playlist."
            }
            var lastError: Exception? = null
            for (server in addressCandidates(source.address).map(::normalizeServerBase)) {
                try {
                    val root = JSONObject(download(apiUrl(server, source, "get_series_info") + "&series_id=${encode(seriesId)}"))
                    val info = root.optJSONObject("info") ?: JSONObject()
                    val backdrop = info.optJSONArray("backdrop_path")?.let { array ->
                        (0 until array.length()).asSequence().map { array.optString(it) }
                            .firstOrNull(String::isNotBlank)
                    } ?: info.optString("backdrop_path").takeIf { it.startsWith("http", true) }
                    val episodesObject = root.optJSONObject("episodes") ?: JSONObject()
                    val episodes = buildList {
                        val seasonKeys = episodesObject.keys()
                        while (seasonKeys.hasNext()) {
                            val seasonKey = seasonKeys.next()
                            val seasonNumber = seasonKey.toIntOrNull() ?: continue
                            val seasonEpisodes = episodesObject.optJSONArray(seasonKey) ?: continue
                            for (index in 0 until seasonEpisodes.length()) {
                                val episode = seasonEpisodes.optJSONObject(index) ?: continue
                                val id = episode.optString("id")
                                if (id.isBlank()) continue
                                val episodeInfo = episode.optJSONObject("info") ?: JSONObject()
                                val extension = episode.optString("container_extension", "mp4").ifBlank { "mp4" }
                                val episodeNumber = episode.optInt("episode_num", index + 1)
                                add(
                                    SeriesEpisode(
                                        id = id,
                                        seasonNumber = seasonNumber,
                                        episodeNumber = episodeNumber,
                                        title = firstText(episode, "title", "name")
                                            ?: "Episode $episodeNumber",
                                        streamUrl = "$server/series/${encode(source.username)}/${encode(source.password)}/$id.$extension",
                                        thumbnailUrl = firstText(episodeInfo, "movie_image", "cover_big", "cover"),
                                        duration = firstText(episodeInfo, "duration", "duration_secs"),
                                        description = firstText(episodeInfo, "plot", "description")
                                    )
                                )
                            }
                        }
                    }.sortedWith(compareBy<SeriesEpisode> { it.seasonNumber }.thenBy { it.episodeNumber })
                    return@runCatching SeriesDetailsInfo(
                        originalTitle = firstText(info, "o_name", "original_name", "original_title", "name")
                            ?.takeIf(::containsLatinText),
                        description = firstText(info, "plot", "description"),
                        year = firstText(info, "year", "releaseDate", "releasedate")?.take(4),
                        rating = firstText(info, "rating")?.takeUnless { it == "0" || it == "0.0" },
                        genre = firstText(info, "genre"),
                        cast = firstText(info, "cast", "actors"),
                        director = firstText(info, "director"),
                        backdropUrl = backdrop,
                        posterUrl = firstText(info, "cover_big", "cover") ?: series.logoUrl,
                        episodes = episodes
                    )
                } catch (error: Exception) {
                    lastError = error
                }
            }
            throw lastError ?: IllegalArgumentException("Series information could not be loaded.")
        }.recoverCatching { throw friendlyError(it) }
    }

    private fun loadM3u(input: PlaylistInput): LoadedPlaylist {
        var lastError: Exception? = null
        for (address in addressCandidates(input.address)) {
            try { return M3uParser.parse(input.name, download(address)) }
            catch (error: Exception) { lastError = error }
        }
        throw lastError ?: IllegalArgumentException("The playlist address could not be reached.")
    }

    private fun loadProvider(input: PlaylistInput): LoadedPlaylist {
        var lastError: Exception? = null
        for (server in addressCandidates(input.address).map(::normalizeServerBase)) {
            try { return loadProviderFromServer(input, server) }
            catch (error: Exception) { lastError = error }
        }
        throw lastError ?: IllegalArgumentException("The provider could not be reached.")
    }

    private fun loadProviderFromServer(input: PlaylistInput, server: String): LoadedPlaylist {
        val auth = JSONObject(download(apiUrl(server, input, null)).trimStart('\uFEFF'))
        val userInfo = auth.optJSONObject("user_info")
            ?: throw IllegalArgumentException("This server did not return a compatible provider login response.")
        val authenticated = userInfo.optInt("auth", 0) == 1
        val status = userInfo.optString("status", "")
        require(authenticated && !status.equals("Disabled", true) && !status.equals("Expired", true)) {
            "The provider rejected this username or password, or the account is inactive."
        }

        val liveCategories = runCatching { categories(apiUrl(server, input, "get_live_categories")) }.getOrDefault(emptyMap())
        val movieCategories = runCatching { categories(apiUrl(server, input, "get_vod_categories")) }.getOrDefault(emptyMap())
        val seriesCategories = runCatching { categories(apiUrl(server, input, "get_series_categories")) }.getOrDefault(emptyMap())
        val items = buildList {
            addAll(liveItems(JSONArray(download(apiUrl(server, input, "get_live_streams"))), liveCategories, server, input))
            addAll(movieItems(JSONArray(download(apiUrl(server, input, "get_vod_streams"))), movieCategories, server, input))
            addAll(seriesItems(JSONArray(download(apiUrl(server, input, "get_series"))), seriesCategories))
        }
        require(items.isNotEmpty()) { "The account connected successfully but contains no available content." }
        val expiry = userInfo.optString("exp_date").toLongOrNull()?.takeIf { it > 0L }
            ?: userInfo.optLong("exp_date", 0L).takeIf { it > 0L }
        return LoadedPlaylist(
            name = input.name.trim(),
            items = items,
            groups = items.map { it.group }.distinct(),
            accountStatus = status.takeIf(String::isNotBlank),
            expiryEpochSeconds = expiry
        )
    }

    private fun categories(url: String): Map<String, String> {
        val array = JSONArray(download(url))
        return buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                put(item.optString("category_id"), item.optString("category_name", "Other"))
            }
        }
    }

    private fun liveItems(array: JSONArray, groups: Map<String, String>, server: String, input: PlaylistInput) = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("stream_id")
            if (id.isBlank()) continue
            add(PlaylistItem(
                item.optString("name", "Unnamed channel"),
                "$server/live/${encode(input.username)}/${encode(input.password)}/$id.ts",
                groups[item.optString("category_id")] ?: "Other",
                item.optString("stream_icon").takeIf(String::isNotBlank),
                // stream_id is the provider's unique channel identity. EPG IDs
                // may be blank or shared by several streams and must not be
                // used for favorites or viewing history.
                id,
                MediaKind.LIVE
            ))
        }
    }

    private fun movieItems(array: JSONArray, groups: Map<String, String>, server: String, input: PlaylistInput) = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("stream_id")
            if (id.isBlank()) continue
            val extension = item.optString("container_extension", "mp4").ifBlank { "mp4" }
            add(PlaylistItem(
                item.optString("name", "Unnamed movie"),
                "$server/movie/${encode(input.username)}/${encode(input.password)}/$id.$extension",
                groups[item.optString("category_id")] ?: "Other",
                item.optString("stream_icon").takeIf(String::isNotBlank), id, MediaKind.MOVIE,
                description = item.optString("plot").takeIf(String::isNotBlank),
                year = item.optString("year").takeIf(String::isNotBlank)
                    ?: item.optString("releaseDate").take(4).takeIf(String::isNotBlank),
                rating = item.optString("rating").takeIf(String::isNotBlank),
                duration = item.optString("duration").takeIf(String::isNotBlank)
            ))
        }
    }

    private fun seriesItems(array: JSONArray, groups: Map<String, String>) = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("series_id")
            if (id.isBlank()) continue
            add(PlaylistItem(
                item.optString("name", "Unnamed series"), "series://$id",
                groups[item.optString("category_id")] ?: "Other",
                item.optString("cover").takeIf(String::isNotBlank), id, MediaKind.SERIES
            ))
        }
    }

    private fun apiUrl(server: String, input: PlaylistInput, action: String?): String = buildString {
        append(server).append("/player_api.php?username=").append(encode(input.username))
        append("&password=").append(encode(input.password))
        if (action != null) append("&action=").append(action)
    }

    private fun firstText(objectValue: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { objectValue.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", true) }

    private fun containsLatinText(value: String): Boolean = value.any { it in 'A'..'Z' || it in 'a'..'z' }

    private fun normalizeServerBase(value: String): String {
        val uri = URI(value.trim())
        val scheme = uri.scheme.lowercase()
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path.orEmpty().trimEnd('/').takeUnless { it == "/" }.orEmpty()
        return "$scheme://${uri.host}$port$path"
    }

    private fun addressCandidates(value: String): List<String> {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Enter a playlist or server address." }
        val address = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            else -> "http://$trimmed"
        }
        require(URI(address).host != null) { "Enter a valid server or playlist address." }
        return listOf(address)
    }

    private fun download(url: String): String {
        val userAgents = listOf("IPTVSmartersPro", "VLC/3.0.20 LibVLC/3.0.20", "Mozilla/5.0 (Android)")
        var lastCode = -1
        for (userAgent in userAgents) {
            var current = URI(url)
            repeat(6) {
                val connection = current.toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Connection", "close")
                lastCode = connection.responseCode
                when {
                    lastCode in 200..299 -> {
                        try {
                            return readBody(connection)
                        } finally {
                            connection.disconnect()
                        }
                    }
                    lastCode in setOf(301, 302, 303, 307, 308) -> {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        require(!location.isNullOrBlank()) { "The provider returned an invalid redirect." }
                        val redirected = current.resolve(location)
                        require(redirected.scheme.equals(current.scheme, true)) {
                            "The provider redirected ${current.scheme.uppercase()} to ${redirected.scheme.uppercase()}. Use the exact working server protocol."
                        }
                        current = redirected
                    }
                    else -> {
                        connection.disconnect()
                        return@repeat
                    }
                }
            }
            if (lastCode != 401 && lastCode != 403) break
        }
        throw IllegalArgumentException(
            if (lastCode == 401 || lastCode == 403) "The provider denied access. Check the account details or connection limit."
            else "The provider could not complete the request. Try again shortly."
        )
    }

    private fun readBody(connection: HttpURLConnection): String =
        BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
            buildString {
                var total = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    total += line.length
                    require(total <= 80_000_000) { "The provider response is too large to load safely." }
                    appendLine(line)
                }
            }
        }

    private fun friendlyError(error: Throwable): Throwable = when {
        error is SocketException -> IllegalArgumentException("The server closed the connection. Verify the server address and try again.", error)
        error.message?.contains("TLS", true) == true -> IllegalArgumentException("This server uses HTTP rather than HTTPS. Please use its HTTP address.", error)
        error is org.json.JSONException -> IllegalArgumentException("This server returned an unsupported response. Confirm that it supports provider login.", error)
        else -> error
    }

    private fun saveSource(input: PlaylistInput) {
        val cleaned = input.copy(name = input.name.trim(), address = input.address.trim())
        val id = sourceId(cleaned)
        val current = savedSources()
        val updated = current.filterNot { sourceId(it) == id } + cleaned
        writeSources(updated, id)
        writeActiveSource(cleaned)
    }

    private fun saveCache(source: PlaylistInput, playlist: LoadedPlaylist) {
        val destination = sourceCacheFile(source)
        val temporary = appContext.filesDir.resolve(destination.name + ".tmp")
        runCatching {
            DataOutputStream(GZIPOutputStream(temporary.outputStream().buffered())).use { output ->
                output.writeInt(CACHE_VERSION)
                output.writeSizedString(playlist.name)
                output.writeNullableString(playlist.accountStatus)
                output.writeLong(playlist.expiryEpochSeconds ?: 0L)
                output.writeInt(playlist.items.size)
                playlist.items.forEach { item ->
                    output.writeSizedString(item.name)
                    output.writeSizedString(item.streamUrl)
                    output.writeSizedString(item.group)
                    output.writeNullableString(item.logoUrl)
                    output.writeNullableString(item.channelId)
                    output.writeSizedString(item.kind.name)
                    output.writeNullableString(item.description)
                    output.writeNullableString(item.year)
                    output.writeNullableString(item.rating)
                    output.writeNullableString(item.duration)
                }
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }.onFailure { temporary.delete() }
    }

    private fun DataOutputStream.writeSizedString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeSizedString(value)
    }

    private fun DataInputStream.readSizedString(): String {
        val size = readInt()
        require(size in 0..2_000_000) { "Invalid cached text." }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readSizedString() else null

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private companion object { const val CACHE_VERSION = 4 }
}
