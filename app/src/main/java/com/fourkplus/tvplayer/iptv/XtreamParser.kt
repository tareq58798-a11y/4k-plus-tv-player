package com.fourkplus.tvplayer.iptv

import com.fourkplus.tvplayer.data.*
import org.json.JSONArray
import org.json.JSONObject

object XtreamParser {
    private fun JSONObject.text(key: String): String? =
        opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    fun authentication(root: JSONObject, entered: XtreamSession): XtreamSession {
        val user = root.optJSONObject("user_info")
            ?: throw IptvFailure("The server did not return Xtream login data.", FailureKind.RESPONSE)
        val status = user.text("status")
        if (user.optInt("auth", 0) != 1 || status?.lowercase() in setOf("disabled", "banned", "expired")) {
            throw IptvFailure("Invalid username/password or inactive account.", FailureKind.AUTH)
        }
        val formats = user.optJSONArray("allowed_output_formats")
        return entered.copy(status = status, expiry = user.text("exp_date")?.toLongOrNull(),
            formats = (0 until (formats?.length() ?: 0)).map { formats!!.optString(it) }
                .map(String::lowercase).filter { it == "ts" || it == "m3u8" }.distinct()
                .ifEmpty { listOf("ts", "m3u8") })
    }

    fun categories(array: JSONArray): Map<String, String> = buildMap {
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.text("category_id") ?: continue
            put(id, entry.text("category_name") ?: "Other")
        }
    }

    fun streams(array: JSONArray, groups: Map<String, String>, session: XtreamSession, kind: MediaKind): List<PlaylistItem> = buildList {
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.text("stream_id")?.takeIf { it.matches(Regex("[0-9]+")) } ?: continue
            val category = entry.text("category_id")
            val extension = entry.text("container_extension")
            val spec = PlaybackSpec(session, id, kind, extension, entry.text("direct_source"))
            val urls = try { StreamUrlBuilder.candidates(spec) } catch (e: IptvFailure) { emptyList() }
            add(PlaylistItem(
                name = entry.text("name") ?: "Unnamed channel", streamUrl = urls.firstOrNull().orEmpty(),
                group = groups[category] ?: "Other", logoUrl = entry.text("stream_icon"),
                channelId = id, kind = kind, description = entry.text("plot"), year = entry.text("year"),
                rating = entry.text("rating"), duration = entry.text("duration"), categoryId = category,
                epgChannelId = entry.text("epg_channel_id"), streamType = entry.text("stream_type"),
                containerExtension = extension, playback = spec
            ))
        }
    }
}
