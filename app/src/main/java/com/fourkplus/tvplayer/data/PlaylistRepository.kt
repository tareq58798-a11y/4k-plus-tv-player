package com.fourkplus.tvplayer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistRepository(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "playlist_source",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    suspend fun load(input: PlaylistInput): Result<LoadedPlaylist> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceUrl = buildSourceUrl(input)
            val content = download(sourceUrl)
            M3uParser.parse(input.name, content).also { saveSource(input) }
        }
    }

    private fun buildSourceUrl(input: PlaylistInput): String {
        val address = normalizeAddress(input.address)
        if (input.kind == PlaylistKind.M3U_URL) return address
        val separator = if ('?' in address) '&' else '?'
        return address.trimEnd('/') + "/get.php" + separator +
            "username=${encode(input.username)}&password=${encode(input.password)}&type=m3u_plus&output=ts"
    }

    private fun normalizeAddress(value: String): String {
        val trimmed = value.trim()
        val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        val uri = URI(normalized)
        require(uri.host != null) { "Enter a valid server or playlist address." }
        require(uri.scheme == "http" || uri.scheme == "https") { "Only HTTP and HTTPS addresses are supported." }
        return normalized
    }

    private fun download(sourceUrl: String): String {
        val connection = URI(sourceUrl).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "4K Plus TV Player/0.2")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "The provider returned error $code. Check your details and try again." }
            return BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                buildString {
                    var total = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        total += line.length
                        require(total <= 25_000_000) { "This playlist is too large to load safely." }
                        appendLine(line)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveSource(input: PlaylistInput) {
        preferences.edit()
            .putString("name", input.name.trim())
            .putString("kind", input.kind.name)
            .putString("address", input.address.trim())
            .putString("username", input.username)
            .putString("password", input.password)
            .apply()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
