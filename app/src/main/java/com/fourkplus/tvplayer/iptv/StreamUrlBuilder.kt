package com.fourkplus.tvplayer.iptv

import com.fourkplus.tvplayer.data.MediaKind
import com.fourkplus.tvplayer.data.PlaylistInput
import com.fourkplus.tvplayer.data.PlaylistKind
import okhttp3.HttpUrl.Companion.toHttpUrl

object ApprovedServers {
    val addresses = listOf("http://bag41135.wd.4kplus-tv-za.xyz/", "http://d4kip.com:80")
    fun allows(input: PlaylistInput): Boolean = input.kind == PlaylistKind.PROVIDER_LOGIN &&
        runCatching { StreamUrlBuilder.normalize(input.address) in addresses.map(StreamUrlBuilder::normalize) }.getOrDefault(false)
    fun candidates(input: PlaylistInput): List<String> {
        require(allows(input)) { "Choose Server 1 or Server 2." }
        return (listOf(input.address) + addresses).map(StreamUrlBuilder::normalize).distinct()
    }
}

/** Credentials are never used in toString or log messages. */
data class XtreamSession(
    val server: String, val username: String, val password: String,
    val formats: List<String> = listOf("ts", "m3u8"),
    val status: String? = null, val expiry: Long? = null
) {
    override fun toString() = "XtreamSession(credentials=REDACTED)"
}

data class PlaybackSpec(
    val session: XtreamSession, val streamId: String, val kind: MediaKind,
    val extension: String? = null, val directSource: String? = null
) {
    override fun toString() = "PlaybackSpec(credentials=REDACTED)"
}

object StreamUrlBuilder {
    fun normalize(value: String): String {
        val url = value.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null &&
            url.encodedPath.trim('/') == "") { "Invalid server base address." }
        return url.toString().trimEnd('/')
    }

    fun api(session: XtreamSession, action: String? = null): String {
        val builder = normalize(session.server).toHttpUrl().newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", session.username)
            .addQueryParameter("password", session.password)
        if (action != null) builder.addQueryParameter("action", action)
        return builder.build().toString()
    }

    fun liveFormats(extension: String?, allowed: List<String>): List<String> {
        val advertised = allowed.map { it.lowercase().trim().removePrefix(".") }
            .filter { it == "ts" || it == "m3u8" }.distinct()
        val preferred = extension?.lowercase()?.trim()?.removePrefix(".")
        // Only fall back between formats the account advertises; absent data supports both.
        val formats = advertised.ifEmpty { listOf("ts", "m3u8") }
        return (listOfNotNull(preferred?.takeIf { it in formats }) + formats).distinct()
    }

    fun candidates(spec: PlaybackSpec): List<String> {
        require(spec.streamId.matches(Regex("[0-9]+"))) { "Invalid stream ID." }
        val base = normalize(spec.session.server).toHttpUrl()
        val direct = spec.directSource?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching {
                val url = base.resolve(raw) ?: return@runCatching null
                // Never replace the authenticated origin with a guessed API host.
                url.takeIf { it.host == base.host && it.port == base.port &&
                    it.scheme == base.scheme && it.username.isEmpty() && it.password.isEmpty() }?.toString()
            }.getOrNull()
        }
        val extensions = when (spec.kind) {
            MediaKind.LIVE -> liveFormats(spec.extension, spec.session.formats)
            MediaKind.MOVIE, MediaKind.SERIES -> listOf(spec.extension?.trim()?.lowercase()?.removePrefix(".")
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                ?: throw IptvFailure("The provider did not supply a valid video container.", FailureKind.FORMAT))
        }
        val path = when (spec.kind) { MediaKind.LIVE -> "live"; MediaKind.MOVIE -> "movie"; MediaKind.SERIES -> "series" }
        return (listOfNotNull(direct) + extensions.map { ext ->
            base.newBuilder().addPathSegment(path).addPathSegment(spec.session.username)
                .addPathSegment(spec.session.password).addPathSegment("${spec.streamId}.$ext").build().toString()
        }).distinct()
    }

    fun redactedPlayback(spec: PlaybackSpec, url: String): String {
        val origin = runCatching {
            val parsed = url.toHttpUrl()
            parsed.newBuilder().username("").password("").encodedPath("/")
                .query(null).fragment(null).build().toString().trimEnd('/')
        }.getOrDefault("unknown")
        val ext = runCatching { url.toHttpUrl().pathSegments.last().substringAfterLast('.', "") }
            .getOrDefault("").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }.orEmpty()
        val path = when (spec.kind) { MediaKind.LIVE -> "live"; MediaKind.MOVIE -> "movie"; MediaKind.SERIES -> "series" }
        val id = spec.streamId.takeIf { it.matches(Regex("[0-9]+")) } ?: "unknown"
        return "$origin/$path/REDACTED/REDACTED/$id" + if (ext.isNotEmpty()) ".$ext" else ""
    }
}
