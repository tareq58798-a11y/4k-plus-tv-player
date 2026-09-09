package com.fourkplus.tvplayer.data

enum class PlaylistKind { M3U_URL, PROVIDER_LOGIN }

data class PlaylistInput(
    val name: String,
    val kind: PlaylistKind,
    val address: String,
    val username: String = "",
    val password: String = ""
)

enum class MediaKind { LIVE, MOVIE, SERIES }

data class PlaylistItem(
    val name: String,
    val streamUrl: String,
    val group: String,
    val logoUrl: String?,
    val channelId: String?,
    val kind: MediaKind,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val duration: String? = null
)

data class LoadedPlaylist(
    val name: String,
    val items: List<PlaylistItem>,
    val groups: List<String>
) {
    val liveCount: Int get() = items.count { it.kind == MediaKind.LIVE }
    val movieCount: Int get() = items.count { it.kind == MediaKind.MOVIE }
    val seriesCount: Int get() = items.count { it.kind == MediaKind.SERIES }
}

data class MovieDetailsInfo(
    val originalTitle: String? = null,
    val description: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val duration: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val trailerUrl: String? = null
)
