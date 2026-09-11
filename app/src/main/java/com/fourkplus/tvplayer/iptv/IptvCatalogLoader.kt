package com.fourkplus.tvplayer.iptv

import com.fourkplus.tvplayer.data.*

/** Once authenticated, every category, catalog and playback request uses this session. */
class IptvCatalogLoader(private val api: IptvApiService = IptvApiService()) {
    suspend fun load(input: PlaylistInput, allowFallback: Boolean = false): Pair<XtreamSession, LoadedPlaylist> {
        val session = api.authenticate(input, allowFallback)
        val groups = XtreamParser.categories(api.array(session, "get_live_categories"))
        val live = XtreamParser.streams(api.array(session, "get_live_streams"), groups, session, MediaKind.LIVE)
        if (live.isEmpty()) throw IptvFailure("Login succeeded, but there are no live channels.", FailureKind.RESPONSE)
        return session to LoadedPlaylist(input.name.trim(), live, live.map { it.group }.distinct(), session.status, session.expiry)
    }

    // Implemented and tested, but not connected to the screens until live playback is verified.
    suspend fun loadVod(session: XtreamSession): List<PlaylistItem> {
        val groups = XtreamParser.categories(api.array(session, "get_vod_categories"))
        return XtreamParser.streams(api.array(session, "get_vod_streams"), groups, session, MediaKind.MOVIE)
    }
}
