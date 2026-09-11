package com.fourkplus.tvplayer.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** On-disk, gzip'd binary cache of a loaded playlist, keyed per source so switching between saved playlists is instant. */
internal class PlaylistCacheStore(context: Context) {
    private val appContext = context.applicationContext
    private val legacyCacheFile = appContext.filesDir.resolve("playlist_cache_v1.bin.gz")

    suspend fun load(source: PlaylistInput): LoadedPlaylist? = withContext(Dispatchers.IO) {
        runCatching {
            val specificCache = cacheFileFor(source)
            val selectedFile = when {
                specificCache.exists() -> specificCache
                legacyCacheFile.exists() -> legacyCacheFile
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
            if (selectedFile == legacyCacheFile && loaded.name != source.name) return@runCatching null
            if (selectedFile == legacyCacheFile) save(source, loaded)
            loaded
        }.getOrNull()
    }

    fun save(source: PlaylistInput, playlist: LoadedPlaylist) {
        val destination = cacheFileFor(source)
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

    fun deleteFor(source: PlaylistInput) {
        cacheFileFor(source).delete()
    }

    fun deleteLegacy() {
        if (legacyCacheFile.exists()) legacyCacheFile.delete()
    }

    private fun cacheFileFor(source: PlaylistInput) = appContext.filesDir.resolve(
        "playlist_cache_" + MessageDigest.getInstance("SHA-256")
            .digest(source.sourceId().toByteArray(StandardCharsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) } + ".bin.gz"
    )

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

    private companion object {
        const val CACHE_VERSION = 4
    }
}
