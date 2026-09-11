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
        active?.let {
            sourceCacheFile(it).delete()
            preferences.edit().remove(fallbackKey(it)).apply()
        }
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
            val source = sourceForDetails() ?: throw IllegalArgumentException("No saved provider is available.")
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
            val source = sourceForDetails() ?: throw IllegalArgumentException("No saved provider is available.")
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

    private class PlaylistHttpException(val status: Int, message: String) : IllegalArgumentException(message)

    private fun fallbackKey(input: PlaylistInput): String = "provider_fallback_" + sourceCacheFile(input).name

    private fun providerFromM3u(input: PlaylistInput): PlaylistInput? = runCatching {
        if (input.kind != PlaylistKind.M3U_URL) return@runCatching null
        val uri = URI(addressCandidates(input.address).single())
        if (!uri.path.orEmpty().endsWith("/get.php")) return@runCatching null
        val parameters = uri.rawQuery.orEmpty().split('&').map { parameter ->
            val key = java.net.URLDecoder.decode(parameter.substringBefore('='), "UTF-8")
            val value = java.net.URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8")
            key to value
        }
        fun singleValue(key: String): String? =
            parameters.filter { it.first == key }.singleOrNull()?.second?.takeIf { it.isNotBlank() }
        val username = singleValue("username") ?: return@runCatching null
        val password = singleValue("password") ?: return@runCatching null
        if (uri.rawUserInfo != null || uri.fragment != null) return@runCatching null
        val base = "${uri.scheme}://${uri.rawAuthority}" + uri.rawPath.removeSuffix("/get.php")
        input.copy(kind = PlaylistKind.PROVIDER_LOGIN, address = base, username = username, password = password)
    }.getOrNull()

    private fun sourceForDetails(): PlaylistInput? {
        val source = savedSource() ?: return null
        return if (source.kind == PlaylistKind.M3U_URL && preferences.getBoolean(fallbackKey(source), false)) {
            providerFromM3u(source)
        } else source
    }

    private fun loadM3u(input: PlaylistInput): LoadedPlaylist {
        val address = addressCandidates(input.address).single()
        try {
            val playlist = M3uParser.parse(input.name, download(address))
            preferences.edit().remove(fallbackKey(input)).apply()
            return playlist
        } catch (error: PlaylistHttpException) {
            if (error.status != 403) throw error
            val provider = providerFromM3u(input) ?: throw error
            try {
                val playlist = loadProvider(provider)
                // Keep the original M3U identity and URL for switching and caching.
                // Remember how the catalog was loaded so details use provider stream IDs.
                preferences.edit().putBoolean(fallbackKey(input), true).apply()
                return playlist
            } catch (fallbackError: Exception) {
                if (fallbackError is kotlinx.coroutines.CancellationException) throw fallbackError
                throw IllegalArgumentException(
                    "The M3U download was rejected (403), and the provider login fallback also failed. " +
                        (if (fallbackError is PlaylistHttpException) fallbackError.message.orEmpty()
                        else "Try Provider Login to check the connection."),
                    fallbackError
                )
            }
        }
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

    private fun requestStage(url: String): String {
        val uri = URI(url)
        if (!uri.path.orEmpty().endsWith("/player_api.php")) return "M3U download"
        val action = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("action=") }?.substringAfter('=')
        return when (action) {
            null -> "Provider authentication"
            "get_live_categories" -> "Live categories"
            "get_vod_categories" -> "Movie categories"
            "get_series_categories" -> "Series categories"
            "get_live_streams" -> "Live catalog"
            "get_vod_streams" -> "Movie catalog"
            "get_series" -> "Series catalog"
            "get_vod_info" -> "Movie details"
            "get_series_info" -> "Series details"
            else -> "Provider request"
        }
    }

    private fun download(url: String): String {
        val stage = requestStage(url)
        var responseInfo = ""
        val attempts = mutableListOf<String>()
        val userAgents = listOf("IPTVSmartersPro", "VLC/3.0.20 LibVLC/3.0.20", "Mozilla/5.0 (Android)")
        var lastCode = -1
        var lastRoute = ""
        for (userAgent in userAgents) {
            // Keep cookies within this download attempt, never across playlists.
            val cookies = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ORIGINAL_SERVER)
            var current = URI(url)
            val protocols = mutableListOf(current.scheme.uppercase())
            val visited = mutableSetOf<URI>()
            for (redirectCount in 0 until 6) {
                require(visited.add(current)) { "The playlist server returned a redirect loop." }
                val connection = current.toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.setRequestProperty("Accept", "*/*")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Connection", "close")
                    cookies.get(current, emptyMap()).forEach { (name, values) ->
                        values.forEach { value -> connection.addRequestProperty(name, value) }
                    }
                    lastRoute = protocols.joinToString(" → ")
                    try {
                        lastCode = connection.responseCode
                    } catch (error: javax.net.ssl.SSLException) {
                        throw IllegalArgumentException(
                            "Secure connection failed ($lastRoute). The entered address was not changed.", error
                        )
                    }
                    // Only fixed labels are shown: never expose URLs, cookies, or response bodies.
                    val cloudflare = connection.getHeaderField("Server").equals("cloudflare", true)
                    val challenge = connection.getHeaderField("cf-mitigated").equals("challenge", true)
                    val html = connection.contentType.orEmpty().startsWith("text/html", true)
                    responseInfo = listOfNotNull(
                        if (cloudflare) "Cloudflare response" else null,
                        if (challenge) "browser verification required" else null,
                        if (html) "HTML response" else null
                    ).joinToString("; ")
                    cookies.put(current, connection.headerFields)
                    when {
                        lastCode in 200..299 -> return readBody(connection)
                        lastCode in setOf(301, 302, 303, 307, 308) -> {
                            val location = connection.getHeaderField("Location")
                            require(!location.isNullOrBlank()) { "The provider returned an invalid redirect." }
                            val redirected = current.resolve(location)
                            require(
                                redirected.scheme.equals("http", true) ||
                                    redirected.scheme.equals("https", true)
                            ) { "The provider returned an unsupported redirect." }
                            require(redirectCount < 5) { "The playlist server returned too many redirects." }
                            // Use the server's destination only for this request. Do not rewrite the saved URL.
                            if (!redirected.scheme.equals(current.scheme, true)) {
                                protocols.add(redirected.scheme.uppercase())
                            }
                            current = redirected
                        }
                        else -> break
                    }
                } finally {
                    connection.disconnect()
                }
            }
            attempts.add("HTTP $lastCode; $lastRoute" + if (responseInfo.isEmpty()) "" else "; $responseInfo")
            if (lastCode != 401 && lastCode != 403) break
        }
        throw PlaylistHttpException(lastCode,
            "$stage failed. " + attempts.distinct().joinToString(" | ") +
                ". The entered address was not changed."
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
        error is javax.net.ssl.SSLException -> IllegalArgumentException("A secure connection to the playlist server could not be established.", error)
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
