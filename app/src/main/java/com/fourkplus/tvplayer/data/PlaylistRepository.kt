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
            saveCache(playlist)
            playlist
        }.recoverCatching { throw friendlyError(it) }
    }

    fun savedSource(): PlaylistInput? {
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

    suspend fun loadCached(): LoadedPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            DataInputStream(GZIPInputStream(cacheFile.inputStream().buffered())).use { input ->
                require(input.readInt() == CACHE_VERSION) { "Unsupported playlist cache." }
                val name = input.readSizedString()
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
                LoadedPlaylist(name, items, items.map { it.group }.distinct())
            }
        }.getOrNull()
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
        return LoadedPlaylist(input.name.trim(), items, items.map { it.group }.distinct())
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

    private fun normalizeServerBase(value: String): String {
        val uri = URI(value)
        val scheme = if (uri.port == 80 && uri.scheme.equals("https", true)) "http" else uri.scheme.lowercase()
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.path.orEmpty().trimEnd('/').takeUnless { it == "/" }.orEmpty()
        return "$scheme://${uri.host}$port$path"
    }

    private fun addressCandidates(value: String): List<String> {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Enter a playlist or server address." }
        val candidates = when {
            trimmed.startsWith("https://", true) && URI(trimmed).port == 80 ->
                listOf(trimmed.replaceFirst(Regex("^https", RegexOption.IGNORE_CASE), "http"))
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> listOf(trimmed)
            else -> listOf("http://$trimmed", "https://$trimmed")
        }
        candidates.forEach { require(URI(it).host != null) { "Enter a valid server or playlist address." } }
        return candidates
    }

    private fun download(url: String): String {
        val userAgents = listOf("IPTVSmartersPro", "VLC/3.0.20 LibVLC/3.0.20", "Mozilla/5.0 (Android)")
        var lastCode = -1
        for (userAgent in userAgents) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            try {
                lastCode = connection.responseCode
                if (lastCode in 200..299) return readBody(connection)
                if (lastCode != 401 && lastCode != 403) break
            } finally { connection.disconnect() }
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
        preferences.edit().putString("name", input.name.trim()).putString("kind", input.kind.name)
            .putString("address", input.address.trim()).putString("username", input.username)
            .putString("password", input.password).apply()
    }

    private fun saveCache(playlist: LoadedPlaylist) {
        val temporary = appContext.filesDir.resolve("playlist_cache_v1.tmp")
        runCatching {
            DataOutputStream(GZIPOutputStream(temporary.outputStream().buffered())).use { output ->
                output.writeInt(CACHE_VERSION)
                output.writeSizedString(playlist.name)
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
            if (!temporary.renameTo(cacheFile)) {
                temporary.copyTo(cacheFile, overwrite = true)
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

    private companion object { const val CACHE_VERSION = 3 }
}
