package com.fourkplus.tvplayer.iptv

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fourkplus.tvplayer.data.*
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Retains saved accounts; obsolete catalog files are deliberately never read. */
internal class IptvAccountStore(context: Context) {
    private val context = context.applicationContext
    private val masterKey = MasterKey.Builder(this.context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(this.context, "playlist_source", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    fun id(input: PlaylistInput): String = MessageDigest.getInstance("SHA-256")
        .digest((StreamUrlBuilder.normalize(input.address) + "|" + input.username + "|" + input.password).toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun allSources(): List<PlaylistInput> = runCatching {
        val json = prefs.getString("sources_json", null)
        val entries = if (json == null) JSONArray().apply {
            val address = prefs.getString("address", null)
            if (address != null) put(JSONObject().put("address", address)
                .put("name", prefs.getString("name", "Playlist"))
                .put("kind", prefs.getString("kind", "PROVIDER_LOGIN"))
                .put("username", prefs.getString("username", ""))
                .put("password", prefs.getString("password", "")))
        } else JSONArray(json)
        (0 until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            runCatching { PlaylistInput(e.getString("name"), PlaylistKind.valueOf(e.getString("kind")),
                e.getString("address"), e.optString("username"), e.optString("password")) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun sources() = allSources().filter(ApprovedServers::allows)
    fun active(): PlaylistInput? {
        val sources = sources()
        val newId = prefs.getString("iptv_active_v2", null)
        if (newId != null) return sources.firstOrNull { id(it) == newId } ?: sources.firstOrNull()
        val oldId = prefs.getString("active_source_id", null)
        return sources.firstOrNull { "${it.kind.name}|${it.address.trim()}|${it.username.trim()}" == oldId }
            ?: sources.firstOrNull()
    }

    private fun write(sources: List<PlaylistInput>) {
        val array = JSONArray()
        sources.forEach { s -> array.put(JSONObject().put("name", s.name).put("kind", s.kind.name)
            .put("address", s.address).put("username", s.username).put("password", s.password)) }
        prefs.edit().putString("sources_json", array.toString()).apply()
    }

    fun save(source: PlaylistInput, activate: Boolean) {
        val others = allSources().filterNot {
            ApprovedServers.allows(it) && StreamUrlBuilder.normalize(it.address) == StreamUrlBuilder.normalize(source.address) && it.username == source.username
        }
        write(others + source)
        if (activate) select(source)
    }
    fun select(source: PlaylistInput) {
        require(sources().any { id(it) == id(source) }) { "This account is no longer saved." }
        prefs.edit().putString("iptv_active_v2", id(source)).apply()
    }
    fun rename(name: String) {
        require(name.isNotBlank()) { "Playlist name cannot be empty." }
        active()?.let { save(it.copy(name = name.trim()), true) }
    }
    fun removeActive() {
        val source = active() ?: return
        write(allSources().filterNot { ApprovedServers.allows(it) && id(it) == id(source) })
        cacheFile(source).delete()
        prefs.edit().remove("iptv_active_v2").remove("active_source_id")
            .remove("name").remove("kind").remove("address").remove("username").remove("password").apply()
    }

    private fun cacheFile(source: PlaylistInput) = context.filesDir.resolve("iptv_catalog_v2_${id(source)}.enc")
    private fun encrypted(source: PlaylistInput) = EncryptedFile.Builder(context, cacheFile(source), masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()

    fun writeCache(source: PlaylistInput, session: XtreamSession, playlist: LoadedPlaylist) {
        // Cache is disposable. A partial cache is rejected and rebuilt after authentication.
        runCatching {
            val records = JSONArray()
            playlist.items.forEach { item ->
                records.put(JSONObject().put("stream_id", item.channelId).put("name", item.name)
                    .put("stream_icon", item.logoUrl).put("category_id", item.categoryId)
                    .put("category_name", item.group).put("epg_channel_id", item.epgChannelId)
                    .put("stream_type", item.streamType).put("container_extension", item.containerExtension)
                    .put("direct_source", item.playback?.directSource))
            }
            val root = JSONObject().put("version", 2).put("source_id", id(source))
                .put("server", session.server).put("formats", JSONArray(session.formats))
                .put("status", session.status).put("expiry", session.expiry).put("streams", records)
            cacheFile(source).delete()
            encrypted(source).openFileOutput().bufferedWriter().use { it.write(root.toString()) }
        }.onFailure { cacheFile(source).delete() }
    }

    fun readCache(source: PlaylistInput): LoadedPlaylist? = runCatching {
        if (!cacheFile(source).exists()) return@runCatching null
        val root = encrypted(source).openFileInput().bufferedReader().use { reader ->
            val text = StringBuilder()
            val chars = CharArray(8192)
            while (true) {
                val n = reader.read(chars)
                if (n < 0) break
                require(text.length.toLong() + n <= 80_000_000)
                text.append(chars, 0, n)
            }
            JSONObject(text.toString())
        }
        require(root.optInt("version") == 2 && root.getString("source_id") == id(source))
        require(root.getString("server") == StreamUrlBuilder.normalize(source.address))
        val formats = root.getJSONArray("formats")
        val session = XtreamSession(root.getString("server"), source.username, source.password,
            (0 until formats.length()).map { formats.getString(it) }, root.optString("status"),
            root.optLong("expiry").takeIf { it > 0 })
        val array = root.getJSONArray("streams")
        val groups = (0 until array.length()).associate { i ->
            val item = array.getJSONObject(i)
            item.optString("category_id") to item.optString("category_name", "Other")
        }
        val items = XtreamParser.streams(array, groups, session, MediaKind.LIVE)
        LoadedPlaylist(source.name, items, items.map { it.group }.distinct(), session.status, session.expiry)
    }.getOrNull()
}
