package com.fourkplus.tvplayer.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketException
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
            val content = downloadWithFallback(input)
            M3uParser.parse(input.name, content).also { saveSource(input) }
        }.recoverCatching { error ->
            when (error) {
                is SocketException -> throw IllegalArgumentException(
                    "The server closed the connection. Check the address and port, then try again.",
                    error
                )
                else -> throw error
            }
        }
    }

    private fun downloadWithFallback(input: PlaylistInput): String {
        val candidates = addressCandidates(input.address)
        var lastError: Throwable? = null
        for (address in candidates) {
            try {
                return download(buildSourceUrl(input, address))
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalArgumentException("The playlist address could not be reached.")
    }

    private fun buildSourceUrl(input: PlaylistInput, address: String): String {
        if (input.kind == PlaylistKind.M3U_URL) return address
        val separator = if ('?' in address) '&' else '?'
        return address.trimEnd('/') + "/get.php" + separator +
            "username=${encode(input.username)}&password=${encode(input.password)}&type=m3u_plus&output=ts"
    }

    private fun addressCandidates(value: String): List<String> {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "Enter a playlist or server address." }
        val candidates = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            listOf(trimmed)
        } else {
            listOf("http://$trimmed", "https://$trimmed")
        }
        candidates.forEach { candidate ->
            val uri = URI(candidate)
            require(uri.host != null) { "Enter a valid server or playlist address." }
            require(uri.scheme == "http" || uri.scheme == "https") { "Only HTTP and HTTPS addresses are supported." }
        }
        return candidates
    }

    private fun download(sourceUrl: String): String {
        val connection = URI(sourceUrl).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Connection", "close")
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
